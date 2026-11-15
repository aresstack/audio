package com.aresstack.audio.studio.score;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Voice;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The words for voices (S1) and how a part without them is voiced. */
public final class VoicingTest {
    @Test
    public void anAnswerMayNameAVoiceInManyWays() {
        assertEquals("rh", Voicing.normalize("RH", true));
        assertEquals("rh", Voicing.normalize("right hand", true));
        assertEquals("lh", Voicing.normalize("left", true));
        assertEquals("lh2", Voicing.normalize("lh 2", true));
        assertEquals("rh", Voicing.normalize("treble", true));
        assertNull("a number names no hand", Voicing.normalize("1", true));
        assertEquals("2", Voicing.normalize("2", false));
        assertEquals("3", Voicing.normalize("voice 3", false));
        assertNull("a hand is no voice of a single staff", Voicing.normalize("rh", false));
        assertNull(Voicing.normalize("9", false));
        assertNull(Voicing.normalize("soprano", false));
        assertNull(Voicing.normalize("  ", false));
        assertNull(Voicing.normalize(null, true));
    }

    @Test
    public void voicesAreOrderedFromTheTopDown() {
        List<String> tokens = new ArrayList<>(List.of("lh", "rh2", "lh2", "rh"));
        tokens.sort(Voicing.ORDER);
        assertEquals(List.of("rh", "rh2", "lh", "lh2"), tokens);
        assertEquals(Voice.Staff.LOWER, Voicing.staffOf("lh2"));
        assertNull(Voicing.staffOf("3"));
        assertEquals("Linke Hand 2", Voicing.name("lh2"));
        assertEquals(Voice.Stem.AUTO, Voicing.stem("1", List.of("1")));
        assertEquals(Voice.Stem.DOWN, Voicing.stem("lh2", List.of("lh", "lh2")));
    }

    @Test
    public void pianoOrganAndHarpAreWrittenOnAGrandStaff() {
        assertEquals(Part.StaffLayout.GRAND, Voicing.layoutFor(0, false));
        assertEquals(Part.StaffLayout.GRAND, Voicing.layoutFor(19, false));
        assertEquals(Part.StaffLayout.GRAND, Voicing.layoutFor(46, false));
        assertEquals(Part.StaffLayout.SINGLE, Voicing.layoutFor(73, false));
        assertEquals(Part.StaffLayout.SINGLE, Voicing.layoutFor(0, true));
    }

    @Test
    public void unnamedNotesOfAnAnswerThatNamesVoicesGoWhereTheirPitchSuggests() {
        List<long[]> notes = List.of(new long[]{0, 480, 72}, new long[]{0, 480, 50}, new long[]{0, 480, 65});
        assertArrayEquals(new String[]{"rh", "lh", "rh2"},
                Voicing.assign(notes, new String[]{null, null, "rh2"}, false, true));
        assertArrayEquals(new String[]{"1", "1", "1"}, Voicing.assign(notes, new String[]{"x", "y", "z"}, true, false));
        assertArrayEquals("nothing named: the heuristic", new String[]{"rh", "lh", "rh"},
                Voicing.assign(notes, new String[]{null, "soprano", null}, false, true));
    }

    @Test
    public void theVoicesOfAPartAreCountedPerSystem() {
        Part piano = new Part("piano", null, "harmony", null, 0, null, Part.StaffLayout.GRAND,
                List.of(new Voice("a", null, Voice.Staff.UPPER, null), new Voice("b", null, Voice.Staff.LOWER, null),
                        new Voice("c", null, Voice.Staff.UPPER, null)), null);
        assertEquals(List.of("rh", "lh", "rh2"), List.copyOf(Voicing.tokens(piano).values()));
    }
}
