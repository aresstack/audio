package com.aresstack.audio.studio.mix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
import com.aresstack.audio.core.automation.AutomationCurve;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Work package D3: one way for every movement on the stage. The level curve of the old stage is
 * its first case and stays what it was; the mix document is read through the time map, so its
 * points stay at their bars when the tempo changes (acceptance of D3, 7.2).
 */
public final class StageAutomationTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final long BAR = 4L * TimeMap.PPQ;

    @Test
    public void theOldLevelCurveGivesWhatTheStageAlwaysGaveToTheLastBit() {
        SoundSourcePlacement placement = new SoundSourcePlacement("melody", 0, 0.2).withDynamics(Arrays.asList(
                new SoundSourcePlacement.LevelPoint(0.5, -18), new SoundSourcePlacement.LevelPoint(1.3, 0),
                new SoundSourcePlacement.LevelPoint(1.3, -4), new SoundSourcePlacement.LevelPoint(2.7, -6)));
        double secondsPerBar = 1.7;
        AutomationCurve level = StageAutomation.of(List.of(placement), secondsPerBar).of("melody").level();
        for (long frame = 0; frame < 6L * RATE; frame += 37) {
            assertEquals("frame " + frame, placement.dynamicsAt(frame / (RATE * secondsPerBar)), level.valueAt(frame), 0);
        }
        assertNull(StageAutomation.of(List.of(placement), 0).of("melody").level());
        assertFalse(StageAutomation.of(List.of(placement), secondsPerBar).of("melody").moves());
    }

    /** Two bars at 120 quarters a minute (4 s), then 60 (4 s a bar). */
    private static TimeMap slowingDown() {
        return new TimeMap(List.of(new TimeMap.Tempo(0, 120), new TimeMap.Tempo(2 * BAR, 60)),
                List.of(new TimeMap.Meter(0, 4, 4)), List.of(new TimeMap.KeyChange(0, Key.C_MAJOR)));
    }

    private static MixScene mix(AutomationLane... lanes) {
        MixChannel melody = new MixChannel("melody-ch", MixChannel.Owner.part("melody"), 0.2, 0.3, -2, 0.8, 0.4,
                false, false);
        return new MixScene(new Room("hall", 2, 0.3), 0, LevelMode.FIXED, List.of(melody), List.of(lanes));
    }

    private static AutomationLane lane(String parameter, long[] ticks, double[] values) {
        AutomationLane.Point[] points = new AutomationLane.Point[ticks.length];
        for (int i = 0; i < ticks.length; i++) {
            points[i] = new AutomationLane.Point(ticks[i], values[i]);
        }
        return new AutomationLane("lane-" + parameter, "melody-ch", parameter, List.of(points));
    }

    @Test
    public void aPointOfTheMixStaysAtItsBarWhenTheTempoChanges() {
        // The level jumps up at the start of bar 5 (bar 4 counted from 0).
        MixScene document = mix(lane(AutomationLane.LEVEL, new long[] {4 * BAR, 4 * BAR}, new double[] {-40, 0}));
        AutomationCurve steady = StageAutomation.of(document, TimeMap.of(120)).of("melody").level();
        AutomationCurve slowing = StageAutomation.of(document, slowingDown()).of("melody").level();
        // At 120 the bar begins after 8 s; slowing down after two bars, after 4 s + 2 * 4 s = 12 s.
        assertEquals(8.0, TimeMap.of(120).seconds(4 * BAR), 0);
        assertEquals(12.0, slowingDown().seconds(4 * BAR), 0);
        assertEquals(-40, steady.valueAt(8L * RATE - 1), 0);
        assertEquals(0, steady.valueAt(8L * RATE + 1), 0);
        assertEquals(-40, slowing.valueAt(8L * RATE + 1), 0);
        assertEquals(-40, slowing.valueAt(12L * RATE - 1), 0);
        assertEquals(0, slowing.valueAt(12L * RATE + 1), 0);
        // The document itself does not change: the points are still at the tick of the bar.
        assertEquals(4 * BAR, document.automation().get(0).points().get(0).tick());
    }

    @Test
    public void betweenTwoPointsTheCurveRunsStraightInBarsAcrossAChangeOfTempo() {
        AutomationLane swell = lane(AutomationLane.LEVEL, new long[] {0, 4 * BAR}, new double[] {-12, 0});
        AutomationCurve level = StageAutomation.of(mix(swell), slowingDown()).of("melody").level();
        assertEquals(3, level.size()); // a point of its own where the tempo changes
        assertEquals(-12, level.valueAtTime(0), 0);
        assertEquals(-6, level.valueAtTime(4), 1e-12);  // bar 2, where the tempo changes
        assertEquals(-3, level.valueAtTime(8), 1e-12);  // bar 3: halfway between bar 2 and bar 4
        assertEquals(swell.valueAt(3 * BAR), level.valueAtTime(8), 1e-12);
        assertEquals(0, level.valueAtTime(12), 0);
    }

    @Test
    public void placeAndRoomShareOfTheMixBecomeCurvesOfTheTrack() {
        MixScene document = mix(
                lane(AutomationLane.X, new long[] {0, 2 * BAR}, new double[] {-1, 1}),
                lane(AutomationLane.DEPTH, new long[] {0}, new double[] {0.9}),
                lane(AutomationLane.REVERB, new long[] {BAR, 2 * BAR}, new double[] {0, 1}),
                new AutomationLane("later", "melody-ch", "brightness", List.of(new AutomationLane.Point(0, 3))),
                new AutomationLane("master", null, AutomationLane.LEVEL, List.of(new AutomationLane.Point(0, -3))));
        StageAutomation.Lanes lanes = StageAutomation.of(document, TimeMap.of(120)).of("melody");
        assertTrue(lanes.moves());
        assertNull(lanes.level());
        assertEquals(0, lanes.x().valueAt(2L * RATE), 1e-12); // after one bar of two, in the middle
        assertEquals(0.9, lanes.depth().valueAt(123_456), 0);
        assertEquals(0.5, lanes.reverb().valueAt(3L * RATE), 1e-12);
        assertFalse(StageAutomation.of(document, TimeMap.of(120)).of("bass").moves());
        assertNull(StageAutomation.of(mix(), TimeMap.of(120)).of("melody").x());
    }

    @Test
    public void theStageOfAMixDocumentIsOnePlacementPerChannelNamedAfterItsOwner() {
        List<SoundSourcePlacement> placements = StageAutomation.placements(mix());
        assertEquals(1, placements.size());
        SoundSourcePlacement melody = placements.get(0);
        assertEquals("melody", melody.trackId());
        assertEquals(0.2, melody.x(), 0);
        assertEquals(0.3, melody.depth(), 0);
        assertEquals(-2, melody.levelDb(), 0);
        assertEquals(0.8, melody.width(), 0);
        assertEquals(0.4, melody.reverb(), 0);
        assertTrue(melody.dynamics().isEmpty());
        assertNotNull(StageAutomation.NONE.of("melody"));
    }
}
