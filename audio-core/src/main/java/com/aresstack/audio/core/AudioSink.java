package com.aresstack.audio.core;

import java.io.IOException;

/**
 * Takes the finished sound block by block: a file, a sound card, a game engine. A sink is the
 * only place where float samples are turned into whole numbers, and therefore the only place
 * where they are clamped.
 */
public interface AudioSink extends AutoCloseable {
    void write(AudioBlock block) throws IOException;

    @Override
    default void close() throws IOException {
    }
}
