package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/**
 * The PO findings on the steps the user takes on a saved piece: a revision that differs in the
 * mix alone, the demo loaded over an open model, and the answers of the AI after undo.
 */
public final class PieceStepsCarryStageDemoAndAnswersTest {
    private static final List<String> PIECE = Arrays.asList("tempo\t120",
            "track\tmelody\t10\t0\tmelody", "note\tmelody\t0\t480\t72\t80",
            "track\tbass\t32\t1\tbass", "note\tbass\t0\t960\t48\t70");
    private static final Path WINDOW = Paths.get("src", "main", "java", "com", "aresstack", "audio", "studio",
            "ui", "AudioStudioFrame.java");

    // ---- 1. A revision that differs in the mix alone ---------------------------------------------------------

    @Test
    public void aRevisionThatDiffersInTheMixAloneBringsItsStageBackAndIsMixedAgain() throws Exception {
        Path folder = piece();
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        ProjectSession session = model.session().get();
        SpatialSceneStore stages = new SpatialSceneStore();
        StageMixService mixing = new StageMixService();
        StageMixService.Piece tracks = StageMixService.Piece.of(session.composition());
        new PieceRendering(new InstrumentRegistry(folder.resolve("none.properties"))).render(session, null,
                com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL, "fluidsynth", "sfizz_render",
                new HashMap<String, Integer>(), message -> { });
        Revision original = session.revisions().get(0);
        SpatialScene first = mixing.scene(tracks, folder);
        double bassAtFirst = first.placement("bass").levelDb();
        byte[] firstMix = Files.readAllBytes(folder.resolve(StageMixService.MIX_FILE));

        // The bass is taken far back and muted on the stage; the model takes the stage over, and the state is kept.
        SpatialScene quiet = first.with(first.placement("bass").withLevel(bassAtFirst - 9).withMuted(true));
        stages.save(folder, quiet);
        session.synchronize();
        session.markRevision("Bass leise");
        mixing.mix(tracks, folder, message -> { });
        assertFalse(Arrays.equals(firstMix, Files.readAllBytes(folder.resolve(StageMixService.MIX_FILE))));
        StageFollowsMix following = new StageFollowsMix();
        following.remember(session); // as when the piece is opened
        // Meanwhile the melody is moved in the window only: the model has not taken that.
        SpatialScene shown = quiet.with(new SoundSourcePlacement("melody", 0.9, quiet.placement("melody").depth()));
        stages.save(folder, shown);

        ChangeSet changes = session.restoreRevision(original.id());

        assertTrue("the notes are the same: nothing to render", changes.isEmpty());
        Optional<SpatialScene> restored = following.follow(session, shown);
        assertTrue("and yet the mix changed: the stage has to follow", restored.isPresent());
        assertEquals(bassAtFirst, restored.get().placement("bass").levelDb(), 0);
        assertFalse(restored.get().placement("bass").muted());
        assertEquals("what the step did not touch stays as the user left it", 0.9,
                restored.get().placement("melody").x(), 0);
        // What the window does with it: saved with the piece, and mixed again without rendering.
        stages.save(folder, restored.get());
        assertEquals(bassAtFirst, stages.load(folder).placement("bass").levelDb(), 0);
        mixing.mix(tracks, folder, restored.get(), message -> { });
        assertTrue(Files.size(folder.resolve(StageMixService.MIX_FILE)) > 1000);
        assertFalse("a second look finds nothing new", following.follow(session, restored.get()).isPresent());

        // Undo of the restoring brings the quiet bass back the same way.
        ChangeSet undone = session.undo();
        assertTrue(undone.isEmpty());
        SpatialScene again = following.follow(session, restored.get()).get();
        assertEquals(bassAtFirst - 9, again.placement("bass").levelDb(), 0);
        assertTrue(again.placement("bass").muted());

        // The window follows before it decides that there is nothing to render.
        String changed = body("private void pieceChanged(");
        assertTrue(changed.indexOf("stageFollows(piece.get())") > 0
                && changed.indexOf("stageFollows(piece.get())") < changed.indexOf("changes.isEmpty()"));
        assertTrue(changed.substring(changed.indexOf("changes.isEmpty()")).contains("remix();"));
        assertTrue(body("private boolean stageFollows(").contains("saveScene();"));
        assertTrue("undo in the note editor takes the same way", body("private void notesEdited(")
                .contains("stageFollows(edited.get())"));
    }

    // ---- 2. The demo over an open model ----------------------------------------------------------------------

    @Test
    public void theDemoLoadedOverAnOpenPieceBecomesItsModelAndCanBeTakenBack() throws Exception {
        Path folder = piece();
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        assertEquals(DemoIntoPiece.Effect.REPLACES_PIECE, DemoIntoPiece.effect(folder, model));

        try (InputStream demo = getClass().getResourceAsStream("/district-dawn.tsv")) {
            DemoIntoPiece.load(demo, folder, model);
        }

        ProjectSession session = model.of(folder).get();
        List<String> demoTracks = new ArrayList<String>();
        for (MusicTrack track : StageMixService.tracks(folder.resolve("arrangement.tsv"))) {
            demoTracks.add(track.id());
        }
        List<String> modelTracks = new ArrayList<String>();
        for (MusicTrack track : StageMixService.Piece.of(session.composition()).tracks()) {
            modelTracks.add(track.id());
        }
        assertTrue("the demo is more than the two notes that were there", session.composition().endTick() > 1920);
        assertEquals("what is rendered next is the demo: the model has its tracks", demoTracks, modelTracks);
        assertFalse(session.externalChanges());
        assertTrue("the piece that was there comes back with undo", session.canUndo());
        session.undo();
        assertEquals(Arrays.asList("melody", "bass"),
                Arrays.asList(session.composition().parts().get(0).id(), session.composition().parts().get(1).id()));
        assertTrue(body("private void loadDemo() {").contains("DemoIntoPiece.load(input, folder, pieceModel)"));
    }

    @Test
    public void aModelTheDemoCannotReplaceIsProtected() throws Exception {
        Path folder = piece();
        new PieceModel(line -> { }).open(folder);
        Composition composed = CompositionJson.load(folder);
        CompositionJson.save(folder, new Composition(composed.meta(), new TimeMap(
                Arrays.asList(new TimeMap.Tempo(0, 120), new TimeMap.Tempo(480, 60)), composed.timeMap().meters(),
                composed.timeMap().keys()), composed.sections(), composed.parts(), composed.tracks(),
                composed.assets()));
        Files.delete(folder.resolve("arrangement.tsv"));
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        String saved = new String(Files.readAllBytes(folder.resolve("composition.json")), StandardCharsets.UTF_8);

        assertEquals(DemoIntoPiece.Effect.REFUSED, DemoIntoPiece.effect(folder, model));
        try (InputStream demo = getClass().getResourceAsStream("/district-dawn.tsv")) {
            DemoIntoPiece.load(demo, folder, model);
            fail("the model would lose its tempo change");
        } catch (IOException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("neuen Ordner"));
        }
        assertFalse("nothing was written", Files.exists(folder.resolve("arrangement.tsv")));
        assertEquals(saved, new String(Files.readAllBytes(folder.resolve("composition.json")), StandardCharsets.UTF_8));
    }

    // ---- 3. The answers of the AI after undo -----------------------------------------------------------------

    @Test
    public void aVoiceTakenAndTakenBackDoesNotReturnWhenTheWholePieceIsComposedAgain() throws Exception {
        Path root = Files.createTempDirectory("answers-");
        Path folder = Files.createDirectories(root.resolve("piece"));
        writePlan(folder);
        writePart(folder, "melody", 10, 72);
        writePart(folder, "harmony", 48, 52);
        writePart(folder, "bass", 32, 48);
        writePart(folder, "drums", 0, 36);
        SongLibrary songs = new SongLibrary(root);
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        ComposingIntoPiece.run(() -> compose(folder), folder, "Offline fixture", songs, model);
        ProjectSession session = model.of(folder).get();
        String firstAnswer = read(folder.resolve("bass.json"));
        int firstPitch = session.composition().notesOf("bass").get(0).note().pitch();

        // The AI writes the bass anew (an octave lower); the user takes the proposal.
        RecompositionOffer offer = RecompositionOffer.stage(session, "bass", "run-1");
        writePart(offer.staging(), "bass", 32, 36);
        compose(offer.staging());
        offer.propose("run-1");
        offer.accept();
        String rejected = read(folder.resolve("bass.json"));
        int rejectedPitch = session.composition().notesOf("bass").get(0).note().pitch();
        assertNotEquals(firstAnswer, rejected);
        assertNotEquals(firstPitch, rejectedPitch);

        // The user takes it back; the window lets the answers follow the step.
        ChangeSet undone = session.undo();
        assertEquals(java.util.Collections.singleton("bass"), AnswerShelf.follow(folder, session.composition(), undone));
        assertEquals("the answer of the bass is again the one the piece was written from", firstAnswer,
                read(folder.resolve("bass.json")));

        // "Compose" for the whole piece takes every track from its kept answer: the rejected bass must not return.
        ComposingIntoPiece.run(() -> compose(folder), folder, "Offline fixture", songs, model);
        assertEquals(firstPitch, model.of(folder).get().composition().notesOf("bass").get(0).note().pitch());

        assertTrue(body("private void pieceChanged(").contains("answersFollow(piece.get(), changes);"));
        assertTrue(body("private void notesEdited(").contains("answersFollow(edited.get(),"));
    }

    @Test
    public void redoBringsTheTakenAnswerBackWithItsVoice() throws Exception {
        Path root = Files.createTempDirectory("answers-");
        Path folder = Files.createDirectories(root.resolve("piece"));
        writePlan(folder);
        writePart(folder, "melody", 10, 72);
        writePart(folder, "harmony", 48, 52);
        writePart(folder, "bass", 32, 48);
        writePart(folder, "drums", 0, 36);
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        ComposingIntoPiece.run(() -> compose(folder), folder, "Offline fixture", new SongLibrary(root), model);
        ProjectSession session = model.of(folder).get();
        String first = read(folder.resolve("bass.json"));
        RecompositionOffer offer = RecompositionOffer.stage(session, "bass", "run-1");
        writePart(offer.staging(), "bass", 32, 36);
        compose(offer.staging());
        offer.propose("run-1");
        offer.accept();
        String taken = read(folder.resolve("bass.json"));

        ChangeSet undone = session.undo();
        AnswerShelf.follow(folder, session.composition(), undone);
        assertEquals(first, read(folder.resolve("bass.json")));
        ChangeSet redone = session.redo();
        AnswerShelf.follow(folder, session.composition(), redone);
        assertEquals(taken, read(folder.resolve("bass.json")));
        assertEquals("a step that changes no notes leaves the answers alone", java.util.Collections.emptySet(),
                AnswerShelf.follow(folder, session.composition(),
                        ChangeSet.between(session.composition(), session.composition())));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static Path piece() throws IOException {
        Path folder = Files.createTempDirectory("piece-steps-");
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        return folder;
    }

    /** The composer without a network: it can only take every track from its kept answer. */
    private static Path compose(Path directory) throws Exception {
        KipitzChatClient offline = new KipitzChatClient(
                "http://127.0.0.1:1/v1/chat/completions", "", "google/gemma-4-31B-it", "", 8080);
        return new GemmaMusicComposer(offline).compose("Offline fixture", directory, message -> { });
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
        Files.write(directory.resolve("composition-plan.json"), new Gson().toJson(plan).getBytes(StandardCharsets.UTF_8));
    }

    private static void writePart(Path directory, String part, int program, int pitch) throws Exception {
        JsonObject result = new JsonObject();
        result.addProperty("part", part);
        result.addProperty("program", program);
        JsonArray notes = new JsonArray();
        for (int beat = 0; beat < 32; beat += 4) {
            JsonObject note = new JsonObject();
            note.addProperty("beat", (double) beat);
            note.addProperty("duration", 1.0);
            note.addProperty("pitch", pitch);
            note.addProperty("velocity", 70);
            notes.add(note);
        }
        result.add("notes", notes);
        Files.write(directory.resolve(part + ".json"), new Gson().toJson(result).getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** The body of a method of the window. */
    private static String body(String header) throws IOException {
        String source = new String(Files.readAllBytes(WINDOW), StandardCharsets.UTF_8);
        int start = source.indexOf(header);
        assertTrue(header, start >= 0);
        int open = source.indexOf('{', start + header.length() - 1);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i);
            }
        }
        return source.substring(open);
    }
}
