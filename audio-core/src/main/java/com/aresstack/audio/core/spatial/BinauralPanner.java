package com.aresstack.audio.core.spatial;

import com.aresstack.audio.core.AudioBlock;

/**
 * Places one mono source for headphones (work package D4): the source is delayed for each ear by
 * its arrival time and filtered with the head-related filter of its direction, both taken from a
 * {@link HrirSet}. It replaces the equal-power {@link com.aresstack.audio.core.dsp.StereoPanner}
 * when the stage is heard binaurally (architecture review 2.6: the panner is exchangeable).
 *
 * <p><b>Moving sources.</b> A new direction is reached over a number of frames: the arrival times
 * glide in a straight line (read between samples, so a source passing the head shifts its pitch a
 * little, as it does in a real room), and the filters cross-fade. Because filtering is linear,
 * a cross-fade of two filter outputs is the output of the cross-faded filter; a new aim during a
 * glide therefore starts from exactly the filter that is heard at that moment.
 *
 * <p>{@link #prepare} allocates; {@link #aimAt}, {@link #jumpTo} and {@link #process} neither
 * allocate nor lock nor throw. Latency 0: the arrival time is part of the sound.
 */
public final class BinauralPanner {
    private final HrirSet set;
    private final int taps;
    private float[] currentLeft;
    private float[] currentRight;
    private float[] targetLeft;
    private float[] targetRight;
    private final float[] delays = new float[2];
    private float delayLeft;
    private float delayRight;
    private float delayLeftStep;
    private float delayRightStep;
    private int remaining;
    private int span;
    private float[] history = new float[0];
    private int mask;
    private float[] earLeft = new float[0];
    private float[] earRight = new float[0];
    private long time;
    private double azimuth;
    private double elevation;

    public BinauralPanner(HrirSet set) {
        if (set == null) {
            throw new IllegalArgumentException("A binaural panner needs its head-related responses.");
        }
        this.set = set;
        this.taps = set.taps();
        currentLeft = new float[taps];
        currentRight = new float[taps];
        targetLeft = new float[taps];
        targetRight = new float[taps];
        jumpTo(0, 0);
    }

    public HrirSet set() {
        return set;
    }

    /** Allocates the delay lines; the rate must be the rate of the set. */
    public void prepare(int sampleRate, int maxBlockFrames) {
        if (sampleRate != set.sampleRate()) {
            throw new IllegalArgumentException("The responses are made for " + set.sampleRate() + " Hz, not "
                    + sampleRate + " Hz.");
        }
        int reach = (int) Math.ceil(set.longestDelay()) + 2;
        int size = Integer.highestOneBit(Math.max(reach + maxBlockFrames, 2) * 2 - 1);
        history = new float[size];
        mask = size - 1;
        int ears = Integer.highestOneBit(Math.max(taps + maxBlockFrames, 2) * 2 - 1);
        earLeft = new float[ears];
        earRight = new float[ears];
        clear();
    }

    /** Forgets what was heard (after a seek); the direction stays. */
    public void clear() {
        java.util.Arrays.fill(history, 0f);
        java.util.Arrays.fill(earLeft, 0f);
        java.util.Arrays.fill(earRight, 0f);
        time = 0;
    }

    /** Takes a direction at once (before playback, or after a seek). */
    public void jumpTo(double azimuth, double elevation) {
        this.azimuth = azimuth;
        this.elevation = elevation;
        set.lookup(azimuth, elevation, currentLeft, currentRight, delays);
        delayLeft = delays[0];
        delayRight = delays[1];
        remaining = 0;
    }

    /** Glides to a direction over this many frames, starting with the next frame processed. */
    public void aimAt(double azimuth, double elevation, int frames) {
        if (frames <= 0) {
            jumpTo(azimuth, elevation);
            return;
        }
        if (azimuth == this.azimuth && elevation == this.elevation) {
            return;
        }
        if (remaining > 0) {
            // Start from the filter heard right now.
            float w = 1 - remaining / (float) span;
            for (int k = 0; k < taps; k++) {
                currentLeft[k] += (targetLeft[k] - currentLeft[k]) * w;
                currentRight[k] += (targetRight[k] - currentRight[k]) * w;
            }
        }
        this.azimuth = azimuth;
        this.elevation = elevation;
        set.lookup(azimuth, elevation, targetLeft, targetRight, delays);
        span = frames;
        remaining = frames;
        delayLeftStep = (delays[0] - delayLeft) / frames;
        delayRightStep = (delays[1] - delayRight) / frames;
    }

    /** Whether the last direction has been reached. */
    public boolean settled() {
        return remaining == 0;
    }

    /** Adds the first {@code count} samples of {@code mono}, placed for both ears, to the bus. */
    public void process(float[] mono, int count, AudioBlock bus) {
        process(mono, 0, count, bus);
    }

    /** Adds {@code count} samples of {@code mono} from {@code from} on, placed, to the same frames of the bus. */
    public void process(float[] mono, int from, int count, AudioBlock bus) {
        float[] busLeft = bus.left;
        float[] busRight = bus.right;
        int earMask = earLeft.length - 1;
        for (int i = from; i < from + count; i++) {
            long now = time;
            history[(int) (now & mask)] = mono[i];
            int at = (int) (now & earMask);
            earLeft[at] = delayed(now, delayLeft);
            earRight[at] = delayed(now, delayRight);
            float outLeft;
            float outRight;
            if (remaining > 0) {
                float w = 1 - remaining / (float) span;
                float l0 = 0;
                float l1 = 0;
                float r0 = 0;
                float r1 = 0;
                for (int k = 0; k < taps; k++) {
                    int j = (at - k) & earMask;
                    float el = earLeft[j];
                    float er = earRight[j];
                    l0 += currentLeft[k] * el;
                    l1 += targetLeft[k] * el;
                    r0 += currentRight[k] * er;
                    r1 += targetRight[k] * er;
                }
                outLeft = l0 + (l1 - l0) * w;
                outRight = r0 + (r1 - r0) * w;
                delayLeft += delayLeftStep;
                delayRight += delayRightStep;
                if (--remaining == 0) {
                    float[] swap = currentLeft;
                    currentLeft = targetLeft;
                    targetLeft = swap;
                    swap = currentRight;
                    currentRight = targetRight;
                    targetRight = swap;
                    delayLeft = delays[0];
                    delayRight = delays[1];
                }
            } else {
                float l = 0;
                float r = 0;
                for (int k = 0; k < taps; k++) {
                    int j = (at - k) & earMask;
                    l += currentLeft[k] * earLeft[j];
                    r += currentRight[k] * earRight[j];
                }
                outLeft = l;
                outRight = r;
            }
            busLeft[i] += outLeft;
            busRight[i] += outRight;
            time = now + 1;
        }
    }

    /** The input of {@code delay} frames before {@code now}, read between samples. */
    private float delayed(long now, float delay) {
        double back = now - delay;
        long whole = (long) Math.floor(back);
        float f = (float) (back - whole);
        float a = whole < 0 ? 0 : history[(int) (whole & mask)];
        float b = whole + 1 < 0 || whole + 1 > now ? 0 : history[(int) ((whole + 1) & mask)];
        return a + (b - a) * f;
    }

    public int latencyFrames() {
        return 0;
    }
}
