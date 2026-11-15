package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.AudioClip;
import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixScene;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * What the section bar above the lanes does to the form of a piece, as patches for the edit core:
 * delete bars (a section, or any marked bars) and leave a gap or close it, drag a section into a
 * gap or behind the end, put empty bars in, and give a gap that the AI fills its new section.
 *
 * <p>The sections of a piece follow one another without gap (a rule of the validator). A stretch
 * without music is therefore a section of its own, a <em>gap</em> ({@link #isGap}): it is shown as
 * such, a section can be dragged into it, and the AI can fill it with a transition from what comes
 * before to what comes after. Gaps next to each other become one; a gap at the end of the piece is
 * dropped, the piece simply ends there.
 *
 * <p>Nothing here knows Swing. Every action is one patch, one undoable step.
 */
public final class SectionEdits {
    /** Name and function of a gap; a section with this function is one. */
    public static final String GAP = "Lücke";
    /** Name and function of the section the AI writes into a gap. */
    public static final String TRANSITION = "Übergang";

    private SectionEdits() {
    }

    /**
     * One action on the form: the patch and its scope, or why it cannot be, and how the automation
     * curves of the mix follow the music ({@code null}: they stay; a shift of the patch moves them anyway).
     */
    public record SectionEdit(Patch patch, EditScope scope, String refusal, UnaryOperator<MixScene> mix) {
        public SectionEdit(Patch patch, EditScope scope, String refusal) {
            this(patch, scope, refusal, null);
        }

        public boolean isEmpty() {
            return patch == null || patch.isEmpty();
        }

        static SectionEdit refused(String why) {
            return new SectionEdit(null, null, why);
        }

        static SectionEdit of(String description, List<EditOp> operations) {
            return of(description, operations, null);
        }

        static SectionEdit of(String description, List<EditOp> operations, UnaryOperator<MixScene> mix) {
            return new SectionEdit(new Patch(description, operations), EditScope.everything(), null, mix);
        }
    }

    /** Whether a section is a gap: bars without music, waiting to be filled or dragged into. */
    public static boolean isGap(Section section) {
        return section != null && GAP.equals(section.function());
    }

    /** Whether no note begins and no clip lies in the bars from {@code firstBar} to before {@code endBar} (from 0). */
    public static boolean isSilent(Composition piece, int firstBar, int endBar) {
        long from = piece.timeMap().tickOfBar(firstBar);
        long to = piece.timeMap().tickOfBar(endBar);
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi) {
                    for (Note note : midi.notes()) {
                        long at = midi.startTick() + note.tick();
                        if (at >= from && at < to) {
                            return false;
                        }
                    }
                } else if (clip instanceof AudioClip audio && audio.startTick() < to
                        && audio.endTick(piece.timeMap()) > from) {
                    return false;
                }
            }
        }
        return true;
    }

    // Deleting bars -------------------------------------------------------------------------

    /**
     * Deletes the bars from {@code firstBar} to before {@code endBar} (counted from 0): their notes,
     * the clips in them and the sections they cover. A section the bars cover in part becomes
     * shorter, one they lie within is cut in two. A note that sounds into the bars from before ends
     * where they begin.
     *
     * @param close {@code false} leaves a gap where the bars were (a section {@value #GAP}), {@code true}
     *              moves everything after them forward, so that no gap remains
     */
    public static SectionEdit deleteBars(Composition piece, int firstBar, int endBar, boolean close,
                                         Supplier<String> ids) {
        if (piece.sections().isEmpty()) {
            return SectionEdit.refused("Dieses Stück hat keine Abschnitte.");
        }
        int lastBar = barsOf(piece);
        int end = Math.min(endBar, lastBar);
        if (firstBar < 0 || end <= firstBar) {
            return SectionEdit.refused("Diese Takte gibt es im Stück nicht.");
        }
        TimeMap time = piece.timeMap();
        long from = time.tickOfBar(firstBar);
        long to = time.tickOfBar(end);
        long length = to - from;
        Set<String> used = usedIds(piece);
        List<EditOp> operations = new ArrayList<>();
        // The music in the bars goes: clips are cut at both borders, what lies between is removed.
        List<Clip> moving = new ArrayList<>();
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi) {
                    MidiClip inside = cut(midi, from, to, ids, used, operations);
                    if (inside != null) {
                        operations.add(new EditOp.RemoveClip(inside.id()));
                    }
                    long after = Math.max(to, midi.startTick());
                    if (close && midi.endTick() > after) {
                        moving.add(new MidiClip(clipAt(operations, midi, after), after, midi.endTick() - after,
                                midi.partId(), List.of()));
                    }
                } else if (clip instanceof AudioClip audio) {
                    long audioEnd = audio.endTick(time);
                    if (audio.startTick() >= from && audio.startTick() < to) {
                        operations.add(new EditOp.RemoveClip(audio.id()));
                    } else if (close && audio.startTick() < from && audioEnd > from) {
                        return SectionEdit.refused("Der Audio-Clip " + audio.id()
                                + " klingt in die Takte hinein; die Lücke lässt sich so nicht schließen.");
                    } else if (close && audio.startTick() >= to) {
                        moving.add(audio);
                    }
                }
            }
        }
        // The form: what the bars covered goes, what lies after them follows if the gap closes.
        List<Section> kept = new ArrayList<>();
        for (Section section : music(piece)) {
            for (Section piecePart : without(time, section, from, to, ids, used)) {
                kept.add(close && piecePart.startTick() >= to ? moved(piecePart, piecePart.startTick() - length)
                        : piecePart);
            }
        }
        String bars = end - firstBar == 1 ? "Takt " + (firstBar + 1) : "Takte " + (firstBar + 1) + " bis " + end;
        if (close) {
            for (Clip clip : moving) {
                operations.add(new EditOp.MoveClip(clip.id(), null, clip.startTick() - length));
            }
            TimeMap closed = closedTime(time, from, to);
            if (!closed.equals(time)) {
                operations.add(new EditOp.SetTimeMap(closed));
            }
            Dynamics dynamics = closedDynamics(piece.dynamics(), from, to);
            if (!dynamics.equals(piece.dynamics())) {
                operations.add(new EditOp.SetDynamics(dynamics));
            }
        }
        operations.add(new EditOp.SetSections(withGaps(kept, ids, used)));
        return SectionEdit.of(bars + (close ? " gelöscht" : " gelöscht, Lücke bleibt"), operations,
                close ? mix -> closedMix(mix, from, to) : null);
    }

    /** Deletes a section with its music; see {@link #deleteBars}. */
    public static SectionEdit deleteSection(Composition piece, String sectionId, boolean close, Supplier<String> ids) {
        Section section = find(piece, sectionId);
        if (section == null) {
            return SectionEdit.refused("Diesen Abschnitt gibt es nicht mehr.");
        }
        TimeMap time = piece.timeMap();
        SectionEdit edit = deleteBars(piece, time.barOf(section.startTick()), time.barsCovering(section.endTick()),
                close, ids);
        if (edit.refusal() != null) {
            return edit;
        }
        String what = isGap(section) ? "Lücke" : "Abschnitt „" + section.name() + "“";
        return new SectionEdit(new Patch(what + (close || isGap(section) ? " gelöscht" : " gelöscht, Lücke bleibt"),
                edit.patch().operations()), edit.scope(), null, edit.mix());
    }

    // Moving a section ----------------------------------------------------------------------

    /**
     * Drags a section with its music to another bar: into a gap, or behind the end of the piece.
     * Where it was, a gap remains (at the end the piece becomes shorter). It cannot land on another
     * section; the user leaves a gap first, and the AI can fill what lies between afterwards.
     *
     * @param toBar the bar the section shall begin in, from 0
     */
    public static SectionEdit moveSection(Composition piece, String sectionId, int toBar, Supplier<String> ids) {
        Section section = find(piece, sectionId);
        if (section == null || isGap(section)) {
            return SectionEdit.refused("Nur ein Abschnitt mit Musik lässt sich verschieben.");
        }
        if (toBar < 0) {
            return SectionEdit.refused("Vor dem ersten Takt ist kein Platz.");
        }
        TimeMap time = piece.timeMap();
        long from = section.startTick();
        long to = section.endTick();
        long target = time.tickOfBar(toBar);
        long shift = target - from;
        if (shift == 0) {
            return new SectionEdit(new Patch("", List.of()), null, null);
        }
        long targetEnd = target + section.lengthTicks();
        for (Section other : music(piece)) {
            if (!other.id().equals(sectionId) && other.startTick() < targetEnd && target < other.endTick()) {
                return SectionEdit.refused("Dort liegt schon „" + other.name() + "“. Abschnitte landen in einer Lücke"
                        + " oder hinter dem Ende; erst Platz schaffen, dann verschieben.");
            }
        }
        Set<String> used = usedIds(piece);
        List<EditOp> operations = new ArrayList<>();
        List<String> moving = new ArrayList<>();
        Map<String, List<long[]>> staying = new LinkedHashMap<>();
        for (Track track : piece.tracks()) {
            List<long[]> rest = new ArrayList<>();
            List<long[]> landing = new ArrayList<>();
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi) {
                    MidiClip inside = cut(midi, from, to, ids, used, operations);
                    if (inside != null) {
                        moving.add(inside.id());
                        landing.add(new long[] {inside.startTick() + shift, inside.endTick() + shift});
                    }
                    if (midi.startTick() < from) {
                        rest.add(new long[] {midi.startTick(), Math.min(from, midi.endTick())});
                    }
                    if (midi.endTick() > to) {
                        rest.add(new long[] {Math.max(to, midi.startTick()), midi.endTick()});
                    }
                } else if (clip instanceof AudioClip audio) {
                    long audioEnd = audio.endTick(time);
                    if (audio.startTick() >= from && audio.startTick() < to) {
                        moving.add(audio.id());
                        landing.add(new long[] {audio.startTick() + shift, audioEnd + shift});
                    } else {
                        rest.add(new long[] {audio.startTick(), audioEnd});
                    }
                }
            }
            for (long[] arriving : landing) {
                for (long[] there : rest) {
                    if (there[0] < arriving[1] && arriving[0] < there[1]) {
                        return SectionEdit.refused("In der Lücke klingt auf der Spur „" + track.name()
                                + "“ noch etwas; der Abschnitt passt dort nicht hin.");
                    }
                }
            }
            staying.put(track.id(), rest);
        }
        for (String clipId : moving) {
            operations.add(new EditOp.MoveClip(clipId, null, startOf(piece, operations, clipId, from) + shift));
        }
        List<Section> kept = new ArrayList<>();
        for (Section other : music(piece)) {
            kept.add(other.id().equals(sectionId) ? moved(other, target) : other);
        }
        TimeMap movedTime = movedTime(time, from, to, target);
        if (!movedTime.equals(time)) {
            operations.add(new EditOp.SetTimeMap(movedTime));
        }
        Dynamics dynamics = movedDynamics(piece.dynamics(), from, to, shift);
        if (!dynamics.equals(piece.dynamics())) {
            operations.add(new EditOp.SetDynamics(dynamics));
        }
        operations.add(new EditOp.SetSections(withGaps(kept, ids, used)));
        return SectionEdit.of("Abschnitt „" + section.name() + "“ nach Takt " + (toBar + 1) + " verschoben", operations,
                mix -> movedMix(mix, from, to, target));
    }

    // Empty bars ----------------------------------------------------------------------------

    /**
     * Puts {@code bars} empty bars in before a bar (from 0): everything from there moves later (the
     * shift of {@code INSERT}), and the room is a gap. A section the bar lies within is cut in two
     * around the gap.
     */
    public static SectionEdit insertEmpty(Composition piece, int beforeBar, int bars, Supplier<String> ids) {
        if (piece.sections().isEmpty()) {
            return SectionEdit.refused("Dieses Stück hat keine Abschnitte.");
        }
        if (beforeBar < 0 || bars < 1 || beforeBar >= barsOf(piece)) {
            return SectionEdit.refused("Dort lassen sich keine Takte einfügen.");
        }
        TimeMap time = piece.timeMap();
        long at = time.tickOfBar(beforeBar);
        long room = time.tickOfBar(beforeBar + bars) - at;
        Set<String> used = usedIds(piece);
        List<Section> after = new ArrayList<>();
        for (Section section : music(piece)) {
            if (section.startTick() >= at) {
                after.add(moved(section, section.startTick() + room));
            } else if (at < section.endTick()) {
                List<Section> halves = without(time, section, at, at, ids, used);
                after.add(halves.get(0));
                after.add(moved(halves.get(1), at + room));
            } else {
                after.add(section);
            }
        }
        List<EditOp> operations = new ArrayList<>();
        operations.add(new EditOp.ShiftTime(at, room));
        operations.add(new EditOp.SetSections(withGaps(after, ids, used)));
        return SectionEdit.of((bars == 1 ? "1 leerer Takt" : bars + " leere Takte") + " vor Takt " + (beforeBar + 1)
                + " eingefügt", operations);
    }

    // Filling by the AI ---------------------------------------------------------------------

    /** The parts that play on a MIDI lane of the piece, in the order of the lanes: what the AI writes when it fills bars. */
    public static List<String> playingParts(Composition piece) {
        Set<String> found = new java.util.LinkedHashSet<>();
        for (Track track : piece.tracks()) {
            if (track.kind() != Track.Kind.MIDI) {
                continue;
            }
            if (track.defaultPartId() != null && piece.part(track.defaultPartId()) != null) {
                found.add(track.defaultPartId());
            }
            for (Clip clip : track.clips()) {
                String part = clip instanceof MidiClip midi ? track.partOf(midi) : null;
                if (part != null && piece.part(part) != null) {
                    found.add(part);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * The form after the AI wrote the bars from {@code firstBar} to before {@code endBar} (from 0):
     * every gap among them becomes a section {@value #TRANSITION}; the other sections stay. Its
     * intensity lies between those of its neighbours. {@code null} if there is no gap to name.
     */
    public static List<Section> filledForm(Composition piece, int firstBar, int endBar, Supplier<String> ids) {
        TimeMap time = piece.timeMap();
        long from = time.tickOfBar(firstBar);
        long to = time.tickOfBar(endBar);
        List<Section> sections = piece.sections();
        List<Section> form = new ArrayList<>();
        boolean named = false;
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            if (isGap(section) && section.startTick() < to && from < section.endTick()) {
                Double before = i > 0 ? sections.get(i - 1).intensity() : null;
                Double after = i + 1 < sections.size() ? sections.get(i + 1).intensity() : null;
                Double intensity = before == null ? after : after == null ? before : (before + after) / 2;
                form.add(new Section(section.id(), TRANSITION, TRANSITION, section.startTick(), section.lengthTicks(),
                        intensity, List.of(), null));
                named = true;
            } else {
                form.add(section);
            }
        }
        return named ? form : null;
    }

    /**
     * What the AI is told when it writes marked bars anew: a transition that leads from what comes
     * before them to what comes after, so that both ends fit.
     */
    public static String transitionInstruction(Composition piece, int firstBar, int endBar) {
        TimeMap time = piece.timeMap();
        long from = time.tickOfBar(firstBar);
        long to = time.tickOfBar(endBar);
        Section before = null;
        Section after = null;
        for (Section section : music(piece)) {
            if (section.endTick() <= from) {
                before = section;
            } else if (section.startTick() >= to && after == null) {
                after = section;
            }
        }
        boolean silent = isSilent(piece, firstBar, endBar);
        StringBuilder text = new StringBuilder(silent ? "These bars are empty: fill them. " : "Write these bars anew. ");
        if (before != null && after != null) {
            text.append("Build a transition that continues naturally from the end of \"").append(before.name())
                    .append("\" and leads into the beginning of \"").append(after.name())
                    .append("\", so that it arrives exactly at the first bar after the passage.");
        } else if (before != null) {
            text.append("Continue naturally from the end of \"").append(before.name()).append("\".");
        } else if (after != null) {
            text.append("Lead into the beginning of \"").append(after.name()).append("\".");
        }
        text.append(" Both ends must join smoothly with the music around the passage.");
        return text.toString();
    }

    // ---------------------------------------------------------------------------------------

    /** The sections that hold music, in order: the gaps are left out, they follow from the others. */
    private static List<Section> music(Composition piece) {
        List<Section> found = new ArrayList<>();
        for (Section section : piece.sections()) {
            if (!isGap(section)) {
                found.add(section);
            }
        }
        return found;
    }

    /** The sections with a gap wherever none follows the one before, from tick 0; nothing after the last. */
    static List<Section> withGaps(List<Section> sections, Supplier<String> ids, Set<String> used) {
        List<Section> sorted = new ArrayList<>(sections);
        sorted.sort(Comparator.comparingLong(Section::startTick));
        List<Section> form = new ArrayList<>();
        long expected = 0;
        for (Section section : sorted) {
            if (section.startTick() > expected) {
                form.add(new Section(fresh(ids, used), GAP, GAP, expected, section.startTick() - expected, null,
                        List.of(), null));
            }
            form.add(section);
            expected = section.endTick();
        }
        return form;
    }

    /** What remains of a section outside the ticks {@code from} to {@code to}: nothing, one piece or two. */
    private static List<Section> without(TimeMap time, Section section, long from, long to, Supplier<String> ids,
                                         Set<String> used) {
        List<Section> rest = new ArrayList<>();
        int firstBar = time.barOf(section.startTick());
        if (section.startTick() < from) {
            long end = Math.min(from, section.endTick());
            rest.add(new Section(section.id(), section.name(), section.function(), section.startTick(),
                    end - section.startTick(), section.intensity(),
                    chords(section, 0, time.barsCovering(end) - firstBar), section.instruction()));
        }
        if (section.endTick() > to) {
            long start = Math.max(to, section.startTick());
            String id = rest.isEmpty() ? section.id() : fresh(ids, used);
            rest.add(new Section(id, section.name(), section.function(), start, section.endTick() - start,
                    section.intensity(), chords(section, time.barOf(start) - firstBar, Integer.MAX_VALUE),
                    section.instruction()));
        }
        return rest;
    }

    private static List<String> chords(Section section, int fromBar, int toBar) {
        List<String> all = section.harmony();
        int start = Math.min(all.size(), Math.max(0, fromBar));
        int end = Math.min(all.size(), Math.max(start, toBar));
        return List.copyOf(all.subList(start, end));
    }

    private static Section moved(Section section, long startTick) {
        return new Section(section.id(), section.name(), section.function(), startTick, section.lengthTicks(),
                section.intensity(), section.harmony(), section.instruction());
    }

    /**
     * Cuts a MIDI clip at {@code from} and {@code to} where it runs across them, adding the cuts to
     * {@code operations}; returns the piece of it between them (with its id as it will be), or
     * {@code null} if the clip does not reach in.
     */
    private static MidiClip cut(MidiClip clip, long from, long to, Supplier<String> ids, Set<String> used,
                                List<EditOp> operations) {
        if (clip.endTick() <= from || clip.startTick() >= to) {
            return null;
        }
        String inside = clip.id();
        long start = clip.startTick();
        if (start < from) {
            inside = fresh(ids, used);
            operations.add(new EditOp.SplitClip(clip.id(), from, inside));
            start = from;
        }
        long end = clip.endTick();
        if (end > to) {
            String rest = fresh(ids, used);
            operations.add(new EditOp.SplitClip(inside, to, rest));
            end = to;
        }
        return new MidiClip(inside, start, end - start, clip.partId(), List.of());
    }

    /** The id the part of a clip from {@code tick} on has after the cuts in {@code operations}. */
    private static String clipAt(List<EditOp> operations, MidiClip clip, long tick) {
        String id = clip.id();
        for (EditOp op : operations) {
            if (op instanceof EditOp.SplitClip split && split.clipId().equals(id) && split.atTick() <= tick) {
                id = split.newClipId();
            }
        }
        return id;
    }

    /** Where a clip begins after the cuts: a piece cut off at {@code from} begins there. */
    private static long startOf(Composition piece, List<EditOp> operations, String clipId, long from) {
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip.id().equals(clipId)) {
                    return clip.startTick();
                }
            }
        }
        for (EditOp op : operations) {
            if (op instanceof EditOp.SplitClip split && split.newClipId().equals(clipId)) {
                return split.atTick();
            }
        }
        return from;
    }

    /** Tempo, meter and key with the ticks from {@code from} to {@code to} taken out; what held at {@code to} holds from {@code from}. */
    static TimeMap closedTime(TimeMap time, long from, long to) {
        long length = to - from;
        List<TimeMap.Tempo> tempos = new ArrayList<>();
        TimeMap.Tempo lastInside = null;
        for (TimeMap.Tempo t : time.tempos()) {
            if (t.tick() < from || t.tick() == 0) {
                tempos.add(t);
            } else if (t.tick() < to) {
                lastInside = t;
            } else {
                tempos.add(new TimeMap.Tempo(t.tick() - length, t.bpm(), t.glide()));
            }
        }
        if (lastInside != null && tempos.stream().noneMatch(t -> t.tick() == from)) {
            tempos.add(new TimeMap.Tempo(from, lastInside.bpm(), lastInside.glide()));
        }
        tempos.sort(Comparator.comparingLong(TimeMap.Tempo::tick));
        List<TimeMap.Meter> meters = new ArrayList<>();
        TimeMap.Meter lastMeter = null;
        for (TimeMap.Meter m : time.meters()) {
            if (m.tick() < from || m.tick() == 0) {
                meters.add(m);
            } else if (m.tick() < to) {
                lastMeter = m;
            } else {
                meters.add(new TimeMap.Meter(m.tick() - length, m.numerator(), m.denominator()));
            }
        }
        if (lastMeter != null && meters.stream().noneMatch(m -> m.tick() == from)) {
            meters.add(new TimeMap.Meter(from, lastMeter.numerator(), lastMeter.denominator()));
        }
        meters.sort(Comparator.comparingLong(TimeMap.Meter::tick));
        List<TimeMap.KeyChange> keys = new ArrayList<>();
        TimeMap.KeyChange lastKey = null;
        for (TimeMap.KeyChange k : time.keys()) {
            if (k.tick() < from || k.tick() == 0) {
                keys.add(k);
            } else if (k.tick() < to) {
                lastKey = k;
            } else {
                keys.add(new TimeMap.KeyChange(k.tick() - length, k.key()));
            }
        }
        if (lastKey != null && keys.stream().noneMatch(k -> k.tick() == from)) {
            keys.add(new TimeMap.KeyChange(from, lastKey.key()));
        }
        keys.sort(Comparator.comparingLong(TimeMap.KeyChange::tick));
        return new TimeMap(tempos, meters, keys);
    }

    /** The dynamic marks with the ticks from {@code from} to {@code to} taken out; hairpins that reach in are dropped. */
    static Dynamics closedDynamics(Dynamics dynamics, long from, long to) {
        if (dynamics.isEmpty()) {
            return dynamics;
        }
        long length = to - from;
        List<Dynamics.Mark> marks = new ArrayList<>();
        Map<String, Dynamics.Mark> lastInside = new LinkedHashMap<>();
        for (Dynamics.Mark mark : dynamics.marks()) {
            if (mark.tick() < from) {
                marks.add(mark);
            } else if (mark.tick() < to) {
                lastInside.put(String.valueOf(mark.partId()), mark);
            } else {
                marks.add(new Dynamics.Mark(mark.tick() - length, mark.level(), mark.partId()));
            }
        }
        for (Dynamics.Mark mark : lastInside.values()) {
            boolean set = marks.stream().anyMatch(m -> m.tick() == from && java.util.Objects.equals(m.partId(), mark.partId()));
            if (!set) {
                marks.add(new Dynamics.Mark(from, mark.level(), mark.partId()));
            }
        }
        marks.sort(Comparator.comparingLong(Dynamics.Mark::tick));
        List<Dynamics.Hairpin> pins = new ArrayList<>();
        for (Dynamics.Hairpin pin : dynamics.hairpins()) {
            if (pin.endTick() <= from) {
                pins.add(pin);
            } else if (pin.startTick() >= to) {
                pins.add(new Dynamics.Hairpin(pin.startTick() - length, pin.endTick() - length, pin.target(), pin.partId()));
            }
        }
        return new Dynamics(marks, pins, dynamics.scale(), dynamics.articulations());
    }

    /**
     * The marks and hairpins of the section from {@code from} to {@code to} moved by {@code shift}:
     * each part begins there at the level it had, and after it the level that held there goes on.
     * Hairpins wholly within the section move with it; others within the bars it lands on go.
     */
    static Dynamics movedDynamics(Dynamics dynamics, long from, long to, long shift) {
        if (dynamics.isEmpty()) {
            return dynamics;
        }
        long target = from + shift;
        long targetEnd = to + shift;
        Map<String, List<Dynamics.Mark>> byPart = new LinkedHashMap<>();
        for (Dynamics.Mark mark : dynamics.marks()) {
            byPart.computeIfAbsent(String.valueOf(mark.partId()), k -> new ArrayList<>()).add(mark);
        }
        List<Dynamics.Mark> marks = new ArrayList<>();
        for (List<Dynamics.Mark> ofPart : byPart.values()) {
            marks.addAll(moved(ofPart, Dynamics.Mark::tick, (m, tick) -> new Dynamics.Mark(tick, m.level(), m.partId()),
                    from, to, target));
        }
        marks.sort(Comparator.comparingLong(Dynamics.Mark::tick));
        List<Dynamics.Hairpin> pins = new ArrayList<>();
        for (Dynamics.Hairpin pin : dynamics.hairpins()) {
            boolean inside = pin.startTick() >= from && pin.endTick() <= to;
            if (inside) {
                pins.add(new Dynamics.Hairpin(pin.startTick() + shift, pin.endTick() + shift, pin.target(), pin.partId()));
            } else if (pin.endTick() <= target || pin.startTick() >= targetEnd) {
                pins.add(pin);
            }
        }
        pins.sort(Comparator.comparingLong(Dynamics.Hairpin::startTick));
        return new Dynamics(marks, pins, dynamics.scale(), dynamics.articulations());
    }

    /**
     * Tempo and key with the section from {@code from} to {@code to}: the changes within it move to
     * {@code target}, it begins there with the tempo and key it had, and after it what held there
     * before goes on. The meter stays where it is, so that bars keep their place.
     */
    static TimeMap movedTime(TimeMap time, long from, long to, long target) {
        List<TimeMap.Tempo> tempos = moved(time.tempos(), TimeMap.Tempo::tick,
                (t, tick) -> new TimeMap.Tempo(tick, t.bpm(), t.glide()), from, to, target);
        List<TimeMap.KeyChange> keys = moved(time.keys(), TimeMap.KeyChange::tick,
                (k, tick) -> new TimeMap.KeyChange(tick, k.key()), from, to, target);
        return new TimeMap(tempos, time.meters(), keys);
    }

    /** Events in time (each holds until the next) with those from {@code from} to {@code to} moved to {@code target}. */
    private static <T> List<T> moved(List<T> events, java.util.function.ToLongFunction<T> tickOf,
                                     java.util.function.BiFunction<T, Long, T> at, long from, long to, long target) {
        long length = to - from;
        long targetEnd = target + length;
        T entering = holding(events, tickOf, from);
        List<T> rest = new ArrayList<>();
        List<T> moving = new ArrayList<>();
        for (T event : events) {
            long tick = tickOf.applyAsLong(event);
            if (tick >= from && tick < to) {
                moving.add(event);
                if (tick == 0) {
                    rest.add(event); // the start of the piece keeps its tempo and key
                }
            } else {
                rest.add(event);
            }
        }
        T resuming = holding(rest, tickOf, targetEnd);
        List<T> result = new ArrayList<>();
        for (T event : rest) {
            long tick = tickOf.applyAsLong(event);
            if (tick < target || tick >= targetEnd) {
                result.add(event);
            }
        }
        for (T event : moving) {
            result.add(at.apply(event, tickOf.applyAsLong(event) - from + target));
        }
        if (entering != null && result.stream().noneMatch(e -> tickOf.applyAsLong(e) == target)) {
            result.add(at.apply(entering, target));
        }
        if (resuming != null && result.stream().noneMatch(e -> tickOf.applyAsLong(e) == targetEnd)) {
            T now = holding(result, tickOf, targetEnd);
            if (now == null || !at.apply(now, 0L).equals(at.apply(resuming, 0L))) {
                result.add(at.apply(resuming, targetEnd)); // after the section, what held there goes on
            }
        }
        if (result.stream().noneMatch(e -> tickOf.applyAsLong(e) == 0) && entering != null) {
            result.add(at.apply(entering, 0L));
        }
        result.sort(Comparator.comparingLong(tickOf));
        return result;
    }

    /** The event that holds at a tick: the last at or before it, or {@code null}. */
    private static <T> T holding(List<T> events, java.util.function.ToLongFunction<T> tickOf, long tick) {
        T found = null;
        long best = -1;
        for (T event : events) {
            long at = tickOf.applyAsLong(event);
            if (at <= tick && at >= best) {
                found = event;
                best = at;
            }
        }
        return found;
    }

    /**
     * The automation curves of the mix with the ticks from {@code from} to {@code to} taken out:
     * what follows moves forward, and each curve goes on from {@code from} with the value it had at
     * {@code to}.
     */
    public static MixScene closedMix(MixScene mix, long from, long to) {
        if (mix == null) {
            return null;
        }
        long length = to - from;
        List<AutomationLane> lanes = new ArrayList<>();
        for (AutomationLane lane : mix.automation()) {
            List<AutomationLane.Point> points = new ArrayList<>();
            boolean reaches = false;
            boolean atTo = false;
            for (AutomationLane.Point point : lane.points()) {
                if (point.tick() < from) {
                    points.add(point);
                } else {
                    if (!reaches) {
                        points.add(new AutomationLane.Point(from, lane.valueAt(from))); // the bars before end as they were
                    }
                    reaches = true;
                    if (point.tick() >= to) {
                        atTo |= point.tick() == to;
                        points.add(new AutomationLane.Point(point.tick() - length, point.value()));
                    }
                }
            }
            if (reaches && !atTo) {
                points.add(new AutomationLane.Point(from, lane.valueAt(to)));
            }
            points.sort(Comparator.comparingLong(AutomationLane.Point::tick));
            lanes.add(new AutomationLane(lane.id(), lane.channelId(), lane.parameter(), points));
        }
        return new MixScene(mix.room(), mix.masterDb(), mix.levelMode(), mix.channels(), lanes);
    }

    /**
     * The automation curves of the mix with the section from {@code from} to {@code to} moved to
     * {@code target}: within it each curve runs as it ran, and after it the curve goes on as it
     * went there before.
     */
    public static MixScene movedMix(MixScene mix, long from, long to, long target) {
        if (mix == null) {
            return null;
        }
        long length = to - from;
        long targetEnd = target + length;
        List<AutomationLane> lanes = new ArrayList<>();
        for (AutomationLane lane : mix.automation()) {
            if (lane.points().isEmpty()) {
                lanes.add(lane);
                continue;
            }
            List<AutomationLane.Point> rest = new ArrayList<>();
            List<AutomationLane.Point> moving = new ArrayList<>();
            moving.add(new AutomationLane.Point(target, lane.valueAt(from)));
            for (AutomationLane.Point point : lane.points()) {
                if (point.tick() > from && point.tick() < to) {
                    moving.add(new AutomationLane.Point(point.tick() - from + target, point.value()));
                } else {
                    rest.add(point);
                }
            }
            moving.add(new AutomationLane.Point(targetEnd - 1, lane.valueAt(to - 1)));
            AutomationLane remaining = rest.isEmpty() ? null
                    : new AutomationLane(lane.id(), lane.channelId(), lane.parameter(), rest);
            List<AutomationLane.Point> points = new ArrayList<>();
            for (AutomationLane.Point point : rest) {
                if (point.tick() < target || point.tick() > targetEnd) {
                    points.add(point);
                }
            }
            points.addAll(moving);
            if (remaining != null) {
                points.add(new AutomationLane.Point(targetEnd, remaining.valueAt(targetEnd)));
            }
            points.sort(Comparator.comparingLong(AutomationLane.Point::tick));
            lanes.add(new AutomationLane(lane.id(), lane.channelId(), lane.parameter(), points));
        }
        return new MixScene(mix.room(), mix.masterDb(), mix.levelMode(), mix.channels(), lanes);
    }

    private static Section find(Composition piece, String sectionId) {
        for (Section section : piece.sections()) {
            if (section.id().equals(sectionId)) {
                return section;
            }
        }
        return null;
    }

    /** The bars of the piece up to the end of its last section or clip. */
    private static int barsOf(Composition piece) {
        return piece.timeMap().barsCovering(piece.endTick());
    }

    private static Set<String> usedIds(Composition piece) {
        Set<String> used = new HashSet<>();
        for (Section section : piece.sections()) {
            used.add(section.id());
        }
        for (Track track : piece.tracks()) {
            used.add(track.id());
            for (Clip clip : track.clips()) {
                used.add(clip.id());
            }
        }
        return used;
    }

    private static String fresh(Supplier<String> ids, Set<String> used) {
        String id = ids.get();
        while (used.contains(id)) {
            id = ids.get();
        }
        used.add(id);
        return id;
    }
}
