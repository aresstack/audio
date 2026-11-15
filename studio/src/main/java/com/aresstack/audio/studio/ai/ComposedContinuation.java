package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.Track;
import java.util.List;

/**
 * A continuation or an insertion as the AI wrote it, repaired, but not yet part of the piece: the new sections of
 * the form, the new parts with their (empty) tracks, the new clips, and with a revised transition
 * the notes taken out of and put into the last bars of the original. Turning it into a patch is
 * the application's business.
 *
 * @param sections    the new sections, in order: for a continuation those that follow the existing
 *                    ones, for an insertion those that fill the room
 * @param form        the whole form of the piece after the change, without gap
 * @param newParts    the new instruments of the plan that play somewhere
 * @param newTracks   the new tracks: one per new part, and one for an existing part without a track
 * @param clips       the new clips, each with the track it goes on
 * @param tail        what changes in clips of the original within the revised bars
 * @param corrections every repair of the normalizer and every note left out, tab-separated, the
 *                    part's key first
 */
public record ComposedContinuation(ContinuationTask task, ContinuationPlan plan, List<Section> sections,
                                   List<Section> form, List<Part> newParts, List<Track> newTracks, List<PlacedClip> clips,
                                   List<TailChange> tail, List<String> corrections, List<Hold> holds) {
    /**
     * A note of the original that sounds across the insertion point of an {@code INSERT}. The
     * shift cuts it at the point; the insertion lets it sound on (into the room, at most to its
     * end), as a {@link TailChange} of its clip that restores it and lets the clip grow. A note
     * that would sound beyond the end of the room cannot keep its length there, because the moved
     * original begins there; it is listed by {@link #shortened()} and in the corrections, never
     * cut silently.
     *
     * @param tick   the tick of the piece at which the note begins, before the insertion point
     * @param length its length in the original
     */
    public record Hold(String partId, String clipId, String noteId, long tick, long length) {
        public long endTick() {
            return tick + length;
        }
    }

    /** As the canonical constructor, without held notes. */
    public ComposedContinuation(ContinuationTask task, ContinuationPlan plan, List<Section> sections, List<Section> form,
                                List<Part> newParts, List<Track> newTracks, List<PlacedClip> clips,
                                List<TailChange> tail, List<String> corrections) {
        this(task, plan, sections, form, newParts, newTracks, clips, tail, corrections, List.of());
    }

    /** A new clip and the track it goes on. */
    public record PlacedClip(String trackId, MidiClip clip) {
    }

    /**
     * Notes that leave and enter an existing clip within the revised bars of the original.
     *
     * @param lengthTicks the clip's new length, so that new notes can sound past its old end; 0
     *                    when it keeps its length
     */
    public record TailChange(String clipId, List<String> removed, List<Note> added, long lengthTicks) {
        public TailChange {
            removed = List.copyOf(removed);
            added = List.copyOf(added);
            if (lengthTicks < 0) {
                throw new IllegalArgumentException("A clip length is not negative: " + lengthTicks);
            }
        }

        public TailChange(String clipId, List<String> removed, List<Note> added) {
            this(clipId, removed, added, 0);
        }
    }

    public ComposedContinuation {
        sections = List.copyOf(sections);
        form = List.copyOf(form);
        holds = List.copyOf(holds == null ? List.of() : holds);
        newParts = List.copyOf(newParts);
        newTracks = List.copyOf(newTracks);
        clips = List.copyOf(clips);
        tail = List.copyOf(tail);
        corrections = List.copyOf(corrections);
    }

    /** The first tick that changes: the start of the revised bars, or of the continuation. */
    public long fromTick() {
        return task.tailStart();
    }

    /**
     * The first tick whose notes the patch touches: the start of the revised bars or of the new
     * ones, or earlier, where a held note of an insertion begins.
     */
    public long editFrom() {
        long from = task.tailStart();
        for (Hold hold : holds) {
            from = Math.min(from, hold.tick());
        }
        return from;
    }

    /**
     * The length a held note keeps in the result: its own, unless the room end or a foreign clip
     * cut it; the length of the original when the note is not restored by this continuation.
     */
    public long keptLength(Hold hold) {
        for (TailChange change : tail) {
            if (change.clipId().equals(hold.clipId())) {
                for (Note note : change.added()) {
                    if (note.id().equals(hold.noteId())) {
                        return note.length();
                    }
                }
            }
        }
        return hold.length();
    }

    /**
     * The held notes that do not keep their original length (they sound beyond the end of the
     * inserted bars, or into a foreign clip): the cases that the user has to see and confirm,
     * because the original cannot be kept in full there. Empty for most insertions.
     */
    public List<Hold> shortened() {
        return holds.stream().filter(hold -> keptLength(hold) < hold.length()).toList();
    }

    public int attempt() {
        return task.attempt();
    }

    /** A neutral reference to the run, for the revision: what, which attempt, which context. */
    public String runId() {
        return (task.inserts() ? "insert/" : "extend/") + task.attempt() + "/" + task.context().shortDigest();
    }

    /** The notes of the new bars and of the revised transition. */
    public int noteCount() {
        return clips.stream().mapToInt(c -> c.clip().notes().size()).sum()
                + tail.stream().mapToInt(t -> t.added().size()).sum();
    }
}
