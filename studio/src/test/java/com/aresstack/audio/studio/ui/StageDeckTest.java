package com.aresstack.audio.studio.ui;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.live.LiveSink;
import com.aresstack.audio.studio.application.StageMixService;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The desk plays the stage live and keeps the mixed file as the picture of the waveform and as
 * the fallback (work package D2). Checked without a sound card: the live stage plays into a sink
 * that takes a block every few milliseconds, as a sound card would.
 */
public final class StageDeckTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** A sound card of a test: takes its time for every block and counts how often it was opened and closed. */
    private static final class Card implements LiveSink {
        static final AtomicInteger OPEN = new AtomicInteger();
        private volatile boolean running;

        Card() {
            OPEN.incrementAndGet();
        }

        @Override
        public void write(AudioBlock block) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void pause() {
            running = false;
        }

        @Override
        public void close() {
            OPEN.decrementAndGet();
        }
    }

    @Test
    public void theStageIsPlayedLiveAndAnotherRoomGoesOnWherePlaybackStands() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        StageMixService service = new StageMixService();
        SpatialScene scene = service.scene(arrangement, song).withFixedLevel(true);
        Path mixed = service.mix(arrangement, song, scene, message -> { });
        Card.OPEN.set(0);
        StageDeck deck = new StageDeck(Card::new);
        AtomicReference<Exception> problem = new AtomicReference<Exception>();

        // A jump before anything played: the stage is read in the background and stands at that place.
        CountDownLatch first = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            deck.show(mixed);
            Assert.assertFalse(deck.ready());
            deck.openLive(arrangement, song, scene, null, Float.NaN, 1.5, false, said -> {
                problem.set(said);
                first.countDown();
            });
            Assert.assertTrue(deck.loading());
            Assert.assertEquals("the display shows the place while the tracks are read", 1.5, deck.seconds(), 0);
        });
        Assert.assertTrue(first.await(20, TimeUnit.SECONDS));
        Assert.assertNull(problem.get());
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertTrue(deck.live());
            Assert.assertTrue(deck.ready());
            Assert.assertFalse(deck.playing());
            Assert.assertEquals(1.5, deck.seconds(), 1e-9);
            Assert.assertEquals(mixed, deck.file());
            Assert.assertNotNull("the waveform of the mixed file stays", deck.envelope());
            deck.play();
        });
        awaitPlaying(deck);

        // Moved on the stage: heard live. The file mixed afterwards only renews the picture.
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertTrue(deck.stage(scene.with(scene.placement("melody").at(1, 0.5)), null));
            try {
                deck.swap(mixed);
            } catch (Exception failed) {
                throw new AssertionError(failed);
            }
            Assert.assertTrue(deck.live());
            Assert.assertTrue(deck.playing());
            Assert.assertTrue(deck.level(false) > 0);
        });

        // Another room: this live stage cannot play it; a new one goes on where playback stands, still playing.
        SpatialScene cathedral = scene.inRoom(AcousticRoom.CATHEDRAL);
        CountDownLatch second = new CountDownLatch(1);
        double[] before = new double[1];
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertFalse(deck.stage(cathedral, null));
            before[0] = deck.seconds();
            deck.openLive(arrangement, song, cathedral, null, Float.NaN, Double.NaN, false, said -> {
                problem.set(said);
                second.countDown();
            });
            Assert.assertTrue("the old room plays on until the new one is ready", deck.playing());
        });
        Assert.assertTrue(second.await(20, TimeUnit.SECONDS));
        Assert.assertNull(problem.get());
        awaitPlaying(deck);
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertTrue(deck.stage(cathedral, null));
            Assert.assertTrue(deck.seconds() >= before[0] - 0.2);
            Assert.assertEquals("the old live stage gave its sound card back", 1, Card.OPEN.get());
            deck.pause();
            deck.seek(0.5);
            Assert.assertEquals(0.5 * deck.length(), deck.seconds(), 1e-6);
            deck.close();
            Assert.assertFalse(deck.live());
            Assert.assertNull(deck.file());
        });
        Assert.assertEquals(0, Card.OPEN.get());
    }

    /**
     * Work package D4: the way of hearing belongs to the live stage. Switched to headphones, the
     * stage that plays for loudspeakers no longer fits and a new one goes on where playback stands.
     */
    @Test
    public void switchedToHeadphonesTheLiveStageIsMadeAnewAndGoesOn() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        SpatialScene scene = new StageMixService().scene(arrangement, song).withFixedLevel(true);
        Card.OPEN.set(0);
        StageDeck deck = new StageDeck(Card::new);
        AtomicReference<Exception> problem = new AtomicReference<Exception>();
        CountDownLatch first = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> deck.openLive(arrangement, song, scene, null, Float.NaN, 0, true, said -> {
            problem.set(said);
            first.countDown();
        }));
        Assert.assertTrue(first.await(20, TimeUnit.SECONDS));
        Assert.assertNull(problem.get());
        awaitPlaying(deck);

        com.aresstack.audio.studio.mix.StageSound headphones = com.aresstack.audio.studio.mix.StageSound.binaural();
        CountDownLatch second = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertTrue(deck.stage(scene, null));
            deck.sound(headphones);
            Assert.assertFalse("a stage for loudspeakers does not play for headphones", deck.stage(scene, null));
            deck.openLive(arrangement, song, scene, null, Float.NaN, Double.NaN, false, said -> {
                problem.set(said);
                second.countDown();
            });
            Assert.assertTrue("the old stage plays on until the new one is ready", deck.playing());
        });
        Assert.assertTrue(second.await(20, TimeUnit.SECONDS));
        Assert.assertNull(problem.get());
        awaitPlaying(deck);
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertTrue(deck.stage(scene, null));
            // A track lifted above the ears is taken live, like any other move.
            Assert.assertTrue(deck.stage(scene.with(scene.placement("melody").atHeight(0.8)), null));
            Assert.assertEquals("the old live stage gave its sound card back", 1, Card.OPEN.get());
            deck.close();
        });
        Assert.assertEquals(0, Card.OPEN.get());
    }

    /** Review: Stop pressed while the tracks are still read must hold when the stage is ready. */
    @Test
    public void stopAndAJumpWhileTheStageIsReadCountWhenItIsReady() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        StageMixService service = new StageMixService();
        SpatialScene scene = service.scene(arrangement, song).withFixedLevel(true);
        Path mixed = service.mix(arrangement, song, scene, message -> { });
        CountDownLatch mayOpen = new CountDownLatch(1);
        StageDeck deck = new StageDeck(() -> {
            mayOpen.await(20, TimeUnit.SECONDS); // the sound card takes its time: the stage is still loading
            return new Card();
        });
        CountDownLatch ready = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            deck.show(mixed);
            deck.openLive(arrangement, song, scene, null, Float.NaN, 0, true, said -> ready.countDown());
            // Stop: pause and back to the start, as the window does it; then a jump to the middle.
            deck.pause();
            deck.seek(0);
            Assert.assertEquals(0, deck.seconds(), 0);
            deck.seek(0.5);
            Assert.assertEquals(0.5 * deck.envelope().seconds, deck.seconds(), 1e-9);
        });
        mayOpen.countDown();
        Assert.assertTrue(ready.await(20, TimeUnit.SECONDS));
        Thread.sleep(100);
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertTrue(deck.live());
            Assert.assertFalse("Stop was pressed while the tracks were read", deck.playing());
            Assert.assertEquals(0.5 * deck.envelope().seconds, deck.seconds(), 0.01);
            deck.close();
        });
        // And Play pressed while a paused stage is read plays it when it is ready.
        StageDeck other = new StageDeck(Card::new);
        CountDownLatch again = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            other.show(mixed);
            other.openLive(arrangement, song, scene, null, Float.NaN, 1, false, said -> again.countDown());
            other.play();
        });
        Assert.assertTrue(again.await(20, TimeUnit.SECONDS));
        awaitPlaying(other);
        SwingUtilities.invokeAndWait(other::close);
    }

    @Test
    public void aStageThatCannotBePlayedLiveSaysWhyAndLeavesTheFile() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        StageMixService service = new StageMixService();
        SpatialScene scene = service.scene(arrangement, song).withFixedLevel(true);
        Path mixed = service.mix(arrangement, song, scene, message -> { });
        StageDeck deck = new StageDeck(() -> {
            throw new javax.sound.sampled.LineUnavailableException("keine Tonausgabe");
        });
        AtomicReference<Exception> problem = new AtomicReference<Exception>();
        CountDownLatch told = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            deck.show(mixed);
            deck.openLive(arrangement, song, scene, null, Float.NaN, 0, true, said -> {
                problem.set(said);
                told.countDown();
            });
        });
        Assert.assertTrue(told.await(20, TimeUnit.SECONDS));
        Assert.assertTrue(problem.get().getMessage().contains("keine Tonausgabe"));
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertFalse(deck.live());
            Assert.assertFalse(deck.loading());
            Assert.assertEquals("the file is still there to fall back to", mixed, deck.file());
            Assert.assertTrue("without a live stage every stage is fine: the file is mixed", deck.stage(scene, null));
            deck.close();
        });
    }

    private static void awaitPlaying(StageDeck deck) throws Exception {
        boolean[] playing = new boolean[1];
        for (long until = System.nanoTime() + 20_000_000_000L; !playing[0]; ) {
            Assert.assertTrue("playback does not start", System.nanoTime() < until);
            Thread.sleep(5);
            SwingUtilities.invokeAndWait(() -> playing[0] = deck.playing());
        }
    }

    /** A piece of eight seconds: its arrangement and a tone as the sound of the melody and of the bass. */
    private Path piece() throws Exception {
        Path song = folder.newFolder().toPath();
        Files.write(song.resolve("arrangement.tsv"), Arrays.asList("tempo\t120", "track\tmelody\t73\t0\tmelody",
                "track\tbass\t32\t1\tbass"), StandardCharsets.UTF_8);
        float[] samples = new float[SpatialStemMixer.RATE * 8];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.3 * Math.sin(2 * Math.PI * 330 * i / SpatialStemMixer.RATE));
        }
        SpatialStemMixer.Mix tone = new SpatialStemMixer().render(
                Arrays.asList(new SpatialStemMixer.Signal(samples, samples, SpatialStemMixer.RATE)),
                Arrays.asList(new SoundSourcePlacement("a", 0, 0, 0, 1, 0, false, false, null)),
                AcousticRoom.STUDIO.withAmount(0), 2);
        SpatialStemMixer.write(tone, song.resolve("melody.wav"), 16);
        SpatialStemMixer.write(tone, song.resolve("bass.wav"), 16);
        return song;
    }
}
