package com.aresstack.audio.studio.figure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Which figure of the {@link FigureLibrary} stands at which track of a piece. Kept in the folder
 * of the piece ({@link #FILE_NAME}), so that a piece keeps its faces. Two tracks of the same kind
 * (five violins) get different figures as long as the library has enough of them.
 */
public final class FigureCast {
    public static final String FILE_NAME = "stage-figures.json";
    public static final FigureCast EMPTY = new FigureCast(Map.of());

    /** A track as the cast sees it: its id and the instrument it plays. */
    public record Seat(String trackId, String instrument) {
        public String kind() {
            return FigureLibrary.kind(instrument);
        }
    }

    private final Map<String, String> figures;

    public FigureCast(Map<String, String> figures) {
        this.figures = Collections.unmodifiableMap(new LinkedHashMap<>(figures));
    }

    /** The figure of a track, or {@code null}. */
    public String figure(String trackId) {
        return figures.get(trackId);
    }

    public Map<String, String> figures() {
        return figures;
    }

    public FigureCast with(String trackId, String figure) {
        Map<String, String> changed = new LinkedHashMap<>(figures);
        if (figure == null) {
            changed.remove(trackId);
        } else {
            changed.put(trackId, figure);
        }
        return new FigureCast(changed);
    }

    /** The cast kept in the folder of a piece; empty if there is none or it cannot be read. */
    public static FigureCast of(Path directory) {
        Path file = directory == null ? null : directory.resolve(FILE_NAME);
        if (file == null || !Files.isRegularFile(file)) {
            return EMPTY;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> read = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    read.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return new FigureCast(read);
        } catch (IOException | RuntimeException unreadable) {
            return EMPTY;
        }
    }

    /** Keeps the cast in the folder of a piece. */
    public void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        JsonObject json = new JsonObject();
        figures.forEach(json::addProperty);
        Path file = directory.resolve(FILE_NAME);
        Path written = directory.resolve(FILE_NAME + ".tmp");
        Files.writeString(written, json.toString(), StandardCharsets.UTF_8);
        Files.move(written, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Gives every seat that has no figure of its kind one from the library, at random among those
     * no other seat of the piece wears. A figure that left the library, or whose kind no longer
     * fits the instrument, is given up first. Seats of tracks the piece no longer has are dropped.
     *
     * @return the new cast; {@link #missing} tells which seats the library had nothing left for
     */
    public FigureCast cast(List<Seat> seats, FigureLibrary library, Random random) {
        Map<String, String> kept = new LinkedHashMap<>();
        Set<String> worn = new HashSet<>();
        for (Seat seat : seats) {
            String figure = figures.get(seat.trackId());
            if (figure != null && FigureLibrary.kindOf(figure).equals(seat.kind()) && library.has(figure)
                    && worn.add(figure)) {
                kept.put(seat.trackId(), figure);
            }
        }
        for (Seat seat : seats) {
            if (kept.containsKey(seat.trackId())) {
                continue;
            }
            List<String> free = new ArrayList<>(library.figures(seat.kind()));
            free.removeAll(worn);
            if (!free.isEmpty()) {
                String figure = free.get(random.nextInt(free.size()));
                kept.put(seat.trackId(), figure);
                worn.add(figure);
            }
        }
        return new FigureCast(kept);
    }

    /** The seats that have no figure in this cast. */
    public List<Seat> missing(List<Seat> seats) {
        List<Seat> without = new ArrayList<>();
        for (Seat seat : seats) {
            if (!figures.containsKey(seat.trackId())) {
                without.add(seat);
            }
        }
        return without;
    }

    /**
     * Another figure of the library for one seat: the next one of its kind that no other seat
     * wears, after the one it has now.
     *
     * @return the new cast, or {@code null} if the library has no other free figure of the kind
     */
    public FigureCast another(Seat seat, List<Seat> seats, FigureLibrary library) {
        Set<String> worn = new HashSet<>();
        for (Seat other : seats) {
            String figure = figures.get(other.trackId());
            if (figure != null && !other.trackId().equals(seat.trackId())) {
                worn.add(figure);
            }
        }
        List<String> all = library.figures(seat.kind());
        String now = figures.get(seat.trackId());
        int start = all.indexOf(now);
        for (int step = 1; step <= all.size(); step++) {
            String candidate = all.get(Math.floorMod(start + step, all.size()));
            if (!candidate.equals(now) && !worn.contains(candidate)) {
                return with(seat.trackId(), candidate);
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FigureCast && ((FigureCast) other).figures.equals(figures);
    }

    @Override
    public int hashCode() {
        return figures.hashCode();
    }

    @Override
    public String toString() {
        return figures.toString();
    }
}
