package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.studio.ai.ComposedContinuation;
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

    /** The kinds of operation a continuation needs; notes of existing clips only change in the transition. */
    public static Set<EditOp.Kind> kinds(boolean revisesTail) {
        EnumSet<EditOp.Kind> kinds = EnumSet.of(EditOp.Kind.ADD_PART, EditOp.Kind.ADD_TRACK, EditOp.Kind.ADD_CLIP,
                EditOp.Kind.SET_SECTIONS);
        if (revisesTail) {
            kinds.add(EditOp.Kind.ADD_NOTES);
            kinds.add(EditOp.Kind.REMOVE_NOTES);
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
                kinds(transition > 0), false, transition);
    }

    /**
     * The patch: the form with the new sections behind the existing ones, the new parts and
     * their tracks, the notes of the revised transition, then the new clips.
     */
    public static Patch patch(Composition piece, ComposedContinuation continuation) {
        List<EditOp> operations = new ArrayList<>();
        List<Section> form = new ArrayList<>(piece.sections());
        form.addAll(continuation.sections());
        operations.add(new EditOp.SetSections(form));
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
            if (!change.added().isEmpty()) {
                operations.add(new EditOp.AddNotes(change.clipId(), change.added()));
            }
        }
        for (ComposedContinuation.PlacedClip placed : continuation.clips()) {
            operations.add(new EditOp.AddClip(placed.trackId(), placed.clip()));
        }
        var request = continuation.task().request();
        return new Patch("Stück um " + request.bars() + " Takte verlängert (" + request.preset().label() + ", "
                + request.ending().label() + ", " + request.transition().label() + ")", operations);
    }

    /**
     * What is different about the original between two states of a piece: a note that begins
     * before {@code keptUntil} gone, new or changed, a note of a part that does not go on changed,
     * a part, track, clip frame or section of the original changed, tempo, meter, key or the
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
                if (kept == null || !frame(kept).equals(frame(clip))) {
                    differences.add("clip " + clip.id() + " moved, resized or gone");
                }
            }
        }
        List<Section> form = after.sections();
        if (form.size() < before.sections().size()
                || !form.subList(0, before.sections().size()).equals(before.sections())) {
            differences.add("sections of the original changed");
        }
        if (!before.timeMap().equals(after.timeMap()) || !before.meta().equals(after.meta())
                || !before.assets().equals(after.assets())) {
            differences.add("time map, meta or assets changed");
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

    private static List<String> notes(Composition piece, String partId, long untilTick) {
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

    private static String frame(Clip clip) {
        return clip instanceof MidiClip midi ? "midi " + midi.startTick() + " " + midi.lengthTicks() + " " + midi.partId()
                : clip.toString();
    }
}
