package com.aresstack.audio.studio.project;

import static com.aresstack.audio.studio.project.CompositionJson.array;
import static com.aresstack.audio.studio.project.CompositionJson.list;
import static com.aresstack.audio.studio.project.CompositionJson.text;

import com.aresstack.audio.composition.Composition;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * {@code render/manifest.json}: which rendered file holds the sound of which part, and for
 * which content of the part. A stem that was there before the model is taken over as it is
 * ({@link #ADOPTED}), so that an old piece need not be rendered again when it is opened; it
 * counts until the part changes.
 *
 * <p>The stem cache (work package R1) notes here what it rendered ({@link #RENDERED}): the file
 * under {@code render/stems/}, the engine as origin, and the cache key the file was rendered
 * for. The manifest is a record, not the cache itself: whether a stem can be used again is
 * decided by its key, so a stem of an earlier state (after undo, say) is found even when the
 * manifest has moved on.
 */
public final class StemManifest {
    public static final String FILE_NAME = "manifest.json";
    public static final String ADOPTED = "adopted";
    public static final String RENDERED = "rendered";

    /**
     * @param file       the file, relative to the folder of the piece
     * @param origin     where it was found: "stems.tsv", "sampled" or "reference"; for a rendered
     *                   stem the engine that rendered it
     * @param partDigest the hash of what sounds of the part ({@link #digest}) when the file was taken over
     * @param key        the stem cache key it was rendered for, or {@code null} for a stem taken over
     */
    public record Entry(String partId, String file, String origin, String state, String partDigest, String key) {
        public Entry(String partId, String file, String origin, String state, String partDigest) {
            this(partId, file, origin, state, partDigest, null);
        }
    }

    private StemManifest() {
    }

    public static Path file(Path folder) {
        return folder.resolve(ProjectFolder.RENDER).resolve(FILE_NAME);
    }

    /**
     * The hash of what sounds of a part: the time map (a tempo change moves every note in time),
     * and its line and its notes as {@code arrangement.tsv} writes them.
     */
    public static String digest(Composition composition, String partId) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String sounding = composition.timeMap() + "\n" + String.join("\n", TsvExport.linesOf(composition, partId));
            return HexFormat.of().formatHex(sha.digest(sounding.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The entries whose part still sounds as it did when its file was taken over. */
    public static List<Entry> current(List<Entry> entries, Composition composition) {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : entries) {
            if (composition.part(entry.partId()) != null
                    && digest(composition, entry.partId()).equals(entry.partDigest())) {
                kept.add(entry);
            }
        }
        return kept;
    }

    public static List<Entry> load(Path folder) throws IOException {
        Path file = file(folder);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return list(JsonText.read(file), "stems", e -> new Entry(text(e, "part"), text(e, "file"),
                    text(e, "origin"), text(e, "state"), text(e, "partDigest"), text(e, "key")));
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read " + file + ": " + invalid.getMessage(), invalid);
        }
    }

    public static void save(Path folder, List<Entry> entries) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("stems", array(entries, entry -> {
            JsonObject e = new JsonObject();
            e.addProperty("part", entry.partId());
            e.addProperty("file", entry.file());
            e.addProperty("origin", entry.origin());
            e.addProperty("state", entry.state());
            e.addProperty("partDigest", entry.partDigest());
            if (entry.key() != null) {
                e.addProperty("key", entry.key());
            }
            return e;
        }));
        JsonText.atomically(file(folder), root);
    }
}
