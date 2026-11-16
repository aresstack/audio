package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The planning step of a continuation, one call of a {@link ChatModel}: sections with bars,
 * function, intensity, chords per bar and the parts that play, and the new instruments that come
 * in. The answer is read leniently and completed to a plan that covers exactly the new bars
 * ({@link ContinuationPlan#complete}); an answer without a usable section gives the plan of the
 * preset ({@link ContinuationPlan#fallback}). Every repair is recorded in the plan.
 */
public final class ChatContinuationPlanner implements ContinuationPlanner {
    /** The standing instruction of the call. */
    public static final String INSTRUCTION = "You are the composer and arranger planning the continuation of an "
            + "existing piece. Return valid JSON only, no Markdown.";
    private final ChatModel model;
    private final int maxTokens;

    public ChatContinuationPlanner(ChatModel model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /** The prompt of the planning step: the context, then the task. */
    public static String prompt(ContinuationTask task) {
        ContinuationRequest request = task.request();
        int first = task.firstNewBar() + 1;
        int last = task.firstNewBar() + request.bars();
        StringBuilder prompt = new StringBuilder(task.context().text());
        if (task.inserts()) {
            prompt.append("\n# TASK\nPlan ").append(request.bars()).append(" new bars to be INSERTED into this piece, "
                    + "bars ").append(first).append('-').append(last).append(" of the piece: they come after bar ")
                    .append(task.firstNewBar()).append(" of the original, and the original bars ").append(first)
                    .append('-').append(task.originalBars()).append(" follow them as bars ").append(task.barAfter())
                    .append('-').append(task.originalBars() + request.bars()).append(" (the context already shows "
                            + "the piece with this empty room). The inserted bars must grow out of the music before "
                            + "them and lead naturally into the music after them: its themes, motifs, harmony, "
                            + "rhythm and the way the parts complement each other.\n");
        } else {
            prompt.append("\n# TASK\nPlan the continuation of this piece: ").append(request.bars())
                    .append(" new bars, bars ").append(first).append('-').append(last).append(" of the piece (the "
                            + "original has ").append(task.firstNewBar()).append(" bars). The continuation must grow "
                            + "out of the existing music: its themes, motifs, harmony, rhythm and the way the parts "
                            + "complement each other.\n");
        }
        prompt.append(task.inserts() ? "Kind of inserted passage: " : "Kind of continuation: ")
                .append(request.preset().brief()).append('\n');
        if (!request.instruction().isEmpty()) {
            prompt.append("User instruction: ").append(request.instruction()).append('\n');
        }
        if (task.inserts()) {
            prompt.append("Ending: the last inserted bar leads seamlessly into bar ").append(task.barAfter())
                    .append(", where the original goes on; no final cadence unless the music after begins anew.\n");
        } else {
            prompt.append("Ending: ").append(request.ending().brief()).append('\n');
        }
        String kept = task.inserts() ? "every note before bar " + (task.firstNewBar() - request.tailBars() + 1)
                + " and from bar " + task.barAfter() + " on stays exactly as it is"
                : "everything before stays";
        if (task.revisesTail()) {
            prompt.append("Transition: the last ").append(request.tailBars()).append(" bars of the original (bars ")
                    .append(task.firstTailBar() + 1).append('-').append(task.firstNewBar()).append(") will be rewritten "
                            + "together with the new bars so that the join is natural; ").append(kept).append(".\n");
        } else if (task.inserts()) {
            prompt.append("Transition: ").append(kept).append("; the inserted bars join seamlessly at both ends.\n");
        } else {
            prompt.append("Transition: every note of bars 1-").append(task.firstNewBar()).append(" stays exactly as it "
                    + "is; the continuation joins seamlessly at bar ").append(first).append(".\n");
        }
        if (request.curve() != null) {
            prompt.append("Intensity curve given by the user from the first to the last new bar: ")
                    .append(request.curve()).append("; give every section the intensity of the curve at its "
                            + "place.\n");
        }
        prompt.append(Dramaturgy.ensembleRule(task.parts().size())).append('\n');
        prompt.append("Meter and key stay as they are; the tempo goes on as it is unless the performance below "
                + "changes it.\n");
        prompt.append("Parts that go on (use their ids):\n");
        for (Part part : task.parts()) {
            prompt.append("  ").append(ContinuationTask.describe(part)).append('\n');
        }
        if (request.newParts() > 0) {
            prompt.append("You may bring in up to ").append(request.newParts()).append(" NEW instruments where the "
                    + "dramaturgy needs them; name them new1, new2, ... and list them in newParts. Bring in none if the "
                    + "existing parts suffice.\n");
        } else {
            prompt.append("Do not add new instruments; newParts stays empty.\n");
        }
        prompt.append("Return one JSON object: {\"sections\":[{\"name\":\"<name>\",\"function\":\"<for example "
                + "variation, build, climax, outro>\",\"bars\":<n>,\"intensity\":<0 to 1>,\"chords\":[\"<one chord "
                + "symbol per bar>\"],\"idea\":\"<what happens musically>\",\"parts\":[\"<ids of the parts that play, "
                + "new keys included>\"]}],\"newParts\":[{\"key\":\"new1\",\"name\":\"<name>\",\"instrument\":\"<instrument>\","
                + "\"program\":<General MIDI program 0-127>,\"role\":\"melody|harmony|bass|drums|texture\",\"idea\":\"<its job>\"}],"
                + "\"motifs\":\"<which motifs of the piece come back and how>\",\"arc\":\"<the dramaturgy in one sentence>\"}. "
                + "The bars of all sections add up to exactly ").append(request.bars()).append(". Sections have 4 to ")
                .append(ContinuationPlan.MAX_SECTION_BARS).append(" bars. Chords are symbols such as C, Dm, G7, F#m, Bb. "
                        + "No notes yet.\n").append(ContinuationPerformance.request(task)).append(task.take());
        return prompt.toString();
    }

    @Override
    public ContinuationPlan plan(ContinuationTask task, Composition piece, Path cacheDirectory) throws IOException {
        String prompt = prompt(task);
        Path cache = cacheDirectory.resolve("plan-" + ContextBuilder.sha256(prompt).substring(0, 12) + ".json");
        try {
            return read(model.generate(INSTRUCTION, prompt, maxTokens, cache, Listening.current()), task, piece);
        } catch (RuntimeException unusable) {
            // A kept plan that does not fit (cut off, broken): put aside and planned once more.
            AnswerCache.discard(cache);
            try {
                return read(model.generate(INSTRUCTION, prompt, maxTokens, cache, Listening.current()), task, piece);
            } catch (RuntimeException again) {
                AnswerCache.discard(cache);
                throw new IOException("Der Plan der Fortsetzung ist unbrauchbar: " + again.getMessage(), again);
            }
        }
    }

    /** Reads and completes a planning answer; see the class comment. */
    public static ContinuationPlan read(JsonObject answer, ContinuationTask task, Composition piece) {
        List<String> repairs = new ArrayList<>();
        Map<String, String> renamed = new HashMap<>();
        List<ContinuationPlan.NewPart> newParts = newParts(answer, piece, repairs, renamed);
        List<ContinuationPlan.PlannedSection> sections = new ArrayList<>();
        int index = 0;
        for (JsonObject given : objects(answer, "sections")) {
            index++;
            String name = text(given, "name");
            String where = "Abschnitt " + (name == null ? index : name);
            Integer bars = integer(given, "bars");
            if (bars == null || bars < 1) {
                repairs.add(where + " ohne gültige Taktzahl verworfen.");
                continue;
            }
            Double intensity = number(given, "intensity");
            double level;
            if (intensity == null || !Double.isFinite(intensity)) {
                level = task.request().preset().startIntensity();
                repairs.add(where + ": keine Intensität, " + level + " angenommen.");
            } else {
                level = Math.max(0, Math.min(1, intensity));
                if (level != intensity) {
                    repairs.add(where + ": Intensität " + intensity + " auf " + level + " begrenzt.");
                }
            }
            List<String> chords = ContinuationPlan.chords(texts(given, "chords"), bars, where, repairs);
            List<String> parts = new ArrayList<>();
            for (String part : texts(given, "parts")) {
                parts.add(renamed.getOrDefault(part, part));
            }
            sections.add(new ContinuationPlan.PlannedSection("s" + (sections.size() + 1), name, text(given, "function"),
                    bars, level, chords, text(given, "idea"), parts));
        }
        ContinuationPerformance performance = ContinuationPerformance.read(answer, task, repairs);
        return ContinuationPlan.complete(sections, newParts, text(answer, "motifs"), text(answer, "arc"), task, piece,
                repairs).withPerformance(performance);
    }

    /**
     * The new instruments of a planning answer, renamed to new1, new2, ... in their order, skipping
     * keys that are ids of existing parts; an instrument without name or valid program is dropped,
     * an unknown role becomes texture. How the keys of the answer were renamed goes into
     * {@code renamed}; a key that is the id of an existing part keeps meaning that part.
     */
    static List<ContinuationPlan.NewPart> newParts(JsonObject answer, Composition piece, List<String> repairs,
                                                   Map<String, String> renamed) {
        List<ContinuationPlan.NewPart> newParts = new ArrayList<>();
        int skipped = 0;
        for (JsonObject given : objects(answer, "newParts")) {
            String name = text(given, "name");
            String key = text(given, "key");
            Integer program = integer(given, "program");
            if (name == null || name.isBlank() || program == null || program < 0 || program > 127) {
                repairs.add("Neues Instrument " + (name == null ? key : name) + " ohne Namen oder gültiges Programm "
                        + "verworfen.");
                if (key != null && !taken(piece, key)) {
                    renamed.put(key, "verworfen:" + key); // its mentions must not name a later new part
                }
                continue;
            }
            String role = text(given, "role");
            if (role == null || !Role.ALL.contains(role)) {
                repairs.add("Neues Instrument " + name + ": unbekannte Rolle " + role + ", als texture übernommen.");
                role = Role.TEXTURE;
            }
            String fixed = freeKey(newParts.size() + 1 + skipped);
            while (taken(piece, fixed)) {
                skipped++;
                fixed = freeKey(newParts.size() + 1 + skipped);
            }
            if (key != null && !taken(piece, key)) {
                renamed.put(key, fixed); // a key that is the id of an existing part keeps meaning that part
            }
            newParts.add(new ContinuationPlan.NewPart(fixed, name, text(given, "instrument"), program, role,
                    text(given, "idea")));
        }
        return newParts;
    }

    /** The key of the n-th new part: "new" and its number. */
    private static String freeKey(int number) {
        return "new" + number;
    }

    /** Whether a key is the id of an existing part of the piece: then no new part may have it. */
    private static boolean taken(Composition piece, String key) {
        return piece.part(key) != null;
    }

    static List<JsonObject> objects(JsonObject object, String name) {
        List<JsonObject> found = new ArrayList<>();
        JsonElement value = object.get(name);
        if (value != null && value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    found.add(element.getAsJsonObject());
                }
            }
        }
        return found;
    }

    static List<String> texts(JsonObject object, String name) {
        List<String> found = new ArrayList<>();
        JsonElement value = object.get(name);
        if (value != null && value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (JsonElement element : array) {
                found.add(element.isJsonPrimitive() ? element.getAsString() : null);
            }
        }
        return found;
    }

    static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    static Integer integer(JsonObject object, String name) {
        Double value = number(object, name);
        return value == null || value != Math.rint(value) || Math.abs(value) > 100_000 ? null : value.intValue();
    }

    static Double number(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException notANumber) {
            return null;
        }
    }
}
