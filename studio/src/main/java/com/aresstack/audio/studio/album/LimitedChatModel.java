package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.ai.ChatModel;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

/**
 * A chat model that lets only so many requests through at once, whoever asks. Every piece limits
 * its own requests as before; all pieces of an album that are composed at the same time share one
 * of these, so that the sum of their requests stays below the limit of the model.
 */
public final class LimitedChatModel implements ChatModel {
    private final ChatModel model;
    private final Semaphore free;

    public LimitedChatModel(ChatModel model, int atOnce) {
        if (atOnce < 1) {
            throw new IllegalArgumentException("Mindestens eine Anfrage zugleich.");
        }
        this.model = model;
        this.free = new Semaphore(atOnce, true);
    }

    /**
     * @param free the permits several models share: every model of the lineup of an album is
     *             wrapped with the same semaphore, so that the limit holds across all of them
     */
    public LimitedChatModel(ChatModel model, Semaphore free) {
        this.model = model;
        this.free = free;
    }

    @Override
    public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException {
        try {
            free.acquire();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Abgebrochen, bevor die KI gefragt wurde.");
        }
        try {
            return model.generate(instruction, prompt, maxTokens, cache);
        } finally {
            free.release();
        }
    }

    /** The name of the model behind the limit, so that kept answers stay those of that model. */
    @Override
    public String name() {
        return model.name();
    }

    /** Asked without waiting for a free place: looking for a kept answer asks the service nothing. */
    @Override
    public boolean hasAnswer(String instruction, String prompt, int maxTokens, Path cache) {
        return model.hasAnswer(instruction, prompt, maxTokens, cache);
    }

    @Override
    public void cancel() {
        model.cancel();
    }
}
