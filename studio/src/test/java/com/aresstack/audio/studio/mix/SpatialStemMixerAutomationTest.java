package com.aresstack.audio.studio.mix;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * Work package D3 in the mixer: level, place and share of the room follow the automation of the
 * mix document; a lane that holds the values of its channel sounds exactly like no lane, and a
 * level step stays at its bar when the tempo changes.
 */
public final class SpatialStemMixerAutomationTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final long BAR = 4L * TimeMap.PPQ;
    private static final AcousticRoom DRY = AcousticRoom.STUDIO.withAmount(0);

    private static MixChannel channel(String part, double x, double depth, double reverb) {
        return new MixChannel(part + "-ch", MixChannel.Owner.part(part), x, depth, -1, 0.7, reverb, false, false);
    }

    private static AutomationLane lane(String channel, String parameter, long[] ticks, double[] values) {
        List<AutomationLane.Point> points = new ArrayList<>();
        for (int i = 0; i < ticks.length; i++) {
            points.add(new AutomationLane.Point(ticks[i], values[i]));
        }
        return new AutomationLane(channel + "-" + parameter, channel, parameter, points);
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

    private static SpatialStemMixer.Signal steady(int frames) {
        float[] level = new float[frames];
        Arrays.fill(level, 0.5f);
        return new SpatialStemMixer.Signal(level, level.clone(), RATE);
    }

    private static SpatialStemMixer.Mix mix(MixScene document, TimeMap time, List<SpatialStemMixer.Signal> signals,
                                            AcousticRoom room) throws Exception {
        return new SpatialStemMixer().withAutomation(StageAutomation.of(document, time))
                .render(signals, StageAutomation.placements(document), room, 0);
    }

    @Test
    public void lanesThatHoldTheValuesOfTheirChannelSoundExactlyLikeNoLanes() throws Exception {
        MixChannel melody = channel("melody", 0.4, 0.6, 0.5);
        MixChannel bass = channel("bass", -0.3, 0.2, 0.1);
        List<SpatialStemMixer.Signal> signals = List.of(noise(RATE, 1), noise(RATE, 2));
        MixScene still = new MixScene(new Room("hall", 2, 0.4), 0, LevelMode.FIXED, List.of(melody, bass), List.of());
        MixScene held = new MixScene(still.room(), 0, LevelMode.FIXED, still.channels(), List.of(
                lane("melody-ch", AutomationLane.X, new long[] {0, 4 * BAR}, new double[] {0.4, 0.4}),
                lane("melody-ch", AutomationLane.DEPTH, new long[] {BAR}, new double[] {0.6}),
                lane("melody-ch", AutomationLane.REVERB, new long[] {0, 2 * BAR}, new double[] {0.5, 0.5})));
        SpatialStemMixer.Mix plain = mix(still, TimeMap.of(120), signals, AcousticRoom.HALL);
        SpatialStemMixer.Mix moving = mix(held, TimeMap.of(120), signals, AcousticRoom.HALL);
        assertArrayEquals(plain.left, moving.left, 0f);
        assertArrayEquals(plain.right, moving.right, 0f);
    }

    @Test
    public void aSourceThatMovesFromLeftToRightIsHeardWhereItIs() throws Exception {
        // Two bars at 120 = 4 s, from the far left to the far right.
        MixScene document = new MixScene(new Room("studio", 0.3, 0), 0, LevelMode.FIXED,
                List.of(channel("melody", 0, 0.1, 0)),
                List.of(lane("melody-ch", AutomationLane.X, new long[] {0, 2 * BAR}, new double[] {-1, 1})));
        SpatialStemMixer.Mix mix = mix(document, TimeMap.of(120), List.of(noise(4 * RATE, 3)), DRY);
        double[] start = power(mix, 0, RATE / 4);
        double[] middle = power(mix, 2 * RATE - RATE / 8, 2 * RATE + RATE / 8);
        double[] end = power(mix, 4 * RATE - RATE / 4, 4 * RATE);
        assertTrue("left at the start " + Arrays.toString(start), start[0] > 4 * start[1]);
        assertTrue("right at the end " + Arrays.toString(end), end[1] > 4 * end[0]);
        assertEquals("in the middle halfway", 1, middle[0] / middle[1], 0.15);
    }

    @Test
    public void aLevelStepStaysAtItsBarWhenTheTempoChanges() throws Exception {
        TimeMap slowing = new TimeMap(List.of(new TimeMap.Tempo(0, 120), new TimeMap.Tempo(2 * BAR, 60)),
                List.of(new TimeMap.Meter(0, 4, 4)), List.of(new TimeMap.KeyChange(0, Key.C_MAJOR)));
        MixChannel front = new MixChannel("melody-ch", MixChannel.Owner.part("melody"), 0, 0, 0, 1, 0, false, false);
        MixScene document = new MixScene(new Room("studio", 0.3, 0), 0, LevelMode.FIXED, List.of(front),
                List.of(lane("melody-ch", AutomationLane.LEVEL, new long[] {4 * BAR, 4 * BAR}, new double[] {-40, 0})));
        SpatialStemMixer.Mix mix = mix(document, slowing, List.of(steady(13 * RATE)), DRY);
        float quiet = (float) (0.5 * Math.pow(10, -40 / 20.0));
        assertEquals(quiet, mix.left[8 * RATE + 200], 1e-4); // where the bar would be at one tempo
        assertEquals(quiet, mix.left[12 * RATE - 100], 1e-4);
        assertEquals(0.5, mix.left[12 * RATE + 100], 1e-4); // the bar after slowing down: 4 s + 2 * 4 s
    }

    @Test
    public void givenAutomationTakesThePlaceOfTheLevelCurvesOfThePlacements() throws Exception {
        SoundSourcePlacement quietened = new SoundSourcePlacement("melody", 0, 0, 0, 1, 0, false, false,
                List.of(new SoundSourcePlacement.LevelPoint(0, -12)));
        SpatialStemMixer.Mix old = new SpatialStemMixer().render(List.of(steady(RATE)), List.of(quietened), DRY, 2);
        SpatialStemMixer.Mix none = new SpatialStemMixer().withAutomation(StageAutomation.NONE)
                .render(List.of(steady(RATE)), List.of(quietened), DRY, 2);
        assertEquals(0.5 * Math.pow(10, -12 / 20.0), old.left[RATE / 2], 1e-4);
        assertEquals(0.5, none.left[RATE / 2], 1e-4);
    }

    @Test
    public void aChannelLevelBeyondTheRangeOfThePlacementIsHeardAsTheDocumentSaysIt() throws Exception {
        MixChannel quiet = new MixChannel("melody-ch", MixChannel.Owner.part("melody"), 0, 0, -60, 1, 0, false, false);
        MixScene document = new MixScene(new Room("studio", 0.3, 0), 0, LevelMode.FIXED, List.of(quiet), List.of());
        SpatialStemMixer.Mix mix = mix(document, TimeMap.of(120), List.of(steady(RATE)), DRY);
        assertEquals(0.5 * Math.pow(10, -60 / 20.0), mix.left[RATE / 2], 1e-6);
    }

    @Test
    public void aPartAndAnAudioTrackOfTheSameIdStayTwoTracks() throws Exception {
        MixChannel part = new MixChannel("part-ch", MixChannel.Owner.part("melody"), 0, 0.1, 0, 1, 0, false, false);
        MixChannel audio = new MixChannel("audio-ch",
                new MixChannel.Owner(MixChannel.Owner.Kind.AUDIO_TRACK, "melody"), 0, 0.1, 0, 1, 0, false, false);
        MixScene document = new MixScene(new Room("studio", 0.3, 0), 0, LevelMode.FIXED, List.of(part, audio), List.of(
                lane("part-ch", AutomationLane.X, new long[] {0}, new double[] {-1}),
                lane("audio-ch", AutomationLane.X, new long[] {0}, new double[] {1})));
        List<SoundSourcePlacement> placements = StageAutomation.placements(document);
        assertEquals(List.of("melody", "audio-track:melody"),
                placements.stream().map(SoundSourcePlacement::trackId).toList());
        SpatialStemMixer.Mix leftOnly = mix(document, TimeMap.of(120),
                List.of(noise(RATE / 2, 4), new SpatialStemMixer.Signal(new float[RATE / 2], new float[RATE / 2], RATE)),
                DRY);
        double[] sides = power(leftOnly, 0, RATE / 2);
        assertTrue("the part stands left, the audio track right: " + Arrays.toString(sides), sides[0] > 4 * sides[1]);
    }

    private static double[] power(SpatialStemMixer.Mix mix, int from, int to) {
        double left = 0;
        double right = 0;
        for (int i = from; i < to; i++) {
            left += mix.left[i] * mix.left[i];
            right += mix.right[i] * mix.right[i];
        }
        return new double[] {left, right};
    }
}
