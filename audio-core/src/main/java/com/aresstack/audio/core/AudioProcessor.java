package com.aresstack.audio.core;

/**
 * Works on a block in place and keeps its state from one block to the next, so that the result
 * does not depend on how the sound is cut into blocks. See the package documentation for the
 * rules every implementation keeps.
 */
public interface AudioProcessor {
    /** Allocates what the processor needs for this rate and at most this many frames per block. */
    void prepare(int sampleRate, int maxBlockFrames);

    /** Processes the frames in use of the block, in place. Never allocates, locks or throws. */
    void process(AudioBlock block);

    /** By how many frames the output lags behind the input. */
    default int latencyFrames() {
        return 0;
    }
}
