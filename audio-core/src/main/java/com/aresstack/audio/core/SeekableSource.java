package com.aresstack.audio.core;

/**
 * A source of known length that can jump to any frame: what live playback needs to start, stop
 * and go on anywhere in a piece. {@link #seek} is called on the audio thread between two blocks and
 * therefore neither allocates nor blocks nor throws; a source that reads from a disk is no
 * seekable source in this sense.
 */
public interface SeekableSource extends AudioSource {
    /** How many frames the source holds. */
    long frames();

    /** Goes on at this frame with the next read; beyond the end the source has ended. */
    void seek(long frame);

    /** Reads without the checked exception: a seekable source holds its sound already. */
    @Override
    int read(AudioBlock block);
}
