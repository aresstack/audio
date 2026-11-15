package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Part;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The shared plan of a re-orchestration (architecture review E4: "the planner redistributes the
 * existing material"): for each frame of the passage which parts play and what each of them takes
 * over, and which new instruments join. A part a frame does not name rests there: its notes in
 * that frame are taken out. Every generator call follows the same plan.
 *
 * <p>A plan always has one entry per frame of the task and names only parts that exist or new
 * parts it brings: {@link #complete} repairs an answer that does not, and records each repair.
 *
 * @param idea    the overall idea of the new orchestration, in words; may be empty
 * @param repairs what was repaired in the planning answer, one line each, in German
 */
public record OrchestrationPlan(List<FramePlan> frames, List<ContinuationPlan.NewPart> newParts, String idea,
                                List<String> repairs) {
    /**
     * Who plays in one frame and what.
     *
     * @param frameId     the id of the frame of the task
     * @param assignments the parts that play, each once
     */
    public record FramePlan(String frameId, List<Assignment> assignments) {
        public FramePlan {
            assignments = List.copyOf(assignments);
        }

        /** What a part plays here, or {@code null} if it rests. */
        public Assignment of(String partKey) {
            for (Assignment a : assignments) {
                if (a.partKey().equals(partKey)) {
                    return a;
                }
            }
            return null;
        }
    }

    /**
     * What one part plays in one frame.
     *
     * @param partKey the id of an existing part, or the key of a new one ("new1")
     * @param job     what it plays, in words, for example "the melody an octave lower"
     * @param source  the id of the part whose material it takes over, or {@code null} for free
     */
    public record Assignment(String partKey, String job, String source) {
        public Assignment {
            if (partKey == null || partKey.isBlank()) {
                throw new IllegalArgumentException("An assignment names its part.");
            }
            job = job == null || job.isBlank() ? "its own line" : job.strip();
            source = source == null || source.isBlank() ? null : source.strip();
        }
    }

    public OrchestrationPlan {
        frames = List.copyOf(frames);
        newParts = List.copyOf(newParts);
        idea = idea == null ? "" : idea.strip();
        repairs = List.copyOf(repairs == null ? List.of() : repairs);
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("A plan has at least one frame.");
        }
    }

    public FramePlan frame(String frameId) {
        for (FramePlan f : frames) {
            if (f.frameId().equals(frameId)) {
                return f;
            }
        }
        return null;
    }

    public ContinuationPlan.NewPart newPart(String key) {
        for (ContinuationPlan.NewPart part : newParts) {
            if (part.key().equals(key)) {
                return part;
            }
        }
        return null;
    }

    /**
     * The plan without a planning answer: in each frame the parts its intensity calls for
     * ({@link Dramaturgy#ensemble}), each keeping its role and its own material; no new
     * instrument. Deterministic.
     */
    public static OrchestrationPlan fallback(OrchestrationTask task) {
        return fallback(task, new ArrayList<>(List.of("Kein Plan der KI; Besetzung nach der Intensität der "
                + "Abschnitte (" + task.request().preset().label() + ").")));
    }

    static OrchestrationPlan fallback(OrchestrationTask task, List<String> repairs) {
        List<FramePlan> frames = new ArrayList<>();
        for (VoiceFrame frame : task.frames()) {
            frames.add(ensembleOf(task, frame));
        }
        return new OrchestrationPlan(frames, List.of(), task.request().preset().brief(), repairs);
    }

    private static FramePlan ensembleOf(OrchestrationTask task, VoiceFrame frame) {
        List<Assignment> assignments = new ArrayList<>();
        for (Part part : Dramaturgy.ensemble(frame.intensity(), task.parts(), Part::role)) {
            assignments.add(new Assignment(part.id(), part.role() == null ? "its own line" : "its " + part.role()
                    + " line", part.id()));
        }
        return new FramePlan(frame.sectionId(), assignments);
    }

    /**
     * Makes a planning answer a plan: one entry per frame of the task in its order (a frame the
     * answer misses gets the ensemble of its intensity), assignments only for known parts, each
     * part once per frame, sources only from the chosen parts, new parts within the allowed number
     * and only if they play somewhere. Every repair is recorded.
     */
    static OrchestrationPlan complete(List<FramePlan> given, List<ContinuationPlan.NewPart> newParts, String idea,
                                      OrchestrationTask task, List<String> repairs) {
        List<String> existing = task.parts().stream().map(Part::id).toList();
        List<ContinuationPlan.NewPart> allowed = new ArrayList<>();
        for (ContinuationPlan.NewPart part : newParts) {
            if (allowed.size() >= task.request().newParts()) {
                repairs.add("Neues Instrument " + part.name() + " verworfen: höchstens " + task.request().newParts()
                        + " erlaubt.");
            } else {
                allowed.add(part);
            }
        }
        Set<String> known = new LinkedHashSet<>(existing);
        allowed.forEach(part -> known.add(part.key()));
        Set<String> frameIds = new HashSet<>();
        task.frames().forEach(f -> frameIds.add(f.sectionId()));
        for (FramePlan f : given) {
            if (!frameIds.contains(f.frameId())) {
                repairs.add("Abschnitt " + f.frameId() + " gehört nicht zum Bereich und entfällt.");
            }
        }
        List<FramePlan> frames = new ArrayList<>();
        Set<String> playing = new HashSet<>();
        for (VoiceFrame frame : task.frames()) {
            FramePlan answer = null;
            for (FramePlan f : given) {
                if (f.frameId().equals(frame.sectionId())) {
                    answer = f;
                    break;
                }
            }
            if (answer == null) {
                repairs.add(frame.name() + ": kein Plan der KI; Besetzung nach der Intensität "
                        + String.format(Locale.ROOT, "%.2f", frame.intensity()) + ".");
                FramePlan made = ensembleOf(task, frame);
                made.assignments().forEach(a -> playing.add(a.partKey()));
                frames.add(made);
                continue;
            }
            List<Assignment> kept = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Assignment a : answer.assignments()) {
                if (!known.contains(a.partKey())) {
                    repairs.add(frame.name() + ": unbekannte Stimme " + a.partKey() + " entfällt.");
                    continue;
                }
                if (!seen.add(a.partKey())) {
                    repairs.add(frame.name() + ": Stimme " + a.partKey() + " doppelt genannt, erste Angabe gilt.");
                    continue;
                }
                String source = a.source();
                if (source != null && !existing.contains(source)) {
                    repairs.add(frame.name() + ": Vorlage " + source + " ist keine gewählte Stimme und entfällt.");
                    source = null;
                }
                kept.add(new Assignment(a.partKey(), a.job(), source));
            }
            if (kept.isEmpty()) {
                repairs.add(frame.name() + ": keine spielende Stimme; Besetzung nach der Intensität.");
                FramePlan made = ensembleOf(task, frame);
                made.assignments().forEach(a -> playing.add(a.partKey()));
                frames.add(made);
                continue;
            }
            kept.forEach(a -> playing.add(a.partKey()));
            frames.add(new FramePlan(frame.sectionId(), kept));
        }
        List<ContinuationPlan.NewPart> used = new ArrayList<>();
        for (ContinuationPlan.NewPart part : allowed) {
            if (playing.contains(part.key())) {
                used.add(part);
            } else {
                repairs.add("Neues Instrument " + part.name() + " spielt in keinem Abschnitt und entfällt.");
            }
        }
        return new OrchestrationPlan(frames, used, idea, repairs);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (FramePlan f : frames) {
            text.append(f.frameId());
            for (Assignment a : f.assignments()) {
                text.append(' ').append(a.partKey()).append('=').append(a.job());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
