package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.render.StemJob;
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

    @Test
    public void aPieceTheOldFileCannotHoldIsRenderedAndMixedFromItsModelAlone() throws Exception {
        // PO finding on the render key: the mix still read arrangement.tsv for the tracks and the time.
        Path plain = pieceWithoutWrittenArrangement(false);
        Path changing = pieceWithoutWrittenArrangement(true);
        PieceModel model = new PieceModel(line -> { });
        PieceRendering rendering = new PieceRendering(new InstrumentRegistry(plain.resolve("none.properties")));

        model.open(plain);
        PieceRendering.Outcome one = rendering.render(model.of(plain).get(), null, Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", new HashMap<String, Integer>(), message -> { });
        model.open(changing);
        ProjectSession session = model.of(changing).get();
        assertFalse("a tempo change is beyond the old file", session.withinTsv());
        List<String> said = Collections.synchronizedList(new ArrayList<String>());
        PieceRendering.Outcome two = rendering.render(session, null, Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", new HashMap<String, Integer>(), said::add);

        assertEquals("every part of the model is rendered", Arrays.asList("melody", "bass"), two.stems().rendered());
        assertEquals(changing.resolve(StageMixService.MIX_FILE), two.mix());
        assertTrue(said.toString(), said.stream().anyMatch(line -> line.startsWith("Mische 2 Spuren")));
        assertFalse("rendering writes no old file either", Files.exists(changing.resolve("arrangement.tsv")));
        // The mix is as long as the time map of the model says: once the tempo halves, the bars last twice as long.
        double plainSeconds = StemJob.pieceSeconds(CompositionJson.load(plain));
        double changingSeconds = StemJob.pieceSeconds(session.composition());
        assertTrue(changingSeconds > plainSeconds * 1.9);
        assertEquals("the same tail after the last bar, whatever the tempo does",
                seconds(one.mix()) - plainSeconds, seconds(two.mix()) - changingSeconds, 0.5);
        assertTrue(seconds(two.mix()) >= changingSeconds);

        // The stage, one track alone and the saved mix come from the model as well.
        StageMixService stage = new StageMixService();
        StageMixService.Piece piece = StageMixService.Piece.of(session.composition());
        assertEquals(Arrays.asList("melody", "bass"), ids(piece.tracks()));
        assertEquals(120, piece.tempo());
        Path solo = stage.solo(piece, changing, stage.scene(piece, changing), "bass");
        assertEquals(seconds(two.mix()), seconds(solo), 0.01);
        Path saved = stage.export(piece, changing, stage.scene(piece, changing), changing.resolve("saved.wav"), 24);
        assertEquals(seconds(two.mix()), seconds(saved), 0.01);
    }

    @Test
    public void aStaleWrittenArrangementBesideTheModelIsNotMixed() throws Exception {
        Path folder = pieceWithoutWrittenArrangement(true);
        // Left behind from an earlier state: another tempo, and a track the model does not have.
        Files.write(folder.resolve("arrangement.tsv"), Arrays.asList("tempo\t60",
                "track\tghost\t10\t0\tmelody", "note\tghost\t0\t19200\t72\t80"), StandardCharsets.UTF_8);
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        byte[] kept = Files.readAllBytes(folder.resolve("composition.json"));
        List<String> said = Collections.synchronizedList(new ArrayList<String>());

        PieceRendering.Outcome outcome = new PieceRendering(new InstrumentRegistry(folder.resolve("none.properties")))
                .render(model.of(folder).get(), null, Sf2Engine.GERVILL, "fluidsynth", "sfizz_render",
                        new HashMap<String, Integer>(), said::add);

        // Mixed from the file, the ghost would be asked for ("noch nicht gerendert") or the mix would be as long as the file.
        assertEquals(Arrays.asList("melody", "bass"), outcome.stems().rendered());
        assertTrue(said.toString(), said.stream().anyMatch(line -> line.startsWith("Mische 2 Spuren")));
        double piece = StemJob.pieceSeconds(model.session().get().composition());
        assertTrue("as long as the model, not as the 40 s of the file", seconds(outcome.mix()) < piece + 5);
        assertEquals(new String(kept, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(folder.resolve("composition.json")), StandardCharsets.UTF_8));
    }

    /**
     * Architect review of the D4 stage: rendering mixes as the stage is heard, so that rendering
     * and mixing again write the same file; and the live stage opens from the model, without a
     * written arrangement, for the same way of hearing.
     */
    @Test
    public void renderingMixingAgainAndTheLiveStageHearThePieceTheSameWay() throws Exception {
        Path folder = pieceWithoutWrittenArrangement(true);
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        com.aresstack.audio.studio.mix.StageSound headphones = com.aresstack.audio.studio.mix.StageSound.binaural();
        PieceRendering rendering = new PieceRendering(new InstrumentRegistry(folder.resolve("none.properties")));

        Path mix = rendering.render(model.of(folder).get(), null, Sf2Engine.GERVILL, "fluidsynth", "sfizz_render",
                new HashMap<String, Integer>(), message -> { }).mix();
        byte[] onLoudspeakers = Files.readAllBytes(mix);
        rendering.withSound(headphones).render(model.of(folder).get(), null, Sf2Engine.GERVILL, "fluidsynth",
                "sfizz_render", new HashMap<String, Integer>(), message -> { });
        byte[] onHeadphones = Files.readAllBytes(mix);
        assertFalse("the rendering hears the way it is told", Arrays.equals(onLoudspeakers, onHeadphones));

        StageMixService.Piece piece = StageMixService.Piece.of(model.session().get().composition());
        StageMixService mixing = new StageMixService();
        mixing.setSound(headphones);
        mixing.mix(piece, folder, message -> { });
        assertTrue("mixing again writes what the rendering wrote", Arrays.equals(onHeadphones, Files.readAllBytes(mix)));

        assertFalse(Files.exists(folder.resolve("arrangement.tsv")));
        LiveDeck deck = LiveDeck.open(piece, folder, mixing.scene(piece, folder), null, Float.NaN,
                () -> block -> { }, headphones);
        try {
            assertEquals(headphones, deck.sound());
            assertEquals("the live stage is as long as the file", seconds(mix), deck.length(), 0.05);
        } finally {
            deck.close();
        }
    }

    /** A piece that has only its model: no {@code arrangement.tsv}; on demand with a tempo that halves after one beat. */
    private static Path pieceWithoutWrittenArrangement(boolean tempoChanges) throws Exception {
        Path folder = Files.createTempDirectory("piece-rendering-");
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        new PieceModel(line -> { }).open(folder);
        Composition composed = CompositionJson.load(folder);
        if (tempoChanges) {
            TimeMap time = composed.timeMap();
            CompositionJson.save(folder, new Composition(composed.meta(), new TimeMap(
                    Arrays.asList(new TimeMap.Tempo(0, 120), new TimeMap.Tempo(480, 60)), time.meters(), time.keys()),
                    composed.sections(), composed.parts(), composed.tracks(), composed.assets()));
        }
        Files.delete(folder.resolve("arrangement.tsv"));
        return folder;
    }

    private static List<String> ids(List<MusicTrack> tracks) {
        List<String> ids = new ArrayList<String>();
        for (MusicTrack track : tracks) {
            ids.add(track.id());
        }
        return ids;
    }

    private static double seconds(Path wav) throws Exception {
        javax.sound.sampled.AudioFileFormat format = javax.sound.sampled.AudioSystem.getAudioFileFormat(wav.toFile());
        return format.getFrameLength() / (double) format.getFormat().getFrameRate();
    }
}
