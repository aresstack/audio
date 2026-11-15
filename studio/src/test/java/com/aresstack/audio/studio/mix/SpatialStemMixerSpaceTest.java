package com.aresstack.audio.studio.mix;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.conv.ImpulseResponse;
import com.aresstack.audio.core.conv.SyntheticRoom;
import com.aresstack.audio.core.live.LiveGraph;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * Work package D4 in the studio: height as the third coordinate, headphones (HRTF) and the
 * convolved room. Loudspeakers with the algorithmic room stay the mix as before (golden tests);
 * on headphones a mirrored stage gives mirrored ears, height is heard, a moving source does not
 * depend on the block size, and the live stage sounds like the file.
 */
public final class SpatialStemMixerSpaceTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final long BAR = 4L * TimeMap.PPQ;
    /** A room that answers the same on both sides, so that a mirrored stage must give mirrored ears. */
    private static final ImpulseResponse SYMMETRIC;

    static {
        ImpulseResponse room = SyntheticRoom.generate(0.6, 0.4, 0.01, RATE, 9);
        SYMMETRIC = new ImpulseResponse(room.left(), room.left(), RATE).scaled(0.3);
    }

    private static SpatialStemMixer.Signal noise(int frames, long seed) {
        Random random = new Random(seed);
        float[] left = new float[frames];
        float[] right = new float[frames];
        for (int i = 0; i < frames; i++) {
            left[i] = (float) (random.nextGaussian() * 0.2);
            right[i] = (float) (random.nextGaussian() * 0.2);
        }
        return new SpatialStemMixer.Signal(left, right, RATE);
    }

    private static SoundSourcePlacement at(String id, double x, double depth, double height) {
        return new SoundSourcePlacement(id, x, depth, -2, 0.8, 0.4, false, false, null, height);
    }

    @Test
    public void onHeadphonesAMirroredStageGivesMirroredEars() throws Exception {
        // Mono sources, so that only the place decides about left and right.
        SpatialStemMixer.Signal a = noise(RATE / 2, 1);
        SpatialStemMixer.Signal b = noise(RATE / 2, 2);
        a = new SpatialStemMixer.Signal(a.left, a.left.clone(), RATE);
        b = new SpatialStemMixer.Signal(b.left, b.left.clone(), RATE);
        StageSound sound = StageSound.binaural().withImpulseResponse(SYMMETRIC);
        SpatialStemMixer mixer = new SpatialStemMixer().withSound(sound);
        SpatialStemMixer.Mix right = mixer.render(List.of(a, b),
                List.of(at("a", 0.6, 0.3, 0.2), at("b", 0.1, 0.8, 0.7)), AcousticRoom.HALL, 2);
        SpatialStemMixer.Mix left = mixer.render(List.of(a, b),
                List.of(at("a", -0.6, 0.3, 0.2), at("b", -0.1, 0.8, 0.7)), AcousticRoom.HALL, 2);
        assertArrayEquals(right.left, left.right, 1e-5f);
        assertArrayEquals(right.right, left.left, 1e-5f);
        assertTrue(rms(right.right) > rms(right.left) * 1.1);
    }

    @Test
    public void heightIsHeardOnHeadphonesAndMakesASourceFartherOnLoudspeakers() throws Exception {
        SpatialStemMixer.Signal a = noise(RATE / 2, 3);
        List<SpatialStemMixer.Signal> one = List.of(a);
        for (StageSound sound : new StageSound[] {StageSound.LOUDSPEAKERS, StageSound.binaural()}) {
            SpatialStemMixer mixer = new SpatialStemMixer().withSound(sound);
            SpatialStemMixer.Mix low = mixer.render(one, List.of(at("a", 0.3, 0.2, 0)), AcousticRoom.STUDIO, 2);
            SpatialStemMixer.Mix high = mixer.render(one, List.of(at("a", 0.3, 0.2, 1)), AcousticRoom.STUDIO, 2);
            double difference = 0;
            for (int i = 0; i < low.left.length; i++) {
                difference += Math.abs(low.left[i] - high.left[i]);
            }
            assertTrue(sound + ": height changes nothing", difference > 1);
            // Six metres up is farther away: the direct sound is quieter.
            assertTrue(rms(high.left) + rms(high.right) < rms(low.left) + rms(low.right));
        }
    }

    @Test
    public void heightZeroIsTheStageAsBefore() throws Exception {
        SpatialStemMixer.Signal a = noise(RATE / 2, 4);
        SoundSourcePlacement old = new SoundSourcePlacement("a", 0.3, 0.6, -2, 0.8, 0.4, false, false, null);
        SpatialStemMixer.Mix before = new SpatialStemMixer().render(List.of(a), List.of(old), AcousticRoom.HALL, 2);
        SpatialStemMixer.Mix now = new SpatialStemMixer().render(List.of(a), List.of(at("a", 0.3, 0.6, 0)),
                AcousticRoom.HALL, 2);
        assertArrayEquals(before.left, now.left, 0f);
        assertEquals(SpatialStemMixer.pan(0.3, 0.6), SpatialStemMixer.pan(0.3, 0.6, 0), 0);
        assertEquals(SpatialStemMixer.distance(0.3, 0.6), SpatialStemMixer.distance(0.3, 0.6, 0), 0);
    }

    @Test
    public void theConvolvedRoomRingsAboutAsLoudAsTheAlgorithmicOne() throws Exception {
        SpatialStemMixer.Signal a = noise(RATE / 4, 5);
        List<SoundSourcePlacement> stage = List.of(at("a", 0, 0.5, 0));
        SpatialStemMixer.Mix algorithmic = new SpatialStemMixer().render(List.of(a), stage, AcousticRoom.HALL, 2);
        SpatialStemMixer.Mix convolved = new SpatialStemMixer().withSound(StageSound.LOUDSPEAKERS.withConvolution(true))
                .render(List.of(a), stage, AcousticRoom.HALL, 2);
        // The ring after the sound has ended.
        int from = RATE / 4 + RATE / 10;
        int to = from + RATE / 2;
        double ratio = 20 * Math.log10(rms(convolved.left, from, to) / rms(algorithmic.left, from, to));
        assertTrue("convolved room is " + ratio + " dB from the algorithmic one", Math.abs(ratio) < 6);
    }

    @Test
    public void aSourceMovingUpAndAroundDoesNotDependOnTheBlockSize() throws Exception {
        MixChannel melody = new MixChannel("melody-ch", MixChannel.Owner.part("melody"), 0.4, 0.6, -1, 0.7, 0.4,
                false, false);
        List<AutomationLane> lanes = new ArrayList<>();
        lanes.add(lane("melody-ch", AutomationLane.X, new long[] {0, 2 * BAR}, new double[] {-1, 1}));
        lanes.add(lane("melody-ch", AutomationLane.HEIGHT, new long[] {0, 2 * BAR}, new double[] {0, 1}));
        MixScene document = new MixScene(new Room("hall", 2, 0.4), 0, LevelMode.FIXED, List.of(melody), lanes);
        List<SpatialStemMixer.Signal> signals = List.of(noise(2 * RATE, 6));
        for (StageSound sound : new StageSound[] {StageSound.LOUDSPEAKERS, StageSound.binaural().withConvolution(true)}) {
            StageAutomation automation = StageAutomation.of(document, TimeMap.of(120));
            SpatialStemMixer.Mix large = new SpatialStemMixer(512).withAutomation(automation).withSound(sound)
                    .render(signals, StageAutomation.placements(document), AcousticRoom.HALL, 0);
            SpatialStemMixer.Mix odd = new SpatialStemMixer(37).withAutomation(automation).withSound(sound)
                    .render(signals, StageAutomation.placements(document), AcousticRoom.HALL, 0);
            assertArrayEquals(large.left, odd.left, 0f);
            assertArrayEquals(large.right, odd.right, 0f);
        }
    }

    @Test
    public void onHeadphonesTheLiveStageSoundsLikeTheFile() throws Exception {
        List<SpatialStemMixer.Signal> signals = List.of(noise(RATE, 7), noise(RATE / 2, 8));
        List<SoundSourcePlacement> stage = List.of(at("a", -0.5, 0.3, 0.4), at("b", 0.8, 0.9, 0));
        StageSound sound = StageSound.binaural().withConvolution(true);
        SpatialStemMixer.Mix file = new SpatialStemMixer().withSound(sound).render(signals, stage, AcousticRoom.CHAMBER,
                2);
        float[][] live;
        try (LiveStage liveStage = LiveStage.of(signals, stage, AcousticRoom.CHAMBER, 2, sound)) {
            live = drain(liveStage.graph(), 512);
        }
        assertArrayEquals(file.left, live[0], 1e-6f);
        assertArrayEquals(file.right, live[1], 1e-6f);
    }

    private static AutomationLane lane(String channel, String parameter, long[] ticks, double[] values) {
        List<AutomationLane.Point> points = new ArrayList<>();
        for (int i = 0; i < ticks.length; i++) {
            points.add(new AutomationLane.Point(ticks[i], values[i]));
        }
        return new AutomationLane(channel + "-" + parameter, channel, parameter, points);
    }

    private static float[][] drain(LiveGraph graph, int blockFrames) {
        float[] left = new float[(int) graph.frames()];
        float[] right = new float[(int) graph.frames()];
        AudioBlock block = new AudioBlock(blockFrames);
        graph.prepare(RATE, blockFrames);
        int at = 0;
        int got;
        do {
            block.setFrames(blockFrames);
            got = graph.read(block);
            System.arraycopy(block.left, 0, left, at, got);
            System.arraycopy(block.right, 0, right, at, got);
            at += got;
        } while (got == blockFrames);
        return new float[][] {left, right};
    }

    private static double rms(float[] samples) {
        return rms(samples, 0, samples.length);
    }

    private static double rms(float[] samples, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += samples[i] * samples[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }
}
