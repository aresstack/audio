package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.studio.ai.ComposedContinuation;
import com.aresstack.audio.studio.ai.ContinuationPerformance;
import com.aresstack.audio.studio.ai.TransitionMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * "Continue the piece" ({@code APPEND}) as a change of the edit core, with the three safeguards
 * of the architecture review (E5): the application builds the patch from the notes the AI wrote
 * for the parts that go on and for the new instruments; the scope allows nothing but the new bars
 * (and with {@link TransitionMode#REVISE_TAIL} the revised last bars, as transition zone) of those
 * parts, creating the new parts and tracks, and the new sections of the form; the editor proves
 * that everything else is as it was. {@link #originalKept} checks the same once more on the notes
 * of the original, as the acceptance tests A2 and A3 ask.
 */
public final class AppendContinuation {
    private AppendContinuation() {
    }

    /**
     * The kinds of operation a continuation needs; notes of existing clips only change in the
     * transition, where a clip of the original may also grow so that a new note sounds past its end.
     */
    public static Set<EditOp.Kind> kinds(boolean revisesTail) {
        EnumSet<EditOp.Kind> kinds = EnumSet.of(EditOp.Kind.ADD_PART, EditOp.Kind.ADD_TRACK, EditOp.Kind.ADD_CLIP,
                EditOp.Kind.SET_SECTIONS);
        if (revisesTail) {
            kinds.add(EditOp.Kind.ADD_NOTES);
            kinds.add(EditOp.Kind.REMOVE_NOTES);
            kinds.add(EditOp.Kind.RESIZE_CLIP);
        }
        return Set.copyOf(kinds);
    }

    /**
     * The scope: the parts that go on and the new ones, the new tracks, from the first new bar to
     * the end, with the revised bars of the original as transition zone.
     */
    public static EditScope scope(ComposedContinuation continuation) {
        Set<String> parts = new HashSet<>();
        continuation.task().parts().forEach(part -> parts.add(part.id()));
        continuation.newParts().forEach(part -> parts.add(part.id()));
        Set<String> tracks = new HashSet<>();
        continuation.newTracks().forEach(track -> tracks.add(track.id()));
        long transition = continuation.task().newStart() - continuation.task().tailStart();
        return new EditScope(false, parts, tracks, Set.of(), continuation.task().newStart(), EditScope.END,
                performed(kinds(transition > 0), continuation), false, transition);
    }

    /**
     * The patch: the form with the new sections behind the existing ones, the new parts and
     * their tracks, the notes of the revised transition, then the new clips.
     */
    public static Patch patch(Composition piece, ComposedContinuation continuation) {
        List<EditOp> operations = new ArrayList<>();
        operations.add(new EditOp.SetSections(continuation.form()));
        operations.addAll(body(continuation));
        List<String> said = new ArrayList<>();
        operations.addAll(performance(piece, continuation, said));
        var request = continuation.task().request();
        return new Patch("Stück um " + request.bars() + " Takte verlängert (" + request.preset().label() + ", "
                + request.ending().label() + ", " + request.transition().label() + ")" + told(said), operations);
    }

    /** The kinds of a scope, with tempo and dynamics when the plan says how the new bars are played. */
    static Set<EditOp.Kind> performed(Set<EditOp.Kind> kinds, ComposedContinuation continuation) {
        if (continuation.plan().performance().isEmpty()) {
            return kinds;
        }
        EnumSet<EditOp.Kind> more = EnumSet.copyOf(kinds);
        more.add(EditOp.Kind.SET_TIME_MAP);
        more.add(EditOp.Kind.SET_DYNAMICS);
        return Set.copyOf(more);
    }

    /**
     * The operations that play the new bars as the plan says: the tempo and the dynamics of
     * {@code room} (the piece the new bars go into) with the planned points in the new bars.
     * What cannot be placed goes to {@code said}.
     */
    static List<EditOp> performance(Composition room, ComposedContinuation continuation, List<String> said) {
        ContinuationPerformance planned = continuation.plan().performance();
        if (planned.isEmpty()) {
            return List.of();
        }
        ContinuationPerformance.Applied applied = planned.applied(room, continuation.task().newStart(),
                continuation.task().newEnd(), said);
        List<EditOp> operations = new ArrayList<>();
        if (applied.timeMap() != null) {
            operations.add(new EditOp.SetTimeMap(applied.timeMap()));
        }
        if (applied.dynamics() != null) {
            operations.add(new EditOp.SetDynamics(applied.dynamics()));
        }
        return operations;
    }

    /** What was left out of the planned performance, for the words of a patch. */
    static String told(List<String> said) {
        return said.isEmpty() ? "" : "; " + String.join(" ", said);
    }

    /**
     * Whether two time maps are the same apart from tempo points of {@code after} that are not in
     * {@code before} and lie from {@code from} to {@code to}: the tempo the new bars were given.
     */
    static boolean sameTimeApartFrom(TimeMap before, TimeMap after, long from, long to) {
        if (!before.meters().equals(after.meters()) || !before.keys().equals(after.keys())) {
            return false;
        }
        List<TimeMap.Tempo> kept = new ArrayList<>();
        for (TimeMap.Tempo tempo : after.tempos()) {
            if (before.tempos().contains(tempo) || tempo.tick() < from || tempo.tick() > to) {
                kept.add(tempo);
            }
        }
        return kept.equals(before.tempos());
    }

    /**
     * Whether two dynamics are the same apart from marks and hairpins of {@code after} that are not
     * in {@code before} and lie from {@code from} to {@code to}: the dynamics of the new bars.
     */
    static boolean sameDynamicsApartFrom(Dynamics before, Dynamics after, long from, long to) {
        if (!before.scale().equals(after.scale()) || !before.articulations().equals(after.articulations())) {
            return false;
        }
        List<Dynamics.Mark> marks = new ArrayList<>();
        for (Dynamics.Mark mark : after.marks()) {
            if (before.marks().contains(mark) || mark.tick() < from || mark.tick() > to) {
                marks.add(mark);
            }
        }
        List<Dynamics.Hairpin> pins = new ArrayList<>();
        for (Dynamics.Hairpin pin : after.hairpins()) {
            if (before.hairpins().contains(pin) || pin.startTick() < from || pin.endTick() > to) {
                pins.add(pin);
            }
        }
        return marks.equals(before.marks()) && pins.equals(before.hairpins());
    }

    /** What every patch of new bars holds after the form: new parts and tracks, the transition notes, the new clips. */
    static List<EditOp> body(ComposedContinuation continuation) {
        List<EditOp> operations = new ArrayList<>();
        for (Part part : continuation.newParts()) {
            operations.add(new EditOp.AddPart(part));
        }
        for (Track track : continuation.newTracks()) {
            operations.add(new EditOp.AddTrack(track));
        }
        for (ComposedContinuation.TailChange change : continuation.tail()) {
            if (!change.removed().isEmpty()) {
                operations.add(new EditOp.RemoveNotes(change.clipId(), change.removed()));
            }
            if (change.lengthTicks() > 0) {
                operations.add(new EditOp.ResizeClip(change.clipId(), change.lengthTicks()));
            }
            if (!change.added().isEmpty()) {
                operations.add(new EditOp.AddNotes(change.clipId(), change.added()));
            }
        }
        for (ComposedContinuation.PlacedClip placed : continuation.clips()) {
            operations.add(new EditOp.AddClip(placed.trackId(), placed.clip()));
        }
        return operations;
    }

    /**
     * What is different about the original between two states of a piece: a note that begins
     * before {@code keptUntil} gone, new or changed, a note of a part that does not go on changed,
     * a part, track, clip frame or section of the original changed (a clip of a part that goes on
     * may only change its end, and only from {@code keptUntil} on), tempo, meter, key or the
     * words about the piece changed, an unexpected new part. Empty when only the continuation
     * came in.
     *
     * @param keptUntil the first tick that may change: the first new bar when the original is
     *                  protected, the first revised bar otherwise
     * @param goOn      the ids of the parts that go on
     * @param newParts  the ids of the parts the continuation brings
     */
    public static List<String> originalKept(Composition before, Composition after, long keptUntil, Set<String> goOn,
                                            Set<String> newParts) {
        List<String> differences = new ArrayList<>();
        for (Part part : before.parts()) {
            Part now = after.part(part.id());
            if (!part.equals(now)) {
                differences.add("part " + part.id() + " changed");
                continue;
            }
            long until = goOn.contains(part.id()) ? keptUntil : Long.MAX_VALUE;
            if (!notes(before, part.id(), until).equals(notes(after, part.id(), until))) {
                differences.add("notes of part " + part.id() + " before tick " + until + " changed");
            }
        }
        for (Track track : before.tracks()) {
            Track now = after.track(track.id());
            if (now == null || !now.name().equals(track.name()) || now.kind() != track.kind()
                    || !java.util.Objects.equals(now.defaultPartId(), track.defaultPartId())) {
                differences.add("track " + track.id() + " changed");
                continue;
            }
            for (Clip clip : track.clips()) {
                Clip kept = now.clips().stream().filter(c -> c.id().equals(clip.id())).findFirst().orElse(null);
                if (kept == null || !frame(kept).equals(frame(clip)) && !grown(track, clip, kept, keptUntil, goOn)) {
                    differences.add("clip " + clip.id() + " moved, resized or gone");
                }
            }
        }
        List<Section> form = after.sections();
        if (form.size() < before.sections().size()
                || !form.subList(0, before.sections().size()).equals(before.sections())) {
            differences.add("sections of the original changed");
        }
        long originalEnd = before.timeMap().tickOfBar(before.timeMap().barsCovering(before.endTick()));
        long newFrom = Math.max(keptUntil, originalEnd);
        if (!sameTimeApartFrom(before.timeMap(), after.timeMap(), newFrom, Long.MAX_VALUE)
                || !before.meta().equals(after.meta()) || !before.assets().equals(after.assets())) {
            differences.add("time map, meta or assets changed");
        }
        if (!sameDynamicsApartFrom(before.dynamics(), after.dynamics(), newFrom, Long.MAX_VALUE)) {
            differences.add("dynamics of the original changed");
        }
        for (Part part : after.parts()) {
            if (before.part(part.id()) == null && !newParts.contains(part.id())) {
                differences.add("unexpected new part " + part.id());
            }
        }
        return differences;
    }

    /**
     * A fingerprint of every note that begins before a tick, over all parts: what sounds and how
     * it is written (part, tick, length, pitch, velocity, voice), not in which clip it is kept.
     * Before and after a continuation that protects the original, the fingerprint of the original
     * bars is the same (acceptance plan 7.2, C3).
     */
    public static String hashBefore(Composition piece, long untilTick) {
        List<String> lines = new ArrayList<>();
        for (Part part : piece.parts()) {
            lines.addAll(notes(piece, part.id(), untilTick));
        }
        lines.sort(null);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                sha.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static List<String> notes(Composition piece, String partId, long untilTick) {
        List<String> lines = new ArrayList<>();
        for (PlacedNote n : piece.notesOf(partId)) {
            if (n.tick() < untilTick) {
                lines.add(partId + " " + n.tick() + " " + n.note().length() + " " + n.note().pitch() + " "
                        + n.note().velocity() + " " + n.note().voiceId() + " " + n.note().id());
            }
        }
        lines.sort(null);
        return lines;
    }

    /**
     * Whether a clip of a part that goes on only got another end, and both ends lie at or after
     * {@code keptUntil}: the transition let it grow so that a revised note sounds out.
     */
    private static boolean grown(Track track, Clip clip, Clip kept, long keptUntil, Set<String> goOn) {
        return clip instanceof MidiClip old && kept instanceof MidiClip now && goOn.contains(track.partOf(old))
                && old.startTick() == now.startTick() && java.util.Objects.equals(old.partId(), now.partId())
                && old.endTick() >= keptUntil && now.endTick() >= keptUntil;
    }

    static String frame(Clip clip) {
        return clip instanceof MidiClip midi ? "midi " + midi.startTick() + " " + midi.lengthTicks() + " " + midi.partId()
                : clip.toString();
    }
}
