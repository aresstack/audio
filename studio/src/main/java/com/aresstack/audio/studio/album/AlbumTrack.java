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
 */
public record AlbumTrack(String id, String title, String mood, int bpm, String key, String meter,
                         String instrumentation, String form, int targetSeconds, String piece) {
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
        bpm = Math.max(CompositionSpec.MIN_BPM, Math.min(CompositionSpec.MAX_BPM, bpm));
        key = keyOf(key).name();
        int[] beats = meterOf(meter);
        meter = beats[0] + "/" + beats[1];
        targetSeconds = Math.max(10, Math.min(AlbumConcept.MAX_SECONDS, targetSeconds));
    }

    /** Whether the piece of this title exists (it may still be unfinished). */
    public boolean hasPiece() {
        return !piece.isEmpty();
    }

    public AlbumTrack withPiece(String folderName) {
        return new AlbumTrack(id, title, mood, bpm, key, meter, instrumentation, form, targetSeconds, folderName);
    }

    public AlbumTrack withTitle(String changed) {
        return new AlbumTrack(id, changed, mood, bpm, key, meter, instrumentation, form, targetSeconds, piece);
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
