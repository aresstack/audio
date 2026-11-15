package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Selection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What the note editor of the arrangement does to a piece (work package B1), as patches for the
 * edit core: select, move, change the length, insert, delete, snap to the grid, copy and paste.
 * Every action of the user is exactly one {@link NoteEdit}: one patch with the scope that holds
 * it to the clips it touches, so that the edit core proves that nothing else changed.
 *
 * <p>Nothing here knows Swing; the window only says which notes and by how much. Notes never leave
 * their clip: a clip end bounds the note events, as the architecture review E2 asks, so a move or
 * a longer note stops at the clip's edges, and a whole group of notes stops together, keeping its
 * shape.
 */
public final class NoteEdits {
    /** The kinds of operation a change of notes needs, and no more. */
    public static final Set<EditOp.Kind> NOTES = Set.copyOf(EnumSet.of(EditOp.Kind.ADD_NOTES,
            EditOp.Kind.REMOVE_NOTES, EditOp.Kind.CHANGE_NOTE));
    /** How loud a new note is when its clip has no other note to go by. */
    static final int VELOCITY = 90;

    private NoteEdits() {
    }

    /** A note of the piece, by the clip it lies in and its own id. */
    public record NoteRef(String clipId, String noteId) {
        public NoteRef {
            if (clipId == null || noteId == null) {
                throw new IllegalArgumentException("Eine Note wird über Clip und Id benannt.");
            }
        }
    }

    /**
     * One action of the editor: the patch, the scope it may touch, and the notes that are selected
     * after it (the moved, the inserted, the pasted ones). An empty patch changes nothing and is not
     * applied.
     */
    public record NoteEdit(Patch patch, EditScope scope, List<NoteRef> selected) {
        public NoteEdit {
            selected = List.copyOf(selected == null ? List.of() : selected);
        }

        public boolean isEmpty() {
            return patch.isEmpty();
        }
    }

    /** Copied notes: where they lie relative to the first of them, and which part played them. */
    public record Clipboard(List<Entry> entries) {
        public record Entry(long offset, long length, int pitch, int velocity, String partId, String voiceId) {
        }

        public Clipboard {
            entries = List.copyOf(entries == null ? List.of() : entries);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /** How long the copied stretch is, from the start of the first note to the end of the last. */
        public long span() {
            long end = 0;
            for (Entry entry : entries) {
                end = Math.max(end, entry.offset() + entry.length());
            }
            return end;
        }
    }

    /** A note found in the piece, with its clip and track. */
    record Found(Track track, MidiClip clip, Note note) {
        long start() {
            return clip.startTick() + note.tick();
        }
    }

    // ---------------------------------------------------------------------------------------
    // The actions

    /**
     * Moves notes by a number of ticks and semitones. The whole group moves by the same amount and
     * stops where its first note would leave its clip or the range of pitches.
     */
    public static NoteEdit move(Composition piece, Collection<NoteRef> notes, long ticks, int semitones) {
        List<Found> found = find(piece, notes);
        long earliest = Long.MIN_VALUE;
        long latest = Long.MAX_VALUE;
        int lowest = Integer.MIN_VALUE;
        int highest = Integer.MAX_VALUE;
        for (Found note : found) {
            earliest = Math.max(earliest, -note.note().tick());
            latest = Math.min(latest, note.clip().lengthTicks() - note.note().endTick());
            lowest = Math.max(lowest, -note.note().pitch());
            highest = Math.min(highest, 127 - note.note().pitch());
        }
        long by = found.isEmpty() ? 0 : clamp(ticks, earliest, Math.max(earliest, latest));
        int up = found.isEmpty() ? 0 : (int) clamp(semitones, lowest, Math.max(lowest, highest));
        List<EditOp> operations = new ArrayList<>();
        if (by != 0 || up != 0) {
            for (Found note : found) {
                Note n = note.note();
                operations.add(new EditOp.ChangeNote(note.clip().id(),
                        new Note(n.id(), n.tick() + by, n.length(), n.pitch() + up, n.velocity(), n.voiceId())));
            }
        }
        return edit(describe(found.size(), "verschoben"), operations, found, refs(found));
    }

    /**
     * Makes notes longer or shorter by a number of ticks. No note gets shorter than
     * {@code shortest} ticks or longer than its clip allows.
     */
    public static NoteEdit resize(Composition piece, Collection<NoteRef> notes, long ticks, long shortest) {
        List<Found> found = find(piece, notes);
        long least = Math.max(1, shortest);
        List<EditOp> operations = new ArrayList<>();
        for (Found note : found) {
            Note n = note.note();
            long length = clamp(n.length() + ticks, Math.min(least, n.length()), note.clip().lengthTicks() - n.tick());
            if (length != n.length()) {
                operations.add(new EditOp.ChangeNote(note.clip().id(),
                        new Note(n.id(), n.tick(), length, n.pitch(), n.velocity(), n.voiceId())));
            }
        }
        return edit(describe(found.size(), ticks < 0 ? "verkürzt" : "verlängert"), operations, found, refs(found));
    }

    /**
     * A new note on a track, in the MIDI clip that holds the tick. It is as loud as the notes of
     * that clip are on average and belongs to the voice whose notes in the clip lie nearest in
     * pitch. Empty if no clip of the track holds the tick or the note does not fit into the clip.
     */
    public static NoteEdit insert(Composition piece, String trackId, long tick, int pitch, long length,
                                  Supplier<String> ids) {
        Track track = piece.track(trackId);
        MidiClip clip = track == null ? null : clipAt(track, tick);
        if (clip == null || pitch < 0 || pitch > 127) {
            return nothing("Note einfügen");
        }
        long at = tick - clip.startTick();
        long fits = Math.min(Math.max(1, length), clip.lengthTicks() - at);
        if (fits < 1) {
            return nothing("Note einfügen");
        }
        Part part = piece.part(track.partOf(clip));
        Note note = new Note(freshId(piece, ids, Set.of()), at, fits, pitch, velocityOf(clip),
                voiceNear(part, clip, pitch));
        List<EditOp> operations = List.of(new EditOp.AddNotes(clip.id(), List.of(note)));
        return new NoteEdit(new Patch("Note eingefügt", operations), scope(Set.of(clip.id())),
                List.of(new NoteRef(clip.id(), note.id())));
    }

    /** Takes notes out of their clips. */
    public static NoteEdit delete(Composition piece, Collection<NoteRef> notes) {
        List<Found> found = find(piece, notes);
        Map<String, List<String>> byClip = new LinkedHashMap<>();
        for (Found note : found) {
            byClip.computeIfAbsent(note.clip().id(), k -> new ArrayList<>()).add(note.note().id());
        }
        List<EditOp> operations = new ArrayList<>();
        for (Map.Entry<String, List<String>> clip : byClip.entrySet()) {
            operations.add(new EditOp.RemoveNotes(clip.getKey(), clip.getValue()));
        }
        return edit(describe(found.size(), "gelöscht"), operations, found, List.of());
    }

    /**
     * Snaps the starts of notes to the grid, counted from the start of the piece. A note keeps its
     * length unless it would then end after its clip, and a note too near the clip end for the next
     * grid line goes to the line before.
     *
     * @param grid the distance of the grid lines in ticks
     */
    public static NoteEdit quantize(Composition piece, Collection<NoteRef> notes, long grid) {
        List<Found> found = find(piece, notes);
        List<EditOp> operations = new ArrayList<>();
        if (grid > 0) {
            for (Found note : found) {
                Note n = note.note();
                long snapped = snap(note.start(), grid) - note.clip().startTick();
                if (snapped < 0) {
                    snapped += grid;
                }
                if (snapped + 1 > note.clip().lengthTicks()) {
                    snapped -= grid;
                }
                if (snapped < 0) {
                    continue;
                }
                long length = Math.min(n.length(), note.clip().lengthTicks() - snapped);
                if (snapped != n.tick() || length != n.length()) {
                    operations.add(new EditOp.ChangeNote(note.clip().id(),
                            new Note(n.id(), snapped, length, n.pitch(), n.velocity(), n.voiceId())));
                }
            }
        }
        return edit(describe(found.size(), "ins Raster gesetzt"), operations, found, refs(found));
    }

    /** The notes as they lie, for pasting elsewhere. */
    public static Clipboard copy(Composition piece, Collection<NoteRef> notes) {
        List<Found> found = find(piece, notes);
        long first = Long.MAX_VALUE;
        for (Found note : found) {
            first = Math.min(first, note.start());
        }
        List<Clipboard.Entry> entries = new ArrayList<>();
        for (Found note : found) {
            Note n = note.note();
            entries.add(new Clipboard.Entry(note.start() - first, n.length(), n.pitch(), n.velocity(),
                    note.track().partOf(note.clip()), n.voiceId()));
        }
        return new Clipboard(entries);
    }

    /**
     * Pastes copied notes onto a track so that the first begins at a tick, into the clips that
     * hold each note's start. A note keeps its voice if it goes to the part it came from, else it
     * goes to the voice of that part that lies nearest in pitch. Notes that would begin outside
     * every clip of the track are left out, and a note is shortened at its clip's end.
     */
    public static NoteEdit paste(Composition piece, Clipboard clipboard, String trackId, long atTick,
                                 Supplier<String> ids) {
        Track track = piece.track(trackId);
        if (track == null || clipboard.isEmpty()) {
            return nothing("Noten eingefügt");
        }
        Map<String, List<Note>> byClip = new LinkedHashMap<>();
        List<NoteRef> pasted = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (Clipboard.Entry entry : clipboard.entries()) {
            long tick = atTick + entry.offset();
            MidiClip clip = clipAt(track, tick);
            if (clip == null) {
                continue;
            }
            long at = tick - clip.startTick();
            long length = Math.min(entry.length(), clip.lengthTicks() - at);
            Part part = piece.part(track.partOf(clip));
            String voice = part != null && part.id().equals(entry.partId()) && part.voice(entry.voiceId()) != null
                    ? entry.voiceId() : voiceNear(part, clip, entry.pitch());
            String id = freshId(piece, ids, taken);
            taken.add(id);
            byClip.computeIfAbsent(clip.id(), k -> new ArrayList<>())
                    .add(new Note(id, at, length, entry.pitch(), entry.velocity(), voice));
            pasted.add(new NoteRef(clip.id(), id));
        }
        List<EditOp> operations = new ArrayList<>();
        for (Map.Entry<String, List<Note>> clip : byClip.entrySet()) {
            operations.add(new EditOp.AddNotes(clip.getKey(), clip.getValue()));
        }
        return new NoteEdit(new Patch(describe(pasted.size(), "eingefügt"), operations), scope(byClip.keySet()), pasted);
    }

    // ---------------------------------------------------------------------------------------
    // Selection, shared with the region changes of the AI

    /**
     * The notes of a range selection ({@link Selection} of work package C2): those that begin in
     * the range and lie in a selected part or clip.
     */
    public static List<NoteRef> notesOf(Composition piece, Selection selection) {
        List<NoteRef> notes = new ArrayList<>();
        for (String part : selection.parts(piece)) {
            for (Composition.PlacedNote placed : selection.notes(piece, part)) {
                notes.add(new NoteRef(placed.clip().id(), placed.note().id()));
            }
        }
        return notes;
    }

    /**
     * The range selection that covers notes picked in the editor: their clips, from the start of the
     * first note to the end of the last. Hand it to "Passage ersetzen" or "variieren" (C2). Empty if
     * no note is picked.
     */
    public static java.util.Optional<Selection> selectionOf(Composition piece, Collection<NoteRef> notes) {
        List<Found> found = find(piece, notes);
        if (found.isEmpty()) {
            return java.util.Optional.empty();
        }
        long from = Long.MAX_VALUE;
        long to = 0;
        Set<String> clips = new LinkedHashSet<>();
        for (Found note : found) {
            from = Math.min(from, note.start());
            to = Math.max(to, note.start() + note.note().length());
            clips.add(note.clip().id());
        }
        return java.util.Optional.of(new Selection(Set.of(), clips, from, Math.max(from + 1, to)));
    }

    /** All notes of a track, in the order of its clips. */
    public static List<NoteRef> notesOfTrack(Composition piece, String trackId) {
        List<NoteRef> notes = new ArrayList<>();
        Track track = piece.track(trackId);
        if (track != null) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi) {
                    for (Note note : midi.notes()) {
                        notes.add(new NoteRef(midi.id(), note.id()));
                    }
                }
            }
        }
        return notes;
    }

    /** The scope of a change of notes in these clips: nothing but their notes, anywhere in time. */
    public static EditScope scope(Set<String> clipIds) {
        return new EditScope(false, Set.of(), Set.of(), clipIds, 0, EditScope.END, NOTES, false, 0);
    }

    /** The grid line nearest to a tick. */
    public static long snap(long tick, long grid) {
        if (grid <= 0) {
            return tick;
        }
        return Math.floorDiv(tick + grid / 2, grid) * grid;
    }

    // ---------------------------------------------------------------------------------------

    private static NoteEdit edit(String description, List<EditOp> operations, List<Found> found, List<NoteRef> selected) {
        Set<String> clips = new LinkedHashSet<>();
        for (Found note : found) {
            clips.add(note.clip().id());
        }
        Patch patch = new Patch(description, operations);
        return new NoteEdit(patch, clips.isEmpty() ? scope(Set.of("none")) : scope(clips), selected);
    }

    private static NoteEdit nothing(String description) {
        return new NoteEdit(new Patch(description, List.of()), scope(Set.of("none")), List.of());
    }

    private static List<NoteRef> refs(List<Found> found) {
        List<NoteRef> refs = new ArrayList<>();
        for (Found note : found) {
            refs.add(new NoteRef(note.clip().id(), note.note().id()));
        }
        return refs;
    }

    /** The named notes that still exist, each once, in the order they were named. */
    static List<Found> find(Composition piece, Collection<NoteRef> notes) {
        Map<String, Set<String>> wanted = new LinkedHashMap<>();
        for (NoteRef ref : notes) {
            wanted.computeIfAbsent(ref.clipId(), k -> new LinkedHashSet<>()).add(ref.noteId());
        }
        List<Found> found = new ArrayList<>();
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                Set<String> ids = wanted.get(clip.id());
                if (ids != null && clip instanceof MidiClip midi) {
                    for (Note note : midi.notes()) {
                        if (ids.contains(note.id())) {
                            found.add(new Found(track, midi, note));
                        }
                    }
                }
            }
        }
        return found;
    }

    /** The MIDI clip of a track that holds a tick of the piece, or {@code null}. */
    static MidiClip clipAt(Track track, long tick) {
        for (Clip clip : track.clips()) {
            if (clip instanceof MidiClip midi && tick >= midi.startTick() && tick < midi.endTick()) {
                return midi;
            }
        }
        return null;
    }

    private static int velocityOf(MidiClip clip) {
        if (clip.notes().isEmpty()) {
            return VELOCITY;
        }
        long sum = 0;
        for (Note note : clip.notes()) {
            sum += note.velocity();
        }
        return (int) Math.max(1, Math.min(127, Math.round(sum / (double) clip.notes().size())));
    }

    /** The voice of the part whose notes in the clip lie nearest to a pitch; its first voice if none does. */
    private static String voiceNear(Part part, MidiClip clip, int pitch) {
        String voice = part == null ? null : part.firstVoice().id();
        int distance = Integer.MAX_VALUE;
        for (Note note : clip.notes()) {
            int away = Math.abs(note.pitch() - pitch);
            if (away < distance && (part == null || part.voice(note.voiceId()) != null)) {
                distance = away;
                voice = note.voiceId();
            }
        }
        if (voice == null) {
            throw new IllegalStateException("Clip ohne Part und ohne Noten: " + clip.id());
        }
        return voice;
    }

    private static String freshId(Composition piece, Supplier<String> ids, Set<String> taken) {
        Set<String> used = new HashSet<>(taken);
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi) {
                    for (Note note : midi.notes()) {
                        used.add(note.id());
                    }
                }
            }
        }
        String id = ids.get();
        while (used.contains(id)) {
            id = ids.get();
        }
        return id;
    }

    private static String describe(int count, String what) {
        return (count == 1 ? "Note " : count + " Noten ") + what;
    }

    private static long clamp(long value, long low, long high) {
        return Math.max(low, Math.min(high, value));
    }
}
