package com.aresstack.audio.studio.album;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The albums of a library as files: {@code <library>/.albums/<id>/album.json}. The folder begins
 * with a dot, so that the library does not take it for a piece. The pieces of an album are not
 * in there: they are ordinary folders of the library, and the album names them.
 */
public final class AlbumFiles implements AlbumRepository {
    public static final String FOLDER = ".albums";
    public static final String FILE_NAME = "album.json";
    private static final int FORMAT = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path root;

    /** @param library the root of the library of pieces ({@code SongLibrary.root()}) */
    public AlbumFiles(Path library) {
        this.root = library.resolve(FOLDER);
    }

    /** The folder of an album, where what belongs to it is kept (its plan, the state of its production). */
    public Path folder(String id) {
        if (!Album.isId(id)) {
            // "." or "a/.." would name the folder of all albums, or one outside of it.
            throw new IllegalArgumentException("Keine Kennung eines Albums: " + id);
        }
        return root.resolve(id);
    }

    @Override
    public synchronized List<Album> albums() {
        List<Album> albums = new ArrayList<Album>();
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
                for (Path folder : folders) {
                    album(folder.getFileName().toString()).ifPresent(albums::add);
                }
            } catch (IOException unreadable) {
                // An unreadable folder of albums is an empty one.
            }
        }
        albums.sort(Comparator.comparing((Album album) -> album.title().toLowerCase(Locale.ROOT))
                .thenComparing(Album::id));
        return albums;
    }

    @Override
    public synchronized Optional<Album> album(String id) {
        if (!Album.isId(id)) {
            return Optional.empty();
        }
        Path file = folder(id).resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonObject json = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return Optional.of(read(id, json));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty(); // one broken album does not hide the others
        }
    }

    @Override
    public synchronized Album create(String title, AlbumConcept concept) throws IOException {
        String slug = slug(title);
        String id = slug;
        for (int number = 2; Files.exists(folder(id)); number++) {
            id = slug + "-" + number;
        }
        Album album = new Album(id, title, concept, List.of());
        save(album);
        return album;
    }

    @Override
    public synchronized void save(Album album) throws IOException {
        Path folder = folder(album.id());
        Files.createDirectories(folder);
        Path temporary = Files.createTempFile(folder, FILE_NAME, ".part");
        try {
            Files.write(temporary, (GSON.toJson(write(album)) + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, folder.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(temporary, folder.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void delete(String id) throws IOException {
        // Only the folder of one album, directly in the folder of the albums, is ever deleted.
        Path folder = folder(id).toAbsolutePath().normalize();
        Path albums = root.toAbsolutePath().normalize();
        if (!albums.equals(folder.getParent()) || !folder.getFileName().toString().equals(id)) {
            throw new IllegalArgumentException("Keine Kennung eines Albums: " + id);
        }
        if (!Files.isDirectory(folder, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    /** A name of a folder from a title: lower case, letters and digits, words joined by hyphens. */
    static String slug(String title) {
        String plain = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace("ß", "ss").toLowerCase(Locale.ROOT);
        String slug = plain.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 48) {
            slug = slug.substring(0, 48).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "album" : slug;
    }

    static JsonObject write(Album album) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("title", album.title());
        JsonObject concept = new JsonObject();
        concept.addProperty("description", album.concept().description());
        concept.addProperty("style", album.concept().style());
        concept.addProperty("trackCount", album.concept().trackCount());
        concept.addProperty("secondsPerTrack", album.concept().secondsPerTrack());
        concept.addProperty("minSeconds", album.concept().minSeconds());
        concept.addProperty("maxSeconds", album.concept().maxSeconds());
        json.add("concept", concept);
        JsonArray tracks = new JsonArray();
        for (AlbumTrack track : album.tracks()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", track.id());
            entry.addProperty("title", track.title());
            entry.addProperty("mood", track.mood());
            entry.addProperty("tempoBpm", track.bpm());
            entry.addProperty("key", track.key());
            entry.addProperty("meter", track.meter());
            entry.addProperty("instrumentation", track.instrumentation());
            entry.addProperty("form", track.form());
            entry.addProperty("targetSeconds", track.targetSeconds());
            if (track.hasPiece()) {
                entry.addProperty("piece", track.piece());
            }
            tracks.add(entry);
        }
        json.add("tracks", tracks);
        return json;
    }

    static Album read(String id, JsonObject json) {
        JsonObject concept = json.getAsJsonObject("concept");
        List<AlbumTrack> tracks = new ArrayList<AlbumTrack>();
        if (json.has("tracks")) {
            for (JsonElement element : json.getAsJsonArray("tracks")) {
                JsonObject entry = element.getAsJsonObject();
                tracks.add(new AlbumTrack(text(entry, "id"), text(entry, "title"), text(entry, "mood"),
                        number(entry, "tempoBpm", 100), text(entry, "key"), text(entry, "meter"),
                        text(entry, "instrumentation"), text(entry, "form"), number(entry, "targetSeconds", 180),
                        // A folder name that is not one plain name in the library is not followed.
                        AlbumTrack.isFolderName(text(entry, "piece")) ? text(entry, "piece") : ""));
            }
        }
        // An album from before the range has one length for all pieces.
        int seconds = number(concept, "secondsPerTrack", 180);
        return new Album(id, text(json, "title"), new AlbumConcept(text(concept, "description"),
                text(concept, "style"), number(concept, "trackCount", Math.max(1, tracks.size())),
                number(concept, "minSeconds", seconds), number(concept, "maxSeconds", seconds)), tracks);
    }

    static String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    static int number(JsonObject json, String name, int otherwise) {
        JsonElement value = json.get(name);
        try {
            return value == null || value.isJsonNull() ? otherwise : (int) Math.round(value.getAsDouble());
        } catch (RuntimeException notANumber) {
            return otherwise;
        }
    }
}
