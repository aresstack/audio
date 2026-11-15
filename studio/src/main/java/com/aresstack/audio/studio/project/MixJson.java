package com.aresstack.audio.studio.project;

import static com.aresstack.audio.studio.project.CompositionJson.array;
import static com.aresstack.audio.studio.project.CompositionJson.list;
import static com.aresstack.audio.studio.project.CompositionJson.put;
import static com.aresstack.audio.studio.project.CompositionJson.text;

import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** {@code mix.json}: the mix of a piece, the successor of {@code spatial-scene.json}. */
public final class MixJson {
    public static final String FILE_NAME = "mix.json";
    public static final String FORMAT = "aresstack-mix";
    public static final int VERSION = 1;

    private MixJson() {
    }

    /** The mix of the piece in a folder, or {@code null} if it has none. */
    public static MixScene load(Path folder) throws IOException {
        Path file = folder.resolve(FILE_NAME);
        return Files.isRegularFile(file) ? read(JsonText.read(file)) : null;
    }

    public static void save(Path folder, MixScene mix) throws IOException {
        JsonText.atomically(folder.resolve(FILE_NAME), write(mix));
    }

    public static JsonObject write(MixScene mix) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        JsonObject room = new JsonObject();
        room.addProperty("kind", mix.room().kind());
        room.addProperty("reverbSeconds", mix.room().reverbSeconds());
        room.addProperty("amount", mix.room().amount());
        root.add("room", room);
        root.addProperty("master", mix.masterDb());
        root.addProperty("levelMode", mix.levelMode().name().toLowerCase(Locale.ROOT));
        root.add("channels", array(mix.channels(), channel -> {
            JsonObject c = new JsonObject();
            c.addProperty("id", channel.id());
            c.addProperty("ownerKind", channel.owner().kind().name().toLowerCase(Locale.ROOT));
            c.addProperty("owner", channel.owner().id());
            c.addProperty("x", channel.x());
            c.addProperty("depth", channel.depth());
            c.addProperty("level", channel.levelDb());
            c.addProperty("width", channel.width());
            c.addProperty("reverb", channel.reverb());
            c.addProperty("muted", channel.muted());
            c.addProperty("locked", channel.locked());
            return c;
        }));
        root.add("automation", array(mix.automation(), lane -> {
            JsonObject l = new JsonObject();
            l.addProperty("id", lane.id());
            put(l, "channel", lane.channelId());
            l.addProperty("parameter", lane.parameter());
            l.add("points", array(lane.points(), point -> {
                JsonObject p = new JsonObject();
                p.addProperty("tick", point.tick());
                p.addProperty("value", point.value());
                return p;
            }));
            return l;
        }));
        return root;
    }

    public static MixScene read(JsonObject root) throws IOException {
        try {
            if (!FORMAT.equals(text(root, "format"))) {
                throw new IOException("Not a mix: format is " + text(root, "format"));
            }
            int version = root.get("version").getAsInt();
            if (version > VERSION) {
                throw new IOException("The mix was written by a newer studio (version " + version + ").");
            }
            JsonObject room = root.getAsJsonObject("room");
            return new MixScene(
                    new Room(text(room, "kind"), room.get("reverbSeconds").getAsDouble(), room.get("amount").getAsDouble()),
                    root.get("master").getAsDouble(),
                    LevelMode.valueOf(text(root, "levelMode").toUpperCase(Locale.ROOT)),
                    list(root, "channels", c -> new MixChannel(text(c, "id"), new MixChannel.Owner(
                            MixChannel.Owner.Kind.valueOf(text(c, "ownerKind").toUpperCase(Locale.ROOT)), text(c, "owner")),
                            c.get("x").getAsDouble(), c.get("depth").getAsDouble(), c.get("level").getAsDouble(),
                            c.get("width").getAsDouble(), c.get("reverb").getAsDouble(), c.get("muted").getAsBoolean(),
                            c.get("locked").getAsBoolean())),
                    list(root, "automation", l -> new AutomationLane(text(l, "id"), text(l, "channel"),
                            text(l, "parameter"), list(l, "points", p -> new AutomationLane.Point(
                                    p.get("tick").getAsLong(), p.get("value").getAsDouble())))));
        } catch (RuntimeException invalid) {
            throw new IOException("The mix cannot be read: " + invalid.getMessage(), invalid);
        }
    }
}
