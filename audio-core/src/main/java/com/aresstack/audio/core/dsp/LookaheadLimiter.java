package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioProcessor;

/**
 * The master limiter: a real dynamics processor with look-ahead, not a clamp of sample values.
 * It looks a few milliseconds ahead, lowers the level as a smooth ramp before a peak arrives and
 * lets it rise again with a release time. Both sides get the same gain, so the stereo image does
 * not move. Below the ceiling it changes nothing: the output is the input, only delayed, down to
 * the last bit. Design and acceptance in section 4b.3 of the architecture review.
 *
 * <pre>
 * x ─► peak max(|L|,|R|) ─► needed gain min(1, ceiling/peak)
 *        ─► release (one pole, upwards only) ─► sliding minimum over n+1 ─► moving average over n ─► g
 * x ─► delay by n frames ──────────────────────────────────────────────────────────────────────► · g ─► y
 * </pre>
 *
 * The sliding minimum followed by a moving average of the same length guarantees that the gain at
 * the delayed peak sample is at most the gain that sample needs, and lowers it over n frames
 * without a step. That replaces an attack time. Peaks between samples (true peak) are not
 * detected; the ceiling is a sample peak.
 *
 * <p>The look-ahead is fixed per instance, because it sets buffer sizes and latency. Ceiling
 * and release are handed over as an immutable {@link Settings} value and may change while the
 * limiter runs, without losing its state.
 */
public final class LookaheadLimiter implements AudioProcessor {
    /** The look-ahead of the architecture review: 2 ms. */
    public static final double DEFAULT_LOOKAHEAD_MS = 2;
    /** Below this distance from 1 the gain lands on exactly 1, so quiet passages pass bit for bit again. */
    private static final double UNITY_SNAP = 1e-6;

    /** Ceiling and release; checked here, outside the audio thread. */
    public static final class Settings {
        /** Ceiling −1 dBFS, release 80 ms. */
        public static final Settings DEFAULT = new Settings(-1, 80);

        private final double ceilingDb;
        private final double releaseMs;

        /**
         * @param ceilingDb the highest sample value that leaves the limiter, in dBFS, at most +6
         * @param releaseMs how fast the gain comes back after a peak, more than 0
         */
        public Settings(double ceilingDb, double releaseMs) {
            if (!(ceilingDb <= 6) || Double.isInfinite(ceilingDb)) {
                throw new IllegalArgumentException("The ceiling must be a level of at most +6 dBFS: " + ceilingDb);
            }
            if (!(releaseMs > 0) || Double.isInfinite(releaseMs)) {
                throw new IllegalArgumentException("The release must be longer than 0 ms: " + releaseMs);
            }
            this.ceilingDb = ceilingDb;
            this.releaseMs = releaseMs;
        }

        public double ceilingDb() {
            return ceilingDb;
        }

        public double releaseMs() {
            return releaseMs;
        }

        /** The ceiling as a sample value, rounded to float as the samples are. */
        public float ceiling() {
            return (float) Math.pow(10, ceilingDb / 20);
        }
    }

    private final double lookaheadMs;
    private volatile Settings settings;
    private Settings applied;
    private int rate;

    private int n;
    private float ceiling;
    private double release;
    // audio delay lines
    private float[] delayLeft;
    private float[] delayRight;
    private int delayAt;
    // sliding minimum: a monotone queue; it needs n+2 places, with n+1 the head is overwritten just before it is dropped
    private double[] minValue;
    private long[] minTime;
    private int minHead;
    private int minTail;
    private int minCount;
    private long time;
    // moving average over n values
    private double[] average;
    private int averageAt;
    private double averageSum;
    // the gain after the release
    private double released;
    private volatile float lastGain = 1f;
    private float lowestGain = 1f;
    private volatile float lowestPublished = 1f;

    public LookaheadLimiter() {
        this(DEFAULT_LOOKAHEAD_MS, Settings.DEFAULT);
    }

    public LookaheadLimiter(Settings settings) {
        this(DEFAULT_LOOKAHEAD_MS, settings);
    }

    /**
     * @param lookaheadMs how far ahead the limiter looks, more than 0 and at most 50 ms; also its latency
     */
    public LookaheadLimiter(double lookaheadMs, Settings settings) {
        if (!(lookaheadMs > 0 && lookaheadMs <= 50)) {
            throw new IllegalArgumentException("The look-ahead lies between 0 and 50 ms: " + lookaheadMs);
        }
        if (settings == null) {
            throw new IllegalArgumentException("A limiter needs its settings.");
        }
        this.lookaheadMs = lookaheadMs;
        this.settings = settings;
    }

    /** Hands over new settings; the next block uses them. Any thread may call this. */
    public void setSettings(Settings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("A limiter needs its settings.");
        }
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    /** The look-ahead in frames at a rate. */
    public static int lookaheadFrames(double lookaheadMs, int sampleRate) {
        return Math.max(1, (int) Math.round(lookaheadMs * sampleRate / 1000.0));
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("A rate is more than 0 frames per second.");
        }
        rate = sampleRate;
        n = lookaheadFrames(lookaheadMs, sampleRate);
        delayLeft = new float[n];
        delayRight = new float[n];
        delayAt = 0;
        minValue = new double[n + 2];
        minTime = new long[n + 2];
        minHead = 0;
        minTail = 0;
        minCount = 0;
        time = 0;
        average = new double[n];
        java.util.Arrays.fill(average, 1.0);
        averageAt = 0;
        averageSum = n;
        released = 1.0;
        lastGain = 1f;
        lowestGain = 1f;
        lowestPublished = 1f;
        applied = null;
        apply(settings);
    }

    private void apply(Settings now) {
        applied = now;
        ceiling = now.ceiling();
        release = 1.0 - Math.exp(-1.0 / (now.releaseMs() * rate / 1000.0));
    }

    @Override
    public void process(AudioBlock block) {
        Settings now = settings;
        if (now != applied) {
            apply(now);
        }
        float[] left = block.left;
        float[] right = block.right;
        int frames = block.frames();
        int length = minValue.length;
        double g = 1.0;
        for (int i = 0; i < frames; i++) {
            float inLeft = left[i];
            float inRight = right[i];
            double peak = Math.max(Math.abs(inLeft), Math.abs(inRight));
            double needed = peak > ceiling ? ceiling / peak : 1.0;
            // release: down at once, up with one pole; land on exactly 1 when close and nothing is over
            released = needed < released ? needed : released + release * (needed - released);
            if (needed == 1.0 && released > 1.0 - UNITY_SNAP) {
                released = 1.0;
            }
            // sliding minimum over the last n+1 values
            while (minCount > 0) {
                int last = minTail == 0 ? length - 1 : minTail - 1;
                if (minValue[last] < released) {
                    break;
                }
                minTail = last;
                minCount--;
            }
            minValue[minTail] = released;
            minTime[minTail] = time;
            minTail = minTail + 1 == length ? 0 : minTail + 1;
            minCount++;
            if (time - minTime[minHead] > n) {
                minHead = minHead + 1 == length ? 0 : minHead + 1;
                minCount--;
            }
            double held = minValue[minHead];
            // moving average over n values; summed afresh once per round so rounding does not pile up
            averageSum += held - average[averageAt];
            average[averageAt] = held;
            if (++averageAt == n) {
                averageAt = 0;
                double sum = 0;
                for (int k = 0; k < n; k++) {
                    sum += average[k];
                }
                averageSum = sum;
            }
            g = averageSum / n;
            if (g > 1.0) {
                g = 1.0;
            }
            // the delayed sound
            float outLeft = delayLeft[delayAt];
            float outRight = delayRight[delayAt];
            delayLeft[delayAt] = inLeft;
            delayRight[delayAt] = inRight;
            if (++delayAt == n) {
                delayAt = 0;
            }
            if (g == 1.0) {
                left[i] = outLeft;
                right[i] = outRight;
            } else {
                float a = (float) (outLeft * g);
                float b = (float) (outRight * g);
                // the float rounding of the product may land one step above the ceiling
                left[i] = Math.abs(a) > ceiling ? Math.copySign(ceiling, a) : a;
                right[i] = Math.abs(b) > ceiling ? Math.copySign(ceiling, b) : b;
                if (g < lowestGain) {
                    lowestGain = (float) g;
                }
            }
            time++;
        }
        if (frames > 0) {
            lastGain = (float) g;
            lowestPublished = lowestGain;
        }
    }

    @Override
    public int latencyFrames() {
        return n;
    }

    /** The gain at the end of the last block, 1 when the limiter does not act; for a meter. */
    public float gain() {
        return lastGain;
    }

    /** The lowest gain since {@link #prepare}; 1 means the limiter never acted. */
    public float lowestGain() {
        return lowestPublished;
    }
}
