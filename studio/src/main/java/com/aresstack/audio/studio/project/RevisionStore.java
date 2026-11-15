package com.aresstack.audio.studio.project;

import com.aresstack.audio.composition.edit.Revision;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The revisions of a piece in {@code revisions/}, one file each, named by number and id
 * ({@code 0001-abcd2345.json}). Every file holds the full piece in the format of
 * {@code composition.json} together with who made it, when and why, so that each one can be read
 * on its own. Files are only ever added; the first, the original, is never deleted.
 */
public final class RevisionStore {
    public static final String FORMAT = "aresstack-revision";
    public static final int VERSION = 1;

    private RevisionStore() {
    }

    public static Path folder(Path piece) {
        return piece.resolve(ProjectFolder.REVISIONS);
    }

    /** Writes a revision; refuses to overwrite one of the same number. */
    public static void save(Path piece, Revision revision) throws IOException {
        Path file = folder(piece).resolve(fileName(revision));
        if (Files.exists(file)) {
            throw new IOException("Revision " + revision.sequence() + " exists already: " + file);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        root.addProperty("id", revision.id());
        root.addProperty("sequence", revision.sequence());
        if (revision.parentId() != null) {
            root.addProperty("parent", revision.parentId());
        }
        root.addProperty("author", revision.author().name().toLowerCase(Locale.ROOT));
        root.addProperty("time", revision.timeMillis());
        root.addProperty("description", revision.description());
        if (revision.runId() != null) {
            root.addProperty("run", revision.runId());
        }
        root.add("composition", CompositionJson.write(revision.composition()));
        JsonText.atomically(file, root);
    }

    /** Every revision of the piece in the order they were made; empty if there is none. */
    public static List<Revision> list(Path piece) throws IOException {
        List<Revision> revisions = new ArrayList<>();
        for (Path file : files(piece)) {
            revisions.add(read(file));
        }
        revisions.sort(Comparator.comparingInt(Revision::sequence));
        return revisions;
    }

    /** The revision of this id. */
    public static Optional<Revision> load(Path piece, String id) throws IOException {
        for (Path file : files(piece)) {
            if (file.getFileName().toString().endsWith("-" + id + ".json")) {
                return Optional.of(read(file));
            }
        }
        return Optional.empty();
    }

    private static List<Path> files(Path piece) throws IOException {
        Path folder = folder(piece);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(f -> f.getFileName().toString().matches("\\d{4,}-[a-z][a-z0-9_-]*\\.json")).sorted().toList();
        }
    }

    private static Revision read(Path file) throws IOException {
        JsonObject root = JsonText.read(file);
        try {
            if (!FORMAT.equals(CompositionJson.text(root, "format")) || root.get("version").getAsInt() > VERSION) {
                throw new IOException("Not a revision this studio reads: " + file);
            }
            String author = CompositionJson.text(root, "author");
            return new Revision(CompositionJson.text(root, "id"), root.get("sequence").getAsInt(),
                    CompositionJson.text(root, "parent"), Revision.Author.valueOf(author.toUpperCase(Locale.ROOT)),
                    root.get("time").getAsLong(), CompositionJson.text(root, "description"),
                    CompositionJson.text(root, "run"), CompositionJson.read(root.getAsJsonObject("composition")));
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read " + file + ": " + invalid.getMessage(), invalid);
        }
    }

    private static String fileName(Revision revision) {
        return String.format(Locale.ROOT, "%04d-%s.json", revision.sequence(), revision.id());
    }
}
