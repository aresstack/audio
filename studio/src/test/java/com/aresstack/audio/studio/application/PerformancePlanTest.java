package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

/** The plan of tempo and loudness reaches the request for the notes of each track, section by section. */
public final class PerformancePlanTest {
    private static final CompositionSpec SPEC = CompositionSpec.of(16, 100, Key.C_MAJOR, 4, 4);

    @Test
    public void eachSectionIsToldItsPartOfThePlan() {
        JsonObject plan = JsonParser.parseString("{\"tempoBpm\":100,"
                + "\"tempoChanges\":[{\"bar\":13,\"bpm\":100,\"glide\":true},{\"bar\":16,\"bpm\":70}],"
                + "\"dynamics\":[{\"bar\":1,\"level\":\"p\"},{\"bar\":9,\"level\":\"f\"},{\"bar\":10,\"level\":\"pp\",\"part\":\"bass\"}],"
                + "\"hairpins\":[{\"fromBar\":5,\"toBar\":9,\"to\":\"f\"}]}").getAsJsonObject();
        String first = GemmaMusicComposer.performance(plan, SPEC, "melody", new int[]{0, 8});
        assertEquals("starts p, bar 5 to bar 9 crescendo to f", first);
        String second = GemmaMusicComposer.performance(plan, SPEC, "melody", new int[]{8, 8});
        assertTrue(second, second.contains("starts f"));
        assertTrue(second, second.contains("bar 13 to bar 16 ritardando to 70 BPM"));
        assertTrue("a mark of another part is not told", !second.contains("pp"));
        String bass = GemmaMusicComposer.performance(plan, SPEC, "bass", new int[]{8, 8});
        assertTrue(bass, bass.contains("bar 10 pp"));
    }

    @Test
    public void aPlanWithoutThemChangesNoRequest() {
        assertNull(GemmaMusicComposer.performance(JsonParser.parseString("{\"tempoBpm\":100}").getAsJsonObject(),
                SPEC, "melody", new int[]{0, 16}));
        String prompt = GemmaMusicComposer.promptFor(new MusicTrack("melody", "melody", "Flute", 73, "builtin"),
                JsonParser.parseString("{\"arrangementNotes\":\"x\"}").getAsJsonObject(), SPEC,
                java.util.Collections.nCopies(16, "C"), new int[]{0, 16}, null);
        assertTrue(!prompt.contains("articulation"));
    }
}
