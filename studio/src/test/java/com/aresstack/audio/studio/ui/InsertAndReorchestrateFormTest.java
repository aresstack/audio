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

    @Test
    public void bothHalvesOfAClipSplitAtTheInsertionPointAreShownInTheLane() {
        // An insertion splits the clip that lies across the point; the second half is a clip of its own ("-2").
        Composition piece = RegionReworkTest.flutePiece();
        com.aresstack.audio.composition.Track track = piece.tracks().get(0);
        com.aresstack.audio.composition.MidiClip whole = (com.aresstack.audio.composition.MidiClip) track.clips().get(0);
        long half = whole.lengthTicks() / 2 / 1920 * 1920;
        List<com.aresstack.audio.composition.Note> before = new ArrayList<com.aresstack.audio.composition.Note>();
        List<com.aresstack.audio.composition.Note> after = new ArrayList<com.aresstack.audio.composition.Note>();
        for (com.aresstack.audio.composition.Note note : whole.notes()) {
            if (note.tick() < half) {
                before.add(note);
            } else {
                after.add(new com.aresstack.audio.composition.Note(note.id(), note.tick() - half, note.length(),
                        note.pitch(), note.velocity(), note.voiceId()));
            }
        }
        assertFalse(before.isEmpty() || after.isEmpty());
        long gap = 2 * 1920; // the two inserted bars
        List<com.aresstack.audio.composition.Clip> clips = new ArrayList<com.aresstack.audio.composition.Clip>();
        clips.add(new com.aresstack.audio.composition.MidiClip(whole.id(), whole.startTick(), half, whole.partId(),
                before));
        clips.add(new com.aresstack.audio.composition.MidiClip(whole.id() + "-2", whole.startTick() + half + gap,
                whole.lengthTicks() - half, whole.partId(), after));
        List<com.aresstack.audio.composition.Track> tracks =
                new ArrayList<com.aresstack.audio.composition.Track>(piece.tracks());
        tracks.set(0, new com.aresstack.audio.composition.Track(track.id(), track.name(), track.kind(),
                track.defaultPartId(), clips));

        ArrangementView.Arrangement shown = ArrangementView.Arrangement.of(piece.withTracks(tracks));

        List<ArrangementView.Note> lane = shown.lanes.get(track.id());
        assertEquals("every note of both halves", whole.notes().size(), lane.size());
        long firstOfSecond = Long.MAX_VALUE;
        int inSecond = 0;
        for (ArrangementView.Note note : lane) {
            if ((whole.id() + "-2").equals(note.clipId)) {
                inSecond++;
                firstOfSecond = Math.min(firstOfSecond, note.start);
            }
        }
        assertEquals(after.size(), inSecond);
        assertTrue("the second half lies behind the inserted bars", firstOfSecond >= whole.startTick() + half + gap);
        assertTrue(shown.ticks >= whole.startTick() + whole.lengthTicks() + gap);
    }
}
