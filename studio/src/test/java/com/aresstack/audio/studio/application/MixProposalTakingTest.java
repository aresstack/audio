package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.live.LivePlayback;
import com.aresstack.audio.core.live.LiveSink;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Acceptance of PR #56 (PO): a mix proposal of the AI with movements is ONE proposal for the user,
 * so "Übernehmen" is one step. Taken, undone, redone and brought back from a revision, the stage
 * file ({@code spatial-scene.json}), the mix document ({@code mix.json}), the exported WAV and the
 * live stage say the same, also after the piece is opened again; and if one half cannot be kept,
 * nothing is taken. The window is played here as it acts: it takes through
 * {@link MixProposalTaking}, and after undo, redo or a revision lets its stage follow the mix
 * ({@link StageFollowsMix}) and saves it.
 */
public final class MixProposalTakingTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final long BAR = 1920;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final SpatialSceneStore stages = new SpatialSceneStore();

    @Test
    public void takenUndoneRedoneAndBroughtBackStageAndMovementsGoTogether() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        StageMixService service = new StageMixService();
        SpatialScene first = service.scene(arrangement, song).withMaster(-14)
                .inRoom(AcousticRoom.STUDIO.withAmount(0));
        stages.save(song, first);
        List<String> log = new ArrayList<>();
        PieceModel model = new PieceModel(log::add);
        model.open(song);
        ProjectSession session = model.session().orElseThrow();
        StageFollowsMix follows = new StageFollowsMix();
        follows.remember(session);

        // The user moved the bass by hand: the stage file knows it, the mix of the model not yet.
        // The melody stands high (D4): the mix document holds no height, the stage keeps it through every step.
        SpatialScene before = first.with(first.placement("bass").at(0.4, 0.6).withLevel(-5))
                .with(first.placement("melody").atHeight(0.5));
        stages.save(song, before);

        // The proposal: the melody left and in front with a level curve, the bass elsewhere, another
        // room; the melody wanders from left to right over the bars.
        SpatialScene proposed = before.inRoom(AcousticRoom.of("chamber"))
                .with(before.placement("melody").at(-0.8, 0.1).withLevel(-2)
                        .withDynamics(List.of(new SoundSourcePlacement.LevelPoint(0, -4),
                                new SoundSourcePlacement.LevelPoint(1, 0))))
                .with(before.placement("bass").at(0.1, 0.3));
        SpatialScene taken = before.taking(proposed);
        Assert.assertEquals(0.5, taken.placement("melody").height(), 0);
        MovementDirector.Proposal moves = new MovementDirector.Proposal(Map.of("melody",
                Map.of(AutomationLane.X, List.of(new StageMovements.Point(0, -0.8), new StageMovements.Point(2, 0.8)))));

        byte[] heardBefore = export(service, arrangement, song, before, "before.wav");
        Assert.assertEquals(1, MixProposalTaking.take(model, follows, stages, before, taken, moves));

        // Taken: stage file and mix document both say the proposal; one step in the history.
        assertStage(taken, stages.load(song));
        MixScene mix = MixJson.load(song);
        String melody = mix.channelOf(MixChannel.Owner.part("melody")).id();
        Assert.assertEquals(-0.8, mix.channelOf(MixChannel.Owner.part("melody")).x(), 0);
        Assert.assertEquals(0.1, mix.channelOf(MixChannel.Owner.part("bass")).x(), 0);
        Assert.assertEquals("chamber", mix.room().kind());
        Assert.assertEquals(List.of(new AutomationLane.Point(0, -0.8), new AutomationLane.Point(2 * BAR, 0.8)),
                mix.lane(melody, AutomationLane.X).points());
        Assert.assertEquals(List.of(new AutomationLane.Point(0, -4), new AutomationLane.Point(BAR, 0)),
                mix.lane(melody, AutomationLane.LEVEL).points());
        Assert.assertEquals(MixProposalTaking.STEP, session.undoDescription());
        byte[] heardTaken = export(service, arrangement, song, taken, "taken.wav");
        Assert.assertFalse("the proposal sounds different", Arrays.equals(heardBefore, heardTaken));
        live(arrangement, song, taken, "taken.wav");

        // Undo: one step takes back places, room, level curve and movement, to the stage the user saw.
        SpatialScene shown = window(follows, session, session.undo(), taken, song);
        assertStage(before, shown);
        assertStage(before, stages.load(song));
        Assert.assertNull("the movement went with it", MixJson.load(song).lane(melody, AutomationLane.X));
        Assert.assertFalse(session.canUndo());
        Assert.assertArrayEquals("sounds as before", heardBefore, export(service, arrangement, song, shown, "undone.wav"));
        live(arrangement, song, shown, "undone.wav");

        // Redo: all of it again.
        shown = window(follows, session, session.redo(), shown, song);
        assertStage(taken, shown);
        Assert.assertEquals(List.of(new AutomationLane.Point(0, -0.8), new AutomationLane.Point(2 * BAR, 0.8)),
                MixJson.load(song).lane(melody, AutomationLane.X).points());
        Assert.assertArrayEquals(heardTaken, export(service, arrangement, song, shown, "redone.wav"));

        // The revisions before and after the step bring back the one or the other as a whole.
        Revision ahead = revision(session, "Stand vor: " + MixProposalTaking.STEP);
        Revision after = revision(session, MixProposalTaking.STEP);
        Assert.assertEquals(Revision.Author.AI, after.author());
        shown = window(follows, session, session.restoreRevision(ahead.id()), shown, song);
        assertStage(before, shown);
        Assert.assertNull(MixJson.load(song).lane(melody, AutomationLane.X));
        Assert.assertArrayEquals(heardBefore, export(service, arrangement, song, shown, "revision-before.wav"));
        shown = window(follows, session, session.restoreRevision(after.id()), shown, song);
        assertStage(taken, shown);
        Assert.assertArrayEquals(heardTaken, export(service, arrangement, song, shown, "revision-after.wav"));

        // Opened again: what the files say is the proposal, and it sounds so, in the file and live.
        PieceModel again = new PieceModel(log::add);
        again.open(song);
        Assert.assertEquals(List.of(new StageMovements.Point(0, -0.8), new StageMovements.Point(2, 0.8)),
                again.movements().of("melody", AutomationLane.X));
        SpatialScene reopened = stages.load(song).fitting(StageMixService.tracks(arrangement));
        assertStage(taken, reopened);
        Assert.assertArrayEquals(heardTaken, export(service, arrangement, song, reopened, "reopened.wav"));
        live(arrangement, song, reopened, "reopened.wav");
        Assert.assertEquals(List.of(), log.stream().filter(line -> line.startsWith("WARN: Der Verlauf")).toList());
    }

    @Test
    public void whatCannotBeKeptIsNotTakenByHalf() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        SpatialScene first = new StageMixService().scene(arrangement, song);
        stages.save(song, first);
        PieceModel model = new PieceModel(line -> { });
        model.open(song);
        ProjectSession session = model.session().orElseThrow();
        StageFollowsMix follows = new StageFollowsMix();
        follows.remember(session);
        MixScene mix = session.mix();
        SpatialScene before = first.with(first.placement("bass").at(0.4, 0.6));
        stages.save(song, before);
        SpatialScene taken = before.taking(before.with(before.placement("melody").at(-0.8, 0.1)));
        MovementDirector.Proposal moves = new MovementDirector.Proposal(Map.of("melody",
                Map.of(AutomationLane.X, List.of(new StageMovements.Point(0, -0.8), new StageMovements.Point(2, 0.8)))));

        // The mix document cannot be written: a folder stands where it belongs.
        Files.delete(song.resolve(MixJson.FILE_NAME));
        Files.createDirectories(song.resolve(MixJson.FILE_NAME).resolve("in-the-way"));
        try {
            MixProposalTaking.take(model, follows, stages, before, taken, moves);
            Assert.fail("mix.json cannot be written");
        } catch (IOException expected) {
            // said to the user; the proposal stays on the page
        }
        assertStage(before, stages.load(song));
        Assert.assertEquals("the model as it was", mix, session.mix());
        Assert.assertFalse("no step", session.canUndo());

        // Without a model there is nothing to take into, and the stage file stays.
        PieceModel none = new PieceModel(line -> { });
        try {
            MixProposalTaking.take(none, follows, stages, before, taken, moves);
            Assert.fail("no model");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("Werkmodell"));
        }
        assertStage(before, stages.load(song));
    }

    /** What the window does after undo, redo or a revision: its stage follows the mix, and is saved. */
    private SpatialScene window(StageFollowsMix follows, ProjectSession session, Object changes, SpatialScene shown,
                                Path song) throws IOException {
        SpatialScene stage = follows.follow(session, shown).orElseThrow(() ->
                new AssertionError("the mix changed, so the stage follows"));
        stages.save(song, stage);
        return stage;
    }

    private static Revision revision(ProjectSession session, String description) throws IOException {
        for (Revision revision : session.revisions()) {
            if (revision.description().equals(description)) {
                return revision;
            }
        }
        throw new AssertionError("no revision \"" + description + "\" in " + session.revisions());
    }

    private static void assertStage(SpatialScene wanted, SpatialScene stage) {
        Assert.assertEquals(wanted.room().kind(), stage.room().kind());
        Assert.assertEquals(wanted.room().reverbSeconds(), stage.room().reverbSeconds(), 1e-9);
        Assert.assertEquals(wanted.room().amount(), stage.room().amount(), 1e-9);
        Assert.assertEquals(wanted.masterDb(), stage.masterDb(), 1e-9);
        for (SoundSourcePlacement place : wanted.placements()) {
            SoundSourcePlacement seen = stage.placement(place.trackId());
            Assert.assertNotNull(place.trackId(), seen);
            String id = place.trackId();
            Assert.assertEquals(id + " x", place.x(), seen.x(), 1e-9);
            Assert.assertEquals(id + " depth", place.depth(), seen.depth(), 1e-9);
            Assert.assertEquals(id + " level", place.levelDb(), seen.levelDb(), 1e-9);
            Assert.assertEquals(id + " width", place.width(), seen.width(), 1e-9);
            Assert.assertEquals(id + " reverb", place.reverb(), seen.reverb(), 1e-9);
            Assert.assertEquals(id + " height", place.height(), seen.height(), 1e-9);
            Assert.assertEquals(id + " curve", place.dynamics().size(), seen.dynamics().size());
            for (int i = 0; i < place.dynamics().size(); i++) {
                Assert.assertEquals(place.dynamics().get(i).bar(), seen.dynamics().get(i).bar(), 1e-9);
                Assert.assertEquals(place.dynamics().get(i).db(), seen.dynamics().get(i).db(), 1e-9);
            }
        }
    }

    private static byte[] export(StageMixService service, Path arrangement, Path song, SpatialScene scene,
                                 String name) throws Exception {
        return Files.readAllBytes(service.export(arrangement, song, scene, song.resolve(name), 24));
    }

    /** The live stage of this scene sounds as its exported file (within the 24 bits of the file and the limiter's delay). */
    private static void live(Path arrangement, Path song, SpatialScene scene, String exported) throws Exception {
        SpatialStemMixer.Signal file = SpatialStemMixer.read(song.resolve(exported));
        Tape tape = new Tape();
        try (LiveDeck deck = LiveDeck.open(arrangement, song, scene, null, Float.NaN, () -> tape)) {
            deck.play();
            for (long until = System.nanoTime() + 20_000_000_000L;
                 deck.playback().state() != LivePlayback.State.ENDED; ) {
                Assert.assertNull(deck.playback().failure());
                Assert.assertTrue("the piece does not end", System.nanoTime() < until);
                Thread.sleep(2);
            }
            int late = deck.playback().master().latencyFrames();
            for (int i = 0; i < file.left.length; i += 7) {
                Assert.assertEquals("live left at " + i, file.left[i], tape.left[i + late], 2e-4f);
                Assert.assertEquals("live right at " + i, file.right[i], tape.right[i + late], 2e-4f);
            }
        }
    }

    /** A sound card of a test: keeps all it gets. */
    private static final class Tape implements LiveSink {
        private float[] left = new float[RATE * 16];
        private float[] right = new float[RATE * 16];
        private int frames;

        @Override
        public synchronized void write(AudioBlock block) {
            int count = block.frames();
            if (frames + count > left.length) {
                left = Arrays.copyOf(left, 2 * left.length);
                right = Arrays.copyOf(right, 2 * right.length);
            }
            System.arraycopy(block.left, 0, left, frames, count);
            System.arraycopy(block.right, 0, right, frames, count);
            frames += count;
        }
    }

    /** A piece of five seconds: its arrangement, a tone as the melody and a lower one as the bass. */
    private Path piece() throws Exception {
        Path song = folder.newFolder().toPath();
        Files.write(song.resolve("arrangement.tsv"), Arrays.asList("tempo\t120", "track\tmelody\t73\t0\tmelody",
                "track\tbass\t32\t1\tbass"), StandardCharsets.UTF_8);
        SpatialStemMixer.write(tone(330, 0.4f), song.resolve("melody.wav"), 16);
        SpatialStemMixer.write(tone(110, 0.3f), song.resolve("bass.wav"), 16);
        return song;
    }

    private static SpatialStemMixer.Mix tone(double frequency, float height) throws Exception {
        float[] samples = new float[RATE * 5];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (height * Math.sin(2 * Math.PI * frequency * i / RATE));
        }
        return new SpatialStemMixer().render(
                Arrays.asList(new SpatialStemMixer.Signal(samples, samples, RATE)),
                Arrays.asList(new SoundSourcePlacement("a", 0, 0, 0, 1, 0, false, false, null)),
                AcousticRoom.STUDIO.withAmount(0), 2);
    }
}
