package com.aresstack.audio.studio.album;

/**
 * What the user says once for a whole album: what it is about, how it sounds, how many pieces it
 * has and how long each may be. The length is a range: the plan gives every title its own length
 * in it, so that not every piece lasts the same.
 *
 * @param description the wish in the user's words, e.g. "a Christmas album, instrumental"
 * @param style       how it sounds, e.g. "classical, orchestral, festive"; may be empty
 * @param trackCount  the number of pieces, 1 to {@link #MAX_TRACKS}
 * @param minSeconds  how long a piece is at least, 10 seconds to {@link #MAX_SECONDS}
 * @param maxSeconds  how long a piece is at most, not below {@code minSeconds}
 * @param original    whether it rebuilds an existing work the wish names, see {@link #withOriginal}
 * @param fromList    whether the track list says how many pieces there are ("aus der Trackliste"),
 *                    not {@code trackCount}: planning fills in the titles of the list and adds none
 */
public record AlbumConcept(String description, String style, int trackCount, int minSeconds, int maxSeconds,
                           boolean original, boolean fromList) {
    public static final int MAX_TRACKS = 99;
    public static final int MIN_SECONDS = 10;
    public static final int MAX_SECONDS = 3600;
    /** The range an album gets unless the user says otherwise: four to six minutes. */
    public static final int DEFAULT_MIN_SECONDS = 240;
    public static final int DEFAULT_MAX_SECONDS = 360;

    public AlbumConcept {
        description = description == null ? "" : description.trim();
        style = style == null ? "" : style.trim();
        if (trackCount < 1 || trackCount > MAX_TRACKS) {
            throw new IllegalArgumentException("Ein Album hat 1 bis " + MAX_TRACKS + " Stücke: " + trackCount);
        }
        if (minSeconds > maxSeconds) {
            int swap = minSeconds;
            minSeconds = maxSeconds;
            maxSeconds = swap;
        }
        if (minSeconds < MIN_SECONDS || maxSeconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Ein Stück dauert 10 Sekunden bis 60 Minuten: " + minSeconds + " bis "
                    + maxSeconds + " s");
        }
    }

    /** An album whose number of pieces is set ("automatisch"). */
    public AlbumConcept(String description, String style, int trackCount, int minSeconds, int maxSeconds,
                        boolean original) {
        this(description, style, trackCount, minSeconds, maxSeconds, original, false);
    }

    /** The same concept with this many pieces. */
    public AlbumConcept withTrackCount(int count) {
        return new AlbumConcept(description, style, count, minSeconds, maxSeconds, original, fromList);
    }

    /** Whether the number of pieces follows the track list ("aus der Trackliste") or is set ("automatisch"). */
    public AlbumConcept withFromList(boolean listed) {
        return new AlbumConcept(description, style, trackCount, minSeconds, maxSeconds, original, listed);
    }

    /** An album of new music (not "Original nachbilden"). */
    public AlbumConcept(String description, String style, int trackCount, int minSeconds, int maxSeconds) {
        this(description, style, trackCount, minSeconds, maxSeconds, false);
    }

    /**
     * Whether the album rebuilds an existing work named in its wish ("Original nachbilden"): the
     * plan takes the real movements with their titles, keys and lengths, the pieces their themes.
     */
    public AlbumConcept withOriginal(boolean rebuild) {
        return new AlbumConcept(description, style, trackCount, minSeconds, maxSeconds, rebuild, fromList);
    }

    /** An album whose pieces all have about the same length. */
    public AlbumConcept(String description, String style, int trackCount, int secondsPerTrack) {
        this(description, style, trackCount, secondsPerTrack, secondsPerTrack);
    }

    /** An album with the usual range of four to six minutes a piece. */
    public static AlbumConcept standard(String description, String style, int trackCount) {
        return new AlbumConcept(description, style, trackCount, DEFAULT_MIN_SECONDS, DEFAULT_MAX_SECONDS);
    }

    /** The middle of the range: about how long a piece is. */
    public int secondsPerTrack() {
        return (minSeconds + maxSeconds) / 2;
    }

    /** Whether a length lies in the range. */
    public boolean fits(int seconds) {
        return seconds >= minSeconds && seconds <= maxSeconds;
    }

    /** About how long the whole album is, in seconds. */
    public int seconds() {
        return trackCount * secondsPerTrack();
    }
}
