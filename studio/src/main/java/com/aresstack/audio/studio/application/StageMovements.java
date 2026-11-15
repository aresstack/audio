package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the instruments of a piece move on its stage over time, as the stage page shows and edits
 * it (work package D3): for each track the curve of its place from left to right, of its depth and
 * of its share of the room, with the points in bars. The mix document of the piece keeps them as
 * {@link AutomationLane}s in ticks ({@code mix.json}); this is that part of it in the measure of
 * the page, and the way back ({@link #ticks}).
 *
 * <p>The curve of the level is not here: the stage itself keeps it, as before.
 */
public final class StageMovements {
    /** One point of a curve: at this bar (counted from 0, with fractions) the parameter has this value. */
    public record Point(double bar, double value) {
    }

    /** What moves here, in the order of the page: side, depth, share of the room, height (work package D4). */
    public static final List<String> PARAMETERS = List.of(AutomationLane.X, AutomationLane.DEPTH,
            AutomationLane.REVERB, AutomationLane.HEIGHT);

    /** A piece without a mix document: nothing moves, and nothing can be drawn. */
    public static final StageMovements NONE = new StageMovements(null, Map.of());

    private final TimeMap time;
    /** Track, then parameter; a track that has a channel is there even if nothing of it moves. */
    private final Map<String, Map<String, List<Point>>> tracks;

    private StageMovements(TimeMap time, Map<String, Map<String, List<Point>>> tracks) {
        this.time = time;
        this.tracks = tracks;
    }

    /** The movements a mix document holds, for the tracks that own its channels. */
    public static StageMovements of(MixScene mix, TimeMap time) {
        if (mix == null || time == null) {
            return NONE;
        }
        Map<String, Map<String, List<Point>>> tracks = new LinkedHashMap<>();
        for (MixChannel channel : mix.channels()) {
            if (tracks.containsKey(channel.owner().id())) {
                continue;
            }
            Map<String, List<Point>> curves = new LinkedHashMap<>();
            for (String parameter : PARAMETERS) {
                AutomationLane lane = mix.lane(channel.id(), parameter);
                if (lane != null && !lane.points().isEmpty()) {
                    List<Point> points = new ArrayList<>();
                    for (AutomationLane.Point point : lane.points()) {
                        points.add(new Point(bar(time, point.tick()), point.value()));
                    }
                    curves.put(parameter, Collections.unmodifiableList(points));
                }
            }
            tracks.put(channel.owner().id(), curves);
        }
        return new StageMovements(time, tracks);
    }

    /** Whether the movements of this track can be drawn: the piece has a mix document with its channel. */
    public boolean editable(String trackId) {
        return time != null && tracks.containsKey(trackId);
    }

    /** The curve of a parameter of a track, in order; empty if it stands still. */
    public List<Point> of(String trackId, String parameter) {
        Map<String, List<Point>> curves = tracks.get(trackId);
        List<Point> points = curves == null ? null : curves.get(parameter);
        return points == null ? List.of() : points;
    }

    /** Whether anything of this track moves. */
    public boolean moves(String trackId) {
        Map<String, List<Point>> curves = tracks.get(trackId);
        return curves != null && !curves.isEmpty();
    }

    /**
     * These movements with another curve for a parameter of a track; without points the track
     * stands still in that parameter. The values are kept within what the parameter allows, the
     * points are put in order (points at the same bar keep theirs: that is a jump).
     */
    public StageMovements with(String trackId, String parameter, List<Point> points) {
        if (!editable(trackId) || !PARAMETERS.contains(parameter)) {
            return this;
        }
        List<Point> kept = new ArrayList<>();
        if (points != null) {
            double least = AutomationLane.least(parameter);
            double most = AutomationLane.most(parameter);
            for (Point point : points) {
                if (point != null && !Double.isNaN(point.bar()) && !Double.isNaN(point.value())) {
                    kept.add(new Point(Math.max(0, point.bar()), Math.max(least, Math.min(most, point.value()))));
                }
            }
            kept.sort((first, second) -> Double.compare(first.bar(), second.bar()));
        }
        Map<String, Map<String, List<Point>>> changed = new LinkedHashMap<>(tracks);
        Map<String, List<Point>> curves = new LinkedHashMap<>(tracks.get(trackId));
        if (kept.isEmpty()) {
            curves.remove(parameter);
        } else {
            curves.put(parameter, Collections.unmodifiableList(kept));
        }
        changed.put(trackId, curves);
        return new StageMovements(time, changed);
    }

    /** A curve as the mix document keeps it: its points in ticks of the piece. */
    public List<AutomationLane.Point> ticks(String trackId, String parameter) {
        List<AutomationLane.Point> points = new ArrayList<>();
        long before = 0;
        for (Point point : of(trackId, parameter)) {
            long tick = Math.max(before, tick(time, point.bar()));
            points.add(new AutomationLane.Point(tick, point.value()));
            before = tick;
        }
        return points;
    }

    /** A tick as a bar with fractions, bars counted from 0, through the meters of the piece. */
    static double bar(TimeMap time, long tick) {
        int bar = time.barOf(tick);
        long start = time.tickOfBar(bar);
        return bar + (tick - start) / (double) time.meterAt(start).barTicks();
    }

    /** The tick of a bar with fractions. */
    static long tick(TimeMap time, double bar) {
        int whole = (int) Math.floor(Math.max(0, bar));
        long start = time.tickOfBar(whole);
        return start + Math.round((Math.max(0, bar) - whole) * time.meterAt(start).barTicks());
    }
}
