package com.aresstack.audio.studio.figure;

import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The figures of musicians the AI has drawn, kept so that they need not be drawn again: one
 * folder per kind of instrument ({@link #kind}), in it the figures as SVG with a number
 * ({@code violine/1.svg}, {@code violine/2.svg}) and a note of who drew them. A figure is known by
 * its id, {@code kind/number}. Every piece takes its figures from here ({@link FigureCast}).
 */
public final class FigureLibrary {
    /** The size at which a figure is drawn into a picture: the shape of the SVG the AI is asked for. */
    public static final int PICTURE_WIDTH = 128;
    public static final int PICTURE_HEIGHT = 256;

    private final Path directory;
    private final Map<String, BufferedImage> pictures = new HashMap<>();

    public FigureLibrary(Path directory) {
        this.directory = directory;
    }

    /** The library of the user, beside the compositions. */
    public static FigureLibrary standard() {
        return new FigureLibrary(Paths.get(System.getProperty("user.home"), ".aresstack", "audio", "figures"));
    }

    public Path directory() {
        return directory;
    }

    /**
     * The kind of figure an instrument asks for: its name in small letters, umlauts and accents
     * written plainly, everything else a hyphen ({@code "Streicher (Ensemble)"} gives
     * {@code streicher-ensemble}). A number at the end tells players apart, not instruments:
     * {@code "Violine 2"} is a {@code violine}.
     */
    public static String kind(String instrument) {
        String plain = instrument == null ? "" : instrument.trim().toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        plain = Normalizer.normalize(plain, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        plain = plain.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        // a trailing number tells players apart ("Violine 2"), not instruments
        plain = plain.replaceAll("-[0-9]+$", "");
        if (plain.length() > 60) {
            plain = plain.substring(0, 60).replaceAll("-+$", "");
        }
        return plain.isEmpty() ? "musiker" : plain;
    }

    /** The kind of a figure id. */
    public static String kindOf(String id) {
        int slash = id.indexOf('/');
        return slash < 0 ? id : id.substring(0, slash);
    }

    /** The figures of one kind, by their number. */
    public synchronized List<String> figures(String kind) {
        Path folder = directory.resolve(kind);
        List<Integer> numbers = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    String name = file.getFileName().toString();
                    if (name.matches("[0-9]+\\.svg")) {
                        numbers.add(Integer.parseInt(name.substring(0, name.length() - 4)));
                    }
                }
            } catch (IOException | NumberFormatException unreadable) {
                // a folder that cannot be read has no figures
            }
        }
        numbers.sort(Integer::compare);
        List<String> ids = new ArrayList<>();
        for (int number : numbers) {
            ids.add(kind + "/" + number);
        }
        return ids;
    }

    /** Whether a figure is in the library. */
    public boolean has(String id) {
        return id != null && id.matches("[a-z0-9-]+/[0-9]+") && Files.isRegularFile(svgFile(id));
    }

    /**
     * Keeps a new figure under the next free number of its kind.
     *
     * @return its id
     */
    public synchronized String add(String kind, String svg, String model, String instrument) throws IOException {
        Path folder = directory.resolve(kind);
        Files.createDirectories(folder);
        List<String> known = figures(kind);
        int number = known.isEmpty() ? 1 : Integer.parseInt(known.get(known.size() - 1).substring(kind.length() + 1)) + 1;
        String id = kind + "/" + number;
        Files.writeString(svgFile(id), svg, StandardCharsets.UTF_8);
        JsonObject note = new JsonObject();
        note.addProperty("instrument", instrument);
        note.addProperty("model", model == null ? "" : model);
        note.addProperty("drawn", java.time.Instant.now().toString());
        Files.writeString(folder.resolve(number + ".json"), note.toString(), StandardCharsets.UTF_8);
        return id;
    }

    /**
     * The figure as a picture of {@link #PICTURE_WIDTH} × {@link #PICTURE_HEIGHT}, standing on its
     * bottom edge; drawn once and then kept. {@code null} if it is not there or cannot be drawn.
     */
    public synchronized BufferedImage picture(String id) {
        if (id == null) {
            return null;
        }
        if (pictures.containsKey(id)) {
            return pictures.get(id);
        }
        BufferedImage picture = null;
        if (has(id)) {
            try {
                picture = SvgRasterizer.render(Files.readString(svgFile(id), StandardCharsets.UTF_8),
                        PICTURE_WIDTH, PICTURE_HEIGHT);
            } catch (IOException | SvgRasterizer.UnreadableSvgException | RuntimeException unreadable) {
                picture = null;
            }
        }
        pictures.put(id, picture);
        return picture;
    }

    /**
     * Takes every figure out of the library. They are not destroyed but moved into a folder beside
     * the library ({@code figures-entfernt/<time>}), from where they can be put back by hand.
     *
     * @return how many figures were taken out
     */
    public synchronized int clear() throws IOException {
        pictures.clear();
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        List<Path> kinds = new ArrayList<>();
        int count = 0;
        try (Stream<Path> folders = Files.list(directory)) {
            for (Path folder : (Iterable<Path>) folders::iterator) {
                if (Files.isDirectory(folder) && !figures(folder.getFileName().toString()).isEmpty()) {
                    kinds.add(folder);
                    count += figures(folder.getFileName().toString()).size();
                }
            }
        }
        if (kinds.isEmpty()) {
            return 0;
        }
        String time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path away = directory.resolveSibling(directory.getFileName() + "-entfernt").resolve(time);
        Files.createDirectories(away);
        for (Path kind : kinds) {
            Files.move(kind, away.resolve(kind.getFileName()));
        }
        return count;
    }

    private Path svgFile(String id) {
        return directory.resolve(kindOf(id)).resolve(id.substring(id.indexOf('/') + 1) + ".svg");
    }
}
