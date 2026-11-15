package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.application.CompositionSpec;
import org.junit.Test;

/** The fields for length, tempo, key and meter of a new piece (work package F2, the window's part). */
public final class PieceSpecFieldsTest {
    @Test
    public void untouchedFieldsAskForWhatEveryPieceWasBefore() {
        PieceSpecFields fields = new PieceSpecFields();
        assertEquals("prompts and files of such a piece stay as they were", CompositionSpec.legacy(), fields.spec());
        assertTrue(fields.spec().isLegacy());
        assertFalse(fields.locked());
    }

    @Test
    public void theFieldsSayWhatWasChosen() {
        PieceSpecFields fields = new PieceSpecFields();
        CompositionSpec wanted = CompositionSpec.of(24, 96, Key.parse("D minor"), 6, 8);
        fields.choose(wanted);
        assertEquals(wanted, fields.spec());
        assertFalse("a new piece keeps notes outside the key unless asked otherwise", fields.spec().snapToHarmony());
        fields.choose(wanted.withSnapToHarmony(true));
        assertTrue(fields.spec().snapToHarmony());
        for (Key key : Key.all()) {
            fields.choose(CompositionSpec.of(1, CompositionSpec.MIN_BPM, key, 32, 16));
            assertEquals(key, fields.spec().key());
        }
        fields.choose(CompositionSpec.of(CompositionSpec.MAX_BARS, CompositionSpec.MAX_BPM, Key.C_MAJOR, 1, 1));
        assertEquals(CompositionSpec.MAX_BARS, fields.spec().bars());
        assertEquals(CompositionSpec.MAX_BPM, fields.spec().bpm());
    }

    @Test
    public void aPlannedPieceShowsItsSpecAndCannotBeChangedAndANewOneIsFreeAgain() {
        PieceSpecFields fields = new PieceSpecFields();
        CompositionSpec planned = CompositionSpec.of(12, 140, Key.parse("E major"), 3, 4);
        fields.show(planned);
        assertTrue(fields.locked());
        assertEquals("composing on asks for exactly what the piece was planned with", planned, fields.spec());
        for (java.awt.Component field : fields.getComponents()) {
            if (field instanceof javax.swing.JSpinner || field instanceof javax.swing.JComboBox
                    || field instanceof javax.swing.JCheckBox) {
                assertFalse(field.isEnabled());
            }
        }
        fields.show(null);
        assertFalse(fields.locked());
        assertEquals("a new piece starts from what was shown last", planned, fields.spec());
        for (java.awt.Component field : fields.getComponents()) {
            assertTrue(field.isEnabled());
        }
    }
}
