package com.aresstack.audio.core.live;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSink;

/**
 * Where live playback sends its blocks: a sound card, a game engine, a test. Only the audio thread
 * of {@link LivePlayback} calls it. {@link #write} may wait until the output has room; that wait is
 * the clock of live playback.
 */
public interface LiveSink extends AudioSink {
    /** Takes the frames in use of the block; may wait for room, never throws once open. */
    @Override
    void write(AudioBlock block);

    /** Starts (or resumes) the output. */
    default void start() {
    }

    /** Stops the output; what waits in it stays until {@link #flush}. */
    default void pause() {
    }

    /** Drops what waits in the output and has not been heard yet. */
    default void flush() {
    }

    /** Waits until everything written has been heard. */
    default void drain() {
    }

    /** How many written frames have not been heard yet. */
    default int bufferedFrames() {
        return 0;
    }

    @Override
    default void close() {
    }
}
