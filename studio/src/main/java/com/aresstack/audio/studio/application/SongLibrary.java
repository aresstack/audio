package com.aresstack.audio.studio.application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The pieces the user has made: one folder each below the studio's folder for
 * compositions. A piece has a name and the description it was composed from,
 * kept in {@code song.properties} in its folder beside the cached answers, the
 * arrangement and the rendered files. Folders from before this file existed are
 * pieces too; they are called by the title the model gave them, or by their folder.
 */
public final class SongLibrary {
    public static final String FILE_NAME = "song.properties";

    /** One piece: its folder and what it is called. */
    public static final class Song {
        private final Path folder;
        private final String name;
        private final long changed;

        Song(Path folder, String name, long changed) {
            this.folder = folder;
            this.name = name;
            this.changed = changed;
        }

        public Path folder() {
            return folder;
        }

        public String name() {
            return name;
        }

        public long changed() {
            return changed;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Song && ((Song) other).folder.equals(folder);
        }

        @Override
        public int hashCode() {
            return folder.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final Path root;

    public SongLibrary(Path root) {
        this.root = root;
    }

    /** The library in the user's folder: {@code ~/.aresstack/audio/compositions}. */
    public static SongLibrary standard() {
        return new SongLibrary(Paths.get(System.getProperty("user.home"), ".aresstack", "audio", "compositions"));
    }

    public Path root() {
        return root;
    }

    /** All pieces, the one changed last first. */
    public List<Song> songs() {
        List<Song> songs = new ArrayList<Song>();
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
                for (Path folder : folders) {
                    if (Files.isDirectory(folder)) {
                        songs.add(song(folder));
                    }
                }
            } catch (IOException unreadable) {
                // An unreadable library is an empty one; the piece in use is still offered by the caller.
            }
        }
        Collections.sort(songs, new Comparator<Song>() {
            @Override
            public int compare(Song first, Song second) {
                return Long.compare(second.changed, first.changed);
            }
        });
        return songs;
    }

    /** The piece in a folder, wherever the folder lies. */
    public Song song(Path folder) {
        long changed = 0;
        try {
            changed = Files.getLastModifiedTime(folder).toMillis();
            for (String file : new String[]{"arrangement.tsv", "arrangement-instruments.wav", FILE_NAME}) {
                Path path = folder.resolve(file);
                if (Files.isRegularFile(path)) {
                    changed = Math.max(changed, Files.getLastModifiedTime(path).toMillis());
                }
            }
        } catch (IOException missing) {
            // A folder that does not exist yet has no date.
        }
        return new Song(folder, name(folder), changed);
    }

    /** What a piece is called: the name it was given, else the title of its plan, else its folder. */
    public String name(Path folder) {
        String given = properties(folder).getProperty("name", "").trim();
        if (!given.isEmpty()) {
            return given;
        }
        Path plan = folder.resolve("composition-plan.json");
        if (Files.isRegularFile(plan)) {
            try {
                JsonObject json = new JsonParser().parse(new String(Files.readAllBytes(plan), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (json.has("title") && json.get("title").isJsonPrimitive()
                        && !json.get("title").getAsString().trim().isEmpty()) {
                    return json.get("title").getAsString().trim();
                }
            } catch (IOException | RuntimeException unreadable) {
                // then the folder's name has to do
            }
        }
        return folder.getFileName() == null ? folder.toString() : folder.getFileName().toString();
    }

    /** The description a piece was composed from, or {@code null} if none was kept. */
    public String description(Path folder) {
        String kept = properties(folder).getProperty("description");
        return kept == null || kept.trim().isEmpty() ? null : kept;
    }

    /** Makes a new, empty piece with this name and returns its folder. */
    public Path create(String name) throws IOException {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            clean = "Neues Stück";
        }
        String slug = clean.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("ß", "ss").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "stueck";
        }
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        Path folder = root.resolve(slug);
        for (int number = 2; Files.exists(folder); number++) {
            folder = root.resolve(slug + "-" + number);
        }
        Files.createDirectories(folder);
        save(folder, clean, null);
        return folder;
    }

    /** Keeps the name and the description of a piece; {@code null} leaves a value as it is. */
    public void save(Path folder, String name, String description) throws IOException {
        Properties properties = properties(folder);
        if (name != null && !name.trim().isEmpty()) {
            properties.setProperty("name", name.trim());
        }
        if (description != null) {
            properties.setProperty("description", description);
        }
        if (properties.isEmpty()) {
            return;
        }
        Files.createDirectories(folder);
        try (Writer writer = Files.newBufferedWriter(folder.resolve(FILE_NAME), StandardCharsets.UTF_8)) {
            properties.store(writer, "Audio Studio piece");
        }
    }

    private static Properties properties(Path folder) {
        Properties properties = new Properties();
        Path file = folder.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException unreadable) {
                // treated as a piece without a name of its own
            }
        }
        return properties;
    }
}
