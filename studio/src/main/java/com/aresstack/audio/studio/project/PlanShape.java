package com.aresstack.audio.studio.project;

import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.TimeMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The course of tempo and loudness a plan of the AI names ({@code composition-plan.json}), as
 * the model holds it: tempo points, dynamic marks and hairpins, counted in bars and beats as a
 * musician counts them, become ticks of the piece. What cannot be read is left out and said.
 *
 * <p>The plan writes, all optional:
 * <pre>
 * "tempoChanges": [{"bar": 13, "bpm": 96, "glide": true}, {"bar": 15, "bpm": 80}]
 * "dynamics":     [{"bar": 1, "level": "p"}, {"bar": 9, "beat": 3, "level": "f", "part": "bass"}]
 * "hairpins":     [{"fromBar": 5, "toBar": 9, "to": "f"}]
 * </pre>
 * Bars and beats count from 1, a beat is a quarter note. A tempo point with {@code glide} changes
 * evenly from there to the next point (accelerando or ritardando); without, the tempo jumps. A
 * hairpin ends at the start of {@code toBar} (or at {@code toBeat} in it) on the level {@code to}.
 */
public final class PlanShape {
    /** The slowest and the fastest tempo a piece may have, in quarter notes per minute. */
    public static final int MIN_BPM = 20;
    public static final int MAX_BPM = 400;

    /** What the plan says, in ticks. */
    public record Shape(TimeMap timeMap, Dynamics dynamics) {
    }

    private PlanShape() {
    }

    /**
     * The time map with the plan's tempo points on top of the first tempo of {@code base}, and
     * the dynamics of the plan. Without such entries, {@code base} and no dynamics.
     *
     * @param parts  the ids of the parts a mark may name; a mark of another part is left out
     * @param minBpm the slowest tempo a point may name
     * @param maxBpm the fastest tempo a point may name
     * @param endTick the end of the piece: nothing is placed at or after it
     */
    public static Shape read(JsonObject plan, TimeMap base, Set<String> parts, double minBpm, double maxBpm,
                             long endTick, Consumer<String> warn) {
        if (plan == null) {
            return new Shape(base, Dynamics.NONE);
        }
        return new Shape(tempos(plan, base, minBpm, maxBpm, endTick, warn), dynamics(plan, base, parts, endTick, warn));
    }

    private static TimeMap tempos(JsonObject plan, TimeMap base, double minBpm, double maxBpm, long endTick,
                                  Consumer<String> warn) {
        JsonArray points = array(plan, "tempoChanges");
        if (points == null || points.isEmpty()) {
            return base;
        }
        TreeMap<Long, TimeMap.Tempo> byTick = new TreeMap<>();
        TimeMap.Tempo first = base.tempos().get(0);
        byTick.put(0L, first);
        for (JsonElement element : points) {
            try {
                JsonObject point = element.getAsJsonObject();
                long tick = tick(base, point, "bar", "beat");
                double bpm = point.get("bpm").getAsDouble();
                boolean glide = point.has("glide") && point.get("glide").getAsBoolean();
                if (!(bpm >= minBpm && bpm <= maxBpm)) {
                    warn.accept("Plan: Tempo " + bpm + " liegt nicht zwischen " + minBpm + " und " + maxBpm
                            + " BPM und wird übergangen.");
                } else if (tick >= endTick) {
                    warn.accept("Plan: Tempowechsel nach dem Ende des Stücks übergangen: " + point);
                } else if (tick == 0) {
                    // The tempo of the first bar is the tempo of the piece; only whether it glides is taken.
                    byTick.put(0L, new TimeMap.Tempo(0, first.bpm(), glide));
                } else {
                    byTick.put(tick, new TimeMap.Tempo(tick, bpm, glide));
                }
            } catch (RuntimeException odd) {
                warn.accept("Plan: unlesbarer Tempowechsel übergangen: " + element);
            }
        }
        List<TimeMap.Tempo> tempos = new ArrayList<>(byTick.values());
        TimeMap.Tempo last = tempos.get(tempos.size() - 1);
        if (last.glide()) {
            warn.accept("Plan: der letzte Tempowechsel hat kein Ziel zum Gleiten; er gilt als fester Wechsel.");
            tempos.set(tempos.size() - 1, new TimeMap.Tempo(last.tick(), last.bpm()));
        }
        return new TimeMap(tempos, base.meters(), base.keys());
    }

    private static Dynamics dynamics(JsonObject plan, TimeMap time, Set<String> parts, long endTick,
                                     Consumer<String> warn) {
        List<Dynamics.Mark> marks = new ArrayList<>();
        JsonArray written = array(plan, "dynamics");
        if (written != null) {
            for (JsonElement element : written) {
                try {
                    JsonObject mark = element.getAsJsonObject();
                    String part = part(mark, parts, warn);
                    long tick = tick(time, mark, "bar", "beat");
                    if (tick < endTick && !"?".equals(part)) {
                        marks.add(new Dynamics.Mark(tick, Dynamics.Level.parse(mark.get("level").getAsString()), part));
                    }
                } catch (RuntimeException odd) {
                    warn.accept("Plan: unlesbares Dynamikzeichen übergangen: " + element);
                }
            }
        }
        List<Dynamics.Hairpin> hairpins = new ArrayList<>();
        JsonArray pins = array(plan, "hairpins");
        if (pins != null) {
            for (JsonElement element : pins) {
                try {
                    JsonObject pin = element.getAsJsonObject();
                    String part = part(pin, parts, warn);
                    long start = tick(time, pin, "fromBar", "fromBeat");
                    long end = Math.min(endTick, tick(time, pin, "toBar", "toBeat"));
                    if ("?".equals(part)) {
                        continue;
                    }
                    Dynamics.Hairpin made = new Dynamics.Hairpin(start, end,
                            Dynamics.Level.parse(pin.get("to").getAsString()), part);
                    if (overlaps(hairpins, made)) {
                        warn.accept("Plan: Gabel überschneidet eine andere und wird übergangen: " + element);
                    } else {
                        hairpins.add(made);
                    }
                } catch (RuntimeException odd) {
                    warn.accept("Plan: unlesbare Gabel übergangen: " + element);
                }
            }
        }
        if (marks.isEmpty() && hairpins.isEmpty()) {
            return Dynamics.NONE;
        }
        return new Dynamics(marks, hairpins, Dynamics.Scale.STANDARD, Dynamics.Articulations.STANDARD);
    }

    private static boolean overlaps(List<Dynamics.Hairpin> pins, Dynamics.Hairpin made) {
        for (Dynamics.Hairpin pin : pins) {
            if (java.util.Objects.equals(pin.partId(), made.partId()) && pin.startTick() < made.endTick()
                    && made.startTick() < pin.endTick()) {
                return true;
            }
        }
        return false;
    }

    /** The part an entry names: {@code null} for all, {@code "?"} for one the piece does not have. */
    private static String part(JsonObject entry, Set<String> parts, Consumer<String> warn) {
        if (!entry.has("part") || entry.get("part").isJsonNull()) {
            return null;
        }
        String part = entry.get("part").getAsString().trim();
        if (part.isEmpty() || part.equalsIgnoreCase("all")) {
            return null;
        }
        if (!parts.contains(part)) {
            warn.accept("Plan: Dynamik für die unbekannte Stimme " + part + " übergangen.");
            return "?";
        }
        return part;
    }

    /** The tick of a bar and a beat in it, both counted from 1; the beat is a quarter note. */
    public static long tick(TimeMap time, JsonObject entry, String barKey, String beatKey) {
        int bar = entry.get(barKey).getAsInt();
        if (bar < 1) {
            throw new IllegalArgumentException("Bars count from 1: " + bar);
        }
        double beat = entry.has(beatKey) ? entry.get(beatKey).getAsDouble() : 1;
        if (beat < 1) {
            throw new IllegalArgumentException("Beats count from 1: " + beat);
        }
        long start = time.tickOfBar(bar - 1);
        long into = Math.round((beat - 1) * TimeMap.PPQ);
        return start + Math.min(into, time.meterAt(start).barTicks() - 1);
    }

    private static JsonArray array(JsonObject plan, String key) {
        JsonElement element = plan.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }
}
