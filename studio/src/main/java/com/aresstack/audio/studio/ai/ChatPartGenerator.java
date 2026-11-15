package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.TimeMap;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the notes of a new voice for one frame with one call of a {@link ChatModel}. Bars count
 * from the start of the frame, beats and durations are quarter notes (architecture review E4: the
 * model counts relative to the section, the application converts to ticks).
 */
public final class ChatPartGenerator implements PartGenerator {
    /** The standing instruction of the call. */
    public static final String INSTRUCTION = "You add one new voice to an existing piece of original instrumental music. "
            + "Return valid JSON only, no Markdown.";
    private final ChatModel model;
    private final int maxTokens;

    public ChatPartGenerator(ChatModel model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /** The prompt for one frame: the context, the plan, the connection, then the task. */
    public static String prompt(VoiceTask task, VoicePlan plan, VoiceFrame frame, String connection) {
        TimeMap time = task.time();
        long barTicks = time.meterAt(frame.startTick()).barTicks();
        String quarters = ContextBuilder.quarters(barTicks);
        TimeMap.Meter meter = time.meterAt(frame.startTick());
        VoicePlan.SectionPlan here = plan.section(frame.sectionId());
        StringBuilder prompt = new StringBuilder(task.context().text());
        prompt.append("\n# TASK\nWrite the notes of ONE NEW part for one section. Do not write, repeat or change notes "
                + "of any other part; they stay exactly as given above.\n");
        prompt.append("New part: ").append(task.target()).append('\n');
        prompt.append("Section: ").append(frame.sectionId()).append(" \"").append(frame.name()).append("\", bars ")
                .append(frame.firstBar() + 1).append('-').append(frame.firstBar() + frame.bars()).append(" of the piece = ")
                .append(frame.bars()).append(" bars of ").append(meter.numerator()).append('/').append(meter.denominator())
                .append(" with ").append(quarters).append(" quarter notes per bar\n");
        if (!frame.harmony().isEmpty()) {
            prompt.append("Chords by bar of this section: ").append(String.join(" | ", frame.harmony())).append('\n');
        }
        prompt.append("Register: ").append(ContextBuilder.pitch(plan.low())).append('/').append(plan.low()).append(" to ")
                .append(ContextBuilder.pitch(plan.high())).append('/').append(plan.high()).append('\n');
        if (here != null && !here.idea().isEmpty()) {
            prompt.append("Plan for this section: ").append(here.idea()).append('\n');
        }
        if (!plan.motifs().isEmpty()) {
            prompt.append("Motifs: ").append(plan.motifs()).append('\n');
        }
        if (!task.motifRule().isEmpty()) {
            prompt.append(task.motifRule()).append('\n');
        }
        if (!task.request().instruction().isEmpty()) {
            prompt.append("User instruction: ").append(task.request().instruction()).append('\n');
        }
        prompt.append(connection.isEmpty() ? "This is the first section the new voice plays in.\n"
                : "The new voice ended its previous section with (bar beat dur pitch vel, bars of the piece):\n"
                + connection + "Continue smoothly from there.\n");
        prompt.append(rules(task.request().role())).append('\n');
        prompt.append(GeneratedNotes.articulationRule()).append('\n');
        String voices = voiceRule(task);
        if (!voices.isEmpty()) {
            prompt.append(voices).append('\n');
        }
        prompt.append("Return exactly one JSON object: {\"part\":\"").append(task.partId()).append("\",\"section\":\"")
                .append(frame.sectionId()).append("\",\"notes\":[{\"bar\":1,\"beat\":0,\"duration\":1,\"pitch\":")
                .append((plan.low() + plan.high()) / 2).append(",\"velocity\":90}]}. ")
                .append("bar counts from 1 at the start of THIS section up to ").append(frame.bars())
                .append("; beat is the offset within the bar in quarter notes, from 0 to below ").append(quarters)
                .append(", on multiples of 0.25; duration is in quarter notes, at least 0.25; every note ends by the end "
                        + "of the section. At most ").append(GeneratedNotes.MAX_NOTES)
                .append(" notes. List the notes explicitly.").append(task.take());
        return prompt.toString();
    }

    @Override
    public GeneratedNotes generate(VoiceTask task, VoicePlan plan, VoiceFrame frame, String connection,
                                   Path cacheDirectory) throws IOException {
        String prompt = prompt(task, plan, frame, connection);
        Path cache = cacheDirectory.resolve(task.partId() + "-" + frame.sectionId() + "-"
                + ContextBuilder.sha256(prompt).substring(0, 12) + ".json");
        return GeneratedNotes.ask(problem -> model.generate(INSTRUCTION, prompt + GeneratedNotes.again(problem),
                maxTokens, cache, Listening.current()), cache,
                task.partId(),
                frame.sectionId());
    }

    /**
     * What the prompt asks about voices (work package S1, as the composer asks it): on a grand staff
     * the hand of every note, on a harmony or texture part the line of each note if it plays several.
     */
    static String voiceRule(VoiceTask task) {
        if (task.grand()) {
            return "This instrument is written on a grand staff: give every note a field voice, \"rh\" for the right "
                    + "hand (treble staff) or \"lh\" for the left hand (bass staff); a hand that plays two independent "
                    + "lines at once uses \"rh2\" or \"lh2\" for its second line.";
        }
        String role = task.request().role();
        if (role == null || "harmony".equals(role) || "texture".equals(role)) {
            return "If the part plays independent lines at the same time, give every note a field voice: \"1\" for the "
                    + "top line, \"2\" for the next one, and so on.";
        }
        return "";
    }

    /** What a part of a role should do, in words for the model; a part without a role counts as texture. */
    static String rules(String role) {
        return switch (role == null ? "texture" : role) {
            case "melody" -> "Role melody: a singable line or counter-melody that answers the existing melody instead "
                    + "of doubling it, with rests where the other melody is busy.";
            case "harmony" -> "Role harmony: chord tones with smooth voice leading, supporting the given chords.";
            case "bass" -> "Role bass: roots, fifths and passing notes that lock with the existing rhythm.";
            case "drums" -> "Role drums: General MIDI drum sounds (36 kick, 38 snare, 42 closed hi-hat, 46 open "
                    + "hi-hat, 49 crash, 51 ride); no pitched notes.";
            default -> "Role texture: sparse colour, long notes or figures that leave room for the other parts.";
        };
    }
}
