package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.studio.ai.ComposedContinuation;
import com.aresstack.audio.studio.ai.ReorchestratedRegion;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * "Re-orchestrate a passage" ({@code REORCHESTRATE}, work package C4) as a change of the edit
 * core, with the three safeguards of the architecture review (E5): the application builds the
 * patch from the notes the AI wrote for the chosen parts and the new instruments; the scope allows
 * only the notes of those parts that begin in the passage, new clips within it, and creating the
 * new parts and tracks; the editor proves that everything else is as it was. {@link #outsideKept}
 * checks the same once more on the notes, as the acceptance plan does for C2.
 */
public final class ReorchestrateRegion {
    private ReorchestrateRegion() {
    }

    /** The kinds of operation a re-orchestration needs. */
    public static final Set<EditOp.Kind> KINDS = Set.copyOf(EnumSet.of(EditOp.Kind.ADD_PART, EditOp.Kind.ADD_TRACK,
            EditOp.Kind.ADD_CLIP, EditOp.Kind.ADD_NOTES, EditOp.Kind.REMOVE_NOTES));

    /** The scope: the chosen and the new parts, the new tracks, the bars of the passage, no transition. */
    public static EditScope scope(ReorchestratedRegion region) {
        Set<String> parts = new HashSet<>();
        region.task().parts().forEach(part -> parts.add(part.id()));
        region.newParts().forEach(part -> parts.add(part.id()));
        Set<String> tracks = new HashSet<>();
        region.newTracks().forEach(track -> tracks.add(track.id()));
        return new EditScope(false, parts, tracks, Set.of(), region.task().fromTick(), region.task().toTick(), KINDS,
                false, 0);
    }

    /** The patch: new parts and tracks, the notes that leave and enter existing clips, the new clips. */
    public static Patch patch(ReorchestratedRegion region) {
        List<EditOp> operations = new ArrayList<>();
        for (Part part : region.newParts()) {
            operations.add(new EditOp.AddPart(part));
        }
        for (Track track : region.newTracks()) {
            operations.add(new EditOp.AddTrack(track));
        }
        for (ReorchestratedRegion.ClipChange change : region.changes()) {
            if (!change.removed().isEmpty()) {
                operations.add(new EditOp.RemoveNotes(change.clipId(), change.removed()));
            }
            if (!change.added().isEmpty()) {
                operations.add(new EditOp.AddNotes(change.clipId(), change.added()));
            }
        }
        for (ComposedContinuation.PlacedClip placed : region.clips()) {
            operations.add(new EditOp.AddClip(placed.trackId(), placed.clip()));
        }
        var request = region.task().request();
        return new Patch("Takte " + request.firstBar() + "-" + request.lastBar() + " umorchestriert ("
                + request.preset().label() + ")", operations);
    }

    /**
     * What is different outside the passage between two states of a piece: a note of a chosen
     * part that begins before {@code fromTick} or at {@code toTick} or later, any note of another
     * part, a part, track or clip frame of the piece, the form, tempo, meter, key, words or assets;
     * an unexpected new part. Empty when only the passage of the chosen parts changed.
     */
    public static List<String> outsideKept(Composition before, Composition after, long fromTick, long toTick,
                                           Set<String> chosen, Set<String> newParts) {
        List<String> differences = new ArrayList<>();
        for (Part part : before.parts()) {
            if (!part.equals(after.part(part.id()))) {
                differences.add("part " + part.id() + " changed");
                continue;
            }
            boolean mayChange = chosen.contains(part.id());
            if (!outside(before, part.id(), mayChange, fromTick, toTick)
                    .equals(outside(after, part.id(), mayChange, fromTick, toTick))) {
                differences.add("notes of part " + part.id() + " outside bars changed");
            }
        }
        for (Track track : before.tracks()) {
            Track now = after.track(track.id());
            if (now == null || !now.name().equals(track.name()) || now.kind() != track.kind()
                    || !Objects.equals(now.defaultPartId(), track.defaultPartId())) {
                differences.add("track " + track.id() + " changed");
                continue;
            }
            for (Clip clip : track.clips()) {
                Clip kept = now.clips().stream().filter(c -> c.id().equals(clip.id())).findFirst().orElse(null);
                if (kept == null || !AppendContinuation.frame(kept).equals(AppendContinuation.frame(clip))) {
                    differences.add("clip " + clip.id() + " moved, resized or gone");
                }
            }
        }
        if (!before.sections().equals(after.sections()) || !before.timeMap().equals(after.timeMap())
                || !before.meta().equals(after.meta()) || !before.assets().equals(after.assets())) {
            differences.add("form, time map, meta or assets changed");
        }
        for (Part part : after.parts()) {
            if (before.part(part.id()) == null && !newParts.contains(part.id())) {
                differences.add("unexpected new part " + part.id());
            }
        }
        return differences;
    }

    private static List<String> outside(Composition piece, String partId, boolean mayChange, long from, long to) {
        List<String> lines = new ArrayList<>();
        for (String line : AppendContinuation.notes(piece, partId, Long.MAX_VALUE)) {
            long tick = Long.parseLong(line.split(" ")[1]);
            if (!mayChange || tick < from || tick >= to) {
                lines.add(line);
            }
        }
        return lines;
    }
}
