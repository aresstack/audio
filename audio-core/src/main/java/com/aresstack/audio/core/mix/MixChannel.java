package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.AudioBlock;
import java.io.IOException;

/**
 * One channel of a {@link MixGraph}: a {@link StemChannel} (standing, or moving in level,
 * panorama and send) or a {@link SpatialChannel} (everything follows the source, also for
 * headphones). The graph calls the three steps; nothing outside the package does.
 */
public abstract sealed class MixChannel permits StemChannel, SpatialChannel {
    MixChannel() {
    }

    /** Allocates what the channel needs; called once before the first block. */
    abstract void prepare(int sampleRate, int maxBlockFrames);

    /** Adds the next {@code count} frames to the master bus and, if there is one, to the send bus. */
    abstract void render(int count, AudioBlock master, AudioBlock send) throws IOException;

    abstract void close() throws IOException;
}
