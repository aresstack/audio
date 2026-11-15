package com.aresstack.audio.studio.assist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.studio.ai.ToolChat;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The conversation of a field's AI help: tools, answers, the kept history. */
public class AssistChatTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theAiCallsTheToolThenAnswersAndTheConversationIsKept() throws Exception {
        String[] field = {""};
        List<AssistTool> tools = List.of(AssistTool.of("set_field", "writes the field",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}", arguments -> {
                    field[0] = arguments.get("text").getAsString();
                    return "ok";
                }));
        List<JsonArray> asked = new ArrayList<JsonArray>();
        ToolChat model = (messages, offered, tokens) -> {
            asked.add(messages.deepCopy());
            JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
            if ("user".equals(last.get("role").getAsString())) {
                assertEquals(1, offered.size());
                return JsonParser.parseString("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"set_field\",\"arguments\":"
                        + "\"{\\\"text\\\":\\\"Ein Album über das All\\\"}\"}}]}").getAsJsonObject();
            }
            assertEquals("tool", last.get("role").getAsString());
            assertEquals("c1", last.get("tool_call_id").getAsString());
            return JsonParser.parseString("{\"role\":\"assistant\",\"content\":\"Erledigt.\"}").getAsJsonObject();
        };
        Path file = folder.getRoot().toPath().resolve("assist").resolve("album-a-description.json");
        AssistChat chat = AssistChat.at(file);
        List<String> shown = new ArrayList<String>();
        assertEquals("Erledigt.", chat.send("Schreib was übers All", model, "help", tools, 1000, shown::add));
        assertEquals("Ein Album über das All", field[0]);
        assertEquals(List.of("set_field: ok"), shown);
        assertEquals("system", asked.get(0).get(0).getAsJsonObject().get("role").getAsString());

        // opened again: the conversation is still there, and goes on from it
        AssistChat again = AssistChat.at(file);
        assertEquals(4, again.messages().size());
        assertEquals("Schreib was übers All", again.messages().get(0).get("content").getAsString());
        again.clear();
        assertTrue(again.messages().isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    public void aFailedRequestLeavesTheConversationAsItWas() throws Exception {
        AssistChat chat = AssistChat.at(folder.getRoot().toPath().resolve("c.json"));
        ToolChat broken = (messages, offered, tokens) -> {
            throw new IOException("weg");
        };
        try {
            chat.send("hallo", broken, "help", List.of(), 100, line -> { });
            fail();
        } catch (IOException expected) {
            assertTrue(chat.messages().isEmpty());
        }
    }

    @Test
    public void anUnknownToolOrAFailingOneIsToldToTheAi() {
        List<AssistTool> tools = List.of(AssistTool.reading("boom", "fails", arguments -> {
            throw new IllegalStateException("kaputt");
        }));
        assertEquals("Fehler: kaputt", AssistChat.run(tools, "boom", null));
        assertTrue(AssistChat.run(tools, "other", null).startsWith("Fehler: kein Werkzeug"));
    }

    @Test
    public void toolsAreUsedUpAfterTheLastRound() throws Exception {
        int[] calls = {0};
        ToolChat greedy = (messages, offered, tokens) -> {
            calls[0]++;
            if (offered.size() == 0) {
                return JsonParser.parseString("{\"content\":\"Schluss.\"}").getAsJsonObject();
            }
            return JsonParser.parseString("{\"tool_calls\":[{\"function\":{\"name\":\"look\",\"arguments\":\"{}\"}}]}")
                    .getAsJsonObject();
        };
        AssistChat chat = AssistChat.at(null);
        String answer = chat.send("los", greedy, "help", List.of(AssistTool.reading("look", "looks", a -> "x")), 100,
                line -> { });
        assertEquals("Schluss.", answer);
        assertEquals(AssistChat.MAX_ROUNDS + 1, calls[0]);
    }
}
