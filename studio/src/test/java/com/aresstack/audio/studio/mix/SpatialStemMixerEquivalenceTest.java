package com.aresstack.audio.studio.mix;

import com.aresstack.audio.studio.mix.legacy.LegacySpatialStemMixer;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * D1a acceptance: the block mixer of the audio core gives the same result as the mixer it
 * replaced, to the last bit, including the old normalisation, and for every block size. The
 * old mixer is kept unchanged as {@link LegacySpatialStemMixer} for this comparison.
 */
public final class SpatialStemMixerEquivalenceTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final int[] BLOCKS = {64, 512, 4096, 1, 777};
    private static final AcousticRoom DRY = AcousticRoom.STUDIO.withAmount(0);
    private static final AcousticRoom[] ROOMS = {DRY, AcousticRoom.STUDIO, AcousticRoom.CHAMBER, AcousticRoom.HALL,
            AcousticRoom.CATHEDRAL, AcousticRoom.CATHEDRAL.withAmount(1)};

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void oneTrackSoundsTheSameEverywhereOnTheStageInEveryRoom() throws Exception {
        float[] noise = noise(RATE, 1);
        double[][] places = {{-1, 0}, {0, 0}, {1, 0}, {0, 1}, {-0.5, 0.3}, {0.3, 0.5}, {0.8, 0.9}};
        for (AcousticRoom room : ROOMS) {
            for (double[] place : places) {
                assertSame(Arrays.asList(stereo(noise, noise, RATE)), Arrays.asList(place(place[0], place[1])), room, 2);
            }
        }
    }

    @Test
    public void manyTracksWithLevelsWidthsMovesAndMutesSoundTheSame() throws Exception {
        List<Signal> signals = new ArrayList<Signal>();
        List<SoundSourcePlacement> placements = new ArrayList<SoundSourcePlacement>();
        List<SoundSourcePlacement.LevelPoint> swell = Arrays.asList(new SoundSourcePlacement.LevelPoint(0, -18),
                new SoundSourcePlacement.LevelPoint(1.3, 0), new SoundSourcePlacement.LevelPoint(2.7, -6));
        signals.add(stereo(noise(3 * RATE, 2), noise(3 * RATE, 3), RATE));
        placements.add(new SoundSourcePlacement("a", -0.7, 0.2, -3, 0.4, 0.5, false, false, swell));
        signals.add(stereo(tone(2 * RATE + 123, 220), tone(2 * RATE + 123, 330), RATE));
        placements.add(new SoundSourcePlacement("b", 0.4, 0.8, 2, 1, 0.9, false, false, null));
        signals.add(stereo(noise(RATE, 4), noise(RATE, 4), RATE));
        placements.add(new SoundSourcePlacement("c", 0, 0, 0, 1, 0.35, true, false, null));
        signals.add(stereo(clicks(5 * RATE / 2), clicks(5 * RATE / 2), RATE));
        placements.add(new SoundSourcePlacement("d", 0.95, 0.05, -12, 0, 0.1, false, false, swell));
        signals.add(stereo(new float[0], new float[0], RATE));
        placements.add(new SoundSourcePlacement("e", 0, 0.5, 0, 1, 0.35, false, false, null));
        for (AcousticRoom room : ROOMS) {
            assertSame(signals, placements, room, 1.7);
        }
    }

    @Test
    public void tracksAtOtherRatesAreResampledTheSame() throws Exception {
        List<Signal> signals = new ArrayList<Signal>();
        List<SoundSourcePlacement> placements = new ArrayList<SoundSourcePlacement>();
        signals.add(stereo(tone(22050, 440), tone(22050, 441), 22050));
        placements.add(place(-0.5, 0.2));
        signals.add(stereo(noise(48000 + 7, 5), noise(48000 + 7, 6), 48000));
        placements.add(place(0.5, 0.4));
        signals.add(stereo(noise(8001, 7), noise(8001, 7), 8000));
        placements.add(place(0, 0.9));
        signals.add(stereo(noise(30000, 8), noise(30000, 9), 96000));
        placements.add(place(0.2, 0.1));
        for (AcousticRoom room : new AcousticRoom[]{DRY, AcousticRoom.HALL}) {
            assertSame(signals, placements, room, 2);
        }
    }

    @Test
    public void theNormalisedMixOfFilesIsTheSameFile() throws Exception {
        Path a = folder.getRoot().toPath().resolve("a.wav");
        Path b = folder.getRoot().toPath().resolve("b.wav");
        Path c = folder.getRoot().toPath().resolve("c.wav");
        writeTrack(stereo(noise(2 * RATE, 10), noise(2 * RATE, 11), RATE), a, 16);
        writeTrack(stereo(tone(RATE * 3 / 2, 330), tone(RATE * 3 / 2, 330), RATE), b, 24);
        writeTrack(stereo(tone(RATE, 110), tone(RATE, 110), RATE), c, 16);
        List<Path> files = Arrays.asList(a, b, c);
        List<SoundSourcePlacement> placements = Arrays.asList(place(-0.6, 0.3),
                new SoundSourcePlacement("b", 0.5, 0.7, -4, 0.7, 0.6, false, false,
                        Arrays.asList(new SoundSourcePlacement.LevelPoint(0, -10), new SoundSourcePlacement.LevelPoint(3, 0))),
                place(0, 0).withMuted(true));
        for (AcousticRoom room : new AcousticRoom[]{DRY, AcousticRoom.HALL, AcousticRoom.CATHEDRAL}) {
            for (double masterDb : new double[]{0, -7.5}) {
                for (int bits : new int[]{16, 24}) {
                    Path old = folder.getRoot().toPath().resolve("old.wav");
                    new LegacySpatialStemMixer().mix(files, placements, room, 2.2, masterDb, old, bits);
                    for (int block : BLOCKS) {
                        Path fresh = folder.getRoot().toPath().resolve("new-" + block + ".wav");
                        new SpatialStemMixer(block).mix(files, placements, room, 2.2, masterDb, fresh, bits);
                        Assert.assertArrayEquals(room.name() + ", " + masterDb + " dB, " + bits + " bits, block " + block,
                                Files.readAllBytes(old), Files.readAllBytes(fresh));
                    }
                }
            }
        }
    }

    @Test
    public void readingAndWritingStayAsTheyWere() throws Exception {
        Signal signal = stereo(noise(RATE / 3, 12), tone(RATE / 3, 1000), RATE);
        for (int bits : new int[]{16, 24}) {
            Path old = folder.getRoot().toPath().resolve("old-" + bits + ".wav");
            Path fresh = folder.getRoot().toPath().resolve("new-" + bits + ".wav");
            LegacySpatialStemMixer.write(LegacySpatialStemMixer.master(new LegacySpatialStemMixer().render(
                    Arrays.asList(signal.old()), Arrays.asList(place(0.1, 0.2)), AcousticRoom.HALL, 2)), old, bits);
            SpatialStemMixer.write(SpatialStemMixer.master(new SpatialStemMixer().render(
                    Arrays.asList(signal.fresh()), Arrays.asList(place(0.1, 0.2)), AcousticRoom.HALL, 2)), fresh, bits);
            Assert.assertArrayEquals(Files.readAllBytes(old), Files.readAllBytes(fresh));
            LegacySpatialStemMixer.Signal before = LegacySpatialStemMixer.read(old);
            SpatialStemMixer.Signal after = SpatialStemMixer.read(fresh);
            Assert.assertArrayEquals(before.left, after.left, 0f);
            Assert.assertArrayEquals(before.right, after.right, 0f);
            Assert.assertEquals(before.rate, after.rate, 0f);
        }
    }

    private static void assertSame(List<Signal> signals, List<SoundSourcePlacement> placements, AcousticRoom room,
                                   double secondsPerBar) throws Exception {
        List<LegacySpatialStemMixer.Signal> old = new ArrayList<LegacySpatialStemMixer.Signal>();
        List<SpatialStemMixer.Signal> fresh = new ArrayList<SpatialStemMixer.Signal>();
        for (Signal signal : signals) {
            old.add(signal.old());
            fresh.add(signal.fresh());
        }
        LegacySpatialStemMixer.Mix expected = new LegacySpatialStemMixer().render(old, placements, room, secondsPerBar);
        float[] left = expected.left.clone();
        float[] right = expected.right.clone();
        float mastered = LegacySpatialStemMixer.master(expected).peak(); // scales in place, hence the copies
        for (int block : BLOCKS) {
            SpatialStemMixer.Mix actual = new SpatialStemMixer(block).render(fresh, placements, room, secondsPerBar);
            String what = room.name() + ", block " + block + ", " + placements;
            // Equal as numbers; a delta of 0 still treats -0 and +0 as the same, which a WAV cannot tell apart.
            Assert.assertArrayEquals(what, left, actual.left, 0f);
            Assert.assertArrayEquals(what, right, actual.right, 0f);
            Assert.assertEquals(what, mastered, SpatialStemMixer.master(actual).peak(), 0f);
        }
    }

    private void writeTrack(Signal signal, Path file, int bits) throws Exception {
        LegacySpatialStemMixer.write(LegacySpatialStemMixer.master(new LegacySpatialStemMixer().render(
                Arrays.asList(signal.old()), Arrays.asList(place(0, 0)), DRY, 2)), file, bits);
    }

    /** A test signal for both mixers. */
    private record Signal(float[] left, float[] right, float rate) {
        LegacySpatialStemMixer.Signal old() {
            return new LegacySpatialStemMixer.Signal(left.clone(), right.clone(), rate);
        }

        SpatialStemMixer.Signal fresh() {
            return new SpatialStemMixer.Signal(left.clone(), right.clone(), rate);
        }
    }

    private static Signal stereo(float[] left, float[] right, float rate) {
        return new Signal(left, right, rate);
    }

    private static SoundSourcePlacement place(double x, double depth) {
        return new SoundSourcePlacement("a", x, depth, 0, 1, 0.35, false, false, null);
    }

    private static float[] noise(int frames, long seed) {
        Random random = new Random(seed);
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (float) ((random.nextDouble() * 2 - 1) * 0.3);
        }
        return samples;
    }

    private static float[] tone(int frames, double frequency) {
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * i / RATE));
        }
        return samples;
    }

    private static float[] clicks(int frames) {
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i += 9000) {
            samples[i] = 0.9f;
        }
        return samples;
    }
}
