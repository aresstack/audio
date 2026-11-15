package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Articulation;
import java.util.List;
import java.util.Map;

/**
 * A passage as the AI wrote it anew, repaired, but not yet part of the piece (work package C2):
 * for every selected part the notes it should play in the region, in ticks of the piece. Turning
 * it into a patch is the application's business.
 *
 * @param passages    the new notes per part, in the order of the piece; an empty list is a rest
 * @param corrections every repair and every note left out, part first, tab-separated
 * @param attempt     which attempt this is, from 1
 * @param performance how the passage is played: tempo, dynamic marks and hairpins in it
 */
public record RewrittenRegion(RegionRequest request, Map<String, List<NewNote>> passages, MusicalContext context,
                              List<String> corrections, int attempt, ContinuationPerformance performance) {
    /** A passage without a planned performance. */
    public RewrittenRegion(RegionRequest request, Map<String, List<NewNote>> passages, MusicalContext context,
                           List<String> corrections, int attempt) {
        this(request, passages, context, corrections, attempt, ContinuationPerformance.NONE);
    }

    /**
     * A note of the new passage.
     *
     * @param tick tick of the piece the note begins at
     */
    public record NewNote(long tick, long length, int pitch, int velocity, Articulation articulation) {
        public NewNote(long tick, long length, int pitch, int velocity) {
            this(tick, length, pitch, velocity, null);
        }

        public long endTick() {
            return tick + length;
        }
    }

    public RewrittenRegion {
        Map<String, List<NewNote>> copy = new java.util.LinkedHashMap<>();
        passages.forEach((part, notes) -> copy.put(part, List.copyOf(notes)));
        passages = java.util.Collections.unmodifiableMap(copy);
        corrections = List.copyOf(corrections);
        performance = performance == null ? ContinuationPerformance.NONE : performance;
    }

    /** A neutral reference to the run, for the revision: what, which parts, which attempt, which context. */
    public String runId() {
        return request.mode().id() + "/" + String.join("+", passages.keySet()) + "/" + attempt + "/"
                + context.shortDigest();
    }

    public int noteCount() {
        return passages.values().stream().mapToInt(List::size).sum();
    }
}
