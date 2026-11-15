package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Chord;
import com.aresstack.audio.studio.ai.ChatModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers the composer's prompts as a model would, without network (architecture review 7.1):
 * a plan with the numbers it was asked for and the given chords, and for each track and section
 * notes on the tones of the chords the prompt names. Keeps its answers in the cache files, as the
 * real client does, and remembers every prompt.
 */
final class FakeChatModel implements ChatModel {
    private final List<String> progression;
    final List<String> prompts = Collections.synchronizedList(new ArrayList<String>());

    FakeChatModel(String... progression) {
        this.progression = Arrays.asList(progression);
    }

    @Override
    public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException {
        if (Files.isRegularFile(cache)) {
            return JsonParser.parseString(new String(Files.readAllBytes(cache), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        prompts.add(prompt);
        JsonObject answer = prompt.contains("No notes yet") ? plan(prompt) : notes(prompt);
        Files.write(cache, new Gson().toJson(answer).getBytes(StandardCharsets.UTF_8));
        return answer;
    }

    @Override
    public void cancel() {
    }

    private JsonObject plan(String prompt) {
        Matcher m = Pattern.compile("tempoBpm=(\\d+), key=\"([^\"]+)\", timeSignature=\"([^\"]+)\", bars=(\\d+)")
                .matcher(prompt);
        if (!m.find()) {
            throw new IllegalStateException("No spec in the plan prompt: " + prompt);
        }
        JsonObject plan = new JsonObject();
        plan.addProperty("title", "Fake");
        plan.addProperty("mood", "misty");
        plan.addProperty("tempoBpm", Integer.parseInt(m.group(1)));
        plan.addProperty("key", m.group(2));
        plan.addProperty("timeSignature", m.group(3));
        int bars = Integer.parseInt(m.group(4));
        plan.addProperty("bars", bars);
        JsonArray chords = new JsonArray();
        for (int bar = 0; bar < bars; bar++) {
            chords.add(progression.get(bar % progression.size()));
        }
        plan.add("chords", chords);
        plan.addProperty("arrangementNotes", "Melody over held chords, bass on the roots, a steady beat.");
        return plan;
    }

    private static String group(String prompt, String regex) {
        Matcher m = Pattern.compile(regex).matcher(prompt);
        if (!m.find()) {
            throw new IllegalStateException("Not in the prompt: " + regex);
        }
        return m.group(1);
    }

    private static JsonObject notes(String prompt) {
        String part = group(prompt, "Set part=\"([a-z0-9_-]+)\"");
        int program = Integer.parseInt(group(prompt, "and program=(\\d+)\\."));
        String role = group(prompt, "role=([a-z]+);");
        double beats = Double.parseDouble(group(prompt, "0 <= beat < ([0-9.]+),"));
        Matcher perBar = Pattern.compile("A bar has ([0-9.]+) quarter-note beats").matcher(prompt);
        double bar = perBar.find() ? Double.parseDouble(perBar.group(1)) : 4;
        String[] chords = group(prompt, "chords by bar: (.*?)\\. ").split(" \\| ");
        JsonArray notes = new JsonArray();
        for (int b = 0; b < chords.length; b++) {
            int[] tones = Chord.parse(chords[b]).tones();
            double start = b * bar;
            if ("drums".equals(role)) {
                add(notes, start, 0.5, 36, 90);
                if (bar >= 3) {
                    add(notes, start + 2, 0.5, 38, 80);
                }
            } else if ("harmony".equals(role)) {
                for (int tone : tones) {
                    add(notes, start, bar, place(tone, 55), 60);
                }
            } else if ("bass".equals(role)) {
                add(notes, start, Math.min(4, bar), place(tones[0], 40), 80);
            } else {
                for (int i = 0; i < (int) bar; i++) {
                    add(notes, start + i, 1, place(tones[i % tones.length], 67), 75);
                }
            }
        }
        if (notes.size() > 256 || beats != chords.length * bar) {
            throw new IllegalStateException("Prompt and answer do not fit: " + beats + " beats");
        }
        JsonObject answer = new JsonObject();
        answer.addProperty("part", part);
        answer.addProperty("program", program);
        answer.add("notes", notes);
        return answer;
    }

    /** The pitch of a pitch class nearest above a lower bound. */
    private static int place(int pitchClass, int from) {
        int pitch = from;
        while (Math.floorMod(pitch, 12) != pitchClass) {
            pitch++;
        }
        return pitch;
    }

    private static void add(JsonArray notes, double beat, double duration, int pitch, int velocity) {
        JsonObject note = new JsonObject();
        note.addProperty("beat", beat);
        note.addProperty("duration", duration);
        note.addProperty("pitch", pitch);
        note.addProperty("velocity", velocity);
        notes.add(note);
    }
}
