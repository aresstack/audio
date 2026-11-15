package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.ai.PartialJson;
import com.aresstack.audio.studio.progress.FormSection;
import java.util.List;
import org.junit.Test;

/** The form of a new piece is seen while its plan arrives: section by section, chord by chord. */
public final class PlanFormIsSeenTest {
    private static final String PLAN = "{\"title\":\"Morgen\",\"bars\":12,\"sections\":[{\"name\":\"Auftakt\","
            + "\"function\":\"intro\",\"bars\":4},{\"name\":\"Thema\",\"function\":\"theme\",\"bars\":6}],"
            + "\"chords\":[\"D\",\"A\",\"Bm\",\"G\",\"D\",\"A\",\"G\",\"A\",\"D\",\"G\",\"A\",\"D\"]}";
    private final CompositionSpec spec = CompositionSpec.of(12, 96, Key.parse("D major"), 4, 4);

    @Test
    public void beforeASectionIsNamedThePieceIsOneSectionOverAllItsBars() {
        List<FormSection> form = GemmaMusicComposer.seenForm(null, spec, false);
        assertEquals(1, form.size());
        assertEquals(12, form.get(0).bars());
        assertEquals("A", form.get(0).name());
    }

    @Test
    public void sectionsAndChordsComeOneByOne() {
        List<FormSection> first = GemmaMusicComposer.seenForm(
                PartialJson.read(PLAN.substring(0, PLAN.indexOf("{\"name\":\"Thema\""))), spec, false);
        assertEquals(1, first.size());
        assertEquals("Auftakt", first.get(0).name());
        assertEquals(4, first.get(0).bars());

        List<FormSection> chords = GemmaMusicComposer.seenForm(
                PartialJson.read(PLAN.substring(0, PLAN.indexOf("\"G\",\"A\",\"D\",\"G\""))), spec, false);
        assertEquals(2, chords.size());
        assertEquals(List.of("D", "A", "Bm", "G"), chords.get(0).chords());
        assertEquals(List.of("D", "A"), chords.get(1).chords());
        assertEquals(4, chords.get(1).firstBar());

        List<FormSection> whole = GemmaMusicComposer.seenForm(PartialJson.read(PLAN), spec, true);
        assertEquals("the last section takes the bars the plan left open", 8, whole.get(1).bars());
        assertEquals(8, whole.get(1).chords().size());
        int seen = 0;
        for (int cut = PLAN.indexOf('{') + 1; cut <= PLAN.length(); cut++) {
            List<FormSection> form = GemmaMusicComposer.seenForm(PartialJson.read(PLAN.substring(0, cut)), spec, false);
            int now = form.stream().mapToInt(section -> section.chords().size()).sum();
            assertTrue(now >= seen);
            seen = now;
        }
        assertEquals(10, seen);
    }
}
