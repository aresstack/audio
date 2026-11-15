package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.ai.ModelAssignment;
import com.aresstack.audio.studio.ai.ModelRole;
import com.aresstack.audio.studio.ai.ModelRoles;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Writes down in the folder of a piece which model each role had when a production started
 * ({@code models-used.json}). Each kept answer also names its own model in its origin file; this
 * is the overview of the last production. It documents and is never read back to decide anything.
 */
public final class ModelsUsed {
    public static final String FILE_NAME = "models-used.json";

    private ModelsUsed() {
    }

    public static void record(Path folder, String what, ModelAssignment assignment) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("production", what);
        json.addProperty("startedAt", Instant.now().toString());
        json.addProperty("baseModel", assignment.base());
        JsonObject roles = new JsonObject();
        for (ModelRole role : ModelRoles.all()) {
            roles.addProperty(role.key(), assignment.modelFor(role));
        }
        json.add("roles", roles);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(FILE_NAME), new GsonBuilder().setPrettyPrinting().create().toJson(json),
                StandardCharsets.UTF_8);
    }
}
