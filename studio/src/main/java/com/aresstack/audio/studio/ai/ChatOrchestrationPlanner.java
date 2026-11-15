package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The planning step of a re-orchestration, one call of a {@link ChatModel}: for each frame of the
 * passage which parts play and what each takes over, and which new instruments join. The answer
 * is read leniently and completed ({@link OrchestrationPlan#complete}); an answer without a usable
 * frame gives the plan of the intensities ({@link OrchestrationPlan#fallback}).
 */
public final class ChatOrchestrationPlanner implements OrchestrationPlanner {
    /** The standing instruction of the call. */
    public static final String INSTRUCTION = "You are the arranger re-orchestrating a passage of an existing piece. "
            + "Return valid JSON only, no Markdown.";
    private final ChatModel model;
    private final int maxTokens;

    public ChatOrchestrationPlanner(ChatModel model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /** The prompt of the planning step: the context, the frames, then the task. */
    public static String prompt(OrchestrationTask task) {
        ReorchestrationRequest request = task.request();
        StringBuilder prompt = new StringBuilder(task.context().text());
        prompt.append("\n# TASK\nRe-orchestrate bars ").append(request.firstBar()).append('-').append(request.lastBar())
                .append(" of this piece: redistribute the material that is there now among the parts. Notes, harmony "
                        + "and motifs are given; you decide who plays what. Everything before bar ")
                .append(request.firstBar()).append(" and after bar ").append(request.lastBar()).append(" and every "
                        + "part not listed below stays exactly as it is.\n");
        prompt.append("Kind of re-orchestration: ").append(request.preset().brief()).append('\n');
        if (!request.instruction().isEmpty()) {
            prompt.append("User instruction: ").append(request.instruction()).append('\n');
        }
        prompt.append("Parts whose material is redistributed and which play it anew (use their ids; a part you do not "
                + "name in a frame rests there):\n");
        for (Part part : task.parts()) {
            prompt.append("  ").append(ContinuationTask.describe(part)).append('\n');
        }
        prompt.append("Frames of the passage (one plan entry each):\n");
        for (VoiceFrame frame : task.frames()) {
            prompt.append("  frame ").append(frame.sectionId()).append(" \"").append(frame.name()).append("\" bars ")
                    .append(frame.firstBar() + 1).append('-').append(frame.firstBar() + frame.bars())
                    .append(" intensity=").append(ContextBuilder.number(frame.intensity())).append(" (about ")
                    .append(Dramaturgy.ensembleSize(frame.intensity(), task.parts().size())).append(" of ")
                    .append(task.parts().size()).append(" parts)\n");
        }
        if (request.curve() != null) {
            prompt.append("The intensities follow the curve the user gave: ").append(request.curve()).append(".\n");
        }
        prompt.append(Dramaturgy.ensembleRule(task.parts().size())).append('\n');
        if (request.newParts() > 0) {
            prompt.append("You may bring in up to ").append(request.newParts()).append(" NEW instruments; name them "
                    + "new1, new2, ... and list them in newParts. Bring in none if the parts suffice.\n");
        } else {
            prompt.append("Do not add new instruments; newParts stays empty.\n");
        }
        prompt.append("Return one JSON object: {\"frames\":[{\"frame\":\"<frame id>\",\"assignments\":[{\"part\":"
                + "\"<part id or new key>\",\"job\":\"<what it plays, for example the melody an octave lower, the "
                + "harmony as sustained chords, the bass line>\",\"source\":\"<id of the part whose material it "
                + "takes over, or empty>\"}]}],\"newParts\":[{\"key\":\"new1\",\"name\":\"<name>\",\"instrument\":"
                + "\"<instrument>\",\"program\":<General MIDI program 0-127>,\"role\":\"melody|harmony|bass|drums|"
                + "texture\",\"idea\":\"<its job>\"}],\"idea\":\"<the new orchestration in one sentence>\"}. "
                + "No notes yet.").append(task.take());
        return prompt.toString();
    }

    @Override
    public OrchestrationPlan plan(OrchestrationTask task, Composition piece, Path cacheDirectory) throws IOException {
        String prompt = prompt(task);
        Path cache = cacheDirectory.resolve("plan-" + ContextBuilder.sha256(prompt).substring(0, 12) + ".json");
        return read(model.generate(INSTRUCTION, prompt, maxTokens, cache), task);
    }

    /** Reads and completes a planning answer; see the class comment. */
    public static OrchestrationPlan read(JsonObject answer, OrchestrationTask task) {
        List<String> repairs = new ArrayList<>();
        Map<String, String> renamed = new HashMap<>();
        List<ContinuationPlan.NewPart> newParts = ChatContinuationPlanner.newParts(answer, repairs, renamed);
        List<OrchestrationPlan.FramePlan> frames = new ArrayList<>();
        for (JsonObject given : ChatContinuationPlanner.objects(answer, "frames")) {
            String id = ChatContinuationPlanner.text(given, "frame");
            if (id == null || id.isBlank()) {
                repairs.add("Ein Planeintrag ohne Abschnitt wurde verworfen.");
                continue;
            }
            List<OrchestrationPlan.Assignment> assignments = new ArrayList<>();
            for (JsonObject a : ChatContinuationPlanner.objects(given, "assignments")) {
                String part = ChatContinuationPlanner.text(a, "part");
                if (part == null || part.isBlank()) {
                    repairs.add("Abschnitt " + id + ": Zuweisung ohne Stimme verworfen.");
                    continue;
                }
                assignments.add(new OrchestrationPlan.Assignment(renamed.getOrDefault(part, part),
                        ChatContinuationPlanner.text(a, "job"), ChatContinuationPlanner.text(a, "source")));
            }
            frames.add(new OrchestrationPlan.FramePlan(id.strip(), assignments));
        }
        if (frames.isEmpty()) {
            repairs.add("Der Plan der KI enthielt keinen brauchbaren Abschnitt; Besetzung nach der Intensität ("
                    + task.request().preset().label() + ").");
            return OrchestrationPlan.fallback(task, repairs);
        }
        return OrchestrationPlan.complete(frames, newParts, ChatContinuationPlanner.text(answer, "idea"), task, repairs);
    }
}
