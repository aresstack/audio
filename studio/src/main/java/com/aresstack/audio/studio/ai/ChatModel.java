package com.aresstack.audio.studio.ai;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A language model that answers a prompt with one JSON object (architecture review 2.4). The
 * planners and generators of the AI editing depend on this and on nothing else of a model:
 * {@link KipitzChatClient} is one, a test double another ({@code client::generate} adapts the
 * client).
 */
@FunctionalInterface
public interface ChatModel {
    /**
     * Answers a prompt.
     *
     * @param instruction the standing instruction (system prompt)
     * @param prompt      the request
     * @param maxTokens   the longest answer wanted
     * @param cache       where the answer is kept: an answer already there is given without asking
     *                    the model again, a new answer is written there. It is the record of where
     *                    a piece came from, never the source of the piece.
     */
    JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException;

    /**
     * The name of the model, as the service knows it ({@code google/gemma-4-31B-it}); empty when
     * unknown. Written down with every kept answer, so that an answer of one model is never
     * given for another.
     */
    default String name() {
        return "";
    }

    /** Whether {@link #generate} would give a kept answer without asking the model. */
    default boolean hasAnswer(String instruction, String prompt, int maxTokens, Path cache) {
        return cache != null && java.nio.file.Files.isRegularFile(cache);
    }

    /** Stops a request that is running, if the model can. */
    default void cancel() {
    }

    /** The KIPITZ/OpenAI-compatible client as a chat model. */
    static ChatModel of(KipitzChatClient client) {
        return new ChatModel() {
            @Override
            public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException {
                return client.generate(instruction, prompt, maxTokens, cache);
            }

            @Override
            public String name() {
                return client.name();
            }

            @Override
            public boolean hasAnswer(String instruction, String prompt, int maxTokens, Path cache) {
                return client.hasAnswer(instruction, prompt, maxTokens, cache);
            }

            @Override
            public void cancel() {
                client.cancel();
            }
        };
    }
}
