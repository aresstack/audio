package com.aresstack.audio.studio.assist;

import com.aresstack.audio.studio.ai.ToolChat;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The conversation of the assistant of one field: what the user and the AI said, kept in a file,
 * so that it is still known when the dialog is opened again (also after a restart) until the user
 * clears it. A turn may call tools several times before the AI answers in words (the tool loop
 * of the chat API, as in the Enterprise AI Client): at most {@link #MAX_ROUNDS} rounds of tools,
 * then one last request without tools.
 */
public final class AssistChat {
    /** The most rounds of tools in one turn. */
    public static final int MAX_ROUNDS = 8;
    /** Told in the last round when the tools are used up. */
    static final String LAST_ROUND = "[Hinweis] Die Werkzeuge sind für diese Anfrage aufgebraucht. Antworte jetzt mit "
            + "dem, was vorliegt, und sage, was noch fehlt.";

    private final Path file;
    private JsonArray history = new JsonArray();

    private AssistChat(Path file) {
        this.file = file;
    }

    /** The conversation kept in this file; a new one if there is none or it cannot be read. */
    public static AssistChat at(Path file) {
        AssistChat chat = new AssistChat(file);
        if (file != null && Files.isRegularFile(file)) {
            try {
                JsonElement read = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (read.isJsonArray()) {
                    chat.history = read.getAsJsonArray();
                }
            } catch (IOException | RuntimeException unreadable) {
                // a conversation that cannot be read begins anew
            }
        }
        return chat;
    }

    /** What was said so far, in the format of the chat API. */
    public synchronized List<JsonObject> messages() {
        List<JsonObject> all = new ArrayList<JsonObject>();
        for (JsonElement element : history) {
            if (element.isJsonObject()) {
                all.add(element.getAsJsonObject().deepCopy());
            }
        }
        return all;
    }

    /** Forgets the conversation. */
    public synchronized void clear() throws IOException {
        history = new JsonArray();
        if (file != null) {
            Files.deleteIfExists(file);
        }
    }

    /**
     * One turn: what the user says, the tools the AI calls, its answer. If the request fails,
     * the conversation stays as it was.
     *
     * @param system what the AI is told about its task, before the conversation
     * @param shown  hears each call of a tool and its result while the turn goes on
     * @return the answer of the AI in words
     */
    public String send(String text, ToolChat model, String system, List<AssistTool> tools, int maxTokens,
                       Consumer<String> shown) throws IOException {
        JsonArray turn;
        synchronized (this) {
            turn = history.deepCopy();
        }
        turn.add(message("user", text));
        JsonArray definitions = new JsonArray();
        for (AssistTool tool : tools) {
            definitions.add(tool.definition());
        }
        String answer = null;
        for (int round = 0; round <= MAX_ROUNDS && answer == null; round++) {
            boolean last = round == MAX_ROUNDS;
            JsonArray asked = new JsonArray();
            asked.add(message("system", system));
            asked.addAll(turn);
            if (last) {
                asked.add(message("user", LAST_ROUND));
            }
            JsonObject said = model.converse(asked, last ? new JsonArray() : definitions, maxTokens);
            JsonArray calls = said.has("tool_calls") && said.get("tool_calls").isJsonArray()
                    ? said.getAsJsonArray("tool_calls") : new JsonArray();
            if (calls.size() == 0 || last) {
                answer = text(said);
                turn.add(message("assistant", answer));
                break;
            }
            JsonObject call = new JsonObject();
            call.addProperty("role", "assistant");
            call.addProperty("content", text(said));
            JsonArray named = new JsonArray();
            List<String[]> results = new ArrayList<String[]>();
            for (int i = 0; i < calls.size(); i++) {
                JsonObject each = calls.get(i).getAsJsonObject().deepCopy();
                String id = each.has("id") && !each.get("id").isJsonNull() ? each.get("id").getAsString()
                        : "call_" + round + "_" + i;
                each.addProperty("id", id);
                each.addProperty("type", "function");
                named.add(each);
                JsonObject function = each.has("function") ? each.getAsJsonObject("function") : new JsonObject();
                String name = function.has("name") ? function.get("name").getAsString() : "";
                String result = run(tools, name, function.get("arguments"));
                shown.accept(name + ": " + firstLine(result));
                results.add(new String[]{id, result});
            }
            call.add("tool_calls", named);
            turn.add(call);
            for (String[] result : results) {
                JsonObject tool = message("tool", result[1]);
                tool.addProperty("tool_call_id", result[0]);
                turn.add(tool);
            }
        }
        synchronized (this) {
            history = turn;
            save();
        }
        return answer == null ? "" : answer;
    }

    /** Runs a tool the model called; what goes wrong is told to the model as text. */
    static String run(List<AssistTool> tools, String name, JsonElement arguments) {
        for (AssistTool tool : tools) {
            if (tool.name().equals(name)) {
                try {
                    JsonObject given = new JsonObject();
                    if (arguments != null && arguments.isJsonPrimitive() && !arguments.getAsString().isBlank()) {
                        given = JsonParser.parseString(arguments.getAsString()).getAsJsonObject();
                    } else if (arguments != null && arguments.isJsonObject()) {
                        given = arguments.getAsJsonObject();
                    }
                    String result = tool.action().run(given);
                    return result == null || result.isBlank() ? "erledigt" : result;
                } catch (Exception failed) {
                    return "Fehler: " + (failed.getMessage() == null ? failed.toString() : failed.getMessage());
                }
            }
        }
        return "Fehler: kein Werkzeug „" + name + "“.";
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(history),
                    StandardCharsets.UTF_8);
        } catch (IOException unwritten) {
            // the conversation stays in memory for this session
        }
    }

    private static String text(JsonObject message) {
        JsonElement content = message.get("content");
        return content == null || content.isJsonNull() ? "" : content.isJsonPrimitive() ? content.getAsString()
                : content.toString();
    }

    private static String firstLine(String text) {
        String line = text.strip();
        int end = line.indexOf('\n');
        line = end < 0 ? line : line.substring(0, end);
        return line.length() > 160 ? line.substring(0, 160) + " …" : line;
    }

    static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content == null ? "" : content);
        return message;
    }
}
