package com.aresstack.audio.studio.template;

import com.aresstack.audio.composition.Key;
import java.util.List;

/** Keys as templates name them, and the key a template most likely is in when it names none. */
public final class TemplateKeys {
    private static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};
    private static final int[] NATURAL_MINOR = {0, 2, 3, 5, 7, 8, 10};

    private TemplateKeys() {
    }

    /**
     * The key of a signature: {@code fifths} sharps (above zero) or flats (below zero), major or
     * minor, as MIDI and MusicXML write it.
     */
    public static Key ofFifths(int fifths, boolean minor) {
        int f = Math.max(-7, Math.min(7, fifths));
        int tonic = Math.floorMod(7 * f + (minor ? 9 : 0), 12);
        return new Key(tonic, minor ? Key.Mode.MINOR : Key.Mode.MAJOR, f < 0);
    }

    /**
     * The key whose scale holds the most of the time the notes sound, the drums left out; of two
     * that hold the same, the one whose tonic sounds longer. C major for a template without notes.
     */
    public static Key estimate(List<ScorePart> parts) {
        long[] weight = new long[12];
        for (ScorePart part : parts) {
            if (part.drums()) {
                continue;
            }
            for (ScoreNote note : part.notes()) {
                weight[Math.floorMod(note.pitch(), 12)] += Math.max(1, note.length());
            }
        }
        Key best = Key.C_MAJOR;
        long bestIn = -1;
        long bestTonic = -1;
        for (Key key : Key.all()) {
            long in = 0;
            for (int step : key.mode() == Key.Mode.MAJOR ? MAJOR : NATURAL_MINOR) {
                in += weight[(key.tonic() + step) % 12];
            }
            long tonic = weight[key.tonic()];
            if (in > bestIn || (in == bestIn && tonic > bestTonic)) {
                best = key;
                bestIn = in;
                bestTonic = tonic;
            }
        }
        return best;
    }
}
