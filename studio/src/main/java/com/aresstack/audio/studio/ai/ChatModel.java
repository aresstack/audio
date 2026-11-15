package com.aresstack.audio.studio.ai;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A model that answers a prompt with one JSON object (architecture review E4). The composer
 * talks to this, not to a particular client, so that tests can answer in its place.
 */
public interface ChatModel {
    /**
     * The answer to a prompt. If {@code cache} exists, it is the answer and the model is not asked;
     * otherwise the answer is kept there.
     */
    JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException;

    /** Stops the requests that are running. */
    void cancel();
}
