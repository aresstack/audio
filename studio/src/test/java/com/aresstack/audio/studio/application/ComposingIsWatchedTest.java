package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.progress.SketchProgress;
import com.aresstack.audio.studio.progress.TrackProgress;
import com.aresstack.audio.studio.score.Score;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The piece can be watched while it is composed: a listener that wants to is shown the notes
 * written so far, and the piece that is saved at the end is the same as without a watcher.
 */
public final class ComposingIsWatchedTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Lets the first half of every answer with notes be read before the answer is whole. */
    private static final class Streamed implements ChatModel {
        private final FakeChatModel model;

        Streamed(FakeChatModel model) {
            this.model = model;
        }

        @Override
        public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException {
            return model.generate(instruction, prompt, maxTokens, cache);
        }

        @Override
        public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache,
                                   Consumer<String> heard) throws IOException {
            JsonObject answer = model.generate(instruction, prompt, maxTokens, cache);
            String text = new Gson().toJson(answer);
            heard.accept(text.substring(0, text.length() / 2));
            return answer;
        }
    }

    private static final class Watcher implements SketchProgress {
        final List<List<String>> sketches = Collections.synchronizedList(new ArrayList<List<String>>());

        final List<String> steps = java.util.Collections.synchronizedList(new ArrayList<String>());
        final List<List<String>> plans = java.util.Collections.synchronizedList(new ArrayList<List<String>>());
        volatile int sketchesWhenPlanned = -1;

        @Override
        public void preparing(String what, long characters) {
            steps.add(what);
        }

        @Override
        public void planned(List<String> chords) {
            sketchesWhenPlanned = sketches.size();
            plans.add(chords);
        }

        @Override
        public void sketch(List<String> arrangement) {
            sketches.add(new ArrayList<String>(arrangement));
        }

        @Override
        public void track(String trackId, TrackProgress.Stage stage, TrackProgress.State state, String detail) {
        }

        @Override
        public void update(String message) {
        }
    }

    private static TreeSet<String> notes(List<String> arrangement) {
        TreeSet<String> notes = new TreeSet<String>();
        for (String line : arrangement) {
            if (line.startsWith("note\t")) {
                notes.add(line);
            }
        }
        return notes;
    }

    @Test
    public void theNotesWrittenSoFarAreShownAndTheLastPictureIsThePiece() throws Exception {
        CompositionSpec spec = CompositionSpec.of(24, 96, Key.parse("d-Moll"), 4, 4);
        Path watched = folder.newFolder("watched").toPath();
        Watcher watcher = new Watcher();
        Path tsv = new GemmaMusicComposer(new Streamed(new FakeChatModel("Dm", "Gm", "A7", "Dm")), 16384, 1)
                .compose("Nebliger Hafen", watched, TrackArrangement.defaults(), spec, watcher);
        List<String> piece = Files.readAllLines(tsv, StandardCharsets.UTF_8);

        assertFalse("the piece was shown on its way", watcher.sketches.isEmpty());
        List<String> first = watcher.sketches.get(0);
        List<String> last = watcher.sketches.get(watcher.sketches.size() - 1);
        assertTrue("the first picture has only a part of the notes", notes(first).size() < notes(piece).size());
        assertFalse("and it has notes already", notes(first).isEmpty());
        assertEquals("the last picture has the notes of the piece", notes(piece), notes(last));
        for (List<String> sketch : watcher.sketches) {
            Score score = Score.of(sketch);
            assertEquals("the paper has the length of the plan from the start", 24, score.bars());
            assertFalse(score.staves().isEmpty());
        }

        // The time before the first note is not silent: the planning is said, then the plan.
        assertEquals("Die KI plant Struktur und Harmonien", watcher.steps.get(0));
        assertEquals(1, watcher.plans.size());
        assertEquals(24, watcher.plans.get(0).size());
        assertEquals("the plan is said before the first notes", 0, watcher.sketchesWhenPlanned);

        // Watching changes nothing of the piece.
        Path unwatched = folder.newFolder("unwatched").toPath();
        Path plain = new GemmaMusicComposer(new FakeChatModel("Dm", "Gm", "A7", "Dm"), 16384, 1)
                .compose("Nebliger Hafen", unwatched, TrackArrangement.defaults(), spec, message -> { });
        assertEquals(Files.readAllLines(plain, StandardCharsets.UTF_8), piece);
    }
}
