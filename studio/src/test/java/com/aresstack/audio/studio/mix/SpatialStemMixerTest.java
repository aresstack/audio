package com.aresstack.audio.studio.mix;

import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** What the stage does to the sound: sides, distance, room, level over time, and a mix that never clips. */
public final class SpatialStemMixerTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final AcousticRoom DRY = AcousticRoom.STUDIO.withAmount(0);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final SpatialStemMixer mixer = new SpatialStemMixer();

    @Test
    public void aTrackOnTheLeftIsHeardOnTheLeftAndEquallyLoudWhereverItStands() throws Exception {
        // A low tone, so that the dulling of the longer way does not count.
        SpatialStemMixer.Mix left = alone(tone(RATE, 220), place(-1, 0), DRY);
        SpatialStemMixer.Mix middle = alone(tone(RATE, 220), place(0, 0), DRY);
        SpatialStemMixer.Mix right = alone(tone(RATE, 220), place(1, 0), DRY);

        Assert.assertTrue(rms(left.left) > 10 * rms(left.right));
        Assert.assertTrue(rms(right.right) > 10 * rms(right.left));
        Assert.assertEquals(rms(middle.left), rms(middle.right), 1e-6);
        // Equal power: the same loudness in the middle and at the side, apart from the longer way.
        double way = Math.pow(3 / SpatialStemMixer.distance(place(-1, 0)), 0.45);
        Assert.assertEquals(power(middle) * way * way, power(left), power(middle) * 0.03);
        Assert.assertEquals(power(left), power(right), 1e-6);
    }

    @Test
    public void aTrackFartherBackIsQuieterAndDuller() throws Exception {
        SpatialStemMixer.Mix near = alone(noise(RATE, 2), place(0, 0), DRY);
        SpatialStemMixer.Mix far = alone(noise(RATE, 2), place(0, 1), DRY);

        Assert.assertTrue(power(far) < power(near) * 0.7);
        // The heights lose more than the whole: the share of fast changes from sample to sample falls.
        Assert.assertTrue(roughness(far.left) < roughness(near.left) * 0.85);
    }

    @Test
    public void theRoomRingsOnAfterTheTrackAndADryRoomDoesNot() throws Exception {
        float[] burst = noise(RATE / 2, 3);
        SpatialStemMixer.Mix hall = alone(burst, place(0, 0.6), AcousticRoom.HALL);
        SpatialStemMixer.Mix dry = alone(burst, place(0, 0.6), DRY);

        Assert.assertTrue("the ring is rendered to its end", hall.left.length >= burst.length + 2 * RATE);
        double early = rms(hall.left, burst.length + RATE / 10, burst.length + RATE / 5);
        double late = rms(hall.left, burst.length + RATE * 3 / 2, burst.length + RATE * 8 / 5);
        Assert.assertTrue(early > 1e-4);
        Assert.assertTrue("the ring dies away", late < early * 0.2);
        Assert.assertTrue(rms(dry.left, burst.length + RATE / 100, dry.left.length) < 1e-6);
        // A track at the back has more room in its sound than one at the front.
        SpatialStemMixer.Mix front = alone(burst, place(0, 0), AcousticRoom.HALL);
        double roomAtBack = rms(hall.left, burst.length + RATE / 10, burst.length + RATE / 5) / rms(hall.left, 0, burst.length);
        double roomInFront = rms(front.left, burst.length + RATE / 10, burst.length + RATE / 5) / rms(front.left, 0, burst.length);
        Assert.assertTrue(roomAtBack > roomInFront * 1.5);
    }

    @Test
    public void theWallsAnswerWithEarlyEchoes() throws Exception {
        float[] click = new float[RATE / 2];
        click[0] = 0.8f;
        AcousticRoom echoing = AcousticRoom.CHAMBER;
        SpatialStemMixer.Mix mix = alone(click, new SoundSourcePlacement("a", -0.5, 0.3, 0, 1, 0, false, false, null),
                echoing);
        // Without any share of the ring, what follows the click within 60 ms are the mirrored sources.
        double echoes = rms(mix.left, RATE / 200, RATE * 6 / 100);
        Assert.assertTrue(echoes > 1e-4);
        SpatialStemMixer.Mix none = alone(click, new SoundSourcePlacement("a", -0.5, 0.3, 0, 1, 0, false, false, null), DRY);
        Assert.assertTrue(rms(none.left, RATE / 200, RATE * 6 / 100) < 1e-6);
    }

    @Test
    public void levelMutingAndWidthDoWhatTheySay() throws Exception {
        float[] tone = tone(RATE, 220);
        SpatialStemMixer.Mix full = alone(tone, place(0, 0), DRY);
        SpatialStemMixer.Mix quieter = alone(tone, place(0, 0).withLevel(-6), DRY);
        SpatialStemMixer.Mix muted = alone(tone, place(0, 0).withMuted(true), DRY);

        Assert.assertEquals(Math.pow(10, -6 / 20.0), rms(quieter.left) / rms(full.left), 0.01);
        Assert.assertEquals(0, muted.peak(), 0);

        // A track recorded wide: at width 0 both sides carry the same, at width 1 they differ.
        SpatialStemMixer.Signal wide = new SpatialStemMixer.Signal(noise(RATE, 5), noise(RATE, 6), RATE);
        SpatialStemMixer.Mix point = mixer.render(Arrays.asList(wide), Arrays.asList(place(0, 0).withWidth(0)), DRY, 2);
        SpatialStemMixer.Mix broad = mixer.render(Arrays.asList(wide), Arrays.asList(place(0, 0).withWidth(1)), DRY, 2);
        Assert.assertTrue(difference(point) < 1e-6);
        Assert.assertTrue(difference(broad) > 0.05);
    }

    @Test
    public void theMixStaysWholeWhenBothSidesAreAddedToOne() throws Exception {
        // Mono compatibility: nothing of a track is cancelled when a mono loudspeaker adds the sides.
        for (double x : new double[]{-1, -0.4, 0, 0.7}) {
            SpatialStemMixer.Mix mix = alone(noise(RATE, 7), place(x, 0.3), AcousticRoom.HALL);
            float[] mono = new float[mix.left.length];
            for (int i = 0; i < mono.length; i++) {
                mono[i] = (mix.left[i] + mix.right[i]) * 0.5f;
            }
            Assert.assertTrue("x=" + x, rms(mono) > 0.6 * Math.sqrt(power(mix) / 2));
        }
    }

    @Test
    public void aLevelThatMovesOverTheBarsGlidesAndNeverClicks() throws Exception {
        float[] steady = new float[RATE * 2];
        Arrays.fill(steady, 0.5f);
        List<SoundSourcePlacement.LevelPoint> swell = Arrays.asList(
                new SoundSourcePlacement.LevelPoint(0, -12), new SoundSourcePlacement.LevelPoint(1, 0));
        // One bar lasts one second here; the room is dry, so only the level acts on the steady signal.
        SpatialStemMixer.Mix mix = alone(steady, place(0, 0).withDynamics(swell), DRY, 1);

        Assert.assertEquals(0.5 * Math.pow(10, -12 / 20.0), mix.left[10], 0.01);
        Assert.assertEquals(0.5, mix.left[RATE + RATE / 2], 0.01);
        Assert.assertTrue(mix.left[RATE / 2] > mix.left[10] && mix.left[RATE / 2] < mix.left[RATE + 10]);
        float largestStep = 0;
        for (int i = 1; i < RATE * 2 - 1; i++) {
            largestStep = Math.max(largestStep, Math.abs(mix.left[i] - mix.left[i - 1]));
        }
        Assert.assertTrue("no jump from one sample to the next: " + largestStep, largestStep < 1e-4f);
    }

    @Test
    public void manyLoudTracksNeverClipAndAQuietMixIsRaised() throws Exception {
        List<SpatialStemMixer.Signal> signals = new ArrayList<SpatialStemMixer.Signal>();
        List<SoundSourcePlacement> placements = new ArrayList<SoundSourcePlacement>();
        for (int i = 0; i < 16; i++) {
            float[] loud = tone(RATE, 110 * (i + 1));
            signals.add(new SpatialStemMixer.Signal(loud, loud, RATE));
            placements.add(place(-1 + i / 7.5, 0.1).withLevel(6));
        }
        SpatialStemMixer.Mix loud = SpatialStemMixer.master(mixer.render(signals, placements, AcousticRoom.CATHEDRAL, 2));
        Assert.assertTrue(loud.peak() <= 0.921f);

        SpatialStemMixer.Mix quiet = SpatialStemMixer.master(alone(tone(RATE, 220), place(0, 0.3).withLevel(-3), DRY));
        Assert.assertEquals(0.92f, quiet.peak(), 0.001f);
        SpatialStemMixer.Mix silent = SpatialStemMixer.master(alone(new float[RATE], place(0, 0), DRY));
        Assert.assertEquals(0, silent.peak(), 0);
    }

    @Test
    public void theSameInputAlwaysGivesTheSameMix() throws Exception {
        SpatialStemMixer.Mix first = alone(noise(RATE, 9), place(0.3, 0.5), AcousticRoom.HALL);
        SpatialStemMixer.Mix second = alone(noise(RATE, 9), place(0.3, 0.5), AcousticRoom.HALL);
        Assert.assertArrayEquals(first.left, second.left, 0);
        Assert.assertArrayEquals(first.right, second.right, 0);
    }

    @Test
    public void writesAndReadsWavesOf16And24BitsAndMixesOtherSampleRates() throws Exception {
        SpatialStemMixer.Mix mix = alone(tone(RATE / 2, 440), place(-0.5, 0.2), DRY);
        for (int bits : new int[]{16, 24}) {
            Path file = folder.newFile("mix-" + bits + ".wav").toPath();
            SpatialStemMixer.write(mix, file, bits);
            SpatialStemMixer.Signal read = SpatialStemMixer.read(file);
            Assert.assertEquals(mix.left.length, read.left.length);
            for (int i = 0; i < mix.left.length; i += 97) {
                Assert.assertEquals(mix.left[i], read.left[i], bits == 16 ? 1 / 30000.0 : 1 / 8000000.0);
                Assert.assertEquals(mix.right[i], read.right[i], bits == 16 ? 1 / 30000.0 : 1 / 8000000.0);
            }
        }
        // A track rendered at 22.05 kHz is as long in the mix as it was.
        float[] slow = tone(22050, 220);
        SpatialStemMixer.Mix resampled = mixer.render(
                Arrays.asList(new SpatialStemMixer.Signal(slow, slow, 22050)), Arrays.asList(place(0, 0)), DRY, 2);
        Assert.assertEquals(RATE + RATE * 3 / 10, resampled.left.length, 2);
        Assert.assertTrue(rms(resampled.left, 0, RATE) > 0.1);
    }

    private SpatialStemMixer.Mix alone(float[] samples, SoundSourcePlacement placement, AcousticRoom room)
            throws Exception {
        return alone(samples, placement, room, 2);
    }

    private SpatialStemMixer.Mix alone(float[] samples, SoundSourcePlacement placement, AcousticRoom room,
                                       double secondsPerBar) throws Exception {
        return mixer.render(Collections.singletonList(new SpatialStemMixer.Signal(samples, samples, RATE)),
                Collections.singletonList(placement), room, secondsPerBar);
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

    private static double rms(float[] samples) {
        return rms(samples, 0, samples.length);
    }

    private static double rms(float[] samples, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += samples[i] * (double) samples[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }

    private static double power(SpatialStemMixer.Mix mix) {
        double left = rms(mix.left);
        double right = rms(mix.right);
        return left * left + right * right;
    }

    private static double difference(SpatialStemMixer.Mix mix) {
        double sum = 0;
        for (int i = 0; i < mix.left.length; i++) {
            sum += Math.abs(mix.left[i] - mix.right[i]);
        }
        return sum / mix.left.length;
    }

    /** How much of a signal is fast change: the loudness of its differences, relative to its own. */
    private static double roughness(float[] samples) {
        float[] steps = new float[samples.length - 1];
        for (int i = 0; i < steps.length; i++) {
            steps[i] = samples[i + 1] - samples[i];
        }
        return rms(steps, 0, RATE) / rms(samples, 0, RATE);
    }
}
