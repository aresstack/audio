package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.album.AlbumTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JComboBox;
import org.junit.Test;

/** Length, tempo, key and meter in one row: each automatic until the user chooses or types a value. */
public final class SpecChoiceTest {
    @SuppressWarnings("unchecked")
    private static List<JComboBox<Object>> boxes(SpecChoice choice) {
        List<JComboBox<Object>> boxes = new ArrayList<JComboBox<Object>>();
        for (java.awt.Component each : choice.getComponents()) {
            if (each instanceof JComboBox) {
                boxes.add((JComboBox<Object>) each);
            }
        }
        return boxes;
    }

    @Test
    public void everythingIsAutomaticUntilTheUserSetsIt() {
        SpecChoice choice = new SpecChoice(SpecChoice.Length.BARS);
        choice.show(new SpecChoice.Values(40, 96, Key.parse("D minor"), 6, 8), Set.of());
        assertTrue(choice.fixed().isEmpty());
        assertEquals("the automatic fields stand for what was shown", 40, choice.values().length(), 0);
        for (JComboBox<Object> box : boxes(choice)) {
            assertEquals("automatisch", box.getSelectedItem().toString());
        }

        List<JComboBox<Object>> boxes = boxes(choice);
        boxes.get(0).getEditor().setItem("64"); // typed, not yet confirmed: counts as typed
        boxes.get(1).setSelectedItem("120");
        boxes.get(3).getEditor().setItem("5/8");
        assertEquals(Set.of(AlbumTrack.LENGTH, AlbumTrack.BPM, AlbumTrack.METER), choice.fixed());
        SpecChoice.Values values = choice.values();
        assertEquals(64, values.length(), 0);
        assertEquals(120, values.bpm());
        assertEquals(5, values.numerator());
        assertEquals(8, values.denominator());
        assertEquals("the key is still the AI's", Key.parse("D minor"), values.key());

        boxes.get(0).getEditor().setItem("");
        assertEquals("an emptied field is automatic again", Set.of(AlbumTrack.BPM, AlbumTrack.METER), choice.fixed());
    }

    @Test
    public void theAutomaticFieldsSayWhatTheAiChoseAndTheUsersStay() {
        SpecChoice choice = new SpecChoice(SpecChoice.Length.MINUTES);
        choice.show(new SpecChoice.Values(4.5, 90, Key.C_MAJOR, 3, 4), Set.of(AlbumTrack.LENGTH));
        assertEquals("4,5", boxes(choice).get(0).getEditor().getItem().toString());

        choice.showChosen(new SpecChoice.Values(3, 132, Key.parse("E major"), 6, 8));

        assertEquals(Set.of(AlbumTrack.LENGTH), choice.fixed());
        SpecChoice.Values values = choice.values();
        assertEquals("what the user set stays", 4.5, values.length(), 0);
        assertEquals(132, values.bpm());
        assertEquals(Key.parse("E major"), values.key());
        assertEquals("auto · 132", boxes(choice).get(1).getEditor().getItem().toString());
        assertEquals("auto · 6/8", boxes(choice).get(3).getEditor().getItem().toString());
    }

    @Test
    public void whatCannotBeAValueIsRefusedInWords() {
        SpecChoice choice = new SpecChoice(SpecChoice.Length.BARS);
        for (String[] wrong : new String[][]{{"0", "0"}, {"0", "viele"}, {"1", "9999"}, {"3", "4"}, {"3", "4/5"}}) {
            choice.show(new SpecChoice.Values(8, 108, Key.C_MAJOR, 4, 4), Set.of());
            boxes(choice).get(Integer.parseInt(wrong[0])).getEditor().setItem(wrong[1]);
            try {
                choice.values();
                fail(wrong[1] + " is no value");
            } catch (IllegalArgumentException said) {
                assertTrue(said.getMessage().length() > 5);
            }
        }
        choice.show(new SpecChoice.Values(8, 108, Key.C_MAJOR, 4, 4), Set.of());
        choice.lock(true);
        for (JComboBox<Object> box : boxes(choice)) {
            assertTrue(!box.isEnabled());
        }
    }
}
