package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonParser;
import org.junit.Test;

/** The arrangement notes of a plan are read however the model writes them (Winterhauch, 2026-11-15). */
public class PlanWordsTest {
    @Test
    public void readsStringsListsObjectsAndNothing() {
        assertEquals("soft and warm", GemmaMusicComposer.words(JsonParser.parseString("\"soft and warm\"")));
        assertEquals("a; b", GemmaMusicComposer.words(JsonParser.parseString("[\"a\", \"b\"]")));
        assertEquals("oboe: melody; bells: shimmer",
                GemmaMusicComposer.words(JsonParser.parseString("{\"oboe\":\"melody\",\"bells\":\"shimmer\"}")));
        assertEquals("", GemmaMusicComposer.words(JsonParser.parseString("null")));
        assertEquals("", GemmaMusicComposer.words(null));
    }
}
