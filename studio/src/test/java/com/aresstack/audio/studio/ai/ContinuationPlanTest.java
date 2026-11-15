package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.Track;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** C3, acceptance plan 7.2: the plan covers exactly the new bars, whatever the planning answer says. */
public final class ContinuationPlanTest {
    private static final Composition PIECE = ContinuationContextTest.piece(8);

    private static ContinuationTask task(ContinuationRequest request) {
        return ContinuationTask.of(PIECE, request, 1);
    }

    private static ContinuationPlan read(String answer, ContinuationRequest request) {
        return ChatContinuationPlanner.read(JsonParser.parseString(answer).getAsJsonObject(), task(request), PIECE);
    }

    @Test
    public void theFallbackCoversExactlyTheNewBarsForEveryPresetAndLength() {
        for (ContinuationPreset preset : ContinuationPreset.values()) {
            for (ContinuationEnding ending : ContinuationEnding.values()) {
                for (int bars = 1; bars <= 70; bars++) {
                    ContinuationRequest request = ContinuationRequest.of(bars, preset, ending, "weiter");
                    ContinuationPlan plan = ContinuationPlan.fallback(task(request), PIECE);
                    assertEquals(preset + " " + bars, bars, plan.bars());
                    for (ContinuationPlan.PlannedSection section : plan.sections()) {
                        assertTrue(section.bars() <= ContinuationPlan.MAX_SECTION_BARS);
                        assertEquals(List.of("melody"), section.parts());
                        assertEquals(section.bars(), section.chords().size());
                    }
                }
            }
        }
    }

    @Test
    public void aDramaticBuildRisesToItsClimaxAndAClosedEndingResolvesOnTheTonic() {
        ContinuationRequest request = ContinuationRequest.of(32, ContinuationPreset.DRAMATIC_BUILD,
                ContinuationEnding.CLOSED);
        ContinuationPlan plan = ContinuationPlan.fallback(task(request), PIECE);
        List<Double> intensities = plan.sections().stream().map(ContinuationPlan.PlannedSection::intensity).toList();
        assertEquals(List.of(0.4, 0.7, 1.0, 0.4), intensities);
        assertEquals(List.of("Aufbau 1", "Aufbau 2", "Höhepunkt", "Auflösung"),
                plan.sections().stream().map(ContinuationPlan.PlannedSection::name).toList());
        List<String> last = plan.sections().get(3).chords();
        assertEquals("C", last.get(last.size() - 1));
        // open, the build ends at its height
        ContinuationPlan open = ContinuationPlan.fallback(task(ContinuationRequest.of(16, ContinuationPreset.DRAMATIC_BUILD,
                ContinuationEnding.OPEN)), PIECE);
        assertEquals(List.of(0.4, 1.0), open.sections().stream().map(ContinuationPlan.PlannedSection::intensity).toList());
    }

    @Test
    public void aPlanningAnswerIsCompletedToTheNewBars() {
        ContinuationRequest request = ContinuationRequest.of(24, ContinuationPreset.STANDALONE, ContinuationEnding.CLOSED)
                .withNewParts(1);
        ContinuationPlan plan = read("{\"sections\":["
                + "{\"name\":\"Eins\",\"bars\":20,\"intensity\":0.6,\"chords\":[\"Am\",\"F\"],\"parts\":[\"melody\",\"neu\"]},"
                + "{\"name\":\"Zwei\",\"bars\":2,\"intensity\":0.3,\"chords\":[\"G\",\"G7\"],\"parts\":[]},"
                + "{\"name\":\"Ohne Takte\",\"intensity\":0.3}],"
                + "\"newParts\":[{\"key\":\"neu\",\"name\":\"Horn\",\"program\":60,\"role\":\"solo\"},"
                + "{\"key\":\"zu viel\",\"name\":\"Tuba\",\"program\":58,\"role\":\"bass\"}]}", request);
        assertEquals(24, plan.bars());
        // 20 bars are split, the last section is lengthened to reach bar 24, the closing chord is the tonic
        assertEquals(List.of(10, 10, 4), plan.sections().stream().map(ContinuationPlan.PlannedSection::bars).toList());
        assertEquals(List.of("G", "G7", "G7", "C"), plan.sections().get(2).chords());
        assertEquals(List.of("melody"), plan.sections().get(2).parts());
        // one new part allowed: the horn, renamed to new1 and kept as texture; the tuba is dropped
        assertEquals(1, plan.newParts().size());
        assertEquals("new1", plan.newParts().get(0).key());
        assertEquals("texture", plan.newParts().get(0).role());
        assertEquals(List.of("melody", "new1"), plan.sections().get(0).parts());
        assertTrue(plan.repairs().stream().anyMatch(r -> r.contains("Tuba")));
        assertTrue(plan.repairs().stream().anyMatch(r -> r.contains("Ohne Takte")));
        assertTrue(plan.repairs().stream().anyMatch(r -> r.contains("geteilt")));

        // the sections of the piece follow the original without gap
        List<String> ids = new ArrayList<>(List.of("x1", "x2", "x3"));
        List<Section> sections = plan.asSections(task(request), ids);
        assertEquals(8 * 1920, sections.get(0).startTick());
        assertEquals(32 * 1920, sections.get(2).endTick());
    }

    @Test
    public void anUnusableAnswerGivesThePlanOfThePreset() {
        ContinuationRequest request = ContinuationRequest.of(16, ContinuationPreset.UNDERSCORE, ContinuationEnding.OPEN);
        ContinuationPlan plan = read("{\"text\":\"I would continue with strings\"}", request);
        assertEquals(16, plan.bars());
        assertEquals(ContinuationPlan.fallback(task(request), PIECE).sections(), plan.sections());
        assertTrue(plan.repairs().get(plan.repairs().size() - 1).contains("Filmmusik / Underscore"));
    }

    @Test
    public void thePromptSaysPresetEndingTransitionAndParts() {
        ContinuationRequest request = ContinuationRequest.of(16, ContinuationPreset.FREE, ContinuationEnding.LOOP,
                "wie ein Wiegenlied").revisingTail(2);
        String prompt = ChatContinuationPlanner.prompt(task(request));
        assertTrue(prompt.contains("User instruction: wie ein Wiegenlied"));
        assertTrue(prompt.contains("seamless loop"));
        assertTrue(prompt.contains("bars 7-8) will be rewritten"));
        assertTrue(prompt.contains("id=melody, name=\"Flöte\""));
        assertTrue(prompt.contains("Do not add new instruments"));
        assertTrue(prompt.contains("add up to exactly 16"));
    }

    @Test
    public void aNewPartNeverTakesTheIdOfAnExistingPart() {
        Composition piece = ContinuationContextTest.piece(8);
        Composition renamed = new Composition(piece.meta(), piece.timeMap(), piece.sections(),
                List.of(new Part("new1", "Alt", null, null, 0, null, null, piece.parts().get(0).voices(), null)),
                List.of(new Track("t1", "Alt", Track.Kind.MIDI, "new1", piece.tracks().get(0).clips())), List.of());
        ContinuationRequest request = ContinuationRequest.of(8, ContinuationPreset.DRAMATIC_BUILD, ContinuationEnding.OPEN);
        ContinuationTask task = ContinuationTask.of(renamed, request, 1);
        ContinuationPlan plan = ChatContinuationPlanner.read(JsonParser.parseString("{\"sections\":[{\"name\":\"A\","
                + "\"bars\":8,\"intensity\":0.5,\"parts\":[\"new1\",\"x\"]}],\"newParts\":[{\"key\":\"x\","
                + "\"name\":\"Horn\",\"program\":60}]}").getAsJsonObject(), task, renamed);
        assertEquals("new2", plan.newParts().get(0).key());
        assertEquals("texture", plan.newParts().get(0).role()); // a missing role is repaired, not a crash
        assertEquals(List.of("new1", "new2"), plan.sections().get(0).parts());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aFreeContinuationNeedsAnInstruction() {
        ContinuationRequest.of(8, ContinuationPreset.FREE, ContinuationEnding.OPEN);
    }
}
