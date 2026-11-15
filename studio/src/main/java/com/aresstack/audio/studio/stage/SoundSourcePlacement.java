package com.aresstack.audio.studio.stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where one track stands on the stage and how loud it is: seen from the listener,
 * {@code x} runs from -1 (far left) to +1 (far right) and {@code depth} from 0 (at
 * the front edge) to 1 (at the back wall). Every value is kept within its range, so
 * a placement can be taken from a file or from the AI as it comes.
 */
public final class SoundSourcePlacement {
    public static final double MIN_LEVEL = -36;
    public static final double MAX_LEVEL = 6;
    public static final int MAX_POINTS = 32;

    /** One point of the level over time: at this bar the track is this many dB above or below its level. */
    public static final class LevelPoint {
        private final double bar;
        private final double db;

        public LevelPoint(double bar, double db) {
            this.bar = within(bar, 0, 4096);
            this.db = within(db, -24, 6);
        }

        public double bar() { return bar; }
        public double db() { return db; }
    }

    private final String trackId;
    private final double x;
    private final double depth;
    private final double levelDb;
    private final double width;
    private final double reverb;
    private final boolean muted;
    private final boolean locked;
    private final List<LevelPoint> dynamics;
    private final double height;

    public SoundSourcePlacement(String trackId, double x, double depth, double levelDb, double width,
                                double reverb, boolean muted, boolean locked, List<LevelPoint> dynamics) {
        this(trackId, x, depth, levelDb, width, reverb, muted, locked, dynamics, 0);
    }

    /**
     * @param height how high the track stands above the ears of the listener, 0 (ear height, as
     *               every stage before work package D4) to 1 (top of the stage)
     */
    public SoundSourcePlacement(String trackId, double x, double depth, double levelDb, double width,
                                double reverb, boolean muted, boolean locked, List<LevelPoint> dynamics,
                                double height) {
        if (trackId == null || trackId.trim().isEmpty()) {
            throw new IllegalArgumentException("A placement needs the id of its track.");
        }
        this.trackId = trackId.trim();
        this.x = within(x, -1, 1);
        this.depth = within(depth, 0, 1);
        this.levelDb = within(levelDb, MIN_LEVEL, MAX_LEVEL);
        this.width = within(width, 0, 1);
        this.reverb = within(reverb, 0, 1);
        this.muted = muted;
        this.locked = locked;
        this.height = within(height, 0, 1);
        List<LevelPoint> points = new ArrayList<LevelPoint>();
        if (dynamics != null) {
            for (LevelPoint point : dynamics) {
                if (point != null && points.size() < MAX_POINTS) {
                    points.add(point);
                }
            }
        }
        Collections.sort(points, (first, second) -> Double.compare(first.bar, second.bar));
        this.dynamics = Collections.unmodifiableList(points);
    }

    public SoundSourcePlacement(String trackId, double x, double depth) {
        this(trackId, x, depth, 0, 1, 0.35, false, false, null);
    }

    public String trackId() { return trackId; }
    public double x() { return x; }
    public double depth() { return depth; }
    public double levelDb() { return levelDb; }
    public double width() { return width; }
    public double reverb() { return reverb; }
    /** Height above the ears of the listener, 0 to 1 (work package D4). */
    public double height() { return height; }
    public boolean muted() { return muted; }
    /** A locked placement keeps its place when the AI proposes a new stage. */
    public boolean locked() { return locked; }
    public List<LevelPoint> dynamics() { return dynamics; }

    public SoundSourcePlacement at(double newX, double newDepth) {
        return new SoundSourcePlacement(trackId, newX, newDepth, levelDb, width, reverb, muted, locked, dynamics, height);
    }

    /** The same placement at another height, 0 (ear height) to 1. */
    public SoundSourcePlacement atHeight(double newHeight) {
        return new SoundSourcePlacement(trackId, x, depth, levelDb, width, reverb, muted, locked, dynamics, newHeight);
    }

    public SoundSourcePlacement withLevel(double db) {
        return new SoundSourcePlacement(trackId, x, depth, db, width, reverb, muted, locked, dynamics, height);
    }

    public SoundSourcePlacement withWidth(double newWidth) {
        return new SoundSourcePlacement(trackId, x, depth, levelDb, newWidth, reverb, muted, locked, dynamics, height);
    }

    public SoundSourcePlacement withReverb(double share) {
        return new SoundSourcePlacement(trackId, x, depth, levelDb, width, share, muted, locked, dynamics, height);
    }

    public SoundSourcePlacement withMuted(boolean silent) {
        return new SoundSourcePlacement(trackId, x, depth, levelDb, width, reverb, silent, locked, dynamics, height);
    }

    public SoundSourcePlacement withLocked(boolean kept) {
        return new SoundSourcePlacement(trackId, x, depth, levelDb, width, reverb, muted, kept, dynamics, height);
    }

    public SoundSourcePlacement withDynamics(List<LevelPoint> points) {
        return new SoundSourcePlacement(trackId, x, depth, levelDb, width, reverb, muted, locked, points, height);
    }

    public SoundSourcePlacement named(String id) {
        return new SoundSourcePlacement(id, x, depth, levelDb, width, reverb, muted, locked, dynamics, height);
    }

    /** The dB this track is above or below its level at a bar, between its points in a straight line. */
    public double dynamicsAt(double bar) {
        if (dynamics.isEmpty()) {
            return 0;
        }
        LevelPoint before = dynamics.get(0);
        if (bar <= before.bar) {
            return before.db;
        }
        for (LevelPoint point : dynamics) {
            if (bar <= point.bar) {
                double span = point.bar - before.bar;
                return span <= 0 ? point.db : before.db + (point.db - before.db) * (bar - before.bar) / span;
            }
            before = point;
        }
        return before.db;
    }

    static double within(double value, double least, double most) {
        if (Double.isNaN(value)) {
            return Math.max(least, Math.min(most, 0));
        }
        return Math.max(least, Math.min(most, value));
    }
}
