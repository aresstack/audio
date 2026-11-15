package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.studio.ai.ContinuationEnding;
import com.aresstack.audio.studio.ai.ContinuationPreset;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.IntensityCurve;
import com.aresstack.audio.studio.ai.TransitionMode;
import com.aresstack.audio.studio.application.RegionReworkTest;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.Test;

/** The form "Stück verlängern" and the form of the piece above the lanes (work package C3, the window's part). */
public final class ExtendFormTest {
    private final Composition piece = RegionReworkTest.flutePiece();

    @Test
    public void untouchedItAsksForAStandalonePieceThatProtectsTheOriginal() {
        ContinuationRequest request = new ExtendForm(piece, 12, 0).read();
        // Open unless the user chooses an ending: a piece that was closed by accident could not be extended well.
        assertEquals(ContinuationRequest.of(32, ContinuationPreset.STANDALONE, ContinuationEnding.OPEN), request);
        assertFalse(request.inserts());
    }

    @Test
    public void itSaysWhatWasChosen() {
        ExtendForm form = new ExtendForm(piece, 12, 0);
        assertFalse("bars of the original are only named when they may change", form.tailBarsEnabled());
        form.choose(24, ContinuationPreset.DRAMATIC_BUILD, " mit Pauken ", ContinuationEnding.LOOP, 3, new int[]{0, 1}, -1, 1);
        assertTrue(form.tailBarsEnabled());
        ContinuationRequest request = form.read();
        assertEquals(24, request.bars());
        assertEquals(ContinuationPreset.DRAMATIC_BUILD, request.preset());
        assertEquals("mit Pauken", request.instruction());
        assertEquals(ContinuationEnding.LOOP, request.ending());
        assertEquals(TransitionMode.REVISE_TAIL, request.transition());
        assertEquals(3, request.tailBars());
        assertEquals(List.of(piece.parts().get(0).id(), piece.parts().get(1).id()), request.partIds());
        assertEquals("a dramatic build brings new instruments unless the user says otherwise", 2, request.newParts());
        assertEquals(IntensityCurve.ramp(0.3, 0.9), request.curve());
    }

    @Test
    public void theNumberOfNewInstrumentsFollowsThePresetUntilTheUserSetsIt() {
        ExtendForm form = new ExtendForm(piece, 12, 0);
        assertEquals(0, form.newPartsShown());
        form.choose(8, ContinuationPreset.DRAMATIC_BUILD, "", ContinuationEnding.CLOSED, 0, new int[0], -1, 0);
        assertEquals(2, form.newPartsShown());
        form.choose(8, ContinuationPreset.DRAMATIC_BUILD, "", ContinuationEnding.CLOSED, 0, new int[0], 1, 0);
        form.choose(8, ContinuationPreset.UNDERSCORE, "", ContinuationEnding.CLOSED, 0, new int[0], -1, 0);
        assertEquals("what the user set stays", 1, form.read().newParts());
        assertNull("the first row leaves the intensity to the AI", form.read().curve());
        assertEquals(0, form.read().tailBars());
    }

    @Test
    public void aFreeDescriptionWithoutWordsIsRefusedInWords() {
        ExtendForm form = new ExtendForm(piece, 12, 0);
        form.choose(8, ContinuationPreset.FREE, "  ", ContinuationEnding.CLOSED, 0, new int[0], -1, 0);
        try {
            form.read();
            fail("a free description needs an instruction");
        } catch (IllegalArgumentException refused) {
            assertEquals("Die freie Beschreibung braucht eine Anweisung.", refused.getMessage());
        }
    }

    @Test
    public void asAnInsertionItNamesTheBarAndStaysOpen() {
        ExtendForm form = new ExtendForm(piece, 12, 5);
        ContinuationRequest request = form.read();
        assertTrue(request.inserts());
        assertEquals(5, request.insertBeforeBar());
        assertEquals(4, request.bars());
        assertEquals("an insertion leads into what follows", ContinuationEnding.OPEN, request.ending());
        form.insertBefore(9);
        assertEquals(9, form.read().insertBeforeBar());
    }

    @Test
    public void theSectionsOfThePieceStandAboveTheLanes() {
        List<SectionMark> marks = SectionMark.of(piece);
        assertEquals(piece.sections().size(), marks.size());
        assertTrue(SectionMark.of(null).isEmpty());
        SectionMark mark = new SectionMark("s2", 4, 4, "Refrain", "chorus", 0.8, List.of("C", "C", "G", "Am"));
        assertEquals("Refrain · chorus", mark.title());
        assertEquals("Refrain · chorus · Takt 5 bis 8 · Intensität 80 % · C | C | G | Am", mark.told());
        assertEquals("G", mark.chordAt(6));
        assertNull(mark.chordAt(8));
        assertEquals("a function that only repeats the name is not said twice", "Intro",
                new SectionMark("s1", 0, 4, "Intro", "intro", null, List.of()).title());

        ArrangementView lanes = new ArrangementView(second -> { });
        SectionBar bar = new SectionBar(lanes);
        assertFalse("a piece without a form shows no bar", bar.isVisible());
        assertEquals(0, bar.getPreferredSize().height);
        bar.setSections(List.of(new SectionMark("s1", 0, 4, "Intro", "intro", 0.3, List.of()), mark));
        assertTrue(bar.isVisible());
        assertEquals(SectionBar.HEIGHT, bar.getPreferredSize().height);
        bar.setSize(600, SectionBar.HEIGHT);
        BufferedImage image = new BufferedImage(600, SectionBar.HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        bar.paint(g); // without lanes below it, it still paints
        g.dispose();
    }
}
