package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.dsp.BiquadFilter;
import com.aresstack.audio.core.dsp.GlidingBiquad;
import com.aresstack.audio.core.dsp.StereoStrip;
import com.aresstack.audio.core.spatial.BinauralPanner;
import com.aresstack.audio.core.spatial.HrirSet;
import java.io.IOException;

/**
 * A channel whose whole chain follows the source (work package D4):
 *
 * <pre>
 * source ─► strip (level, width) ─┬─► send to the room bus            (gains glide)
 *                                 └─► distance shelf (glides) ─┬─► panner ─► master bus
 *                                                              └─► early reflections ─► master bus
 * </pre>
 *
 * The panner is the equal-power stereo panner (gains from the motion) or, for headphones, a
 * {@link BinauralPanner} (direction from the motion). The early reflections read their history
 * between samples at delays that glide, so an echo that gets longer as the source moves away
 * bends in pitch instead of clicking; its gains glide as well.
 *
 * <p>Every {@link SpatialMotion#RAMP} frames, counted from the start of the mix, the channel asks
 * the motion where it stands now and in one ramp, and glides in between. A motion that does not
 * move on loudspeakers gives exactly the mix of a standing {@link StemChannel} with the same
 * numbers, sample for sample.
 */
public final class SpatialChannel extends MixChannel {
    private static final int RAMP = SpatialMotion.RAMP;
    private static final int TO_LEFT = 0;
    private static final int TO_RIGHT = 1;
    private static final int SEND_LEFT = 2;
    private static final int SEND_RIGHT = 3;

    private final AudioSource source;
    private final long frames;
    private final StereoStrip strip;
    private final SpatialMotion motion;
    private final int walls;
    private final int echoCapacity;
    private final BiquadFilter echoLowPass;
    private final BinauralPanner binaural;
    private final Seat now;
    private final Seat next;
    private final GlidingBiquad shelf = new GlidingBiquad(null);
    private final float[] glide = new float[4];
    private final float[] step = new float[4];
    private final float[] echoDelay;
    private final float[] echoDelayStep;
    private final float[] echoLeft;
    private final float[] echoLeftStep;
    private final float[] echoRight;
    private final float[] echoRightStep;
    private float[][] gains;
    private AudioBlock block;
    private float[] mono;
    private float[] history = new float[0];
    private long position;
    private long heardTotal;
    private boolean ended;
    private int binauralTail;

    /**
     * @param source       the sound of the channel, at the rate of the mix
     * @param frames       how many frames of the source are heard at most
     * @param strip        level and width
     * @param motion       where the channel stands over time
     * @param walls        how many walls answer with an echo (as many as every {@link Seat} has)
     * @param echoCapacity the longest echo delay the motion may ask for, in frames
     * @param echoLowPass  how the walls dull the echoes; {@code null} only without walls
     * @param hrirs        the head-related responses for headphones, or {@code null} for loudspeakers
     */
    public SpatialChannel(AudioSource source, long frames, StereoStrip strip, SpatialMotion motion, int walls,
                          int echoCapacity, BiquadCoefficients echoLowPass, HrirSet hrirs) {
        if (source == null || strip == null || motion == null) {
            throw new IllegalArgumentException("A spatial channel needs a source, a strip and a motion.");
        }
        if (walls > 0 && echoLowPass == null) {
            throw new IllegalArgumentException("Echoes need the low-pass of the walls.");
        }
        if (walls < 0 || echoCapacity < 0) {
            throw new IllegalArgumentException("Walls and echo capacity are not negative.");
        }
        this.source = source;
        this.frames = Math.max(0, frames);
        this.strip = strip;
        this.motion = motion;
        this.walls = walls;
        this.echoCapacity = echoCapacity;
        this.echoLowPass = walls > 0 ? new BiquadFilter(echoLowPass) : null;
        this.binaural = hrirs != null ? new BinauralPanner(hrirs) : null;
        now = new Seat(walls);
        next = new Seat(walls);
        echoDelay = new float[walls];
        echoDelayStep = new float[walls];
        echoLeft = new float[walls];
        echoLeftStep = new float[walls];
        echoRight = new float[walls];
        echoRightStep = new float[walls];
    }

    @Override
    void prepare(int sampleRate, int maxBlockFrames) {
        block = new AudioBlock(maxBlockFrames);
        mono = new float[maxBlockFrames];
        gains = new float[4][maxBlockFrames];
        source.prepare(sampleRate, maxBlockFrames);
        strip.prepare(sampleRate, maxBlockFrames);
        shelf.prepare(sampleRate, maxBlockFrames);
        if (walls > 0) {
            history = new float[echoCapacity + maxBlockFrames + 2];
            echoLowPass.reset();
        }
        motion.seatAt(0, now);
        shelf.jumpTo(now.shelf);
        if (binaural != null) {
            binaural.prepare(sampleRate, maxBlockFrames);
            binaural.jumpTo(now.azimuth, now.elevation);
            binauralTail = binaural.set().taps() + (int) Math.ceil(binaural.set().longestDelay()) + 2;
        }
        position = 0;
        heardTotal = 0;
        ended = frames == 0;
    }

    /** Asks the motion at a grid frame and sets every glide to reach the next one in one ramp. */
    private void look(long at) {
        motion.seatAt(at, now);
        motion.seatAt(at + RAMP, next);
        glide[TO_LEFT] = now.toLeft;
        glide[TO_RIGHT] = now.toRight;
        glide[SEND_LEFT] = now.sendLeft;
        glide[SEND_RIGHT] = now.sendRight;
        step[TO_LEFT] = (next.toLeft - now.toLeft) / RAMP;
        step[TO_RIGHT] = (next.toRight - now.toRight) / RAMP;
        step[SEND_LEFT] = (next.sendLeft - now.sendLeft) / RAMP;
        step[SEND_RIGHT] = (next.sendRight - now.sendRight) / RAMP;
        for (int w = 0; w < walls; w++) {
            echoDelay[w] = Math.max(0, Math.min(echoCapacity, now.echoDelay[w]));
            float to = Math.max(0, Math.min(echoCapacity, next.echoDelay[w]));
            echoDelayStep[w] = (to - echoDelay[w]) / RAMP;
            echoLeft[w] = now.echoLeft[w];
            echoLeftStep[w] = (next.echoLeft[w] - now.echoLeft[w]) / RAMP;
            echoRight[w] = now.echoRight[w];
            echoRightStep[w] = (next.echoRight[w] - now.echoRight[w]) / RAMP;
        }
        shelf.glideTo(next.shelf, RAMP);
        if (binaural != null) {
            binaural.aimAt(next.azimuth, next.elevation, RAMP);
        }
    }

    @Override
    void render(int count, AudioBlock master, AudioBlock send) throws IOException {
        int heard = 0;
        if (!ended) {
            int wanted = (int) Math.min(count, frames - position);
            block.setFrames(wanted);
            heard = source.read(block);
            if (heard < wanted || position + heard >= frames) {
                ended = true;
            }
        }
        if (heard > 0) {
            strip.process(block, heard);
        }
        boolean echoing = walls > 0 && !(ended && position >= heardTotal + heard + echoCapacity + 1);
        boolean placing = binaural != null && !(ended && heard == 0 && position >= heardTotal + binauralTail);
        int i = 0;
        while (i < count) {
            long at = position + i;
            int inRamp = (int) (at & (RAMP - 1));
            if (inRamp == 0) {
                look(at);
            }
            int end = Math.min(count, i + RAMP - inRamp);
            fillGains(i, end);
            int heardEnd = Math.min(end, heard);
            if (i < heardEnd) {
                if (send != null) {
                    add(block, i, heardEnd, send, gains[SEND_LEFT], gains[SEND_RIGHT]);
                }
                shelf.process(block, i, heardEnd - i);
                if (binaural == null) {
                    add(block, i, heardEnd, master, gains[TO_LEFT], gains[TO_RIGHT]);
                }
                if (walls > 0) {
                    remember(i, heardEnd);
                }
            }
            if (placing) {
                float[] left = block.left;
                float[] right = block.right;
                float[] gl = gains[TO_LEFT];
                float[] gr = gains[TO_RIGHT];
                for (int j = i; j < end; j++) {
                    mono[j] = j < heardEnd ? (left[j] * gl[j] + right[j] * gr[j]) * 0.5f : 0f;
                }
                binaural.process(mono, i, end - i, master);
            }
            if (echoing) {
                echoes(at, i, end, master);
            } else {
                skipEchoes(end - i);
            }
            i = end;
        }
        if (heard < count) {
            ended = true;
        }
        position += count;
    }

    /** The gains of the frames {@code from..to}, in a straight line from the last look. */
    private void fillGains(int from, int to) {
        for (int j = from; j < to; j++) {
            for (int k = 0; k < 4; k++) {
                gains[k][j] = glide[k];
                glide[k] += step[k];
            }
        }
    }

    /** Folds the heard frames {@code from..to} (after the shelf) to mono, dulls them and keeps them for the echoes. */
    private void remember(int from, int to) {
        float[] left = block.left;
        float[] right = block.right;
        for (int j = from; j < to; j++) {
            mono[j] = (left[j] + right[j]) * 0.5f;
        }
        echoLowPass.process(mono, from, to - from);
        int size = history.length;
        int at = (int) (heardTotal % size);
        for (int j = from; j < to; j++) {
            history[at] = mono[j];
            at = at + 1 == size ? 0 : at + 1;
        }
        heardTotal += to - from;
    }

    /** Adds the echoes of the frames {@code from..to} (the first at frame {@code first} of the mix). */
    private void echoes(long first, int from, int to, AudioBlock bus) {
        float[] busLeft = bus.left;
        float[] busRight = bus.right;
        int size = history.length;
        for (int w = 0; w < walls; w++) {
            float delay = echoDelay[w];
            float delayStep = echoDelayStep[w];
            float gl = echoLeft[w];
            float glStep = echoLeftStep[w];
            float gr = echoRight[w];
            float grStep = echoRightStep[w];
            long frame = first;
            for (int j = from; j < to; j++, frame++) {
                double back = frame - (double) delay;
                long whole = (long) Math.floor(back);
                if (whole >= -1 && whole < heardTotal) {
                    // between two samples; before the first one of the source there is silence, as live
                    float echo = whole >= 0 ? history[(int) (whole % size)] : 0f;
                    float f = (float) (back - whole);
                    if (f != 0) {
                        float later = whole + 1 < heardTotal ? history[(int) ((whole + 1) % size)] : 0f;
                        echo += (later - echo) * f;
                    }
                    busLeft[j] += echo * gl;
                    busRight[j] += echo * gr;
                }
                delay += delayStep;
                gl += glStep;
                gr += grStep;
            }
            echoDelay[w] = delay;
            echoLeft[w] = gl;
            echoRight[w] = gr;
        }
    }

    private void skipEchoes(int frames) {
        for (int w = 0; w < walls; w++) {
            echoDelay[w] += echoDelayStep[w] * frames;
            echoLeft[w] += echoLeftStep[w] * frames;
            echoRight[w] += echoRightStep[w] * frames;
        }
    }

    /** Adds the frames {@code from..to} of the block to a bus, each side with its gain of the frame. */
    private static void add(AudioBlock block, int from, int to, AudioBlock bus, float[] toLeft, float[] toRight) {
        float[] left = block.left;
        float[] right = block.right;
        float[] busLeft = bus.left;
        float[] busRight = bus.right;
        for (int j = from; j < to; j++) {
            busLeft[j] += left[j] * toLeft[j];
            busRight[j] += right[j] * toRight[j];
        }
    }

    @Override
    void close() throws IOException {
        source.close();
    }
}
