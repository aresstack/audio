package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioProcessor;
import com.aresstack.audio.core.BiquadCoefficients;

/** The same second-order filter on both sides, each side with a state of its own. */
public final class StereoBiquad implements AudioProcessor {
    private final BiquadFilter left;
    private final BiquadFilter right;

    public StereoBiquad(BiquadCoefficients coefficients) {
        left = new BiquadFilter(coefficients);
        right = new BiquadFilter(coefficients);
    }

    /** Takes effect with the next block; the state of both sides stays. */
    public void setCoefficients(BiquadCoefficients coefficients) {
        left.setCoefficients(coefficients);
        right.setCoefficients(coefficients);
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        left.reset();
        right.reset();
    }

    @Override
    public void process(AudioBlock block) {
        process(block, block.frames());
    }

    /** Filters only the first {@code count} frames of the block. */
    public void process(AudioBlock block, int count) {
        left.process(block.left, 0, count);
        right.process(block.right, 0, count);
    }
}
