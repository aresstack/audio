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
    public void anAnswerWithoutPartIsTheAnswerForThePartAskedFor() throws IOException {
        // Flockentanz, 2026-11-15: the model left out "part"; the answer is to this request.
        GeneratedNotes read = GeneratedNotes.read(json(
                "{\"notes\":[{\"bar\":1,\"beat\":1,\"duration\":1,\"pitch\":60}]}"), "tp", "a");
        assertEquals(1, read.notes().size());
    }

    @Test
    public void anAnswerWithoutNotesIsNoAnswer() {
        for (String text : new String[]{"{\"part\":\"tp\"}"}) {
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

    @Test
    public void barsOfThePieceAreCountedBackToTheFrame() throws IOException {
        // Test Techno, 2026-11-16: far into a long piece the model wrote the bars of the piece.
        GeneratedNotes read = GeneratedNotes.read(json("{\"notes\":[{\"bar\":297,\"beat\":0,\"duration\":1,"
                + "\"pitch\":36},{\"bar\":312,\"beat\":2,\"duration\":1,\"pitch\":38}]}"), "drums", "s7");
        GeneratedNotes moved = read.inFrame(296, 16);
        assertEquals(1, moved.notes().get(0).bar(), 0);
        assertEquals(16, moved.notes().get(1).bar(), 0);
        assertEquals(2, moved.inBars(16));
        // Near the start both counts overlap: nothing is moved.
        assertEquals(297, read.inFrame(8, 16).notes().get(0).bar(), 0);
    }

    @Test
    public void anAnswerWithoutNotesWhereThePartPlaysIsAskedForAgain() throws IOException {
        Backoff.forTests(GeneratedNotes.CHECKED_TRIES + 2, 1);
        try {
            int[] asked = {0};
            GeneratedNotes notes = GeneratedNotes.ask(problem -> {
                asked[0]++;
                return json(problem == null ? "{\"notes\":[]}"
                        : "{\"notes\":[{\"bar\":1,\"beat\":0,\"duration\":1,\"pitch\":60}]}");
            }, null, "lead", "a", read -> read.inBars(16) > 0 ? null : "no note");
            assertEquals(2, asked[0]);
            assertEquals(1, notes.notes().size());
            // A model that insists on rests is taken after the checked tries, not asked for ever.
            int[] again = {0};
            GeneratedNotes rests = GeneratedNotes.ask(problem -> {
                again[0]++;
                return json("{\"notes\":[]}");
            }, null, "lead", "a", read -> read.inBars(16) > 0 ? null : "no note");
            assertEquals(GeneratedNotes.CHECKED_TRIES + 1, again[0]);
            assertTrue(rests.notes().isEmpty());
        } finally {
            Backoff.forTests(0, 2000);
        }
    }
}
