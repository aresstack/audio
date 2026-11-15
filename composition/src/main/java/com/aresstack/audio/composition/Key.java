package com.aresstack.audio.composition;

/**
 * A key: its tonic as a pitch class (0 = C, 1 = C sharp or D flat, ... 11 = B) and its mode.
 * Spelling of pitches and key signatures follow in work package F2.
 */
public record Key(int tonic, Mode mode) {
    public enum Mode { MAJOR, MINOR }

    /** C major, the key of every piece from before the model. */
    public static final Key C_MAJOR = new Key(0, Mode.MAJOR);

    public Key {
        if (tonic < 0 || tonic > 11) {
            throw new IllegalArgumentException("A tonic is a pitch class from 0 to 11: " + tonic);
        }
        if (mode == null) {
            throw new IllegalArgumentException("A key needs a mode.");
        }
    }
}
