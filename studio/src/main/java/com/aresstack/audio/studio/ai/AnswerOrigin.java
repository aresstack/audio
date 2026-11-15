package com.aresstack.audio.studio.ai;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;

/**
 * Where a kept answer of the AI came from: which model was asked, with which standing
 * instruction, which prompt and which sampling. Two requests with the same identity would be
 * answered alike, so a kept answer is given again only for the same identity; with another model
 * or other words the model is asked anew.
 *
 * <p>The answer length is written down but not part of the identity: a longer limit does not
 * make a complete answer different.
 */
public record AnswerOrigin(String model, String instruction, String prompt, int maxTokens,
                           double temperature, double topP, int topK) {
    public AnswerOrigin {
        model = model == null ? "" : model.trim();
        instruction = instruction == null ? "" : instruction;
        prompt = prompt == null ? "" : prompt;
    }

    /** The identity as a SHA-256 hex string. */
    public String key() {
        return sha256(model + '\u0000' + instruction + '\u0000' + prompt + '\u0000'
                + String.format(Locale.ROOT, "t=%s;p=%s;k=%d", temperature, topP, topK));
    }

    /** What is written next to the answer; prompt and instruction as hashes, the rest readable. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("model", model);
        json.addProperty("key", key());
        json.addProperty("instructionSha256", sha256(instruction));
        json.addProperty("promptSha256", sha256(prompt));
        json.addProperty("temperature", temperature);
        json.addProperty("topP", topP);
        json.addProperty("topK", topK);
        json.addProperty("maxTokens", maxTokens);
        json.addProperty("createdAt", Instant.now().toString());
        return json;
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
