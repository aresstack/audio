package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.TimeMap;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the notes of one part for one frame of a region with one call of a {@link ChatModel}
 * (work package C2). The model sees the piece around the region, what the part plays before and
 * after it, and for a variation the old notes; it counts bars from the start of the frame, and
 * the application converts to ticks (architecture review E4).
 */
public final class ChatRegionGenerator implements RegionGenerator {
    /** The standing instruction of the call. */
    public static final String INSTRUCTION = "You rewrite one passage of one part in an existing piece of original "
            + "instrumental music. Return valid JSON only, no Markdown.";
    private final ChatModel model;
    private final int maxTokens;

    public ChatRegionGenerator(ChatModel model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /** The prompt for one frame: the context, then the task with what comes before and after. */
    public static String prompt(RegionTask task, VoiceFrame frame, boolean last, String connection) {
        TimeMap time = task.time();
        TimeMap.Meter meter = time.meterAt(frame.startTick());
        String quarters = ContextBuilder.quarters(meter.barTicks());
        RegionRequest request = task.request();
        boolean variate = request.mode() == RegionRequest.Mode.VARIATE;
        int regionFirst = time.barOf(request.selection().fromTick()) + 1;
        int regionLast = time.barsCovering(request.selection().toTick());
        StringBuilder prompt = new StringBuilder(task.context().text());
        prompt.append("\n# TASK\n");
        prompt.append(variate ? "Write a VARIATION of" : "Write NEW notes for").append(" ONE part in the REGION, bars ")
                .append(regionFirst).append('-').append(regionLast).append(" of the piece. Everything outside the region, "
                        + "and every other part, stays exactly as given above: do not write, repeat or change it.\n");
        prompt.append("Part: ").append(task.target()).append('\n');
        prompt.append("This call: section ").append(frame.sectionId()).append(" \"").append(frame.name())
                .append("\", bars ").append(frame.firstBar() + 1).append('-').append(frame.firstBar() + frame.bars())
                .append(" of the piece = ").append(frame.bars()).append(" bars of ").append(meter.numerator()).append('/')
                .append(meter.denominator()).append(" with ").append(quarters).append(" quarter notes per bar\n");
        if (!frame.harmony().isEmpty()) {
            prompt.append("Chords by bar of these bars: ").append(String.join(" | ", frame.harmony())).append('\n');
        }
        prompt.append(connection.isEmpty() ? "Before these bars the part rests.\n"
                : "Before these bars the part plays (bar beat dur pitch vel, bars of the piece):\n" + connection
                + "Continue smoothly from there.\n");
        if (!task.held().isEmpty()) {
            prompt.append("Still sounding into the region from before; these notes stay, do not write them again:\n")
                    .append(task.held());
        }
        if (last) {
            prompt.append(task.after().isEmpty() ? "After the region the part rests; end the passage so that this fits.\n"
                    : "After the region the part continues with (bars of the piece):\n" + task.after()
                    + "End the passage so that it leads naturally into this.\n");
        } else {
            prompt.append("The region continues after these bars; another call writes the next bars.\n");
        }
        if (variate) {
            String template = task.template(frame);
            prompt.append(template.isEmpty() ? "Template: the part rests in these bars so far.\n"
                    : "Template, the notes of the part here so far (bar beat dur pitch vel, bars from 1 at the start of "
                    + "these bars). Keep its idea recognisable and change it as asked:\n" + template);
        } else {
            prompt.append("The old notes of the part here are discarded; write fresh material that fits the piece.\n");
        }
        if (!request.preset().rule().isEmpty()) {
            prompt.append(request.preset().rule()).append('\n');
        }
        if (!request.instruction().isEmpty()) {
            prompt.append("User instruction: ").append(request.instruction()).append('\n');
        }
        prompt.append(ChatPartGenerator.rules(task.part().role() == null ? "" : task.part().role())).append('\n');
        prompt.append(GeneratedNotes.articulationRule()).append('\n');
        if (task.plansPerformance(frame)) {
            prompt.append(ContinuationPerformance.request("passage of the parts " + String.join(", ", task.performs())
                    + ", bars", task.firstBar(), task.lastBar(), "after the passage the piece goes on in its own tempo "
                    + "and loudness"));
        }
        int middle = (task.range().low() + task.range().high()) / 2;
        prompt.append("Return exactly one JSON object: {\"part\":\"").append(task.part().id()).append("\",\"section\":\"")
                .append(frame.sectionId()).append("\",\"notes\":[{\"bar\":1,\"beat\":0,\"duration\":1,\"pitch\":")
                .append(middle).append(",\"velocity\":80}]}. ")
                .append("bar counts from 1 at the start of THESE bars up to ").append(frame.bars())
                .append("; beat is the offset within the bar in quarter notes, from 0 to below ").append(quarters)
                .append(", on multiples of 0.25; duration is in quarter notes, at least 0.25; every note ends by the end "
                        + "of these bars. An empty list means the part rests here. At most ")
                .append(GeneratedNotes.MAX_NOTES).append(" notes. List the notes explicitly.").append(task.take());
        return prompt.toString();
    }

    @Override
    public GeneratedNotes generate(RegionTask task, VoiceFrame frame, boolean last, String connection,
                                   Path cacheDirectory) throws IOException {
        String prompt = prompt(task, frame, last, connection);
        Path cache = cacheDirectory.resolve(task.request().mode().id() + "-" + task.part().id() + "-" + frame.sectionId()
                + "-" + ContextBuilder.sha256(prompt).substring(0, 12) + ".json");
        return GeneratedNotes.ask(problem -> model.generate(INSTRUCTION, prompt + GeneratedNotes.again(problem),
                maxTokens, cache, Listening.current()), cache,
                task.part().id(),
                frame.sectionId());
    }
}
