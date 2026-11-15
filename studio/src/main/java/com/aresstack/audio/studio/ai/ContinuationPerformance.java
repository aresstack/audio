package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.studio.project.PlanShape;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * How the new bars of a continuation or an insertion are played, as the planning step names it:
 * tempo points (with glides for accelerando and ritardando), dynamic marks and hairpins, all
 * within the new bars. Only the new bars change: before them nothing is touched, and after an
 * insertion the original goes on exactly as before, because the tempo and the level it had there
 * are set again where it resumes ({@link #applied}). What cannot be placed that way is left out
 * and said.
 *
 * <p>The planning answer writes, all optional and counted in bars of the piece as in the prompt:
 * <pre>
 * "tempoChanges": [{"bar": 17, "bpm": 92, "glide": true}, {"bar": 20, "bpm": 76}]
 * "dynamics":     [{"bar": 9, "level": "p"}, {"bar": 13, "beat": 3, "level": "f", "part": "bass"}]
 * "hairpins":     [{"fromBar": 13, "toBar": 17, "to": "ff"}]
 * </pre>
 *
 * @param tempos   tempo points in ticks of the piece (with the room of an insertion), in order
 * @param marks    dynamic marks within the new bars
 * @param hairpins hairpins within the new bars
 */
public record ContinuationPerformance(List<TimeMap.Tempo> tempos, List<Dynamics.Mark> marks,
                                      List<Dynamics.Hairpin> hairpins) {
    /** No performance planned: the new bars are played as the piece is played where they begin. */
    public static final ContinuationPerformance NONE = new ContinuationPerformance(List.of(), List.of(), List.of());

    /** The part of the planning prompt that asks for the performance of the new bars. */
    public static String request(ContinuationTask task) {
        int first = task.firstNewBar() + 1;
        int last = task.firstNewBar() + task.request().bars();
        return "Plan the performance of the new bars " + first + "-" + last + " like the marks of a score, all "
                + "optional: \"tempoChanges\":[{\"bar\":<" + first + "-" + last + ">,\"bpm\":<" + PlanShape.MIN_BPM
                + "-" + PlanShape.MAX_BPM + ">,\"glide\":<true for an accelerando or ritardando that changes evenly "
                + "until the next tempo change>}], \"dynamics\":[{\"bar\":<n>,\"beat\":<from 1>,\"level\":\"ppp|pp|p|mp|"
                + "mf|f|ff|fff\",\"part\":\"<id, leave out for all parts>\"}], \"hairpins\":[{\"fromBar\":<n>,\"fromBeat\":"
                + "<from 1>,\"toBar\":<n>,\"toBeat\":<from 1>,\"to\":\"<level reached>\",\"part\":\"<id or left out>\"}] "
                + "as keys of the same JSON object. Bars count in the piece; give marks only where the music asks for "
                + "them" + (task.inserts() ? "; after the inserted bars the original goes on in its own tempo and "
                + "loudness" : "") + ".\n";
    }

    /**
     * The performance a planning answer names, kept to the new bars of the task and to the parts
     * that go on; every entry left out is said in {@code repairs}.
     */
    public static ContinuationPerformance read(JsonObject answer, ContinuationTask task, List<String> repairs) {
        if (answer == null) {
            return NONE;
        }
        long from = task.newStart();
        long to = task.newEnd();
        TimeMap time = task.time();
        List<TimeMap.Tempo> tempos = new ArrayList<>();
        JsonElement points = answer.get("tempoChanges");
        if (points != null && points.isJsonArray()) {
            TreeMap<Long, TimeMap.Tempo> byTick = new TreeMap<>();
            for (JsonElement element : points.getAsJsonArray()) {
                try {
                    JsonObject point = element.getAsJsonObject();
                    long tick = PlanShape.tick(time, point, "bar", "beat");
                    double bpm = point.get("bpm").getAsDouble();
                    boolean glide = point.has("glide") && point.get("glide").getAsBoolean();
                    if (!(bpm >= PlanShape.MIN_BPM && bpm <= PlanShape.MAX_BPM)) {
                        repairs.add("Tempo " + bpm + " liegt nicht zwischen " + PlanShape.MIN_BPM + " und "
                                + PlanShape.MAX_BPM + " BPM und wird übergangen.");
                    } else if (tick < from || tick >= to) {
                        repairs.add("Tempowechsel außerhalb der neuen Takte übergangen: " + point);
                    } else {
                        byTick.put(tick, new TimeMap.Tempo(tick, bpm, glide));
                    }
                } catch (RuntimeException odd) {
                    repairs.add("Unlesbarer Tempowechsel übergangen: " + element);
                }
            }
            tempos.addAll(byTick.values());
            if (!task.inserts() && !tempos.isEmpty() && tempos.get(tempos.size() - 1).glide()) {
                TimeMap.Tempo last = tempos.remove(tempos.size() - 1);
                tempos.add(new TimeMap.Tempo(last.tick(), last.bpm()));
                repairs.add("Der letzte Tempowechsel hat kein Ziel zum Gleiten; er gilt als fester Wechsel.");
            }
        }
        Set<String> parts = new HashSet<>();
        task.parts().forEach(part -> parts.add(part.id()));
        JsonObject marksOnly = answer.deepCopy();
        marksOnly.remove("tempoChanges");
        Dynamics planned = PlanShape.read(marksOnly, time, parts, PlanShape.MIN_BPM, PlanShape.MAX_BPM, Long.MAX_VALUE,
                repairs::add).dynamics();
        List<Dynamics.Mark> marks = new ArrayList<>();
        for (Dynamics.Mark mark : planned.marks()) {
            if (mark.tick() >= from && mark.tick() < to) {
                marks.add(mark);
            } else {
                repairs.add("Dynamikzeichen außerhalb der neuen Takte übergangen (Tick " + mark.tick() + ").");
            }
        }
        List<Dynamics.Hairpin> hairpins = new ArrayList<>();
        for (Dynamics.Hairpin pin : planned.hairpins()) {
            if (pin.startTick() >= from && pin.startTick() < to) {
                hairpins.add(pin.endTick() <= to ? pin
                        : new Dynamics.Hairpin(pin.startTick(), to, pin.target(), pin.partId()));
            } else {
                repairs.add("Gabel außerhalb der neuen Takte übergangen (Tick " + pin.startTick() + ").");
            }
        }
        return new ContinuationPerformance(tempos, marks, hairpins);
    }

    public ContinuationPerformance {
        tempos = List.copyOf(tempos);
        marks = List.copyOf(marks);
        hairpins = List.copyOf(hairpins);
    }

    public boolean isEmpty() {
        return tempos.isEmpty() && marks.isEmpty() && hairpins.isEmpty();
    }

    /**
     * What the piece holds once the new bars are played this way.
     *
     * @param timeMap  the time map with the new tempo points, {@code null} if it stays
     * @param dynamics the dynamics with the new marks and hairpins, {@code null} if they stay
     */
    public record Applied(TimeMap timeMap, Dynamics dynamics) {
    }

    /**
     * The time map and the dynamics of {@code room} (the piece the new bars are written into)
     * with this performance in the new bars from {@code from} to {@code to}. After the new bars
     * the tempo and the level of every part are set back to what they were in {@code room}. A
     * tempo course is left out when the tempo glides into the new bars, dynamics are left out
     * when a hairpin of the piece runs into them or a part's level there is not a level of the
     * scale; the reason goes to {@code said}.
     */
    public Applied applied(Composition room, long from, long to, List<String> said) {
        TimeMap time = room.timeMap();
        TimeMap changedTime = null;
        if (!tempos.isEmpty()) {
            if (time.glidingAt(from)) {
                said.add("Tempoverlauf der neuen Takte übergangen: das Tempo gleitet in sie hinein.");
            } else {
                changedTime = withTempos(time, to, room.endTick() > to);
            }
        }
        Dynamics changedDynamics = null;
        if (!marks.isEmpty() || !hairpins.isEmpty()) {
            changedDynamics = withDynamics(room, from, to, said);
        }
        return new Applied(changedTime, changedDynamics);
    }

    private TimeMap withTempos(TimeMap time, long to, boolean goesOn) {
        TreeMap<Long, TimeMap.Tempo> byTick = new TreeMap<>();
        for (TimeMap.Tempo tempo : time.tempos()) {
            byTick.put(tempo.tick(), tempo);
        }
        if (goesOn && !byTick.containsKey(to)) {
            byTick.put(to, new TimeMap.Tempo(to, time.tempoAt(to)));
        }
        for (TimeMap.Tempo tempo : tempos) {
            byTick.put(tempo.tick(), tempo);
        }
        List<TimeMap.Tempo> all = new ArrayList<>(byTick.values());
        TimeMap.Tempo last = all.get(all.size() - 1);
        if (last.glide()) {
            all.set(all.size() - 1, new TimeMap.Tempo(last.tick(), last.bpm()));
        }
        return new TimeMap(all, time.meters(), time.keys());
    }

    private Dynamics withDynamics(Composition room, long from, long to, List<String> said) {
        Dynamics dynamics = room.dynamics();
        for (Dynamics.Hairpin pin : dynamics.hairpins()) {
            if (pin.startTick() < to && pin.endTick() > from) {
                said.add("Dynamik der neuen Takte übergangen: eine Gabel des Stücks läuft in sie hinein.");
                return null;
            }
        }
        List<Dynamics.Mark> allMarks = new ArrayList<>(dynamics.marks());
        allMarks.addAll(marks);
        boolean goesOn = room.endTick() > to;
        if (goesOn) {
            List<Dynamics.Mark> restored = restore(room, dynamics, to, said);
            if (restored == null) {
                return null;
            }
            allMarks.addAll(restored);
        }
        List<Dynamics.Hairpin> allPins = new ArrayList<>(dynamics.hairpins());
        for (Dynamics.Hairpin pin : hairpins) {
            if (allPins.stream().anyMatch(other -> overlaps(pin, other))) {
                said.add("Gabel der neuen Takte überschneidet eine andere und wird übergangen (Tick "
                        + pin.startTick() + ").");
            } else {
                allPins.add(pin);
            }
        }
        return new Dynamics(allMarks, allPins, dynamics.scale(), dynamics.articulations());
    }

    /**
     * The marks at the end of the new bars that give every part they touched its level back: one
     * for all parts when they all play at the same level there, one per part otherwise;
     * {@code null} when a part's level there is no level of the scale.
     */
    private List<Dynamics.Mark> restore(Composition room, Dynamics dynamics, long to, List<String> said) {
        boolean all = false;
        Set<String> touched = new HashSet<>();
        for (Dynamics.Mark mark : marks) {
            all |= mark.partId() == null;
            touched.add(mark.partId());
        }
        for (Dynamics.Hairpin pin : hairpins) {
            all |= pin.partId() == null;
            touched.add(pin.partId());
        }
        List<String> parts = all ? partIds(room) : new ArrayList<>(touched);
        List<Dynamics.Mark> restored = new ArrayList<>();
        Dynamics.Level common = null;
        boolean same = true;
        for (String part : parts) {
            Dynamics.Level level = levelOf(dynamics, dynamics.levelVelocity(part, to));
            if (level == null) {
                said.add("Dynamik der neuen Takte übergangen: die Lautstärke von " + part + " danach ist keine Stufe.");
                return null;
            }
            same &= common == null || common == level;
            common = level;
            restored.add(new Dynamics.Mark(to, level, part));
        }
        if (all && same && common != null) {
            return List.of(new Dynamics.Mark(to, common, null));
        }
        return restored;
    }

    /** The level of the scale with exactly this velocity, {@code null} if none. */
    private static Dynamics.Level levelOf(Dynamics dynamics, double velocity) {
        for (Dynamics.Level level : Dynamics.Level.values()) {
            if (dynamics.scale().velocity(level) == velocity) {
                return level;
            }
        }
        return null;
    }

    static boolean overlaps(Dynamics.Hairpin a, Dynamics.Hairpin b) {
        return (a.partId() == null || b.partId() == null || Objects.equals(a.partId(), b.partId()))
                && a.startTick() < b.endTick() && b.startTick() < a.endTick();
    }

    static List<String> partIds(Composition piece) {
        List<String> ids = new ArrayList<>();
        for (Part part : piece.parts()) {
            ids.add(part.id());
        }
        return ids;
    }
}
