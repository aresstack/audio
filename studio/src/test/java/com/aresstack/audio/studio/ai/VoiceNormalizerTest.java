package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.studio.ai.GeneratedNotes.RawNote;
import com.aresstack.audio.studio.ai.VoiceNormalizer.ShapedNote;
import java.util.List;
import org.junit.Test;

/** C1: formal repairs only; pitches limited to the instrument, notes outside the key recorded, not moved. */
public final class VoiceNormalizerTest {
    private static final long BAR = 1920;
    private static final TimeMap D_MINOR = new TimeMap(List.of(new TimeMap.Tempo(0, 96)),
            List.of(new TimeMap.Meter(0, 4, 4)), List.of(new TimeMap.KeyChange(0, new Key(2, Key.Mode.MINOR))));
    /** Bars 5 to 8 of the piece. */
    private static final VoiceFrame FRAME = new VoiceFrame("b", "B", null, 4 * BAR, 8 * BAR, 4, 4, List.of(), null, null);

    private static GeneratedNotes notes(RawNote... notes) {
        return new GeneratedNotes("tp", "b", List.of(notes), List.of());
    }

    @Test
    public void correctNotesInDMinorStayAsTheyAre() {
        VoiceNormalizer normalizer = new VoiceNormalizer(D_MINOR, InstrumentRange.of(56, false), false);
        List<ShapedNote> shaped = normalizer.shape(FRAME, notes(new RawNote(1, 1, 0, 1, 62, 90, null),
                new RawNote(2, 2, 1.5, 0.5, 65, 80, null), new RawNote(3, 4, 3, 1, 73, 70, null)));
        assertEquals(List.of(new ShapedNote(0, 480, 62, 90), new ShapedNote(BAR + 720, 240, 65, 80),
                new ShapedNote(3 * BAR + 1440, 480, 73, 70)), shaped);
        assertEquals(List.of(), normalizer.corrections());
    }

    @Test
    public void repairsAreFormalAndRecorded() {
        VoiceNormalizer normalizer = new VoiceNormalizer(D_MINOR, InstrumentRange.of(56, false), false);
        List<ShapedNote> shaped = normalizer.shape(FRAME, notes(
                new RawNote(1, 1, 0.1, 0.3, 62, 200, null),   // grid, velocity
                new RawNote(2, 4, 3, 4, 64, 80, null),        // sounds past the end: cut
                new RawNote(3, 2, 0, 1, 30, 80, null),        // below a trumpet: limited to its range
                new RawNote(4, 3, 0, 1, 66, 80, null),        // F sharp in D minor: recorded, unchanged
                new RawNote(5, 5, 0, 1, 62, 80, null),        // bar 5 of a four-bar section: left out
                new RawNote(6, 1, 4, 1, 62, 80, null),        // beat 4 of a 4/4 bar: left out
                new RawNote(7, 1, 0, 1, 62, 80, null)));      // same tick and pitch as note 1: left out
        assertEquals(List.of(new ShapedNote(0, 120, 62, 127), new ShapedNote(3 * BAR + 1440, 480, 64, 80),
                new ShapedNote(BAR, 480, 54, 80), new ShapedNote(2 * BAR, 480, 66, 80)), shaped);
        String all = String.join("\n", normalizer.corrections());
        assertTrue(all, all.contains("b\t1\tbeat\t0.1\t0\tauf Sechzehntelraster"));
        assertTrue(all, all.contains("b\t1\tvelocity\t200\t127"));
        assertTrue(all, all.contains("b\t2\tduration\t4\t1\tam Abschnittsende abgeschnitten"));
        assertTrue(all, all.contains("b\t3\tpitch\t30\t54\tauf den Tonumfang des Instruments begrenzt"));
        assertTrue(all, all.contains("b\t4\tpitch\t66\t66\tleiterfremd in D minor (nicht verändert)"));
        assertTrue(all, all.contains("b\t5\tnote\tpresent\tskipped"));
        assertTrue(all, all.contains("b\t6\tnote\tpresent\tskipped"));
        assertTrue(all, all.contains("b\t7\tnote\tpresent\tskipped\tgleiche Tonhöhe"));
    }

    @Test
    public void drumsAreNotCheckedAgainstTheKey() {
        VoiceNormalizer normalizer = new VoiceNormalizer(D_MINOR, InstrumentRange.of(0, true), true);
        List<ShapedNote> shaped = normalizer.shape(FRAME, notes(new RawNote(1, 1, 0, 0.25, 42, 70, null),
                new RawNote(2, 1, 0, 0.25, 100, 70, null)));
        assertEquals(List.of(new ShapedNote(0, 120, 42, 70), new ShapedNote(0, 120, 87, 70)), shaped);
        assertEquals(1, normalizer.corrections().size());
    }
}
