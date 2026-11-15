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
                                   List<TailChange> tail, List<String> corrections) {
    /** A new clip and the track it goes on. */
    public record PlacedClip(String trackId, MidiClip clip) {
    }

    /** Notes that leave and enter an existing clip within the revised bars of the original. */
    public record TailChange(String clipId, List<String> removed, List<Note> added) {
        public TailChange {
            removed = List.copyOf(removed);
            added = List.copyOf(added);
        }
    }

    public ComposedContinuation {
        sections = List.copyOf(sections);
        form = List.copyOf(form);
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
