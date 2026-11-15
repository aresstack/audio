package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.TimeMap;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the notes of one part for one frame of a continuation with one call of a
 * {@link ChatModel}. The prompt carries the context of the piece, the whole shared plan (so that
 * every part knows what the others will do), the section, the connection to what the part played
 * before, and the rules of its role. Bars count from the start of the frame, beats and durations
 * are quarter notes; the application converts to ticks.
 */
public final class ChatSectionGenerator implements SectionGenerator {
    /** The standing instruction of the call. */
    public static final String INSTRUCTION = "You continue an existing piece of original instrumental music, one part "
            + "and one section at a time. Return valid JSON only, no Markdown.";
    private final ChatModel model;
    private final int maxTokens;

    public ChatSectionGenerator(ChatModel model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /** The prompt for one part and one frame. */
    public static String prompt(ContinuationTask task, ContinuationPlan plan, ContinuationVoice voice, VoiceFrame frame,
                                String connection) {
        TimeMap time = task.time();
        TimeMap.Meter meter = time.meterAt(frame.startTick());
        String quarters = ContextBuilder.quarters(meter.barTicks());
        ContinuationRequest request = task.request();
        boolean transition = SectionGenerator.TRANSITION.equals(frame.sectionId());
        StringBuilder prompt = new StringBuilder(task.context().text());
        prompt.append(task.inserts() ? "\n# PLAN OF THE INSERTED BARS (bars " : "\n# PLAN OF THE CONTINUATION (bars ")
                .append(task.firstNewBar() + 1).append('-')
                .append(task.firstNewBar() + request.bars()).append(")\n");
        for (ContinuationPlan.PlannedSection section : plan.sections()) {
            int first = task.firstNewBar() + plan.firstBarOf(section) + 1;
            prompt.append("section ").append(section.key()).append(" \"").append(section.name()).append("\" bars ")
                    .append(first).append('-').append(first + section.bars() - 1);
            if (section.function() != null) {
                prompt.append(" function=").append(section.function());
            }
            prompt.append(" intensity=").append(ContextBuilder.number(section.intensity())).append(" parts=")
                    .append(String.join(",", section.parts())).append('\n');
            if (!section.idea().isEmpty()) {
                prompt.append("  idea: ").append(section.idea()).append('\n');
            }
        }
        for (ContinuationPlan.NewPart part : plan.newParts()) {
            prompt.append("new part ").append(part.key()).append(" \"").append(part.name()).append("\" ")
                    .append(part.instrument()).append(" role=").append(part.role()).append(" program=")
                    .append(part.program()).append(part.idea().isEmpty() ? "" : ": " + part.idea()).append('\n');
        }
        if (!plan.motifs().isEmpty()) {
            prompt.append("motifs: ").append(plan.motifs()).append('\n');
        }
        if (!plan.arc().isEmpty()) {
            prompt.append("arc: ").append(plan.arc()).append('\n');
        }
        prompt.append("\n# TASK\n");
        if (transition) {
            prompt.append("Rewrite the last ").append(frame.bars()).append(" bars of the original for ONE part (bars ")
                    .append(frame.firstBar() + 1).append('-').append(frame.firstBar() + frame.bars()).append(" of the "
                            + "piece), so that it leads naturally into the continuation. Its original notes there are "
                            + "listed above; keep their character and their connection to what comes before. Every "
                            + "other part and every earlier bar stays exactly as given.\n");
        } else {
            prompt.append("Write the notes of ONE part for one section of the ")
                    .append(task.inserts() ? "inserted bars" : "continuation").append(". The other parts are "
                            + "written after the same plan; do not write, repeat or change their notes.\n");
        }
        prompt.append("Part: ").append(voice.describe()).append('\n');
        if (voice.newPart()) {
            prompt.append("This part is a NEW instrument in the piece").append(voice.idea().isEmpty() ? "" : ": "
                    + voice.idea()).append('\n');
        }
        prompt.append(transition ? "Bars: " : "Section: " + frame.sectionId() + " \"" + frame.name() + "\", bars ")
                .append(frame.firstBar() + 1).append('-')
                .append(frame.firstBar() + frame.bars()).append(" of the piece = ").append(frame.bars()).append(" bars of ")
                .append(meter.numerator()).append('/').append(meter.denominator()).append(" with ").append(quarters)
                .append(" quarter notes per bar\n");
        if (frame.intensity() != null) {
            prompt.append("Intensity: ").append(ContextBuilder.number(frame.intensity())).append(" (0 = very quiet and "
                    + "sparse, 1 = full and dense; let it shape density, register and velocity)\n");
            prompt.append(Dramaturgy.guidance(frame.intensity(), voice.role())).append('\n');
        }
        if (!frame.harmony().isEmpty()) {
            prompt.append("Chords by bar: ").append(String.join(" | ", frame.harmony())).append('\n');
        }
        if (frame.instruction() != null && !frame.instruction().isEmpty()) {
            prompt.append(transition ? "Leads into: " : "Idea: ").append(frame.instruction()).append('\n');
        }
        prompt.append(task.inserts() ? "Kind of inserted passage: " : "Kind of continuation: ")
                .append(request.preset().brief()).append('\n');
        if (!request.instruction().isEmpty()) {
            prompt.append("User instruction: ").append(request.instruction()).append('\n');
        }
        if (frame.endTick() == task.newEnd() && task.inserts()) {
            prompt.append("This is the last inserted section: lead seamlessly into bar ").append(task.barAfter())
                    .append(", where this part goes on as listed under AFTER THE INSERTED BARS (or rests there if it "
                            + "is not listed); no notes held over into that bar.\n");
        } else if (frame.endTick() == task.newEnd()) {
            prompt.append("This is the last section. Ending: ").append(request.ending().brief()).append('\n');
        }
        prompt.append(connection.isEmpty() ? "The part rests before these bars.\n"
                : "The part played just before (bar beat dur pitch vel, bars of the piece):\n" + connection
                + "Continue smoothly from there.\n");
        prompt.append(ChatPartGenerator.rules(voice.role())).append('\n');
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
    public GeneratedNotes generate(ContinuationTask task, ContinuationPlan plan, ContinuationVoice voice,
                                   VoiceFrame frame, String connection, Path cacheDirectory) throws IOException {
        String prompt = prompt(task, plan, voice, frame, connection);
        Path cache = cacheDirectory.resolve(voice.key() + "-" + frame.sectionId() + "-"
                + ContextBuilder.sha256(prompt).substring(0, 12) + ".json");
        return GeneratedNotes.read(model.generate(INSTRUCTION, prompt, maxTokens, cache), voice.key(), frame.sectionId());
    }
}
