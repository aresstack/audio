package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Verify all five cached model stages without consuming KIPITZ tokens. */
public final class GemmaMusicComposerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void composesCachedTracksAndRepairsOnlyIncorrectChordTones() throws Exception {
        Path output = folder.newFolder("composition").toPath();
        writePlan(output);
        writePart(output, "melody", 10, new double[]{0, 4, 8, 12, 16, 20, 24, 28},
                new int[]{72, 72, 72, 72, 72, 72, 72, 72});
        // Keep a cached C-major note ending beyond beat 32 to reproduce the user's crash.
        Path originalMelody = output.resolve("melody.json");
        JsonObject melody = new com.google.gson.JsonParser().parse(
                new String(Files.readAllBytes(originalMelody), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject overflowingNote = new JsonObject();
        overflowingNote.addProperty("beat", 29.5);
        overflowingNote.addProperty("duration", 4.0);
        overflowingNote.addProperty("pitch", 84);
        overflowingNote.addProperty("velocity", 70);
        melody.getAsJsonArray("notes").add(overflowingNote);
        save(originalMelody, melody);
        String unchangedMelody = new String(Files.readAllBytes(originalMelody), StandardCharsets.UTF_8);

        writePart(output, "harmony", 48, new double[]{0, 4, 8, 12, 16, 20, 24, 28},
                new int[]{52, 52, 52, 52, 52, 52, 52, 52});
        writePart(output, "bass", 32, new double[]{0, 4, 8, 12, 16, 20, 24, 27, 31.75},
                new int[]{48, 48, 48, 48, 48, 48, 48, 45, 48});
        writePart(output, "drums", 0, new double[]{0, 4, 8, 12, 16, 20, 24, 28},
                new int[]{36, 38, 42, 46, 36, 38, 42, 46});

        KipitzChatClient offline = new KipitzChatClient(
                "http://127.0.0.1:1/v1/chat/completions", "", "google/gemma-4-31B-it", "", 8080);
        Path arrangement = new GemmaMusicComposer(offline).compose(
                "Offline fixture", output, new GemmaMusicComposer.Progress() {
                    @Override
                    public void update(String message) {
                    }
                });

        String content = new String(Files.readAllBytes(arrangement), StandardCharsets.UTF_8);
        Assert.assertTrue(content.contains("tempo\t108"));
        Assert.assertTrue(content.contains("note\tbass\t12960\t480\t45\t70"));
        Assert.assertTrue(content.contains("note\tbass\t15240\t120\t47\t70"));
        Assert.assertTrue(content.contains("track\tdrums\t0\t9"));
        Assert.assertTrue(content.contains("note\tmelody\t14160\t1200\t84\t70"));
        // F0 safety net: the whole arrangement the composer writes from these answers, not only parts of it.
        com.aresstack.audio.studio.golden.Golden.assertMatches("compose/offline-fixture.tsv", content);
        String corrections = new String(Files.readAllBytes(
                output.resolve("note-corrections.tsv")), StandardCharsets.UTF_8);
        Assert.assertTrue(corrections.contains("melody\t9\tduration\t4.0\t2.5"));
        Assert.assertEquals(unchangedMelody,
                new String(Files.readAllBytes(originalMelody), StandardCharsets.UTF_8));
        try (java.util.stream.Stream<Path> generatedFiles = Files.list(output)) {
            Assert.assertEquals(7L, generatedFiles.count());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooSmallModelTokenLimit() {
        new GemmaMusicComposer(null, 255);
    }

    @Test(expected = IOException.class)
    public void refusesToGenerateMissingPartWithoutCredentials() throws Exception {
        Path output = folder.newFolder("missing").toPath();
        writePlan(output);
        KipitzChatClient offline = new KipitzChatClient(
                "http://127.0.0.1:1/v1/chat/completions", "", "google/gemma-4-31B-it", "", 8080);
        new GemmaMusicComposer(offline).compose("No network", output,
                new GemmaMusicComposer.Progress() {
                    @Override
                    public void update(String message) {
                    }
                });
    }

    private static void writePlan(Path directory) throws Exception {
        JsonObject plan = new JsonObject();
        plan.addProperty("title", "Fixture");
        plan.addProperty("mood", "cozy");
        plan.addProperty("tempoBpm", 108);
        plan.addProperty("bars", 8);
        plan.addProperty("key", "C major");
        plan.addProperty("timeSignature", "4/4");
        plan.addProperty("arrangementNotes", "Original melody with four coordinated parts.");
        JsonArray chords = new JsonArray();
        for (String chord : Arrays.asList("C", "G", "Am", "F", "C", "G", "F", "G")) {
            chords.add(chord);
        }
        plan.add("chords", chords);
        save(directory.resolve("composition-plan.json"), plan);
    }

    private static void writePart(Path directory, String part, int program,
                                  double[] beats, int[] pitches) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("part", part);
        result.addProperty("program", program);
        JsonArray notes = new JsonArray();
        for (int i = 0; i < beats.length; i++) {
            JsonObject note = new JsonObject();
            note.addProperty("beat", beats[i]);
            note.addProperty("duration", beats[i] == 31.75 ? 0.25 : 1.0);
            note.addProperty("pitch", pitches[i]);
            note.addProperty("velocity", 70);
            notes.add(note);
        }
        result.add("notes", notes);
        save(directory.resolve(part + ".json"), result);
    }

    private static void save(Path file, JsonObject content) throws Exception {
        Files.write(file, new Gson().toJson(content).getBytes(StandardCharsets.UTF_8));
    }
}
