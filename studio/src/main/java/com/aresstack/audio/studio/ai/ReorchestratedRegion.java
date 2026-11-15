package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Track;
import java.util.List;

/**
 * A passage as the AI re-orchestrated it, repaired, but not yet part of the piece: which notes of
 * the chosen parts leave which clips, which new notes enter existing clips, the new clips (in gaps
 * of a part's track, and for new instruments), the new parts with their (empty) tracks. Turning it
 * into a patch is the application's business.
 *
 * @param changes     notes leaving and entering existing clips
 * @param clips       the new clips, each with the track it goes on
 * @param corrections every repair of the normalizer, every thinning and every note left out,
 *                    tab-separated, the part's key first
 */
public record ReorchestratedRegion(OrchestrationTask task, OrchestrationPlan plan, List<Part> newParts,
                                   List<Track> newTracks, List<ClipChange> changes,
                                   List<ComposedContinuation.PlacedClip> clips, List<String> corrections) {
    /** Notes that leave and enter an existing clip within the passage. */
    public record ClipChange(String clipId, List<String> removed, List<Note> added) {
        public ClipChange {
            removed = List.copyOf(removed);
            added = List.copyOf(added);
        }
    }

    public ReorchestratedRegion {
        newParts = List.copyOf(newParts);
        newTracks = List.copyOf(newTracks);
        changes = List.copyOf(changes);
        clips = List.copyOf(clips);
        corrections = List.copyOf(corrections);
    }

    public int attempt() {
        return task.attempt();
    }

    /** A neutral reference to the run, for the revision. */
    public String runId() {
        return "reorchestrate/" + task.attempt() + "/" + task.context().shortDigest();
    }

    /** The new notes of the passage. */
    public int noteCount() {
        return clips.stream().mapToInt(c -> c.clip().notes().size()).sum()
                + changes.stream().mapToInt(c -> c.added().size()).sum();
    }
}
