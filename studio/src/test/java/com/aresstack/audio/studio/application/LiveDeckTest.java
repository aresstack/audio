package com.aresstack.audio.studio.application;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.live.LivePlayback;
import com.aresstack.audio.core.live.LiveSink;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The stage of a piece played live (work package D2, acceptance A13 as far as it can be checked
 * without a sound card): as loud and as placed as its file, and what is moved on the stage while
 * it plays is heard without a file being mixed.
 */
public final class LiveDeckTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final int BLOCK = SpatialStemMixer.BLOCK;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** A sound card of a test: keeps what it gets, and takes only as many blocks as it is allowed. */
    private static final class Tape implements LiveSink {
        final Semaphore allowed = new Semaphore(0);
        private float[] left = new float[RATE * 8];
        private float[] right = new float[RATE * 8];
        private volatile int frames;

        @Override
        public void write(AudioBlock block) {
            allowed.acquireUninterruptibly();
            int count = block.frames();
            if (frames + count > left.length) {
                left = Arrays.copyOf(left, 2 * left.length);
                right = Arrays.copyOf(right, 2 * right.length);
            }
            System.arraycopy(block.left, 0, left, frames, count);
            System.arraycopy(block.right, 0, right, frames, count);
            frames += count;
        }

        void allow(int blocks) {
            allowed.release(blocks);
        }

        void awaitFrames(int wanted) throws InterruptedException {
            for (long until = System.nanoTime() + 20_000_000_000L; frames < wanted; ) {
                Assert.assertTrue("the audio thread stands still at frame " + frames, System.nanoTime() < until);
                Thread.sleep(2);
            }
        }

        double rms(boolean rightSide, int from, int to) {
            float[] side = rightSide ? right : left;
            double sum = 0;
            for (int i = from; i < to; i++) {
                sum += side[i] * (double) side[i];
            }
            return Math.sqrt(sum / Math.max(1, to - from));
        }
    }

    @Test
    public void theLiveStageIsAsLoudAndAsPlacedAsItsFileInBothWaysOfSettingTheLevel() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        StageMixService service = new StageMixService();
        SpatialScene usual = service.scene(arrangement, song);
        usual = usual.with(usual.placement("melody").at(-0.8, 0.2)).with(usual.placement("bass").withLevel(-4));
        for (SpatialScene scene : new SpatialScene[]{usual, usual.withMaster(-6),
                usual.withFixedLevel(true).withMaster(-3)}) {
            SpatialStemMixer.Signal file = SpatialStemMixer.read(service.mix(arrangement, song, scene, message -> { }));
            Tape tape = new Tape();
            tape.allow(Integer.MAX_VALUE);
            // The factor of the file mix is not handed over: a piece mixed to its peak finds it itself.
            try (LiveDeck deck = LiveDeck.open(arrangement, song, scene, null, Float.NaN, () -> tape)) {
                Assert.assertEquals(file.left.length / (double) RATE, deck.length(), 1e-9);
                deck.play();
                awaitEnd(deck);
                int late = deck.playback().master().latencyFrames();
                // The file has 16 bits; the limiter of the fixed level looks ahead, so live comes that much later.
                for (int i = 0; i < file.left.length; i += 7) {
                    Assert.assertEquals("left at " + i, file.left[i], tape.left[i + late], 2e-4f);
                    Assert.assertEquals("right at " + i, file.right[i], tape.right[i + late], 2e-4f);
                }
            }
        }
    }

    /** A13: dragged on the stage while the piece plays, an instrument is heard there at once; no file is mixed. */
    @Test
    public void whatIsMovedOnTheStageWhilePlayingIsHeardWithoutMixingAFile() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        // A dry room and the master well below the limiter, so that a fader is heard as what it is.
        SpatialScene scene = new StageMixService().scene(arrangement, song).withFixedLevel(true).withMaster(-14)
                .inRoom(AcousticRoom.STUDIO.withAmount(0));
        scene = scene.with(scene.placement("melody").at(-1, 0)).with(scene.placement("bass").withMuted(true));
        Tape tape = new Tape();
        try (LiveDeck deck = LiveDeck.open(arrangement, song, scene, null, Float.NaN, () -> tape)) {
            Assert.assertFalse("a piece with the fixed level needs no file to be as loud as it",
                    Files.exists(song.resolve(StageMixService.MIX_FILE)));
            deck.play();
            tape.allow(30);
            tape.awaitFrames(30 * BLOCK);
            double leftBefore = tape.rms(false, 20 * BLOCK, 30 * BLOCK);
            Assert.assertTrue(leftBefore > 3 * tape.rms(true, 20 * BLOCK, 30 * BLOCK));

            // Dragged to the right, in the middle of the drag (nothing settled, nothing saved or mixed).
            Assert.assertTrue(deck.stage(scene.with(scene.placement("melody").at(1, 0)), null));
            tape.allow(30);
            tape.awaitFrames(60 * BLOCK);
            Assert.assertTrue(tape.rms(true, 50 * BLOCK, 60 * BLOCK) > 3 * tape.rms(false, 50 * BLOCK, 60 * BLOCK));

            // The fader of the track down by 12 dB, and its mute key.
            SpatialScene right = scene.with(scene.placement("melody").at(1, 0));
            double loud = tape.rms(true, 50 * BLOCK, 60 * BLOCK);
            deck.stage(right.with(right.placement("melody").withLevel(-12)), null);
            tape.allow(30);
            tape.awaitFrames(90 * BLOCK);
            Assert.assertEquals(Math.pow(10, -12 / 20.0), tape.rms(true, 80 * BLOCK, 90 * BLOCK) / loud, 0.02);
            deck.stage(right.with(right.placement("melody").withMuted(true)), null);
            tape.allow(30);
            tape.awaitFrames(120 * BLOCK);
            Assert.assertEquals(0, tape.rms(true, 115 * BLOCK, 120 * BLOCK), 1e-4);

            // The master fader is heard live as well; the bass, muted when the stage was read, can be turned up.
            deck.stage(right.with(right.placement("bass").withMuted(false)).with(
                    right.placement("melody").withMuted(true)), null);
            tape.allow(30);
            tape.awaitFrames(150 * BLOCK);
            double bass = tape.rms(false, 140 * BLOCK, 150 * BLOCK);
            Assert.assertTrue(bass > 0.01);
            deck.stage(right.with(right.placement("bass").withMuted(false)).with(
                    right.placement("melody").withMuted(true)).withMaster(-20), null);
            tape.allow(30);
            tape.awaitFrames(180 * BLOCK);
            Assert.assertEquals(Math.pow(10, -6 / 20.0), tape.rms(false, 170 * BLOCK, 180 * BLOCK) / bass, 0.02);
            Assert.assertFalse(Files.exists(song.resolve(StageMixService.MIX_FILE)));
            tape.allow(Integer.MAX_VALUE / 2);
        }
    }

    @Test
    public void anotherRoomOrAnotherWayOfSettingTheLevelNeedsANewLiveStage() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        SpatialScene scene = new StageMixService().scene(arrangement, song).withFixedLevel(true);
        Tape tape = new Tape();
        tape.allow(Integer.MAX_VALUE);
        try (LiveDeck deck = LiveDeck.open(arrangement, song, scene, null, Float.NaN, () -> tape)) {
            Assert.assertTrue(deck.fits(scene.withMaster(-9)));
            Assert.assertTrue(deck.fits(scene.with(scene.placement("bass").at(0.3, 0.9))));
            Assert.assertFalse(deck.fits(scene.inRoom(AcousticRoom.CATHEDRAL)));
            Assert.assertFalse(deck.fits(scene.inRoom(scene.room().withAmount(scene.room().amount() / 2))));
            Assert.assertFalse(deck.fits(scene.inRoom(scene.room().withReverbSeconds(5))));
            Assert.assertFalse(deck.fits(scene.withFixedLevel(false)));
            Assert.assertFalse("a stage that does not fit changes nothing",
                    deck.stage(scene.inRoom(AcousticRoom.CATHEDRAL), null));
        }
    }

    @Test
    public void aTrackAloneIsHeardLiveEvenIfItIsMutedInTheMixAndAJumpIsShownAtOnce() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        SpatialScene scene = new StageMixService().scene(arrangement, song).withFixedLevel(true);
        scene = scene.with(scene.placement("bass").at(1, 0).withMuted(true)).with(scene.placement("melody").at(-1, 0));
        Tape tape = new Tape();
        tape.allow(Integer.MAX_VALUE);
        try (LiveDeck deck = LiveDeck.open(arrangement, song, scene, "bass", Float.NaN, () -> tape)) {
            Assert.assertEquals(0, deck.seconds(), 0);
            deck.seek(0.5); // paused: the display shows the place before a block is rendered
            Assert.assertEquals(0.5, deck.seconds(), 1e-9);
            Assert.assertFalse(deck.playing());
            deck.play();
            awaitEnd(deck);
            Assert.assertEquals(deck.length(), deck.seconds(), 0.1);
            int heard = (int) ((deck.length() - 0.5) * RATE);
            Assert.assertEquals("played from the place of the jump", heard, tape.frames, 2 * BLOCK + 4096);
            Assert.assertTrue("only the bass, on the right", tape.rms(true, BLOCK, RATE / 4)
                    > 3 * tape.rms(false, BLOCK, RATE / 4));
        }
    }

    /** Review of the window's link to the live stage: measuring the level must not publish a mix behind the window's back. */
    @Test
    public void findingTheLevelOfAPieceMixedToItsPeakLeavesItsMixedFileAlone() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        StageMixService service = new StageMixService();
        SpatialScene scene = service.scene(arrangement, song);
        // The window has mixed a newer stage (everything muted) while the live stage is read for the older one.
        SpatialScene newer = scene.with(scene.placement("melody").withMuted(true))
                .with(scene.placement("bass").withMuted(true));
        byte[] published = Files.readAllBytes(service.mix(arrangement, song, newer, message -> { }));
        Tape tape = new Tape();
        LiveDeck.open(arrangement, song, scene, null, Float.NaN, () -> tape).close();
        Assert.assertArrayEquals(published, Files.readAllBytes(song.resolve(StageMixService.MIX_FILE)));
        try (java.util.stream.Stream<Path> files = Files.list(song)) {
            Assert.assertEquals("nothing is left behind: arrangement, two tracks, the mix", 4, files.count());
        }
    }

    /** A track alone is brought to its own peak in its file; live it is as loud as that file, not as in the mix. */
    @Test
    public void aTrackAloneIsAsLoudLiveAsItsOwnFileInAPieceMixedToItsPeak() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        StageMixService service = new StageMixService();
        SpatialScene scene = service.scene(arrangement, song);
        SpatialStemMixer.Signal file = SpatialStemMixer.read(service.solo(arrangement, song, scene, "bass"));
        float whole = LiveDeck.measuredFactor(arrangement, song, scene, null);
        float alone = LiveDeck.measuredFactor(arrangement, song, scene, "bass");
        Assert.assertEquals(service.outcome().legacyFactor(), alone, 0f);
        Assert.assertTrue("alone the bass is raised further than within the mix", alone > 1.2f * whole);
        Tape tape = new Tape();
        tape.allow(Integer.MAX_VALUE);
        try (LiveDeck deck = LiveDeck.open(arrangement, song, scene, "bass", Float.NaN, () -> tape)) {
            deck.play();
            awaitEnd(deck);
            int late = deck.playback().master().latencyFrames();
            for (int i = 0; i < file.left.length; i += 7) {
                Assert.assertEquals("left at " + i, file.left[i], tape.left[i + late], 2e-4f);
            }
        }
    }

    @Test
    public void aJumpAfterThePieceEndedIsWherePlayGoesOn() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        SpatialScene scene = new StageMixService().scene(arrangement, song).withFixedLevel(true);
        Tape tape = new Tape();
        tape.allow(Integer.MAX_VALUE);
        try (LiveDeck deck = LiveDeck.open(arrangement, song, scene, null, Float.NaN, () -> tape)) {
            deck.seek(deck.length() - 0.2);
            deck.play();
            awaitEnd(deck);
            int first = tape.frames;
            // Ended. A jump to four seconds, then Play: from there, not from the start.
            deck.seek(4);
            Assert.assertEquals(4, deck.seconds(), 1e-9);
            deck.play();
            awaitPlayedAgain(deck);
            int heard = (int) ((deck.length() - 4) * RATE);
            Assert.assertEquals(heard, tape.frames - first, 2 * BLOCK + 4096);
            // Without a jump, Play after the end starts from the beginning, as before.
            int second = tape.frames;
            deck.play();
            awaitPlayedAgain(deck);
            Assert.assertEquals((int) (deck.length() * RATE), tape.frames - second, 2 * BLOCK + 4096);
        }
    }

    /** Waits until playback, started again after its end, has ended again. */
    private static void awaitPlayedAgain(LiveDeck deck) throws InterruptedException {
        long until = System.nanoTime() + 20_000_000_000L;
        while (deck.playback().state() == LivePlayback.State.ENDED) {
            Assert.assertTrue("playback does not start again", System.nanoTime() < until);
            Thread.sleep(1);
        }
        awaitEnd(deck);
    }

    /** D2 with D3: the movements the piece keeps in mix.json are heard live, also one saved while it plays. */
    @Test
    public void movementsOfTheMixDocumentAreHeardLiveAlsoWhenSavedWhilePlaying() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        SpatialScene scene = new StageMixService().scene(arrangement, song).withFixedLevel(true).withMaster(-14)
                .inRoom(AcousticRoom.STUDIO.withAmount(0));
        scene = scene.with(scene.placement("melody").at(-1, 0)).with(scene.placement("bass").withMuted(true));
        // The piece says: the melody stands far right, whatever its disc says.
        com.aresstack.audio.composition.mix.MixChannel channel = new com.aresstack.audio.composition.mix.MixChannel(
                "c1", com.aresstack.audio.composition.mix.MixChannel.Owner.part("melody"), -1, 0, 0, 1, 0.3, false, false);
        com.aresstack.audio.composition.mix.Room room = new com.aresstack.audio.composition.mix.Room("studio", 0.4, 0);
        com.aresstack.audio.composition.mix.AutomationLane right = new com.aresstack.audio.composition.mix.AutomationLane(
                "l1", "c1", "x", java.util.List.of(new com.aresstack.audio.composition.mix.AutomationLane.Point(0, 1)));
        com.aresstack.audio.studio.project.MixJson.save(song, new com.aresstack.audio.composition.mix.MixScene(room, 0,
                com.aresstack.audio.composition.mix.LevelMode.FIXED, java.util.List.of(channel), java.util.List.of(right)));
        Tape tape = new Tape();
        try (LiveDeck deck = LiveDeck.open(arrangement, song, scene, null, Float.NaN, () -> tape)) {
            deck.play();
            tape.allow(30);
            tape.awaitFrames(30 * BLOCK);
            Assert.assertTrue(tape.rms(true, 20 * BLOCK, 30 * BLOCK) > 3 * tape.rms(false, 20 * BLOCK, 30 * BLOCK));
            // The curve is taken away and saved while the piece plays: the melody is where its disc is, on the left.
            com.aresstack.audio.studio.project.MixJson.save(song, new com.aresstack.audio.composition.mix.MixScene(room,
                    0, com.aresstack.audio.composition.mix.LevelMode.FIXED, java.util.List.of(channel),
                    java.util.List.of()));
            deck.reloadMovements();
            tape.allow(30);
            tape.awaitFrames(60 * BLOCK);
            Assert.assertTrue(tape.rms(false, 50 * BLOCK, 60 * BLOCK) > 3 * tape.rms(true, 50 * BLOCK, 60 * BLOCK));
            tape.allow(Integer.MAX_VALUE / 2);
        }
    }

    @Test
    public void aTrackThatIsNotRenderedIsNamedUnlessItIsMuted() throws Exception {
        Path song = piece();
        Files.write(song.resolve("arrangement.tsv"), Arrays.asList("tempo\t120", "track\tmelody\t73\t0\tmelody",
                "track\tbass\t32\t1\tbass", "track\tchoir\t52\t2\ttexture"), StandardCharsets.UTF_8);
        Path arrangement = song.resolve("arrangement.tsv");
        SpatialScene scene = new StageMixService().scene(arrangement, song).withFixedLevel(true);
        Tape tape = new Tape();
        try {
            LiveDeck.open(arrangement, song, scene, null, Float.NaN, () -> tape).close();
            Assert.fail("the choir has no sound yet");
        } catch (java.io.IOException missing) {
            Assert.assertTrue(missing.getMessage().contains("choir"));
        }
        LiveDeck.open(arrangement, song, scene.with(scene.placement("choir").withMuted(true)), null, Float.NaN,
                () -> tape).close();
    }

    private static void awaitEnd(LiveDeck deck) throws InterruptedException {
        for (long until = System.nanoTime() + 20_000_000_000L;
             deck.playback().state() != LivePlayback.State.ENDED; ) {
            Assert.assertNull(deck.playback().failure());
            Assert.assertTrue("the piece does not end", System.nanoTime() < until);
            Thread.sleep(2);
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
