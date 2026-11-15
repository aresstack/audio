package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.aresstack.audio.studio.progress.FormSection;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.Test;

/** A planning answer is read while it arrives: every section and chord that is already whole. */
public final class PartialJsonTest {
    private static final String ANSWER = "Here: {\"sections\":[{\"name\":\"Aufbau\",\"function\":\"build\",\"bars\":8,"
            + "\"intensity\":0.6,\"chords\":[\"C\",\"G\",\"Am\",\"F\",\"C\",\"G\",\"F\",\"G\"],\"idea\":\"a {brace} and \\\"quote\\\"\"},"
            + "{\"name\":\"Höhepunkt\",\"function\":\"climax\",\"bars\":4,\"chords\":[\"F\",\"G\",\"C\",\"C\"]}],"
            + "\"newParts\":[],\"arc\":\"up\"}";

    @Test
    public void everyCutOfAnAnswerCanBeReadAndOnlyGrows() {
        int sections = 0;
        for (int cut = 0; cut <= ANSWER.length(); cut++) {
            JsonObject seen = PartialJson.read(ANSWER.substring(0, cut));
            if (cut < ANSWER.indexOf('{') + 1) {
                assertNull(seen);
                continue;
            }
            List<FormSection> told = ContinuationComposer.seenSections(seen);
            assertEquals("sections only come, at cut " + cut, true, told.size() >= sections);
            sections = told.size();
        }
        assertEquals(2, sections);
    }

    @Test
    public void aSectionAppearsWithItsNameAndGetsItsChordsOneByOne() {
        String cut = ANSWER.substring(0, ANSWER.indexOf("\"Am\"") + 3); // breaks off inside "Am"
        List<FormSection> told = ContinuationComposer.seenSections(PartialJson.read(cut));
        assertEquals(1, told.size());
        assertEquals("Aufbau", told.get(0).name());
        assertEquals(8, told.get(0).bars());
        assertEquals(List.of("C", "G"), told.get(0).chords());

        String named = ANSWER.substring(0, ANSWER.indexOf("\"Höhepunkt\"") + 11);
        told = ContinuationComposer.seenSections(PartialJson.read(named));
        assertEquals("the next section is there as soon as it has its name", 2, told.size());
        assertEquals("Höhepunkt", told.get(1).name());

        List<FormSection> whole = ContinuationComposer.seenSections(PartialJson.read(ANSWER));
        assertEquals(List.of("F", "G", "C", "C"), whole.get(1).chords());
        assertEquals("up", PartialJson.read(ANSWER).get("arc").getAsString());
    }
}
