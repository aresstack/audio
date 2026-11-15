package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioProcessor;

/**
 * A tap that measures the highest sample value of both sides and lets the sound through
 * unchanged. The rendering thread is the only writer; it publishes the peak of the last block
 * and the peak since {@link #prepare} after each block, which any other thread may read
 * without locks.
 */
public final class PeakMeter implements AudioProcessor {
    private float running;
    private volatile float lastBlock;
    private volatile float overall;

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        running = 0;
        lastBlock = 0;
        overall = 0;
    }

    @Override
    public void process(AudioBlock block) {
        float[] left = block.left;
        float[] right = block.right;
        float peak = 0;
        for (int i = 0, frames = block.frames(); i < frames; i++) {
            peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
        }
        running = Math.max(running, peak);
        lastBlock = peak;
        overall = running;
    }

    /** The highest absolute sample value since {@link #prepare}. */
    public float peak() {
        return overall;
    }

    /** The highest absolute sample value of the last block. */
    public float lastBlockPeak() {
        return lastBlock;
    }
}
