package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;

import com.aresstack.audio.core.mix.ExportNormalization;
import org.junit.Test;

/** The choice of a normalization beside the export dialog. */
public final class ExportLevelChoiceTest {
    @Test
    public void nothingIsNormalizedUnlessChosen() {
        assertEquals(ExportNormalization.Kind.NONE, new ExportLevelChoice(true, null).chosen().kind());
    }

    @Test
    public void loudnessStartsAtTheUsualTargetAndPeakJustBelowFullLevel() {
        ExportLevelChoice choice = new ExportLevelChoice(true, null);
        choice.choose(ExportNormalization.Kind.LOUDNESS, ExportLevelChoice.USUAL_LOUDNESS);
        assertEquals(ExportNormalization.Kind.LOUDNESS, choice.chosen().kind());
        assertEquals(-16, choice.chosen().target(), 0);
        assertEquals(-1, choice.chosen().peakCeilingDb(), 0);
        choice.choose(ExportNormalization.Kind.PEAK, -3);
        assertEquals(ExportNormalization.Kind.PEAK, choice.chosen().kind());
        assertEquals(-3, choice.chosen().target(), 0);
    }

    @Test
    public void theLastChoiceIsOfferedAgain() {
        ExportLevelChoice choice = new ExportLevelChoice(true, ExportNormalization.loudness(-14));
        assertEquals(ExportNormalization.Kind.LOUDNESS, choice.chosen().kind());
        assertEquals(-14, choice.chosen().target(), 0);
    }

    @Test
    public void aPieceThatIsMixedToItsPeakCannotBeNormalized() {
        ExportLevelChoice choice = new ExportLevelChoice(false, ExportNormalization.loudness(-14));
        assertEquals(ExportNormalization.Kind.NONE, choice.chosen().kind());
        choice.choose(ExportNormalization.Kind.PEAK, -3);
        assertEquals(ExportNormalization.Kind.NONE, choice.chosen().kind());
    }
}
