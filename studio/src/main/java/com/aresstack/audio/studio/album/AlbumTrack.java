package com.aresstack.audio.studio.album;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.application.CompositionSpec;

/**
 * One planned title of an album. It says what the piece is meant to be; the piece itself is an
 * ordinary piece of the library in a folder of its own, named here once it exists.
 *
 * @param id              stays the same while the title is renamed or moved in the list
 * @param title           what the piece is called
 * @param mood            its mood in a few words
 * @param bpm             its tempo
 * @param key             its key as {@link Key#parse} reads it, e.g. "D major"
 * @param meter           its meter, e.g. "4/4" or "3/4"
 * @param instrumentation who plays, in words
 * @param form            how it is built, e.g. "intro, theme, variation, middle part, reprise, outro"
 * @param targetSeconds   about how long it is meant to be
 * @param piece           the name of the folder of the piece in the library, or empty before it exists
 * @param given           whether the user gave this title (and its mood, if any): planning keeps
 *                        both and only fills in the rest
 * @param theme           how the main theme of the album appears in this piece, in words, or empty
 *                        if it does not appear
 */
public record AlbumTrack(String id, String title, String mood, int bpm, String key, String meter,
                         String instrumentation, String form, int targetSeconds, String piece, boolean given,
                         String theme) {
    /** A piece may be this share longer or shorter than asked before the list says so. */
    public static final double LENGTH_TOLERANCE = 0.10;

    public AlbumTrack {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Ein Titel braucht eine Kennung.");
        }
        title = title == null || title.isBlank() ? "Ohne Titel" : title.trim();
        mood = mood == null ? "" : mood.trim();
        instrumentation = instrumentation == null ? "" : instrumentation.trim();
        form = form == null ? "" : form.trim();
        piece = piece == null ? "" : piece.trim();
        theme = theme == null ? "" : theme.trim();
        if (!piece.isEmpty() && !isFolderName(piece)) {
            throw new IllegalArgumentException("Kein Ordnername eines Stücks in der Bibliothek: " + piece);
        }
        bpm = Math.max(CompositionSpec.MIN_BPM, Math.min(CompositionSpec.MAX_BPM, bpm));
        key = keyOf(key).name();
        int[] beats = meterOf(meter);
        meter = beats[0] + "/" + beats[1];
        targetSeconds = Math.max(10, Math.min(AlbumConcept.MAX_SECONDS, targetSeconds));
    }

    /**
     * Whether this names one folder directly in the library of pieces: a single plain name, not
     * {@code .} or {@code ..}, not hidden, without a separator, a drive or a control character.
     * The production writes into this folder, so nothing else is ever accepted, also not from a file.
     */
    public static boolean isFolderName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255 || name.startsWith(".")
                || !name.equals(name.trim()) || name.endsWith(".")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    /** A title planned by the AI, without a part of the main theme. */
    public AlbumTrack(String id, String title, String mood, int bpm, String key, String meter,
                      String instrumentation, String form, int targetSeconds, String piece) {
        this(id, title, mood, bpm, key, meter, instrumentation, form, targetSeconds, piece, false, "");
    }

    /** A title the user gives: only its name and, if said, its mood; planning fills in the rest. */
    public static AlbumTrack given(String id, String title, String mood) {
        return new AlbumTrack(id, title, mood, 100, "", "", "", "", 180, "", true, "");
    }

    /** Whether the piece of this title exists (it may still be unfinished). */
    public boolean hasPiece() {
        return !piece.isEmpty();
    }

    public AlbumTrack withPiece(String folderName) {
        return new AlbumTrack(id, title, mood, bpm, key, meter, instrumentation, form, targetSeconds, folderName,
                given, theme);
    }

    public AlbumTrack withTitle(String changed) {
        return new AlbumTrack(id, changed, mood, bpm, key, meter, instrumentation, form, targetSeconds, piece,
                given, theme);
    }

    public AlbumTrack withMood(String changed) {
        return new AlbumTrack(id, title, changed, bpm, key, meter, instrumentation, form, targetSeconds, piece,
                given, theme);
    }

    public AlbumTrack withBpm(int changed) {
        return new AlbumTrack(id, title, mood, changed, key, meter, instrumentation, form, targetSeconds, piece,
                given, theme);
    }

    public AlbumTrack withKey(String changed) {
        return new AlbumTrack(id, title, mood, bpm, changed, meter, instrumentation, form, targetSeconds, piece,
                given, theme);
    }

    public AlbumTrack withMeter(String changed) {
        return new AlbumTrack(id, title, mood, bpm, key, changed, instrumentation, form, targetSeconds, piece,
                given, theme);
    }

    public AlbumTrack withInstrumentation(String changed) {
        return new AlbumTrack(id, title, mood, bpm, key, meter, changed, form, targetSeconds, piece, given, theme);
    }

    public AlbumTrack withForm(String changed) {
        return new AlbumTrack(id, title, mood, bpm, key, meter, instrumentation, changed, targetSeconds, piece,
                given, theme);
    }

    public AlbumTrack withSeconds(int changed) {
        return new AlbumTrack(id, title, mood, bpm, key, meter, instrumentation, form, changed, piece, given, theme);
    }

    /** The title as given by the user, or no longer: planning keeps a given title and its mood. */
    public AlbumTrack withGiven(boolean changed) {
        return new AlbumTrack(id, title, mood, bpm, key, meter, instrumentation, form, targetSeconds, piece,
                changed, theme);
    }

    /** How the main theme of the album appears in this piece; empty if it does not. */
    public AlbumTrack withTheme(String changed) {
        return new AlbumTrack(id, title, mood, bpm, key, meter, instrumentation, form, targetSeconds, piece,
                given, changed);
    }

    /**
     * What the piece is composed with: tempo, key and meter of the title, and as many bars as its
     * length needs: {@code seconds × bpm ÷ (60 × quarters per bar)}, rounded, at least one and at
     * most {@link CompositionSpec#MAX_BARS}. Five minutes of 4/4 at 120 BPM are 150 bars.
     */
    public CompositionSpec spec() {
        int[] beats = meterOf(meter);
        double quartersPerBar = beats[0] * 4.0 / beats[1];
        long bars = Math.round(targetSeconds * (double) bpm / (60 * quartersPerBar));
        return CompositionSpec.of((int) Math.max(1, Math.min(CompositionSpec.MAX_BARS, bars)), bpm, keyOf(key),
                beats[0], beats[1]);
    }

    /** How long the piece is as it is composed, which differs from the target by the rounding to whole bars. */
    public double plannedSeconds() {
        return spec().seconds();
    }

    /** Whether a length in seconds is what was asked for, within {@link #LENGTH_TOLERANCE}. */
    public boolean asLongAsAsked(double seconds) {
        return Math.abs(seconds - targetSeconds) <= LENGTH_TOLERANCE * targetSeconds;
    }

    /** The key a text names; C major for a text that names none, so that a plan never fails on it. */
    static Key keyOf(String text) {
        if (text != null && !text.isBlank()) {
            try {
                Key key = Key.parse(text.trim());
                if (key != null) {
                    return key;
                }
            } catch (RuntimeException unknown) {
                // not a key
            }
        }
        return Key.C_MAJOR;
    }

    /** The meter a text names as numerator and denominator; 4/4 for a text that names none. */
    static int[] meterOf(String text) {
        if (text != null) {
            String[] parts = text.trim().split("/");
            if (parts.length == 2) {
                try {
                    int numerator = Integer.parseInt(parts[0].trim());
                    int denominator = Integer.parseInt(parts[1].trim());
                    if (numerator >= 1 && numerator <= 32 && denominator >= 1 && denominator <= 16
                            && Integer.bitCount(denominator) == 1) {
                        return new int[]{numerator, denominator};
                    }
                } catch (NumberFormatException notNumbers) {
                    // not a meter
                }
            }
        }
        return new int[]{4, 4};
    }
}
