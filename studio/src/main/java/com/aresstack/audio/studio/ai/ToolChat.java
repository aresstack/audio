package com.aresstack.audio.studio.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;

/**
 * A chat model that may call tools (function calling as in the OpenAI chat API): it gets the
 * conversation and the tools it may use and answers with text or with calls of tools.
 */
public interface ToolChat {
    /**
     * One answer of the model to the conversation so far.
     *
     * @param messages  the conversation in the format of the chat API (system, user, assistant, tool)
     * @param tools     the tools it may call, in the format of the chat API; empty for none
     * @param maxTokens how long the answer may be
     * @return the message of the assistant: {@code content} and maybe {@code tool_calls}
     */
    JsonObject converse(JsonArray messages, JsonArray tools, int maxTokens) throws IOException;
}
