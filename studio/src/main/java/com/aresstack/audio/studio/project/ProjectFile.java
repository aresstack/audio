package com.aresstack.audio.studio.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code project.json}: the version of the format the folder is written in, the current
 * revision (see {@link RevisionStore}), and what the import of the old files reported.
 *
 * @param warnings what the import of the old files could not take over as it was written
 * @param sources  the SHA-256 of each file the old pipeline still writes, as the model last took it
 *                 over; a file that is not there has no entry
 */
public record ProjectFile(int formatVersion, String currentRevision, String importedFrom, List<String> warnings,
                          Map<String, String> sources) {
    public static final String FILE_NAME = "project.json";
    /** The version this studio writes and the highest it reads. */
    public static final int FORMAT_VERSION = 1;

    public ProjectFile {
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        sources = Collections.unmodifiableMap(new TreeMap<>(sources == null ? Map.of() : sources));
    }

    public ProjectFile withSources(Map<String, String> changed) {
        return new ProjectFile(formatVersion, currentRevision, importedFrom, warnings, changed);
    }

    /** The same file naming another revision as the current one (work package F3). */
    public ProjectFile withCurrentRevision(String revisionId) {
        return new ProjectFile(formatVersion, revisionId, importedFrom, warnings, sources);
    }

    /** The SHA-256 of each of these files in a folder that is there. */
    public static Map<String, String> fingerprint(Path folder, List<String> names) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        for (String name : names) {
            Path file = folder.resolve(name);
            if (Files.isRegularFile(file)) {
                try {
                    hashes.put(name, HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                } catch (NoSuchAlgorithmException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }
        return hashes;
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
            Map<String, String> sources = new TreeMap<>();
            if (root.has("sources")) {
                root.getAsJsonObject("sources").entrySet().forEach(e -> sources.put(e.getKey(), e.getValue().getAsString()));
            }
            return new ProjectFile(version, CompositionJson.text(root, "currentRevision"),
                    root.has("import") ? CompositionJson.text(root.getAsJsonObject("import"), "from") : null, warnings,
                    sources);
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
        JsonObject hashes = new JsonObject();
        sources.forEach(hashes::addProperty);
        root.add("sources", hashes);
        JsonText.atomically(folder.resolve(FILE_NAME), root);
    }
}
