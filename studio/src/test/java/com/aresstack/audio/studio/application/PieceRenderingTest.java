package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.progress.TrackProgress;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

/** What the render key of the studio does for a piece with a model. */
public final class PieceRenderingTest {
    private static final List<String> PIECE = Arrays.asList("tempo\t120",
            "track\tmelody\t10\t0\tmelody", "note\tmelody\t0\t480\t72\t80",
            "track\tbass\t32\t1\tbass", "note\tbass\t0\t960\t48\t70");

    /** Hears what the channel strips of the deck hear. */
    private static final class Heard implements TrackProgress {
        final List<String> tracks = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void update(String message) {
        }

        @Override
        public void track(String trackId, Stage stage, State state, String detail) {
            tracks.add(trackId + " " + state + (detail.isEmpty() ? "" : " " + detail));
        }
    }

    @Test
    public void thePieceIsRenderedFromItsModelAndOnlyWhatChangedIsRenderedAgain() throws Exception {
        Path folder = Files.createTempDirectory("piece-rendering-");
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        PieceRendering rendering = new PieceRendering(new InstrumentRegistry(folder.resolve("none.properties")));

        Heard heard = new Heard();
        PieceRendering.Outcome first = rendering.render(model.of(folder).get(), null, Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", new HashMap<String, Integer>(), heard);

        assertEquals(Arrays.asList("melody", "bass"), first.stems().rendered());
        assertEquals(folder.resolve(StageMixService.MIX_FILE), first.mix());
        assertTrue(Files.size(first.mix()) > 1000);
        assertTrue(Files.isRegularFile(folder.resolve(PieceRendering.MIDI_FILE)));
        assertFalse("no second synthesis of the whole piece", Files.exists(folder.resolve("arrangement.wav")));
        assertTrue(heard.tracks.toString(), heard.tracks.containsAll(Arrays.asList("melody WAITING", "bass WAITING",
                "melody WORKING", "bass WORKING", "melody DONE", "bass DONE")));

        // The composer wrote the bass anew: the model follows, and only the bass is rendered.
        List<String> composed = new ArrayList<String>(PIECE);
        composed.set(4, "note\tbass\t0\t960\t43\t70");
        Files.write(folder.resolve("arrangement.tsv"), composed, StandardCharsets.UTF_8);
        model.synchronize(); // as the window does right after composing
        heard.tracks.clear();
        PieceRendering.Outcome second = rendering.render(model.of(folder).get(), null, Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", new HashMap<String, Integer>(), heard);

        assertEquals(Arrays.asList("bass"), second.stems().rendered());
        assertEquals(Arrays.asList("melody"), second.stems().reused());
        assertTrue(heard.tracks.toString(), heard.tracks.contains("melody DONE unverändert"));
        assertFalse(heard.tracks.toString(), heard.tracks.contains("melody WORKING"));
    }

    @Test
    public void renderingNeverTakesAFileThatWasChangedBehindTheModelsBack() throws Exception {
        Path folder = Files.createTempDirectory("piece-rendering-");
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        PieceRendering rendering = new PieceRendering(new InstrumentRegistry(folder.resolve("none.properties")));
        rendering.render(model.of(folder).get(), null, Sf2Engine.GERVILL, "fluidsynth", "sfizz_render",
                new HashMap<String, Integer>(), message -> { });
        byte[] saved = Files.readAllBytes(folder.resolve("composition.json"));

        // The file is changed outside the studio; the user opens the piece and only clicks "render".
        List<String> stale = new ArrayList<String>(PIECE);
        stale.set(4, "note\tbass\t0\t960\t43\t70");
        Files.write(folder.resolve("arrangement.tsv"), stale, StandardCharsets.UTF_8);
        model.open(folder);
        PieceRendering.Outcome again = rendering.render(model.of(folder).get(), null, Sf2Engine.GERVILL,
                "fluidsynth", "sfizz_render", new HashMap<String, Integer>(), message -> { });

        assertEquals("the saved piece is what was rendered: nothing new to render", Arrays.asList(),
                again.stems().rendered());
        assertEquals(new String(saved, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(folder.resolve("composition.json")), StandardCharsets.UTF_8));
        assertEquals(48, model.session().get().composition().notesOf("bass").get(0).note().pitch());
        assertTrue(model.session().get().externalChanges());
    }

    @Test
    public void theSoundBankChosenInTheTableCountsWithoutChangingTheModel() throws Exception {
        Path folder = Files.createTempDirectory("piece-rendering-");
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        byte[] saved = Files.readAllBytes(folder.resolve("composition.json"));
        PieceRendering rendering = new PieceRendering(new InstrumentRegistry(folder.resolve("none.properties")));
        java.util.Map<String, String> libraries = new HashMap<String, String>();
        libraries.put("melody", "builtin");
        libraries.put("bass", "a-bank-that-is-not-installed");

        try {
            rendering.render(model.of(folder).get(), libraries, Sf2Engine.GERVILL, "fluidsynth", "sfizz_render",
                    new HashMap<String, Integer>(), message -> { });
            org.junit.Assert.fail("the bass asks for the bank chosen in the table");
        } catch (IllegalArgumentException missing) {
            assertTrue(missing.getMessage(), missing.getMessage().contains("a-bank-that-is-not-installed"));
        }
        assertEquals(new String(saved, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(folder.resolve("composition.json")), StandardCharsets.UTF_8));
    }

    @Test
    public void onlyTheOpenPieceWithAModelIsRenderedFromIt() throws Exception {
        Path folder = Files.createTempDirectory("piece-rendering-");
        Path other = Files.createTempDirectory("piece-rendering-");
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);

        assertFalse("nothing composed yet", model.of(folder).isPresent());
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        assertFalse("looking never takes a file over", model.of(folder).isPresent());
        model.synchronize(); // composed: the piece gets its model
        assertTrue(model.of(folder).isPresent());
        assertFalse("another folder is not the open piece", model.of(other).isPresent());
        assertFalse(model.of(null).isPresent());
    }
}
