package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.Test;

/** C1, 7.2: an answer for another part is refused as a whole. */
public final class GeneratedNotesTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    public void anAnswerForAnotherPartIsRefused() throws IOException {
        refused("{\"part\":\"melody\",\"notes\":[{\"bar\":1,\"beat\":0,\"duration\":1,\"pitch\":60}]}", "fremden Part");
        refused("{\"part\":\"tp\",\"section\":\"b\",\"notes\":[]}", "fremden Abschnitt");
        refused("{\"part\":\"tp\",\"notes\":[{\"part\":\"bass\",\"bar\":1,\"beat\":0,\"duration\":1,\"pitch\":40}]}",
                "fremdem Part");
    }

    @Test
    public void anUnreadableNoteIsSkippedAndNamed() throws IOException {
        GeneratedNotes notes = GeneratedNotes.read(json("{\"part\":\"tp\",\"section\":\"a\",\"notes\":["
                + "{\"bar\":1,\"beat\":0,\"duration\":1,\"pitch\":72,\"velocity\":90},{\"beat\":\"x\"},7]}"), "tp", "a");
        assertEquals(1, notes.notes().size());
        assertEquals(2, notes.skipped().size());
        assertEquals(new GeneratedNotes.RawNote(1, 1, 0, 1, 72, 90, null), notes.notes().get(0));
    }

    @Test
    public void anAnswerWithoutPartOrNotesIsNoAnswer() {
        for (String text : new String[]{"{\"notes\":[]}", "{\"part\":\"tp\"}"}) {
            try {
                GeneratedNotes.read(json(text), "tp", "a");
                fail("accepted " + text);
            } catch (ForeignPartException wrong) {
                fail("not a foreign part: " + text);
            } catch (IOException expected) {
                // fine
            }
        }
    }

    private static void refused(String text, String reason) throws IOException {
        try {
            GeneratedNotes.read(json(text), "tp", "a");
            fail("accepted " + text);
        } catch (ForeignPartException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(reason));
        }
    }
}
