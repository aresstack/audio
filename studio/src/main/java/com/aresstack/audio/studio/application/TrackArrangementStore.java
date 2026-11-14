package com.aresstack.audio.studio.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Preserve user-edited track assignments independently of the generated note JSON caches. */
public final class TrackArrangementStore {
    public static final String FILE_NAME = "tracks.json";

    public void save(Path directory, TrackArrangement arrangement) throws IOException {
        Files.createDirectories(directory);
        JsonArray tracks = new JsonArray();
        for (MusicTrack track : arrangement.tracks()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", track.id());
            value.addProperty("role", track.role());
            value.addProperty("instrument", track.instrument());
            value.addProperty("program", track.program());
            value.addProperty("libraryId", track.libraryId());
            tracks.add(value);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("tracks", tracks);
        Path target = directory.resolve(FILE_NAME);
        Path temporary = Files.createTempFile(directory, "tracks-", ".tmp");
        try {
            Files.write(temporary, root.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public TrackArrangement load(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return TrackArrangement.defaults();
        }
        try {
            JsonObject root = new JsonParser().parse(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            List<MusicTrack> tracks = new ArrayList<MusicTrack>();
            for (JsonElement element : root.getAsJsonArray("tracks")) {
                JsonObject item = element.getAsJsonObject();
                tracks.add(new MusicTrack(item.get("id").getAsString(),
                        item.get("role").getAsString(),
                        item.get("instrument").getAsString(),
                        item.get("program").getAsInt(),
                        item.has("libraryId") ? item.get("libraryId").getAsString() : "builtin"));
            }
            return new TrackArrangement(tracks);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read saved arrangement tracks: " + file, invalid);
        }
    }
}
