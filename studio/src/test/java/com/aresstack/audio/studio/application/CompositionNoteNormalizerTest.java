package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.Test;

/** F2: the normalizer repairs against the key and the harmony plan, not against C major. */
public final class CompositionNoteNormalizerTest {
    private static final CompositionSpec D_MINOR = CompositionSpec.of(24, 96, Key.parse("D minor"), 4, 4);
    private static final List<String> CHORDS = List.of("Dm", "Gm", "A7", "Dm");

    private static JsonObject note(double beat, double duration, int pitch) {
        JsonObject note = new JsonObject();
        note.addProperty("beat", beat);
        note.addProperty("duration", duration);
        note.addProperty("pitch", pitch);
        note.addProperty("velocity", 80);
        return note;
    }

    @Test
    public void correctNotesInDMinorStayAsTheyAre() {
        int[] melody = {62, 64, 65, 67, 69, 70, 72, 73, 71, 74, 77, 81};
        for (boolean snap : new boolean[]{false, true}) {
            CompositionNoteNormalizer normalizer = new CompositionNoteNormalizer(m -> { },
                    D_MINOR.withSnapToHarmony(snap), CHORDS);
            for (int i = 0; i < melody.length; i++) {
                assertEquals("snap " + snap + ": " + melody[i], melody[i],
                        normalizer.normalize(note(i, 1, melody[i]), "melody", "melody", i + 1).pitch());
            }
            // Chord tones of A7 in bar 3, including the leading tone C sharp.
            for (int pitch : new int[]{57, 61, 64, 67}) {
                assertEquals(pitch, normalizer.normalize(note(8, 2, pitch), "harmony", "harmony", 1).pitch());
            }
            assertEquals("snap " + snap, 0, normalizer.correctionCount());
        }
    }

    @Test
    public void withoutSnappingAToneOutsideTheKeyIsKeptAndReported() {
        CompositionNoteNormalizer normalizer = new CompositionNoteNormalizer(m -> { }, D_MINOR, CHORDS);
        assertEquals(63, normalizer.normalize(note(0, 1, 63), "melody", "melody", 1).pitch());
        assertEquals(1, normalizer.correctionCount());
        assertTrue(normalizer.corrections().get(1).endsWith("Outside D minor; kept"));
        // Out of range: moved by octaves, the pitch class stays.
        assertEquals(86, normalizer.normalize(note(1, 1, 98), "melody", "melody", 2).pitch());
    }

    @Test
    public void withSnappingAToneMovesToTheKeyOrTheChordOfItsBar() {
        CompositionNoteNormalizer normalizer = new CompositionNoteNormalizer(m -> { },
                D_MINOR.withSnapToHarmony(true), CHORDS);
        assertEquals(62, normalizer.normalize(note(0, 1, 63), "melody", "melody", 1).pitch());
        // Bar 2 is G minor (G, B flat, D): an E becomes the nearest chord tone, D.
        assertEquals(62, normalizer.normalize(note(4, 1, 64), "harmony", "harmony", 2).pitch());
        assertEquals(67, normalizer.normalize(note(4, 1, 67), "harmony", "harmony", 3).pitch());
    }

    @Test
    public void sectionsCountTheirBeatsFromTheirStartAndEndThere() {
        CompositionNoteNormalizer normalizer = new CompositionNoteNormalizer(m -> { }, D_MINOR, CHORDS);
        CompositionNoteNormalizer.NormalizedNote note = normalizer.normalize(note(31.5, 4, 62), "melody", "melody", 1, 8, 8);
        assertEquals(8 * 1920 + 31.5 * 480, note.startTick(), 0);
        assertEquals("cut at the end of the section", 240, note.durationTicks());
        assertNull(normalizer.normalize(note(32, 1, 62), "melody", "melody", 2, 8, 8));
    }

    /** PO F2: C9 holds D, Cmaj9 holds B and D; a wrong reading of the chord must not move correct notes. */
    @Test
    public void ninthChordsKeepTheirNinthAndTheirSeventh() {
        CompositionSpec spec = CompositionSpec.of(4, 100, Key.C_MAJOR, 4, 4).withSnapToHarmony(true);
        CompositionNoteNormalizer normalizer = new CompositionNoteNormalizer(m -> { }, spec,
                List.of("C9", "Cmaj9", "Cmaj9", "C7alt"));
        // C9 in bar 1: C E G B flat D all stay.
        for (int pitch : new int[]{60, 64, 67, 70, 62}) {
            assertEquals("C9 " + pitch, pitch, normalizer.normalize(note(0, 1, pitch), "harmony", "harmony", 1).pitch());
        }
        // Cmaj9 in bar 2: C E G B D all stay.
        for (int pitch : new int[]{60, 64, 67, 71, 62}) {
            assertEquals("Cmaj9 " + pitch, pitch, normalizer.normalize(note(4, 1, pitch), "harmony", "harmony", 2).pitch());
        }
        assertEquals(0, normalizer.correctionCount());
        // A B flat is no tone of Cmaj9: it moves to the major seventh B.
        assertEquals(71, normalizer.normalize(note(8, 1, 70), "harmony", "harmony", 3).pitch());
        // An unknown symbol is no chord: the scale of the key applies, D stays.
        assertEquals(62, normalizer.normalize(note(12, 1, 62), "harmony", "harmony", 4).pitch());
    }
}
