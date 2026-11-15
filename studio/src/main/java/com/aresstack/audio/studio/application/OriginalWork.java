package com.aresstack.audio.studio.application;

/**
 * "Original nachbilden": the wish names an existing work (a public-domain composition such as
 * Vivaldi's Four Seasons) or sources, and the AI is to rebuild it as faithfully as it can instead
 * of composing something new about it. Without notes given as a template the AI can only rebuild
 * from what it knows; with notes ({@link ScoreTemplates}) the notes themselves are taken.
 */
public final class OriginalWork {
    /** What the AI is told about a piece that rebuilds an existing work. */
    public static final String GUIDANCE = "REBUILD THE ORIGINAL: the wish names an existing work or sources. Do not "
            + "compose something new about it: reproduce the original as faithfully as you can, with its real "
            + "title and movement, its original key, meter and tempo, its known themes and melodies, harmonies, "
            + "form and instrumentation, in the order and with the lengths of the original.";

    /** What the AI planning an album is told when the album rebuilds an existing work. */
    public static final String ALBUM_GUIDANCE = "REBUILD THE ORIGINAL: the wish names an existing work or sources. "
            + "Plan one track per movement (or per piece) of the original, in its order, with the real titles "
            + "(e.g. \"Spring – I. Allegro\" in the language of the wish), the original keys, meters and tempi, the "
            + "original players, and the length a usual performance has; no length range of the album applies; the meter of each movement is its own (3/4, 12/8 ...), not 4/4 unless the original is. "
            + "In form describe the original sections and themes of the movement. Invent nothing.";

    private OriginalWork() {
    }

    /** The wish with the guidance added when {@code original} is set. */
    public static String wish(String wish, boolean original) {
        String words = wish == null ? "" : wish;
        return original ? words + "\n" + GUIDANCE : words;
    }
}
