package com.aresstack.audio.studio.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code project.json}: the version of the format the folder is written in, the current
 * revision (none before work package F3), and what the import of the old files reported.
 *
 * @param warnings what the import of the old files could not take over as it was written
 */
public record ProjectFile(int formatVersion, String currentRevision, String importedFrom, List<String> warnings) {
    public static final String FILE_NAME = "project.json";
    /** The version this studio writes and the highest it reads. */
    public static final int FORMAT_VERSION = 1;

    public ProjectFile {
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public static ProjectFile load(Path folder) throws IOException {
        Path file = folder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        JsonObject root = JsonText.read(file);
        try {
            int version = root.get("formatVersion").getAsInt();
            if (version > FORMAT_VERSION) {
                throw new IOException("Das Stück wurde mit einem neueren Audio Studio gespeichert (Formatversion "
                        + version + ", dieses liest bis " + FORMAT_VERSION + "): " + folder);
            }
            List<String> warnings = new ArrayList<>();
            if (root.has("import") && root.getAsJsonObject("import").has("warnings")) {
                root.getAsJsonObject("import").getAsJsonArray("warnings").forEach(w -> warnings.add(w.getAsString()));
            }
            return new ProjectFile(version, CompositionJson.text(root, "currentRevision"),
                    root.has("import") ? CompositionJson.text(root.getAsJsonObject("import"), "from") : null, warnings);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read " + file + ": " + invalid.getMessage(), invalid);
        }
    }

    public void save(Path folder) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", formatVersion);
        if (currentRevision != null) {
            root.addProperty("currentRevision", currentRevision);
        }
        if (importedFrom != null) {
            JsonObject imported = new JsonObject();
            imported.addProperty("from", importedFrom);
            JsonArray list = new JsonArray();
            warnings.forEach(list::add);
            imported.add("warnings", list);
            root.add("import", imported);
        }
        JsonText.atomically(folder.resolve(FILE_NAME), root);
    }
}
