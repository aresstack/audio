package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Generate one shared arrangement plan and four compatible instrument parts. */
public final class GemmaMusicComposer {
    public interface Progress {
        void update(String message);
    }

    private static final String[] PARTS = {"melody", "harmony", "bass", "drums"};
    private static final String[] CHORDS = {"C", "G", "Am", "F", "C", "G", "F", "G"};
    private static final int[] PROGRAMS = {10, 48, 32, 0};
    private static final int[] CHANNELS = {0, 1, 2, 9};
    private final KipitzChatClient client;

    public GemmaMusicComposer(KipitzChatClient client) {
        this.client = client;
    }

    public Path compose(String description, Path directory, Progress progress) throws IOException {
        Files.createDirectories(directory);
        progress.update("Composition plan ...");
        String planPrompt = "Compose ORIGINAL short cozy instrumental game music for: " + description
                + "\nReturn JSON with title, mood, tempoBpm=108, key=\"C major\", timeSignature=\"4/4\", "
                + "bars=8, chords=[\"C\",\"G\",\"Am\",\"F\",\"C\",\"G\",\"F\",\"G\"], arrangementNotes."
                + "\nKeep these exact musical constraints. No notes yet. Return only JSON.";
        JsonObject plan = client.generate("Return valid JSON only, with no Markdown.", planPrompt,
                2048, directory.resolve("composition-plan.json"));
        validatePlan(plan);

        List<String> arrangement = new ArrayList<String>();
        arrangement.add("tempo\t108");
        CompositionNoteNormalizer normalizer = new CompositionNoteNormalizer(progress);
        for (int index = 0; index < PARTS.length; index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Composition cancelled.");
            }
            String partName = PARTS[index];
            progress.update("Composing " + partName + " ...");
            String prompt = "Compose the " + partName + " track for an ORIGINAL 8-bar instrumental song. "
                    + "4/4, 108 BPM, C major, 32 total quarter-note beats, chords by bar: "
                    + "C | G | Am | F | C | G | F | G. "
                    + "Arrangement: " + plan.get("arrangementNotes").getAsString()
                    + "\n" + rules(partName)
                    + "\nReturn exactly one JSON object with keys part, program, notes. "
                    + "Set part=\"" + partName + "\" and program=" + PROGRAMS[index] + ". "
                    + "Every note must have numeric beat,duration,pitch,velocity; beat in [0,32), "
                    + "duration in [0.25,4], beat+duration<=32. "
                    + "Align all beats to multiples of 0.25. All notes must use absolute song beats. "
                    + "Write every note explicitly; no Markdown.";
            JsonObject part = client.generate("Compose original instrumental music. Return valid JSON only.",
                    prompt, 8192, directory.resolve(partName + ".json"));
            if (!partName.equals(part.get("part").getAsString())
                    || part.get("program").getAsInt() != PROGRAMS[index]) {
                throw new IOException("Unexpected part identity or program: " + partName);
            }
            JsonArray notes = part.getAsJsonArray("notes");
            if (notes.size() < 8 || notes.size() > 90) {
                throw new IOException("Unsupported note count for " + partName + ": " + notes.size());
            }
            arrangement.add("track\t" + partName + "\t" + PROGRAMS[index] + "\t" + CHANNELS[index]);
            int accepted = 0;
            for (int noteIndex = 0; noteIndex < notes.size(); noteIndex++) {
                JsonElement element = notes.get(noteIndex);
                if (!element.isJsonObject()) {
                    progress.update("WARN: Skipped " + partName + " note " + (noteIndex + 1)
                            + ": not a JSON object");
                    continue;
                }
                CompositionNoteNormalizer.NormalizedNote note = normalizer.normalize(
                        element.getAsJsonObject(), partName, noteIndex + 1);
                if (note == null) {
                    continue;
                }
                arrangement.add("note\t" + partName + "\t" + note.startTick() + "\t"
                        + note.durationTicks() + "\t" + note.pitch() + "\t" + note.velocity());
                accepted++;
            }
            if (accepted == 0) {
                throw new IOException("No playable notes remain for " + partName
                        + ". Original model response is saved in " + partName + ".json.");
            }
            progress.update(partName + ": " + accepted + " playable of " + notes.size() + " notes");
        }
        Path output = directory.resolve("arrangement.tsv");
        Files.write(output, arrangement, StandardCharsets.UTF_8);
        Files.write(directory.resolve("note-corrections.tsv"),
                normalizer.corrections(), StandardCharsets.UTF_8);
        progress.update("Note corrections: " + normalizer.correctionCount()
                + " (see note-corrections.tsv). Original JSON cache preserved.");
        return output;
    }

    private static String rules(String part) {
        if ("melody".equals(part)) {
            return "Write 16-40 notes for a music box melody, MIDI pitches 60..88, C major scale. "
                    + "Create a question/answer motif that returns near bar 5.";
        }
        if ("harmony".equals(part)) {
            return "Write 20-32 notes for soft string ensemble chords, MIDI pitches 48..76. "
                    + "Use chord triads appropriate to each bar and elegant voice leading.";
        }
        if ("bass".equals(part)) {
            return "Write 12-28 acoustic bass notes, pitches 36..60, roots and fifths of active chords.";
        }
        return "Write 32-80 gentle hits using GM drum pitches 36 kick, 38 snare, 42 closed "
                + "hi-hat, 46 open hi-hat. End with a soft celebratory fill.";
    }

    private static void validatePlan(JsonObject plan) throws IOException {
        if (plan.get("tempoBpm").getAsInt() != 108 || plan.get("bars").getAsInt() != 8
                || !"C major".equals(plan.get("key").getAsString())
                || !"4/4".equals(plan.get("timeSignature").getAsString())) {
            throw new IOException("The model changed the locked tempo, key or duration.");
        }
        JsonArray chords = plan.getAsJsonArray("chords");
        if (chords.size() != CHORDS.length) {
            throw new IOException("Expected eight chords.");
        }
        for (int index = 0; index < CHORDS.length; index++) {
            if (!CHORDS[index].equals(chords.get(index).getAsString())) {
                throw new IOException("Unexpected chord at bar " + (index + 1));
            }
        }
    }

}
