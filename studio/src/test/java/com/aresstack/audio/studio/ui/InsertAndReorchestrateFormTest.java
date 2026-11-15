package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.studio.ai.IntensityCurve;
import com.aresstack.audio.studio.ai.OrchestrationPreset;
import com.aresstack.audio.studio.ai.ReorchestrationRequest;
import com.aresstack.audio.studio.application.RegionReworkTest;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import org.junit.Test;

/** The forms and controls of work package C4 in the window: confirming shortened notes, "Umorchestrieren", intensity per section. */
public final class InsertAndReorchestrateFormTest {
    private final Composition piece = RegionReworkTest.flutePiece();

    @Test
    public void everyLossIsShownAndTakingWaitsForTheTick() {
        List<String> losses = List.of("Flöte: Note G4 ab Takt 4, Schlag 3 · im Original 14 Viertel lang, bleibt 10 Viertel",
                "Bass: Note C2 ab Takt 4, Schlag 1 · im Original 16 Viertel lang, bleibt 12 Viertel");
        int[] changes = new int[1];
        LossConfirmation confirmation = new LossConfirmation(losses, () -> changes[0]++);
        assertEquals("every shortened note is listed", losses, confirmation.lines());
        assertEquals("Ich habe die 2 gekürzten Noten gesehen und bin einverstanden", confirmation.question());
        assertFalse("nothing is confirmed before the user says so", confirmation.confirmed());
        confirmation.tick(true);
        assertTrue(confirmation.confirmed());
        assertEquals(1, changes[0]);
        confirmation.tick(false);
        assertFalse("the word can be taken back", confirmation.confirmed());
        assertEquals(2, changes[0]);
    }

    @Test
    public void theMarkedPassageFillsTheOrchestrationForm() {
        String flute = piece.parts().get(0).id();
        ReorchestrateForm form = new ReorchestrateForm(piece, 12, new ArrangementView.Range(List.of(flute), 5, 8));
        ReorchestrationRequest request = form.read();
        assertEquals(5, request.firstBar());
        assertEquals(8, request.lastBar());
        assertEquals(List.of(flute), request.partIds());
        assertEquals(OrchestrationPreset.REDISTRIBUTE, request.preset());
        assertNull(request.curve());
        assertEquals("without a mark: the whole piece and every voice",
                ReorchestrationRequest.of(1, 12, OrchestrationPreset.REDISTRIBUTE),
                new ReorchestrateForm(piece, 12, null).read());
    }

    @Test
    public void theOrchestrationFormSaysWhatWasChosenAndRefusesInWords() {
        ReorchestrateForm form = new ReorchestrateForm(piece, 12, null);
        form.choose(3, 6, new int[]{0, 1}, OrchestrationPreset.FOLLOW_INTENSITY, " heller ", 2, 3);
        ReorchestrationRequest request = form.read();
        assertEquals(3, request.firstBar());
        assertEquals(6, request.lastBar());
        assertEquals(List.of(piece.parts().get(0).id(), piece.parts().get(1).id()), request.partIds());
        assertEquals(OrchestrationPreset.FOLLOW_INTENSITY, request.preset());
        assertEquals("heller", request.instruction());
        assertEquals(2, request.newParts());
        assertEquals(IntensityCurve.arch(0.3, 0.9, 0.4), request.curve());
        form.choose(6, 3, new int[0], OrchestrationPreset.REDISTRIBUTE, "", 0, 0);
        try {
            form.read();
            fail("a passage ends after it begins");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().startsWith("Ein Bereich reicht von Takt 1 an"));
        }
        form.choose(3, 6, new int[0], OrchestrationPreset.FREE, " ", 0, 0);
        try {
            form.read();
            fail("a free description needs an instruction");
        } catch (IllegalArgumentException refused) {
            assertEquals("Die freie Beschreibung braucht eine Anweisung.", refused.getMessage());
        }
    }

    @Test
    public void theIntensityOfASectionIsSetFromTheSectionBar() {
        SectionBar bar = new SectionBar(new ArrangementView(second -> { }));
        SectionMark chorus = new SectionMark("chorus", 4, 4, "Refrain", "chorus", 0.8, List.of());
        bar.setSections(List.of(chorus));
        final List<String> told = new ArrayList<String>();
        bar.setListener((section, intensity) -> told.add(section.id() + " " + intensity));
        JPopupMenu menu = bar.intensityMenu(chorus);
        JSlider slider = (JSlider) named(menu, "intensity");
        assertEquals("the slider starts at the intensity the section has", 80, slider.getValue());
        slider.setValue(35);
        assertTrue("moving the slider alone changes nothing", told.isEmpty());
        ((JButton) named(menu, "set")).doClick();
        assertEquals(List.of("chorus 0.35"), told);
        assertEquals("a section that names no intensity starts in the middle", 50,
                ((JSlider) named(bar.intensityMenu(new SectionMark("x", 0, 1, "X", "", null, List.of())),
                        "intensity")).getValue());
    }

    private static java.awt.Component named(java.awt.Container within, String name) {
        for (java.awt.Component component : within.getComponents()) {
            if (name.equals(component.getName())) {
                return component;
            }
            if (component instanceof java.awt.Container) {
                java.awt.Component found = named((java.awt.Container) component, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
