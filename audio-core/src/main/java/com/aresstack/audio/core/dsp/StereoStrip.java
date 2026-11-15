package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioProcessor;

/**
 * The first stage of a channel: its level, which may move over time, and its width. Width keeps
 * the middle of the two sides and scales their difference: 0 is a point, 1 leaves the signal as
 * it is. A moving level is looked up every 64 frames and glides in between, so that it never
 * clicks; the 64-frame grid counts from the start of the channel, so the result does not depend
 * on the block size.
 */
public final class StereoStrip implements AudioProcessor {
    /** Frames between two looks at a moving level. */
    public static final int RAMP = 64;

    /**
     * How the level moves: a factor on the fixed level for a frame counted from the start of the
     * channel. Called on the rendering thread every {@link #RAMP} frames; it must not block.
     */
    @FunctionalInterface
    public interface LevelCurve {
        float factorAt(long frame);
    }

    /** The settings of a strip, checked when they are made. */
    public record Settings(float level, float width, LevelCurve curve) {
        public Settings {
            if (!Float.isFinite(level) || level < 0) {
                throw new IllegalArgumentException("A level is a finite factor of at least 0.");
            }
            if (!Float.isFinite(width)) {
                throw new IllegalArgumentException("A width is a finite factor.");
            }
        }

        /** A fixed level without movement. */
        public Settings(float level, float width) {
            this(level, width, null);
        }
    }

    private volatile Settings settings;
    private long position;
    private float gain;
    private float step;

    public StereoStrip(Settings settings) {
        setSettings(settings);
    }

    /** Takes effect with the next block. */
    public void setSettings(Settings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("A strip needs its settings.");
        }
        this.settings = settings;
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        position = 0;
        gain = settings.level();
        step = 0;
    }

    @Override
    public void process(AudioBlock block) {
        process(block, block.frames());
    }

    /** Processes only the first {@code count} frames of the block. */
    public void process(AudioBlock block, int count) {
        Settings now = settings;
        float level = now.level();
        float width = now.width();
        LevelCurve curve = now.curve();
        float[] left = block.left;
        float[] right = block.right;
        float g = curve == null ? level : gain;
        float s = curve == null ? 0 : step;
        long at = position;
        for (int i = 0; i < count; i++, at++) {
            if (curve != null && (at & (RAMP - 1)) == 0) {
                float from = level * curve.factorAt(at);
                float to = level * curve.factorAt(at + RAMP);
                g = from;
                s = (to - from) / RAMP;
            }
            float middle = (left[i] + right[i]) * 0.5f;
            float side = (left[i] - right[i]) * 0.5f * width;
            left[i] = (middle + side) * g;
            right[i] = (middle - side) * g;
            g += s;
        }
        position = at;
        gain = g;
        step = s;
    }
}
