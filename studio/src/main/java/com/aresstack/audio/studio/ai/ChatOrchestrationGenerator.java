package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.TimeMap;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the notes of one part for one frame of a re-orchestration with one call of a
 * {@link ChatModel}. The prompt carries the context around the passage, the whole plan (so that
 * every part knows what the others take over), the material of the frame as it is now, the
 * assignment of the part, the intensity with what it means for density and register, the
 * connection to what the part played before, and the rules of its role. Bars count from the start
 * of the frame; the application converts to ticks.
 */
public final class ChatOrchestrationGenerator implements OrchestrationGenerator {
    /** The standing instruction of the call. */
    public static final String INSTRUCTION = "You re-orchestrate a passage of an existing piece of original "
            + "instrumental music, one part and one section at a time. Return valid JSON only, no Markdown.";
    private final ChatModel model;
    private final int maxTokens;

    public ChatOrchestrationGenerator(ChatModel model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /** The prompt for one part and one frame. */
    public static String prompt(OrchestrationTask task, OrchestrationPlan plan, ContinuationVoice voice,
                                VoiceFrame frame, OrchestrationPlan.Assignment assignment, String material,
                                String connection) {
        TimeMap time = task.time();
        TimeMap.Meter meter = time.meterAt(frame.startTick());
        String quarters = ContextBuilder.quarters(meter.barTicks());
        ReorchestrationRequest request = task.request();
        StringBuilder prompt = new StringBuilder(task.context().text());
        prompt.append("\n# PLAN OF THE RE-ORCHESTRATION (bars ").append(request.firstBar()).append('-')
                .append(request.lastBar()).append(")\n");
        for (VoiceFrame f : task.frames()) {
            OrchestrationPlan.FramePlan entry = plan.frame(f.sectionId());
            prompt.append("frame ").append(f.sectionId()).append(" \"").append(f.name()).append("\" bars ")
                    .append(f.firstBar() + 1).append('-').append(f.firstBar() + f.bars()).append(" intensity=")
                    .append(ContextBuilder.number(f.intensity())).append('\n');
            for (OrchestrationPlan.Assignment a : entry.assignments()) {
                prompt.append("  ").append(a.partKey()).append(": ").append(a.job())
                        .append(a.source() == null ? "" : " (material of " + a.source() + ")").append('\n');
            }
        }
        for (ContinuationPlan.NewPart part : plan.newParts()) {
            prompt.append("new part ").append(part.key()).append(" \"").append(part.name()).append("\" ")
                    .append(part.instrument()).append(" role=").append(part.role()).append(" program=")
                    .append(part.program()).append(part.idea().isEmpty() ? "" : ": " + part.idea()).append('\n');
        }
        if (!plan.idea().isEmpty()) {
            prompt.append("idea: ").append(plan.idea()).append('\n');
        }
        prompt.append("\n# MATERIAL OF THESE BARS AS IT IS NOW (bar beat dur pitch vel, bars of the piece)\n")
                .append(material);
        prompt.append("\n# TASK\nWrite the notes of ONE part for one frame of the re-orchestration, after its job in "
                + "the plan, from the material above. The other parts are written after the same plan; do not write "
                + "their notes.\n");
        prompt.append("Part: ").append(voice.describe()).append('\n');
        if (voice.newPart()) {
            prompt.append("This part is a NEW instrument in the piece").append(voice.idea().isEmpty() ? "" : ": "
                    + voice.idea()).append('\n');
        }
        prompt.append("Job: ").append(assignment.job());
        if (assignment.source() != null) {
            prompt.append(" (take over the material of part ").append(assignment.source())
                    .append(", transposed into this instrument's range where needed)");
        }
        prompt.append('\n');
        prompt.append("Section: ").append(frame.sectionId()).append(" \"").append(frame.name()).append("\", bars ")
                .append(frame.firstBar() + 1).append('-').append(frame.firstBar() + frame.bars())
                .append(" of the piece = ").append(frame.bars()).append(" bars of ").append(meter.numerator()).append('/')
                .append(meter.denominator()).append(" with ").append(quarters).append(" quarter notes per bar\n");
        prompt.append("Intensity: ").append(ContextBuilder.number(frame.intensity())).append(" (0 = very quiet and "
                + "sparse, 1 = full and dense)\n");
        prompt.append(Dramaturgy.guidance(frame.intensity(), voice.role())).append('\n');
        if (!frame.harmony().isEmpty()) {
            prompt.append("Chords by bar: ").append(String.join(" | ", frame.harmony())).append('\n');
        }
        prompt.append("Kind of re-orchestration: ").append(request.preset().brief()).append('\n');
        if (!request.instruction().isEmpty()) {
            prompt.append("User instruction: ").append(request.instruction()).append('\n');
        }
        prompt.append(connection.isEmpty() ? "The part rests before these bars.\n"
                : "The part played just before (bar beat dur pitch vel, bars of the piece):\n" + connection
                + "Continue smoothly from there.\n");
        prompt.append(ChatPartGenerator.rules(voice.role())).append('\n');
        prompt.append(GeneratedNotes.articulationRule()).append('\n');
        int middle = (voice.range().low() + voice.range().high()) / 2;
        prompt.append("Return exactly one JSON object: {\"part\":\"").append(voice.key()).append("\",\"section\":\"")
                .append(frame.sectionId()).append("\",\"notes\":[{\"bar\":1,\"beat\":0,\"duration\":1,\"pitch\":")
                .append(middle).append(",\"velocity\":90}]}. ")
                .append("bar counts from 1 at the start of THESE bars up to ").append(frame.bars())
                .append("; beat is the offset within the bar in quarter notes, from 0 to below ").append(quarters)
                .append(", on multiples of 0.25; duration is in quarter notes, at least 0.25; every note ends by the end "
                        + "of these bars. At most ").append(GeneratedNotes.MAX_NOTES)
                .append(" notes. List the notes explicitly.").append(task.take());
        return prompt.toString();
    }

    @Override
    public GeneratedNotes generate(OrchestrationTask task, OrchestrationPlan plan, ContinuationVoice voice,
                                   VoiceFrame frame, OrchestrationPlan.Assignment assignment, String material,
                                   String connection, Path cacheDirectory) throws IOException {
        String prompt = prompt(task, plan, voice, frame, assignment, material, connection);
        Path cache = cacheDirectory.resolve(voice.key() + "-" + frame.sectionId() + "-"
                + ContextBuilder.sha256(prompt).substring(0, 12) + ".json");
        return GeneratedNotes.ask(problem -> model.generate(INSTRUCTION, prompt + GeneratedNotes.again(problem),
                maxTokens, cache, Listening.current()), cache,
                voice.key(), frame.sectionId());
    }
}
