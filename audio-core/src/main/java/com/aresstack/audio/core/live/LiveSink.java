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

    /**
     * How often the output ran empty while it was started: a drop-out the listener hears even
     * though every block was rendered in time (scheduling, a buffer too small for the system's
     * audio driver). 0 for sinks that cannot tell.
     */
    default long underruns() {
        return 0;
    }

    /** The fewest frames that waited in the output just before a write since it was started; -1 if unknown. */
    default int lowestBufferedFrames() {
        return -1;
    }

    @Override
    default void close() {
    }
}
