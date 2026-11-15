package com.aresstack.audio.studio.album;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The proposals made for the fields of one album so far. They are kept with the album
 * ({@code suggestions.json} in its folder), so that the list grows while the user works and is
 * there again the next time; new proposals come first, old ones stay.
 */
public final class AlbumSuggestions {
    public static final String FILE_NAME = "suggestions.json";
    /** The most proposals kept for a field; the oldest go first. */
    public static final int MAX = 80;
    public static final AlbumSuggestions EMPTY = new AlbumSuggestions(Map.of());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Map<AlbumSuggester.Field, List<String>> fields;

    private AlbumSuggestions(Map<AlbumSuggester.Field, List<String>> fields) {
        Map<AlbumSuggester.Field, List<String>> copy =
                new EnumMap<AlbumSuggester.Field, List<String>>(AlbumSuggester.Field.class);
        for (AlbumSuggester.Field field : AlbumSuggester.Field.values()) {
            List<String> list = fields.get(field);
            copy.put(field, list == null ? List.of() : List.copyOf(list));
        }
        this.fields = copy;
    }

    /** The proposals for a field, the newest first. */
    public List<String> of(AlbumSuggester.Field field) {
        return fields.get(field);
    }

    /** These proposals in front of the ones known already; one that is known keeps its place. */
    public AlbumSuggestions with(AlbumSuggester.Field field, List<String> fresh) {
        List<String> joined = new ArrayList<String>();
        for (String text : fresh) {
            if (text != null && !text.isBlank() && !contains(joined, text) && !contains(of(field), text)) {
                joined.add(text.trim());
            }
        }
        if (joined.isEmpty()) {
            return this;
        }
        joined.addAll(of(field));
        Map<AlbumSuggester.Field, List<String>> changed =
                new EnumMap<AlbumSuggester.Field, List<String>>(fields);
        changed.put(field, joined.size() > MAX ? joined.subList(0, MAX) : joined);
        return new AlbumSuggestions(changed);
    }

    /** Whether a list has this text already, whatever its capitals. */
    static boolean contains(List<String> list, String text) {
        if (list == null) {
            return false;
        }
        for (String each : list) {
            if (each.trim().equalsIgnoreCase(text.trim())) {
                return true;
            }
        }
        return false;
    }

    /** The proposals kept in the folder of an album; none if there is no readable file. */
    public static AlbumSuggestions load(Path albumFolder) {
        Path file = albumFolder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<AlbumSuggester.Field, List<String>> read =
                    new EnumMap<AlbumSuggester.Field, List<String>>(AlbumSuggester.Field.class);
            for (AlbumSuggester.Field field : AlbumSuggester.Field.values()) {
                List<String> list = new ArrayList<String>();
                if (json.has(field.key()) && json.get(field.key()).isJsonArray()) {
                    for (JsonElement entry : json.getAsJsonArray(field.key())) {
                        if (entry.isJsonPrimitive() && !entry.getAsString().isBlank() && list.size() < MAX) {
                            list.add(entry.getAsString().trim());
                        }
                    }
                }
                read.put(field, list);
            }
            return new AlbumSuggestions(read);
        } catch (IOException | RuntimeException unreadable) {
            return EMPTY;
        }
    }

    /** Keeps the proposals in the folder of an album. */
    public void save(Path albumFolder) throws IOException {
        JsonObject json = new JsonObject();
        for (AlbumSuggester.Field field : AlbumSuggester.Field.values()) {
            JsonArray array = new JsonArray();
            for (String text : of(field)) {
                array.add(text);
            }
            json.add(field.key(), array);
        }
        Files.createDirectories(albumFolder);
        Path fresh = albumFolder.resolve(FILE_NAME + ".part");
        Files.writeString(fresh, GSON.toJson(json), StandardCharsets.UTF_8);
        Files.move(fresh, albumFolder.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
    }
}
