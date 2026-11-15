package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.ai.RegionRequest;
import com.aresstack.audio.studio.ai.RewrittenRegion;
import com.aresstack.audio.studio.ai.RewrittenRegion.NewNote;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * "Write this passage anew" ({@code REPLACE_REGION}, {@code VARIATE_REGION}; work package C2) as
 * a change of the edit core. The application builds the patch from the notes the AI wrote for
 * exactly the selected parts (architecture review E5, first safeguard): the selected notes go,
 * the new ones come into the clips that hold the region, or into a new clip on the part's own
 * track where it had none. The scope is the selection and allows nothing else (second safeguard),
 * and the editor proves that everything outside it is as it was (third safeguard).
 * {@link #outsideChanges} checks the same once more on the notes of every part, as acceptance A5
 * asks.
 *
 * <p>Rule of the border (E5): a note that begins before the region and sounds into it stays; a
 * note that begins in the region belongs to it and goes, however long it sounds. New notes end
 * within the region and within their clip; what would sound longer is cut and recorded.
 */
public final class RegionEdit {
    /** The only kinds of operation a region change needs. */
    public static final Set<EditOp.Kind> KINDS = Set.copyOf(EnumSet.of(EditOp.Kind.REMOVE_NOTES,
            EditOp.Kind.ADD_NOTES, EditOp.Kind.ADD_CLIP));

    private RegionEdit() {
    }

    /** The scope: the selected parts and clips within the selected bars, notes and new clips only. */
    public static EditScope scope(Selection selection) {
        return selection.scope(KINDS);
    }

    /** What the patch is called in the history and the revisions. */
    public static String description(Composition piece, RegionRequest request) {
        Selection selection = request.selection();
        List<String> names = new ArrayList<>();
        for (String id : selection.parts(piece)) {
            names.add(piece.part(id).name());
        }
        int first = piece.timeMap().barOf(selection.fromTick()) + 1;
        int last = piece.timeMap().barsCovering(selection.toTick());
        return (request.mode() == RegionRequest.Mode.VARIATE ? "Variation: " : "Neu geschrieben: ")
                + String.join(", ", names) + ", Takt " + first + (last > first ? "-" + last : "");
    }

    /**
     * The patch that puts the new passage in place of the selected notes.
     *
     * @param ids source of ids for new notes and clips; ids used in the piece are skipped
     * @param log receives one line for every new note that had to be cut or left out
     */
    public static Patch patch(Composition base, RewrittenRegion region, Supplier<String> ids, List<String> log) {
        Selection selection = region.request().selection();
        Set<String> used = usedIds(base);
        List<EditOp> removals = new ArrayList<>();
        Map<String, List<Note>> additions = new LinkedHashMap<>();
        List<EditOp> newClips = new ArrayList<>();
        for (Map.Entry<String, List<NewNote>> passage : region.passages().entrySet()) {
            String partId = passage.getKey();
            Part part = base.part(partId);
            if (part == null) {
                throw new IllegalArgumentException("Unbekannter Part: " + partId);
            }
            Map<String, List<String>> gone = new LinkedHashMap<>();
            Map<String, Integer> voices = new TreeMap<>();
            for (PlacedNote n : selection.notes(base, partId)) {
                gone.computeIfAbsent(n.clip().id(), k -> new ArrayList<>()).add(n.note().id());
                voices.merge(n.note().voiceId(), 1, Integer::sum);
            }
            gone.forEach((clip, notes) -> removals.add(new EditOp.RemoveNotes(clip, notes)));
            String voice = voices.entrySet().stream().filter(e -> part.voice(e.getKey()) != null)
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(part.firstVoice().id());

            List<Located> holders = holders(base, selection, partId);
            Track home = selection.selectsPart(partId) ? home(base, partId) : null;
            List<long[]> free = home == null ? List.of() : free(home, selection.fromTick(), selection.toTick());
            Map<Integer, List<NewNote>> intoFree = new TreeMap<>();
            for (NewNote n : passage.getValue()) {
                Located holder = holders.stream().filter(h -> h.clip.startTick() <= n.tick() && n.tick() < h.clip.endTick())
                        .findFirst().orElse(null);
                if (holder != null) {
                    long length = cut(log, partId, n, holder.clip.endTick(), "Clip-Ende");
                    additions.computeIfAbsent(holder.clip.id(), k -> new ArrayList<>()).add(new Note(fresh(ids, used),
                            n.tick() - holder.clip.startTick(), length, n.pitch(), n.velocity(), voice, n.articulation()));
                    continue;
                }
                int stretch = -1;
                for (int i = 0; i < free.size(); i++) {
                    if (free.get(i)[0] <= n.tick() && n.tick() < free.get(i)[1]) {
                        stretch = i;
                    }
                }
                if (stretch < 0) {
                    log.add(partId + "\t" + bar(base, n.tick()) + "\tnote\tpresent\tskipped\tkein Clip des Parts an dieser "
                            + "Stelle und kein Platz für einen neuen");
                    continue;
                }
                intoFree.computeIfAbsent(stretch, k -> new ArrayList<>()).add(n);
            }
            for (Map.Entry<Integer, List<NewNote>> group : intoFree.entrySet()) {
                long start = free.get(group.getKey())[0];
                long end = free.get(group.getKey())[1];
                List<Note> notes = new ArrayList<>();
                for (NewNote n : group.getValue()) {
                    long length = cut(log, partId, n, end, "Ende der freien Stelle");
                    notes.add(new Note(fresh(ids, used), n.tick() - start, length, n.pitch(), n.velocity(), voice,
                            n.articulation()));
                }
                String clipPart = partId.equals(home.defaultPartId()) ? null : partId;
                newClips.add(new EditOp.AddClip(home.id(), new MidiClip(fresh(ids, used), start, end - start, clipPart,
                        notes)));
            }
        }
        List<EditOp> all = new ArrayList<>(removals);
        additions.forEach((clip, notes) -> all.add(new EditOp.AddNotes(clip, notes)));
        all.addAll(newClips);
        return new Patch(description(base, region.request()), all);
    }

    /**
     * What is different outside the selection between two states of a piece: a part, track,
     * clip, the time map, the form or the meta data that changed; a note that is not selected and
     * is gone, new or changed (by where it sounds, not by which clip holds it); a new clip outside
     * the selection. Empty when only the selected notes changed.
     */
    public static List<String> outsideChanges(Composition before, Composition after, Selection selection) {
        List<String> differences = new ArrayList<>();
        if (!before.parts().equals(after.parts())) {
            differences.add("parts changed");
        }
        if (!before.timeMap().equals(after.timeMap()) || !before.sections().equals(after.sections())
                || !before.meta().equals(after.meta()) || !before.assets().equals(after.assets())) {
            differences.add("time map, form, meta or assets changed");
        }
        Map<String, String> clipsBefore = new HashMap<>();
        for (Track track : before.tracks()) {
            Track now = after.track(track.id());
            if (now == null || !now.name().equals(track.name()) || now.kind() != track.kind()
                    || !java.util.Objects.equals(now.defaultPartId(), track.defaultPartId())) {
                differences.add("track " + track.id() + " changed");
            }
            for (Clip clip : track.clips()) {
                clipsBefore.put(clip.id(), frame(track, clip));
            }
        }
        if (before.tracks().size() != after.tracks().size()) {
            differences.add("tracks added or removed");
        }
        for (Track track : after.tracks()) {
            for (Clip clip : track.clips()) {
                String was = clipsBefore.remove(clip.id());
                if (was == null) {
                    boolean inside = clip instanceof MidiClip midi && selection.selects(track, midi)
                            && midi.startTick() >= selection.fromTick() && midi.endTick() <= selection.toTick();
                    if (!inside) {
                        differences.add("new clip " + clip.id() + " outside the selection");
                    }
                } else if (!was.equals(frame(track, clip))) {
                    differences.add("clip " + clip.id() + " moved or changed");
                }
            }
        }
        clipsBefore.keySet().forEach(id -> differences.add("clip " + id + " removed"));
        for (Part part : before.parts()) {
            List<String> was = unselected(before, part.id(), selection);
            List<String> now = unselected(after, part.id(), selection);
            if (!was.equals(now)) {
                List<String> lost = new ArrayList<>(was);
                lost.removeAll(now);
                List<String> came = new ArrayList<>(now);
                came.removeAll(was);
                differences.add("notes of part " + part.id() + " outside the selection changed: gone " + lost + ", new "
                        + came);
            }
        }
        return differences;
    }

    private record Located(Track track, MidiClip clip) {
    }

    /** The clips of a part that the selection selects and that reach into its range, in the order of the piece. */
    private static List<Located> holders(Composition piece, Selection selection, String partId) {
        List<Located> found = new ArrayList<>();
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi && partId.equals(track.partOf(midi)) && selection.selects(track, midi)
                        && midi.startTick() < selection.toTick() && midi.endTick() > selection.fromTick()) {
                    found.add(new Located(track, midi));
                }
            }
        }
        found.sort(java.util.Comparator.comparingLong(l -> l.clip.startTick()));
        return found;
    }

    /** The track a part plays on by default, else the MIDI track of its first clip; {@code null} if none. */
    private static Track home(Composition piece, String partId) {
        for (Track track : piece.tracks()) {
            if (track.kind() == Track.Kind.MIDI && partId.equals(track.defaultPartId())) {
                return track;
            }
        }
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi && partId.equals(track.partOf(midi))) {
                    return track;
                }
            }
        }
        return null;
    }

    /** The stretches of {@code from..to} where the track has no clip, as {start, end}. */
    private static List<long[]> free(Track track, long from, long to) {
        List<long[]> busy = new ArrayList<>();
        for (Clip clip : track.clips()) {
            long end = clip instanceof MidiClip midi ? midi.endTick() : clip.startTick() + 1;
            busy.add(new long[] {clip.startTick(), end});
        }
        busy.sort(java.util.Comparator.comparingLong(b -> b[0]));
        List<long[]> free = new ArrayList<>();
        long at = from;
        for (long[] b : busy) {
            if (b[1] <= at) {
                continue;
            }
            if (b[0] >= to) {
                break;
            }
            if (b[0] > at) {
                free.add(new long[] {at, b[0]});
            }
            at = Math.max(at, b[1]);
        }
        if (at < to) {
            free.add(new long[] {at, to});
        }
        return free;
    }

    private static long cut(List<String> log, String partId, NewNote n, long limit, String why) {
        if (n.endTick() <= limit) {
            return n.length();
        }
        log.add(partId + "\t" + n.tick() + "\tduration\t" + n.length() + "\t" + (limit - n.tick()) + "\tam " + why
                + " abgeschnitten");
        return limit - n.tick();
    }

    private static String bar(Composition piece, long tick) {
        return "Takt " + (piece.timeMap().barOf(tick) + 1);
    }

    private static List<String> unselected(Composition piece, String partId, Selection selection) {
        List<String> notes = new ArrayList<>();
        for (PlacedNote n : piece.notesOf(partId)) {
            if (!selection.selects(n)) {
                notes.add(n.tick() + "/" + n.note().length() + "/" + n.note().pitch() + "/" + n.note().velocity() + "/"
                        + n.note().voiceId());
            }
        }
        notes.sort(null);
        return notes;
    }

    private static String frame(Track track, Clip clip) {
        String text = track.id() + " " + clip.startTick();
        return clip instanceof MidiClip midi ? text + " " + midi.lengthTicks() + " " + midi.partId() : text;
    }

    private static Set<String> usedIds(Composition piece) {
        Set<String> used = new HashSet<>();
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                used.add(clip.id());
                if (clip instanceof MidiClip midi) {
                    midi.notes().forEach(n -> used.add(n.id()));
                }
            }
        }
        return used;
    }

    private static String fresh(Supplier<String> ids, Set<String> used) {
        String id;
        do {
            id = ids.get();
        } while (!used.add(id));
        return id;
    }
}
