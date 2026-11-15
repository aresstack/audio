package com.aresstack.audio.studio.album;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How far the production of an album is, title by title. It is written to
 * {@code production.json} in the folder of the album at every change, so that a production that
 * was interrupted, also by the end of the program, goes on where it stood. Immutable.
 */
public final class AlbumProductionState {
    public static final String FILE_NAME = "production.json";

    /** Where a title stands. */
    public enum Status {
        /** Planned only; nobody asked for its piece yet. */
        PLANNED,
        /** Waiting for its turn. */
        QUEUED,
        /** Its piece is being made right now. */
        RUNNING,
        /** Its piece is finished. */
        DONE,
        /** Making its piece failed; the others went on. */
        FAILED
    }

    /**
     * @param reason  why it failed, in the words of the failure; empty otherwise
     * @param seconds how long the finished piece is; 0 before
     * @param at      when the status was set, in milliseconds since 1970
     */
    public record Entry(Status status, String reason, double seconds, long at) {
        public Entry {
            reason = reason == null ? "" : reason;
        }
    }

    public static final AlbumProductionState EMPTY = new AlbumProductionState(Map.of());
    private static final Entry PLANNED = new Entry(Status.PLANNED, "", 0, 0);

    private final Map<String, Entry> entries;

    private AlbumProductionState(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /** Where the title with this id stands; planned if nothing is known of it. */
    public Entry of(String trackId) {
        Entry entry = entries.get(trackId);
        return entry == null ? PLANNED : entry;
    }

    public Status status(String trackId) {
        return of(trackId).status();
    }

    public AlbumProductionState with(String trackId, Status status, String reason, double seconds) {
        Map<String, Entry> next = new LinkedHashMap<String, Entry>(entries);
        next.put(trackId, new Entry(status, reason, seconds, System.currentTimeMillis()));
        return new AlbumProductionState(next);
    }

    public AlbumProductionState with(String trackId, Status status) {
        return with(trackId, status, "", 0);
    }

    /** How many titles of an album stand at this status. */
    public int count(Album album, Status status) {
        int count = 0;
        for (AlbumTrack track : album.tracks()) {
            if (status(track.id()) == status) {
                count++;
            }
        }
        return count;
    }

    /** Reads the state kept in the folder of an album; empty if there is none or it cannot be read. */
    public static AlbumProductionState load(Path albumFolder) {
        Path file = albumFolder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        try {
            JsonObject json = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
            for (Map.Entry<String, JsonElement> track : json.getAsJsonObject("tracks").entrySet()) {
                JsonObject entry = track.getValue().getAsJsonObject();
                entries.put(track.getKey(), new Entry(Status.valueOf(AlbumFiles.text(entry, "status")),
                        AlbumFiles.text(entry, "reason"),
                        entry.has("seconds") ? entry.get("seconds").getAsDouble() : 0,
                        entry.has("at") ? entry.get("at").getAsLong() : 0));
            }
            return new AlbumProductionState(entries);
        } catch (IOException | RuntimeException unreadable) {
            return EMPTY; // the pieces say what is done; see AlbumProducer
        }
    }

    /** Writes the state into the folder of an album, whole or not at all. */
    public void save(Path albumFolder) throws IOException {
        JsonObject tracks = new JsonObject();
        for (Map.Entry<String, Entry> track : entries.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("status", track.getValue().status().name());
            if (!track.getValue().reason().isEmpty()) {
                entry.addProperty("reason", track.getValue().reason());
            }
            if (track.getValue().seconds() > 0) {
                entry.addProperty("seconds", track.getValue().seconds());
            }
            entry.addProperty("at", track.getValue().at());
            tracks.add(track.getKey(), entry);
        }
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.add("tracks", tracks);
        Files.createDirectories(albumFolder);
        Path temporary = Files.createTempFile(albumFolder, FILE_NAME, ".part");
        try {
            Files.write(temporary, (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json)
                    + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, albumFolder.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(temporary, albumFolder.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
