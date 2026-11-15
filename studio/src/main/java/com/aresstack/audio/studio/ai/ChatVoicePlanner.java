package com.aresstack.audio.studio.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The planning step of a new voice, one call of a {@link ChatModel}: where the voice plays, in
 * which register, how it takes up motifs. The answer is checked and completed: an unknown frame
 * is ignored, a frame the plan leaves out plays, a register outside the instrument is limited to
 * it, a plan for another part is refused.
 */
public final class ChatVoicePlanner implements VoicePlanner {
    /** The standing instruction of the call. */
    public static final String INSTRUCTION = "You are the arranger of an existing piece. Return valid JSON only, no Markdown.";
    private final ChatModel model;
    private final int maxTokens;

    public ChatVoicePlanner(ChatModel model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /** The prompt of the planning step: the context, then the task. */
    public static String prompt(VoiceTask task) {
        StringBuilder frames = new StringBuilder();
        for (VoiceFrame frame : task.frames()) {
            frames.append(frames.length() == 0 ? "" : ", ").append(frame.sectionId()).append(" (bars ")
                    .append(frame.firstBar() + 1).append('-').append(frame.firstBar() + frame.bars()).append(')');
        }
        return task.context().text()
                + "\n# TASK\nPlan ONE NEW voice to add to this piece. Every other part stays exactly as it is; "
                + "the new voice must fit their harmony, rhythm and motifs and fulfil its role.\n"
                + "New part: " + task.target() + "\n"
                + "Sections it may play in: " + frames + "\n"
                + (task.request().instruction().isEmpty() ? "" : "User instruction: " + task.request().instruction() + "\n")
                + (task.motifRule().isEmpty() ? "" : task.motifRule() + "\n")
                + "Return one JSON object: {\"part\":\"" + task.partId() + "\",\"register\":{\"low\":<midi>,\"high\":<midi>},"
                + "\"sections\":[{\"id\":\"<section id>\",\"plays\":true|false,\"idea\":\"<what the voice does there>\"}],"
                + "\"motifs\":\"<which motifs it takes up and how>\"}. List each of the sections above exactly once. "
                + "No notes yet." + task.take();
    }

    @Override
    public VoicePlan plan(VoiceTask task, Path cacheDirectory) throws IOException {
        String prompt = prompt(task);
        Path cache = cacheDirectory.resolve(task.partId() + "-plan-" + ContextBuilder.sha256(prompt).substring(0, 12)
                + ".json");
        return read(model.generate(INSTRUCTION, prompt, maxTokens, cache), task);
    }

    /** Reads and completes a plan; see the class comment. */
    public static VoicePlan read(JsonObject answer, VoiceTask task) throws IOException {
        try {
            JsonElement part = answer.get("part");
            if (part == null || part.isJsonNull()) {
                throw new IOException("Der Plan nennt keinen Part.");
            }
            if (!task.partId().equals(part.getAsString())) {
                throw new ForeignPartException("Plan für fremden Part \"" + part.getAsString() + "\" abgelehnt.");
            }
            int low = task.range().low();
            int high = task.range().high();
            if (answer.has("register") && answer.get("register").isJsonObject()) {
                JsonObject register = answer.getAsJsonObject("register");
                int wantedLow = task.range().clamp(register.get("low").getAsInt());
                int wantedHigh = task.range().clamp(register.get("high").getAsInt());
                if (wantedHigh > wantedLow) {
                    low = wantedLow;
                    high = wantedHigh;
                }
            }
            Map<String, VoicePlan.SectionPlan> given = new LinkedHashMap<>();
            JsonArray sections = answer.has("sections") && answer.get("sections").isJsonArray()
                    ? answer.getAsJsonArray("sections") : new JsonArray();
            for (JsonElement element : sections) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject section = element.getAsJsonObject();
                String id = section.has("id") ? section.get("id").getAsString() : null;
                boolean plays = !section.has("plays") || section.get("plays").getAsBoolean();
                String idea = section.has("idea") && !section.get("idea").isJsonNull()
                        ? section.get("idea").getAsString() : "";
                if (id != null && !given.containsKey(id)) {
                    given.put(id, new VoicePlan.SectionPlan(id, plays, idea));
                }
            }
            List<VoicePlan.SectionPlan> plans = new ArrayList<>();
            for (VoiceFrame frame : task.frames()) {
                plans.add(given.getOrDefault(frame.sectionId(),
                        new VoicePlan.SectionPlan(frame.sectionId(), true, task.request().instruction())));
            }
            String motifs = answer.has("motifs") && !answer.get("motifs").isJsonNull()
                    ? answer.get("motifs").getAsString() : "";
            VoicePlan plan = new VoicePlan(low, high, plans, motifs);
            if (!plan.playsAnywhere()) {
                throw new IOException("Der Plan lässt die neue Stimme in keinem Abschnitt spielen.");
            }
            return plan;
        } catch (RuntimeException malformed) {
            throw new IOException("Der Plan für " + task.partId() + " ist fehlerhaft: " + malformed.getMessage(), malformed);
        }
    }
}
