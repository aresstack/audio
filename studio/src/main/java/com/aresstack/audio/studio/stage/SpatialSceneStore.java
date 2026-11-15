package com.aresstack.audio.studio.stage;

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

/** Keeps the stage of a piece in {@code spatial-scene.json} next to its tracks. */
public final class SpatialSceneStore {
    public static final String FILE_NAME = "spatial-scene.json";
    public static final int VERSION = 1;

    public void save(Path directory, SpatialScene scene) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(FILE_NAME);
        Path temporary = Files.createTempFile(directory, "scene-", ".tmp");
        try {
            Files.write(temporary, write(scene).toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The saved scene, or {@code null} when the piece has none yet (a piece from before the stage). */
    public SpatialScene load(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return read(new JsonParser().parse(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read the saved stage: " + file, invalid);
        }
    }

    /** The value of "level" for a piece with the fixed level structure; without it the piece is mixed to its peak. */
    private static final String LEVEL_FIXED = "fixed";

    public static JsonObject write(SpatialScene scene) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject room = new JsonObject();
        room.addProperty("kind", scene.room().kind());
        room.addProperty("reverbSeconds", round(scene.room().reverbSeconds()));
        room.addProperty("amount", round(scene.room().amount()));
        root.add("room", room);
        root.addProperty("master", round(scene.masterDb()));
        if (scene.fixedLevel()) {
            // Only a piece that was switched says so: the file of every other piece stays as it was.
            root.addProperty("level", LEVEL_FIXED);
        }
        JsonArray sources = new JsonArray();
        for (SoundSourcePlacement placement : scene.placements()) {
            JsonObject source = new JsonObject();
            source.addProperty("track", placement.trackId());
            source.addProperty("x", round(placement.x()));
            source.addProperty("depth", round(placement.depth()));
            source.addProperty("level", round(placement.levelDb()));
            source.addProperty("width", round(placement.width()));
            source.addProperty("reverb", round(placement.reverb()));
            source.addProperty("muted", placement.muted());
            source.addProperty("locked", placement.locked());
            if (!placement.dynamics().isEmpty()) {
                JsonArray points = new JsonArray();
                for (SoundSourcePlacement.LevelPoint point : placement.dynamics()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("bar", round(point.bar()));
                    entry.addProperty("db", round(point.db()));
                    points.add(entry);
                }
                source.add("dynamics", points);
            }
            sources.add(source);
        }
        root.add("sources", sources);
        return root;
    }

    /**
     * Reads a scene as it was saved or as the AI wrote it. Whatever is missing takes
     * its usual value and whatever is out of range is brought into range; a source
     * without the name of its track is left out.
     */
    public static SpatialScene read(JsonObject root) {
        AcousticRoom room = AcousticRoom.HALL;
        if (root.has("room") && root.get("room").isJsonObject()) {
            JsonObject saved = root.getAsJsonObject("room");
            room = AcousticRoom.of(text(saved, "kind", "hall"));
            room = room.withReverbSeconds(number(saved, "reverbSeconds", room.reverbSeconds()))
                    .withAmount(number(saved, "amount", room.amount()));
        } else if (root.has("room") && root.get("room").isJsonPrimitive()) {
            room = AcousticRoom.of(root.get("room").getAsString());
        }
        List<SoundSourcePlacement> placements = new ArrayList<SoundSourcePlacement>();
        JsonElement list = root.has("sources") ? root.get("sources") : root.get("stage");
        if (list != null && list.isJsonArray()) {
            for (JsonElement element : list.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject source = element.getAsJsonObject();
                String track = text(source, "track", text(source, "id", ""));
                if (track.trim().isEmpty()) {
                    continue;
                }
                List<SoundSourcePlacement.LevelPoint> points = new ArrayList<SoundSourcePlacement.LevelPoint>();
                if (source.has("dynamics") && source.get("dynamics").isJsonArray()) {
                    for (JsonElement entry : source.getAsJsonArray("dynamics")) {
                        if (entry.isJsonObject()) {
                            JsonObject point = entry.getAsJsonObject();
                            points.add(new SoundSourcePlacement.LevelPoint(number(point, "bar", 0),
                                    number(point, "db", 0)));
                        }
                    }
                }
                placements.add(new SoundSourcePlacement(track, number(source, "x", 0), number(source, "depth", 0.4),
                        number(source, "level", 0), number(source, "width", 1), number(source, "reverb", 0.35),
                        flag(source, "muted"), flag(source, "locked"), points));
            }
        }
        return new SpatialScene(room, placements, number(root, "master", 0),
                LEVEL_FIXED.equals(text(root, "level", "")));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static String text(JsonObject object, String name, String otherwise) {
        try {
            return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : otherwise;
        } catch (RuntimeException odd) {
            return otherwise;
        }
    }

    private static double number(JsonObject object, String name, double otherwise) {
        try {
            if (object.has(name) && object.get(name).isJsonPrimitive()) {
                double value = object.get(name).getAsDouble();
                return Double.isNaN(value) || Double.isInfinite(value) ? otherwise : value;
            }
        } catch (RuntimeException odd) {
            // a word where a number belongs: the usual value
        }
        return otherwise;
    }

    private static boolean flag(JsonObject object, String name) {
        try {
            return object.has(name) && object.get(name).isJsonPrimitive() && object.get(name).getAsBoolean();
        } catch (RuntimeException odd) {
            return false;
        }
    }
}
