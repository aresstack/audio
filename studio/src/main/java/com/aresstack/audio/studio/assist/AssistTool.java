package com.aresstack.audio.studio.assist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A tool the assistant of a field may call: reading what is around, or writing the one field it
 * helps with. What it does runs on the thread of the conversation; a tool that changes the
 * window passes the change to the event thread itself.
 *
 * @param name        the name the model calls it by, in snake case
 * @param description what it does, for the model
 * @param parameters  its arguments as a JSON schema object
 * @param action      what it does; its answer goes back to the model as text
 */
public record AssistTool(String name, String description, JsonObject parameters, Action action) {
    /** What a tool does with the arguments the model gave. */
    public interface Action {
        /** @return what the model is told: the result, or what went wrong */
        String run(JsonObject arguments) throws Exception;
    }

    /** A tool whose arguments are given as a JSON schema in text. */
    public static AssistTool of(String name, String description, String parametersSchema, Action action) {
        return new AssistTool(name, description, JsonParser.parseString(parametersSchema).getAsJsonObject(), action);
    }

    /** A tool without arguments. */
    public static AssistTool reading(String name, String description, Action action) {
        return of(name, description, "{\"type\":\"object\",\"properties\":{}}", action);
    }

    /** The tool as the chat API describes it. */
    public JsonObject definition() {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters.deepCopy());
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }
}
