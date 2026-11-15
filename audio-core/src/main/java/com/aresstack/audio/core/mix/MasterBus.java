package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioProcessor;
import com.aresstack.audio.core.dsp.LookaheadLimiter;

/**
 * The end of the chain in live playback, preview and export: a fixed gain, then the limiter.
 *
 * <pre>
 * master bus of the graph ─► · gain ─► LookaheadLimiter ─► sink
 * </pre>
 *
 * The gain is not taken from the mix, so it is known before the first sample and the same live
 * and offline. A new gain is reached as a straight ramp over one block; the limiter keeps its
 * state when its settings change. The latency is that of the limiter.
 */
public final class MasterBus implements AudioProcessor {
    /** Gain and limiter, as one immutable value. */
    public static final class Settings {
        private final LevelMode mode;
        private final float gain;
        private final LookaheadLimiter.Settings limiter;

        private Settings(LevelMode mode, float gain, LookaheadLimiter.Settings limiter) {
            if (!(gain >= 0) || Float.isInfinite(gain)) {
                throw new IllegalArgumentException("A gain is a finite factor of at least 0: " + gain);
            }
            if (limiter == null) {
                throw new IllegalArgumentException("A master bus needs its limiter settings.");
            }
            this.mode = mode;
            this.gain = gain;
            this.limiter = limiter;
        }

        /**
         * The fixed level structure: the master level ({@code masterDb}, 0 or less; more is
         * taken as 0), then the limiter with its ceiling and release.
         */
        public static Settings fixed(double masterDb, LookaheadLimiter.Settings limiter) {
            return fixed(0, masterDb, limiter);
        }

        /**
         * The fixed level structure with a fixed make-up gain in front of the master level: an
         * application whose stems are rendered with headroom raises them by the same amount for
         * every piece, so that the limiter, not a look at the whole piece, keeps the peaks.
         *
         * @param makeupDb the fixed make-up gain, 0 to +24 dB
         * @param masterDb the master level, 0 or less; more is taken as 0
         */
        public static Settings fixed(double makeupDb, double masterDb, LookaheadLimiter.Settings limiter) {
            if (!(makeupDb >= 0 && makeupDb <= 24)) {
                throw new IllegalArgumentException("A make-up gain lies between 0 and +24 dB: " + makeupDb);
            }
            return new Settings(LevelMode.FIXED, (float) Math.pow(10, (makeupDb + Math.min(0, masterDb)) / 20), limiter);
        }

        /** The fixed level structure with the master level and the usual limiter (−1 dBFS, 80 ms). */
        public static Settings fixed(double masterDb) {
            return fixed(masterDb, LookaheadLimiter.Settings.DEFAULT);
        }

        /**
         * The compatibility mode for live preview: the factor the last offline mix in
         * {@link LevelMode#LEGACY_PEAK} found (it already contains the master level), so the
         * preview is as loud as the file. The limiter sits at 0 dBFS only as a guard; a
         * legacy mix peaks at 0.92 and never reaches it.
         */
        public static Settings legacy(float factor) {
            return new Settings(LevelMode.LEGACY_PEAK, factor, new LookaheadLimiter.Settings(0, 80));
        }

        public LevelMode mode() {
            return mode;
        }

        public float gain() {
            return gain;
        }

        public LookaheadLimiter.Settings limiter() {
            return limiter;
        }
    }

    private final LookaheadLimiter limiter;
    private volatile Settings settings;
    private Settings applied;
    private float gain;

    public MasterBus(Settings settings) {
        this(LookaheadLimiter.DEFAULT_LOOKAHEAD_MS, settings);
    }

    public MasterBus(double lookaheadMs, Settings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("A master bus needs its settings.");
        }
        this.settings = settings;
        this.limiter = new LookaheadLimiter(lookaheadMs, settings.limiter());
    }

    /** Hands over new settings; the next block ramps to them. Any thread may call this. */
    public void setSettings(Settings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("A master bus needs its settings.");
        }
        this.settings = settings;
        limiter.setSettings(settings.limiter());
    }

    public Settings settings() {
        return settings;
    }

    /** The limiter, for its meters. */
    public LookaheadLimiter limiter() {
        return limiter;
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        Settings now = settings;
        applied = now;
        gain = now.gain();
        limiter.setSettings(now.limiter());
        limiter.prepare(sampleRate, maxBlockFrames);
    }

    @Override
    public void process(AudioBlock block) {
        Settings now = settings;
        int frames = block.frames();
        if (now != applied && now.gain() != gain) {
            // a new gain is applied only by a block that has frames to ramp over
            if (frames > 0) {
                float from = gain;
                float to = now.gain();
                float[] left = block.left;
                float[] right = block.right;
                for (int i = 0; i < frames; i++) {
                    float g = from + (to - from) * (i + 1) / frames;
                    left[i] *= g;
                    right[i] *= g;
                }
                gain = to;
                applied = now;
            }
        } else {
            applied = now;
            if (gain != 1f) {
                block.scale(gain);
            }
        }
        limiter.process(block);
    }

    @Override
    public int latencyFrames() {
        return limiter.latencyFrames();
    }
}
