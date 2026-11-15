package com.aresstack.audio.studio.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * A chat model for the tests (architecture review 7.1): answers each prompt with what a function
 * makes of it, deliberately wrong answers included, and keeps answers in the cache file as the
 * real client does, so that a second run with the same prompt does not ask again.
 */
public final class FakeChatModel implements ChatModel {
    private final BiFunction<String, String, String> answers;
    private final List<String> prompts = new ArrayList<>();

    /** @param answers instruction and prompt to the JSON text of the answer */
    public FakeChatModel(BiFunction<String, String, String> answers) {
        this.answers = answers;
    }

    @Override
    public synchronized JsonObject generate(String instruction, String prompt, int maxTokens, Path cache)
            throws IOException {
        if (cache != null && Files.isRegularFile(cache)) {
            return JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        prompts.add(prompt);
        String answer = answers.apply(instruction, prompt);
        if (answer == null) {
            throw new IOException("The fake model has no answer.");
        }
        if (cache != null) {
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, answer, StandardCharsets.UTF_8);
        }
        return JsonParser.parseString(answer).getAsJsonObject();
    }

    /** The prompts the model was really asked, cached answers not counted. */
    public synchronized List<String> prompts() {
        return List.copyOf(prompts);
    }

    /** The value between {@code "<key>":"} and the next quote in a prompt, as a test reads its own request. */
    public static String quoted(String prompt, String key) {
        String marker = "\"" + key + "\":\"";
        int from = prompt.lastIndexOf(marker);
        if (from < 0) {
            return null;
        }
        from += marker.length();
        return prompt.substring(from, prompt.indexOf('"', from));
    }
}
