package com.aresstack.audio.studio.album;

/**
 * What the user says once for a whole album: what it is about, how it sounds, how many pieces it
 * has and how long each is meant to be.
 *
 * @param description     the wish in the user's words, e.g. "a Christmas album, instrumental"
 * @param style           how it sounds, e.g. "classical, orchestral, festive"; may be empty
 * @param trackCount      the number of pieces, 1 to {@link #MAX_TRACKS}
 * @param secondsPerTrack about how long each piece is, 10 seconds to {@link #MAX_SECONDS}
 */
public record AlbumConcept(String description, String style, int trackCount, int secondsPerTrack) {
    public static final int MAX_TRACKS = 99;
    public static final int MAX_SECONDS = 3600;

    public AlbumConcept {
        description = description == null ? "" : description.trim();
        style = style == null ? "" : style.trim();
        if (trackCount < 1 || trackCount > MAX_TRACKS) {
            throw new IllegalArgumentException("Ein Album hat 1 bis " + MAX_TRACKS + " Stücke: " + trackCount);
        }
        if (secondsPerTrack < 10 || secondsPerTrack > MAX_SECONDS) {
            throw new IllegalArgumentException("Ein Stück dauert 10 Sekunden bis 60 Minuten: " + secondsPerTrack + " s");
        }
    }

    /** About how long the whole album is, in seconds. */
    public int seconds() {
        return trackCount * secondsPerTrack;
    }
}
