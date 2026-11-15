package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;

/**
 * The room all channels send into together (architecture review 2.6): exchangeable, the
 * algorithmic {@link RoomReverb} or the {@link ConvolutionReverb} with an impulse response.
 */
public interface RoomBus {
    /** Allocates what the room needs for this rate and at most this many frames per block. */
    void prepare(int sampleRate, int maxBlockFrames);

    /**
     * Adds the ring of the first {@code frames} frames that were sent to the bus, scaled by
     * {@code gain}. Never allocates, locks or throws.
     */
    void process(AudioBlock send, int frames, AudioBlock bus, float gain);

    /** Forgets the ring (after a seek), keeping what was prepared. Never allocates, locks or throws. */
    void reset();

    /** By how many frames the ring lags behind what was sent, beyond its own pre-delay. */
    default int latencyFrames() {
        return 0;
    }
}
