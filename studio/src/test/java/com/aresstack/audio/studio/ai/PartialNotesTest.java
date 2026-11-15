package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonArray;
import org.junit.Test;

/** The notes of an answer are read while the answer is still being written. */
public final class PartialNotesTest {
    private static final String WHOLE = "{\"part\":\"melody\",\"program\":73,\"notes\":["
            + "{\"beat\":0,\"duration\":1,\"pitch\":67,\"velocity\":75},"
            + "{\"beat\":1,\"duration\":1,\"pitch\":69,\"velocity\":75,\"voice\":\"a}b\"},"
            + "{\"beat\":2,\"duration\":2,\"pitch\":71,\"velocity\":75}]}";

    @Test
    public void everyWholeNoteIsThereAndTheBrokenOneIsNot() {
        // Where each of the three notes is whole; the second has a brace inside a text.
        int first = WHOLE.indexOf("},") + 1;
        int second = WHOLE.indexOf("\"},") + 2;
        int third = WHOLE.lastIndexOf("}]") + 1;
        for (int length = 0; length <= WHOLE.length(); length++) {
            int whole = (length >= first ? 1 : 0) + (length >= second ? 1 : 0) + (length >= third ? 1 : 0);
            assertEquals("after " + length + " characters", whole,
                    PartialNotes.read(WHOLE.substring(0, length)).size());
        }
        JsonArray all = PartialNotes.read(WHOLE);
        assertEquals(3, all.size());
        assertEquals(71, all.get(2).getAsJsonObject().get("pitch").getAsInt());
        assertEquals("a}b", all.get(1).getAsJsonObject().get("voice").getAsString());
    }

    @Test
    public void anAnswerWithoutAListOfNotesHasNone() {
        assertEquals(0, PartialNotes.read("").size());
        assertEquals(0, PartialNotes.read("{\"part\":\"melody\",\"no").size());
        assertEquals(0, PartialNotes.read("{\"title\":\"x\",\"chords\":[\"C\"]}").size());
    }

    @Test
    public void nothingAfterTheEndOfTheListIsTakenForANote() {
        assertEquals(1, PartialNotes.read("{\"notes\":[{\"beat\":0}],\"more\":{\"beat\":1}}").size());
    }
}
