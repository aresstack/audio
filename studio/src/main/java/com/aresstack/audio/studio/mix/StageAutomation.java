package com.aresstack.audio.studio.mix;

import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.core.automation.AutomationCurve;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What moves on the stage while a piece plays (work package D3): for each track the curves of its
 * level, its place and its share of the room, in frames of the mix. There is one way for all of
 * them, and the level curve of the old stage ({@code dynamics}, in bars) is the first case of it:
 * {@link #of(List, double)} turns it into the same curves the mix document gives with
 * {@link #of(MixScene, TimeMap)}.
 *
 * <p>The mix document keeps its points in ticks; they are turned into seconds with the time map
 * of the piece, so a point stays at its bar when the tempo changes. Where the tempo changes
 * between two points, the curve gets a point of its own, so that it runs straight in ticks, as
 * the lane says, and not in seconds.
 */
public final class StageAutomation {
    /** Nothing moves. */
    public static final StageAutomation NONE = new StageAutomation(Map.of());

    /**
     * The curves of one track; each is {@code null} if that parameter stands still.
     *
     * @param level  dB above or below the level of the track
     * @param x      the place between -1 (left) and +1 (right)
     * @param depth  the place between 0 (front edge) and 1 (back wall)
     * @param reverb the share sent to the room, 0 to 1
     */
    public record Lanes(AutomationCurve level, AutomationCurve x, AutomationCurve depth, AutomationCurve reverb) {
        static final Lanes STILL = new Lanes(null, null, null, null);

        /** Whether the track changes its place or its share of the room over time. */
        public boolean moves() {
            return x != null || depth != null || reverb != null;
        }
    }

    private final Map<String, Lanes> tracks;

    private StageAutomation(Map<String, Lanes> tracks) {
        this.tracks = Collections.unmodifiableMap(new LinkedHashMap<>(tracks));
    }

    /** The curves of a track; never {@code null}. */
    public Lanes of(String trackId) {
        Lanes lanes = tracks.get(trackId);
        return lanes == null ? Lanes.STILL : lanes;
    }

    /**
     * The level curves of the old stage, whose points are in bars of a piece with one tempo:
     * what {@link SpatialStemMixer} has always heard of {@link SoundSourcePlacement#dynamics()},
     * to the last bit.
     */
    public static StageAutomation of(List<SoundSourcePlacement> placements, double secondsPerBar) {
        if (!(secondsPerBar > 0) || Double.isInfinite(secondsPerBar)) {
            return NONE;
        }
        Map<String, Lanes> tracks = new LinkedHashMap<>();
        for (SoundSourcePlacement placement : placements) {
            List<SoundSourcePlacement.LevelPoint> points = placement.dynamics();
            if (points.isEmpty() || tracks.containsKey(placement.trackId())) {
                continue;
            }
            double[] bars = new double[points.size()];
            double[] db = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                bars[i] = points.get(i).bar();
                db[i] = points.get(i).db();
            }
            tracks.put(placement.trackId(), new Lanes(
                    AutomationCurve.of(bars, db, SpatialStemMixer.RATE * secondsPerBar), null, null, null));
        }
        return new StageAutomation(tracks);
    }

    /**
     * The automation of a mix document, for the tracks named as the owners of their channels (a
     * part or an audio track). Lanes of the master, lanes without points and lanes of parameters
     * the studio does not know are not heard.
     */
    public static StageAutomation of(MixScene mix, TimeMap time) {
        Map<String, Lanes> tracks = new LinkedHashMap<>();
        for (MixChannel channel : mix.channels()) {
            AutomationCurve level = curve(mix.lane(channel.id(), AutomationLane.LEVEL), time);
            AutomationCurve x = curve(mix.lane(channel.id(), AutomationLane.X), time);
            AutomationCurve depth = curve(mix.lane(channel.id(), AutomationLane.DEPTH), time);
            AutomationCurve reverb = curve(mix.lane(channel.id(), AutomationLane.REVERB), time);
            if (level != null || x != null || depth != null || reverb != null) {
                tracks.putIfAbsent(channel.owner().id(), new Lanes(level, x, depth, reverb));
            }
        }
        return new StageAutomation(tracks);
    }

    /**
     * The stage of a mix document as the mixer takes it: one placement per channel, named after
     * its owner, in the order of the channels. Its level curves come with {@link #of(MixScene,
     * TimeMap)}, not with the placements.
     */
    public static List<SoundSourcePlacement> placements(MixScene mix) {
        List<SoundSourcePlacement> placements = new ArrayList<>();
        for (MixChannel channel : mix.channels()) {
            placements.add(new SoundSourcePlacement(channel.owner().id(), channel.x(), channel.depth(),
                    channel.levelDb(), channel.width(), channel.reverb(), channel.muted(), channel.locked(), null));
        }
        return placements;
    }

    /** A lane in seconds of the piece, with a point of its own at every change of tempo it spans. */
    static AutomationCurve curve(AutomationLane lane, TimeMap time) {
        if (lane == null || lane.points().isEmpty() || !lane.known()) {
            return null;
        }
        List<AutomationLane.Point> points = lane.points();
        long first = points.get(0).tick();
        long last = points.get(points.size() - 1).tick();
        TreeSet<Long> changes = new TreeSet<>();
        for (TimeMap.Tempo tempo : time.tempos()) {
            if (tempo.tick() > first && tempo.tick() < last) {
                changes.add(tempo.tick());
            }
        }
        for (AutomationLane.Point point : points) {
            changes.remove(point.tick());
        }
        List<double[]> joined = new ArrayList<>();
        for (AutomationLane.Point point : points) {
            // A change of tempo before this point (and after the previous one) gets its point first.
            while (!changes.isEmpty() && changes.first() < point.tick()) {
                long tick = changes.pollFirst();
                joined.add(new double[] {time.seconds(tick), lane.valueAt(tick)});
            }
            joined.add(new double[] {time.seconds(point.tick()), point.value()});
        }
        double[] seconds = new double[joined.size()];
        double[] values = new double[joined.size()];
        for (int i = 0; i < joined.size(); i++) {
            seconds[i] = joined.get(i)[0];
            values[i] = joined.get(i)[1];
        }
        return AutomationCurve.of(seconds, values, SpatialStemMixer.RATE);
    }
}
