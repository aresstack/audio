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
import com.aresstack.audio.studio.ai.ContinuationTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * "Insert new bars" ({@code INSERT}, work package C4) as a change of the edit core. It is
 * {@code APPEND} with a room made first (architecture review E4, E5; condition A of the product
 * owner): the patch begins with {@link EditOp.ShiftTime}, a deterministic operation of the
 * application, never of the AI, that moves everything from the insertion point back by the new
 * bars; then come the new form, parts, tracks and clips exactly as for a continuation. The scope
 * allows that shift and nothing but the empty room (and, with a revised transition, the bars
 * before it) of the parts that play; the editor compares "original plus approved shift" with
 * "after", so an insertion passes and any further change outside the room does not.
 *
 * <p>What the shift does to the original, by design of {@code TimeShift}: clips and sections
 * after the point start later, a clip across the point is cut there into two, a section across
 * it is cut into two by the new form, tempo, meter and key events and automation points after the
 * point move along. A note that sounds across the point ends at it there; the insertion then
 * gives it its length back (at most to the end of the room) and lets its clip grow with it
 * ({@code ResizeClip}), so that it sounds on as in the original. For that the scope's transition
 * zone reaches back to the start of the earliest such note; {@link #originalKept} checks that these
 * notes are exactly as in the original.
 */
public final class InsertPassage {
    private InsertPassage() {
    }

    /** The kinds of operation an insertion needs: those of a continuation, and the shift. */
    public static Set<EditOp.Kind> kinds(boolean revisesTail) {
        EnumSet<EditOp.Kind> kinds = EnumSet.copyOf(AppendContinuation.kinds(revisesTail));
        kinds.add(EditOp.Kind.SHIFT_TIME);
        return Set.copyOf(kinds);
    }

    /**
     * The scope: the parts that play and the new ones, the new tracks, the room of the new bars
     * (ticks after the shift), the revised bars before it as transition zone, the shift allowed.
     */
    public static EditScope scope(ComposedContinuation insertion) {
        ContinuationTask task = requireInsertion(insertion);
        Set<String> parts = new HashSet<>();
        task.parts().forEach(part -> parts.add(part.id()));
        insertion.newParts().forEach(part -> parts.add(part.id()));
        insertion.holds().forEach(hold -> parts.add(hold.partId()));
        Set<String> tracks = new HashSet<>();
        insertion.newTracks().forEach(track -> tracks.add(track.id()));
        long transition = task.newStart() - insertion.editFrom();
        return new EditScope(false, parts, tracks, Set.of(), task.newStart(), task.newEnd(), kinds(transition > 0), true,
                transition);
    }

    /** The patch: the shift, then the whole new form, then what a continuation brings. */
    public static Patch patch(ComposedContinuation insertion) {
        ContinuationTask task = requireInsertion(insertion);
        List<EditOp> operations = new ArrayList<>();
        operations.add(new EditOp.ShiftTime(task.newStart(), task.shiftTicks()));
        operations.add(new EditOp.SetSections(insertion.form()));
        operations.addAll(AppendContinuation.body(insertion));
        var request = task.request();
        return new Patch(request.bars() + " Takte vor Takt " + request.insertBeforeBar() + " eingefügt ("
                + request.preset().label() + ", " + request.transition().label() + ")", operations);
    }

    /**
     * What is different about the original between the piece before and after an insertion: a
     * note that begins before {@code keptUntil} (any note before the insertion point for a part
     * that does not play) not as in the original, where a note that sounded across the point may
     * only end at the end of the room if it was longer; a note after the room not as in the
     * original with the approved shift; a note of a part that does not play in the room; a part or
     * track changed; a clip frame changed beyond the shift, except a clip that ended at the point
     * and grew into the room for a held note; a section of the original that is neither kept nor
     * only cut at the room; tempo, meter, key, words or assets changed beyond the shift; an
     * unexpected new part. Empty when only the inserted bars came in.
     *
     * @param keptUntil the first tick before the room whose notes may change: the start of the
     *                  room, or of the revised bars
     */
    public static List<String> originalKept(Composition before, Composition after, ContinuationTask task,
                                            long keptUntil, Set<String> playing, Set<String> newParts) {
        Composition reference = task.room(before);
        long point = task.newStart();
        long end = task.newEnd();
        List<String> differences = new ArrayList<>();
        for (Part part : reference.parts()) {
            if (!part.equals(after.part(part.id()))) {
                differences.add("part " + part.id() + " changed");
                continue;
            }
            boolean plays = playing.contains(part.id());
            long until = plays ? keptUntil : point;
            if (!lines(before, part.id(), 0, until, end).equals(lines(after, part.id(), 0, until, end))) {
                differences.add("notes of part " + part.id() + " before the inserted bars changed");
            }
            if (!lines(reference, part.id(), end, Long.MAX_VALUE, Long.MAX_VALUE)
                    .equals(lines(after, part.id(), end, Long.MAX_VALUE, Long.MAX_VALUE))) {
                differences.add("notes of part " + part.id() + " after the inserted bars changed");
            }
            if (!plays && !lines(after, part.id(), point, end, Long.MAX_VALUE).isEmpty()) {
                differences.add("part " + part.id() + " plays in the inserted bars without being asked");
            }
        }
        for (Track track : reference.tracks()) {
            Track now = after.track(track.id());
            if (now == null || !now.name().equals(track.name()) || now.kind() != track.kind()
                    || !Objects.equals(now.defaultPartId(), track.defaultPartId())) {
                differences.add("track " + track.id() + " changed");
                continue;
            }
            for (Clip clip : track.clips()) {
                Clip kept = now.clips().stream().filter(c -> c.id().equals(clip.id())).findFirst().orElse(null);
                if (kept == null || !AppendContinuation.frame(kept).equals(AppendContinuation.frame(clip))
                        && !grown(clip, kept, point, end)) {
                    differences.add("clip " + clip.id() + " moved, resized or gone beyond the shift");
                }
            }
        }
        for (Section section : reference.sections()) {
            boolean stretched = section.startTick() < point && section.endTick() > point;
            boolean kept = stretched
                    ? after.sections().stream().anyMatch(s -> s.id().equals(section.id())
                    && s.startTick() == section.startTick() && s.endTick() == point
                    && s.name().equals(section.name()))
                    : after.sections().contains(section);
            if (!kept) {
                differences.add("section " + section.id() + " of the original changed");
            }
        }
        if (!reference.timeMap().equals(after.timeMap()) || !reference.meta().equals(after.meta())
                || !reference.assets().equals(after.assets())) {
            differences.add("time map, meta or assets changed beyond the shift");
        }
        for (Part part : after.parts()) {
            if (before.part(part.id()) == null && !newParts.contains(part.id())) {
                differences.add("unexpected new part " + part.id());
            }
        }
        return differences;
    }

    /** Whether a clip that ended at the insertion point only grew into the room. */
    private static boolean grown(Clip clip, Clip kept, long point, long end) {
        return clip instanceof MidiClip old && kept instanceof MidiClip now && old.endTick() == point
                && old.startTick() == now.startTick() && Objects.equals(old.partId(), now.partId())
                && now.endTick() > point && now.endTick() <= end;
    }

    /**
     * The notes of a part that begin from {@code from} to before {@code to}, as lines (part, tick,
     * length, pitch, velocity, voice, id), each ending at {@code endLimit} at the latest.
     */
    private static List<String> lines(Composition piece, String partId, long from, long to, long endLimit) {
        List<String> lines = new ArrayList<>();
        for (PlacedNote n : piece.notesOf(partId)) {
            if (n.tick() >= from && n.tick() < to) {
                long length = Math.min(n.note().length(), endLimit - n.tick());
                lines.add(partId + " " + n.tick() + " " + length + " " + n.note().pitch() + " " + n.note().velocity()
                        + " " + n.note().voiceId() + " " + n.note().id());
            }
        }
        lines.sort(null);
        return lines;
    }

    /**
     * A fingerprint of every note that begins at {@code fromTick} or later, over all parts, its
     * tick counted from {@code fromTick}: what sounds and how it is written. The music after an
     * insertion has the same fingerprint from the end of the room as the original from the
     * insertion point.
     */
    public static String hashFrom(Composition piece, long fromTick) {
        List<String> lines = new ArrayList<>();
        for (Part part : piece.parts()) {
            for (PlacedNote n : piece.notesOf(part.id())) {
                if (n.tick() >= fromTick) {
                    lines.add(part.id() + " " + (n.tick() - fromTick) + " " + n.note().length() + " "
                            + n.note().pitch() + " " + n.note().velocity() + " " + n.note().voiceId() + " "
                            + n.note().id());
                }
            }
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

    private static ContinuationTask requireInsertion(ComposedContinuation composed) {
        if (!composed.task().inserts()) {
            throw new IllegalArgumentException("Keine Einfügung, sondern eine Verlängerung.");
        }
        return composed.task();
    }
}
