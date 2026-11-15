package com.aresstack.audio.studio.project;

import static com.aresstack.audio.studio.project.CompositionJson.array;
import static com.aresstack.audio.studio.project.CompositionJson.list;
import static com.aresstack.audio.studio.project.CompositionJson.text;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
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
 * counts until the part changes. Work package R1 extends this to the stem cache.
 */
public final class StemManifest {
    public static final String FILE_NAME = "manifest.json";
    public static final String ADOPTED = "adopted";

    /**
     * @param file       the file, relative to the folder of the piece
     * @param origin     where it was found: "stems.tsv", "sampled" or "reference"
     * @param partDigest the hash of what sounds of the part ({@link #digest}) when the file was taken over
     */
    public record Entry(String partId, String file, String origin, String state, String partDigest) {
    }

    private StemManifest() {
    }

    public static Path file(Path folder) {
        return folder.resolve(ProjectFolder.RENDER).resolve(FILE_NAME);
    }

    /**
     * The hash of what sounds of a part: the time map (a tempo change moves every note in time),
     * its sound (program, role, sound bank, MIDI channel) and its notes where they sound. It does
     * not depend on whether the piece can be written as {@code arrangement.tsv}.
     */
    public static String digest(Composition composition, String partId) {
        Part part = composition.part(partId);
        if (part == null) {
            return "";
        }
        StringBuilder sounding = new StringBuilder();
        sounding.append(composition.timeMap()).append('\n');
        sounding.append(part.program()).append('\t').append(part.role()).append('\t').append(part.libraryId())
                .append('\t').append(TsvExport.channels(composition).get(partId)).append('\n');
        for (Composition.PlacedNote placed : composition.notesOf(partId)) {
            sounding.append(placed.tick()).append('\t').append(placed.note().length()).append('\t')
                    .append(placed.note().pitch()).append('\t').append(placed.note().velocity()).append('\n');
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(sounding.toString().getBytes(StandardCharsets.UTF_8)));
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
                    text(e, "origin"), text(e, "state"), text(e, "partDigest")));
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
            return e;
        }));
        JsonText.atomically(file(folder), root);
    }
}
