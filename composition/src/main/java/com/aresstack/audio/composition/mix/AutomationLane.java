package com.aresstack.audio.composition.mix;

import com.aresstack.audio.composition.Ids;
import java.util.List;

/**
 * A parameter of a channel over time. Points are placed in ticks of the piece, not in bars or
 * seconds, so that they stay with the music when tempo or meter change. Between two points the
 * value runs in a straight line.
 *
 * @param channelId the channel, or {@code null} for the master
 * @param parameter what is automated; {@link #LEVEL} is the first and so far only one
 */
public record AutomationLane(String id, String channelId, String parameter, List<Point> points) {
    /** dB above or below the level of the channel. */
    public static final String LEVEL = "level";

    public record Point(long tick, double value) {
        public Point {
            if (tick < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("Invalid automation point at tick " + tick);
            }
        }
    }

    public AutomationLane {
        if (!Ids.valid(id) || (channelId != null && !Ids.valid(channelId))) {
            throw new IllegalArgumentException("Invalid automation lane: " + id);
        }
        if (parameter == null || parameter.isBlank()) {
            throw new IllegalArgumentException("An automation lane names its parameter: " + id);
        }
        points = List.copyOf(points == null ? List.of() : points);
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).tick() < points.get(i - 1).tick()) {
                throw new IllegalArgumentException("The points of an automation lane are in order: " + id);
            }
        }
    }
}
