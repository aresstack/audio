package com.aresstack.audio.studio.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The folder of a piece (architecture review E6): which file is source and which is derived,
 * and the copy of the old files in {@code legacy/}. That copy is made once, when a piece from
 * before the model is first opened, and is never changed or deleted afterwards: it is how the
 * old state comes back, and every later import reads from it.
 */
public final class ProjectFolder {
    public static final String ARRANGEMENT = "arrangement.tsv";
    public static final String MIDI = "arrangement.mid";
    public static final String LEGACY = "legacy";
    public static final String RENDER = "render";
    public static final String TRACKS = "tracks.json";
    public static final String PLAN = "composition-plan.json";
    public static final String SONG = "song.properties";
    public static final String SCENE = "spatial-scene.json";
    public static final String STEMS = "stems.tsv";

    /**
     * The old files that the import reads or that the studio may write again while the model is
     * the truth. Rendered sound and the cached answers of earlier runs are not among them: they
     * are never overwritten by the model, so they stay where they are.
     */
    public static final List<String> LEGACY_FILES = List.of(ARRANGEMENT, TRACKS, PLAN, SONG, SCENE, STEMS,
            "note-corrections.tsv");

    private ProjectFolder() {
    }

    public static Path legacy(Path folder) {
        return folder.resolve(LEGACY);
    }

    /** Where the old files of a piece are read from: {@code legacy/} once it exists, else the folder. */
    public static Path source(Path folder) {
        Path legacy = legacy(folder);
        return Files.isRegularFile(legacy.resolve(ARRANGEMENT)) ? legacy : folder;
    }

    /**
     * Copies the old files of a piece to {@code legacy/}, unchanged and with their times, unless
     * that folder exists already. Returns whether it copied anything.
     */
    public static boolean keepLegacy(Path folder) throws IOException {
        Path legacy = legacy(folder);
        if (Files.exists(legacy) || !Files.isRegularFile(folder.resolve(ARRANGEMENT))) {
            return false;
        }
        Path partial = folder.resolve(LEGACY + ".partial");
        deleteTree(partial);
        Files.createDirectories(partial);
        for (String name : LEGACY_FILES) {
            Path file = folder.resolve(name);
            if (Files.isRegularFile(file)) {
                Files.copy(file, partial.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        Files.move(partial, legacy);
        return true;
    }

    /**
     * Brings back the state from before the model: the files of the model go, the old files come
     * back from {@code legacy/}. The copy in {@code legacy/} stays. Returns false if there is none.
     */
    public static boolean restoreLegacy(Path folder) throws IOException {
        Path legacy = legacy(folder);
        if (!Files.isRegularFile(legacy.resolve(ARRANGEMENT))) {
            return false;
        }
        for (String name : List.of(CompositionJson.FILE_NAME, MixJson.FILE_NAME, ProjectFile.FILE_NAME)) {
            Files.deleteIfExists(folder.resolve(name));
        }
        Files.deleteIfExists(StemManifest.file(folder));
        for (String name : LEGACY_FILES) {
            Path kept = legacy.resolve(name);
            if (Files.isRegularFile(kept)) {
                Files.copy(kept, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            } else {
                // A file the old piece did not have, written since: it goes, so that the state is the old one.
                Files.deleteIfExists(folder.resolve(name));
            }
        }
        return true;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }
}
