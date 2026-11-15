package com.aresstack.audio.studio.stage;

import com.aresstack.audio.studio.application.MusicTrack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stage of one piece: a room, the listener in front of it, and a placement for
 * each track. The scene knows nothing of windows or of sound files; it only says
 * where everything is. It cannot be changed: every change gives a new scene.
 */
public final class SpatialScene {
    public static final double MIN_MASTER = -30;
    private final AcousticRoom room;
    private final Map<String, SoundSourcePlacement> placements;
    private final double masterDb;
    private final boolean fixedLevel;

    public SpatialScene(AcousticRoom room, List<SoundSourcePlacement> placements) {
        this(room, placements, 0);
    }

    /**
     * @param masterDb how far below full level the finished mix is, 0 (full) down to -30 dB
     */
    public SpatialScene(AcousticRoom room, List<SoundSourcePlacement> placements, double masterDb) {
        this(room, placements, masterDb, false);
    }

    /**
     * @param masterDb   how far below full level the finished mix is, 0 (full) down to -30 dB
     * @param fixedLevel whether the piece was switched to the fixed level structure with the
     *                   limiter; a piece is mixed to its peak as before until its user switches it
     */
    public SpatialScene(AcousticRoom room, List<SoundSourcePlacement> placements, double masterDb,
                        boolean fixedLevel) {
        this.fixedLevel = fixedLevel;
        this.masterDb = SoundSourcePlacement.within(masterDb, MIN_MASTER, 0);
        this.room = room == null ? AcousticRoom.HALL : room;
        Map<String, SoundSourcePlacement> kept = new LinkedHashMap<String, SoundSourcePlacement>();
        if (placements != null) {
            for (SoundSourcePlacement placement : placements) {
                if (placement != null) {
                    kept.put(placement.trackId(), placement);
                }
            }
        }
        this.placements = Collections.unmodifiableMap(kept);
    }

    /** The usual seating for these tracks: melody in front, bass and drums in the middle, the rest around. */
    public static SpatialScene standard(List<MusicTrack> tracks) {
        return new SpatialScene(AcousticRoom.HALL, null).fitting(tracks);
    }

    public AcousticRoom room() { return room; }

    /** The level of the finished mix: 0 dB is full level, less is quieter. */
    public double masterDb() { return masterDb; }

    public SpatialScene withMaster(double db) {
        return new SpatialScene(room, placements(), db, fixedLevel);
    }

    /**
     * Whether the mix has a fixed level structure (master level, then the limiter) instead of
     * being brought to its peak. False for every piece until its user switches it on purpose.
     */
    public boolean fixedLevel() { return fixedLevel; }

    public SpatialScene withFixedLevel(boolean fixed) {
        return new SpatialScene(room, placements(), masterDb, fixed);
    }

    public List<SoundSourcePlacement> placements() {
        return new ArrayList<SoundSourcePlacement>(placements.values());
    }

    /** The placement of a track, or {@code null} if the scene does not know the track. */
    public SoundSourcePlacement placement(String trackId) {
        return placements.get(trackId);
    }

    public SpatialScene with(SoundSourcePlacement placement) {
        List<SoundSourcePlacement> changed = new ArrayList<SoundSourcePlacement>();
        boolean found = false;
        for (SoundSourcePlacement existing : placements.values()) {
            if (existing.trackId().equals(placement.trackId())) {
                changed.add(placement);
                found = true;
            } else {
                changed.add(existing);
            }
        }
        if (!found) {
            changed.add(placement);
        }
        return new SpatialScene(room, changed, masterDb, fixedLevel);
    }

    public SpatialScene inRoom(AcousticRoom other) {
        return new SpatialScene(other, placements(), masterDb, fixedLevel);
    }

    /**
     * The scene for exactly these tracks, in their order: a track that is already
     * placed keeps its place, a new one gets the usual seat of its role, and what
     * belongs to no track any more is dropped. This is also how a piece from before
     * the stage gets its scene.
     */
    public SpatialScene fitting(List<MusicTrack> tracks) {
        List<SoundSourcePlacement> seats = new ArrayList<SoundSourcePlacement>();
        Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
        for (MusicTrack track : tracks) {
            SoundSourcePlacement existing = placements.get(track.id());
            if (existing != null) {
                seats.add(existing);
                continue;
            }
            Integer before = seen.get(track.role());
            int nth = before == null ? 0 : before;
            seen.put(track.role(), nth + 1);
            seats.add(seat(track, nth));
        }
        return new SpatialScene(room, seats, masterDb, fixedLevel);
    }

    /** The scene with every placement that is not locked taken from a proposal; the room comes from the proposal. */
    public SpatialScene taking(SpatialScene proposal) {
        List<SoundSourcePlacement> merged = new ArrayList<SoundSourcePlacement>();
        for (SoundSourcePlacement existing : placements.values()) {
            SoundSourcePlacement proposed = proposal.placement(existing.trackId());
            if (proposed == null || existing.locked()) {
                merged.add(existing);
            } else {
                merged.add(proposed.withMuted(existing.muted()));
            }
        }
        return new SpatialScene(proposal.room, merged, masterDb, fixedLevel);
    }

    /** The usual seat of a role; several tracks of one role fan out to both sides in turn. */
    private static SoundSourcePlacement seat(MusicTrack track, int nth) {
        double side = nth == 0 ? 0 : (nth % 2 == 1 ? -1 : 1) * Math.min(1, 0.3 * ((nth + 1) / 2));
        String role = track.role();
        if (MusicTrack.MELODY.equals(role)) {
            return new SoundSourcePlacement(track.id(), side, 0.22, 0, 0.6, 0.30, false, false, null);
        }
        if (MusicTrack.BASS.equals(role)) {
            return new SoundSourcePlacement(track.id(), 0.12 + side * 0.5, 0.50, -1, 0.3, 0.15, false, false, null);
        }
        if (MusicTrack.DRUMS.equals(role)) {
            return new SoundSourcePlacement(track.id(), side * 0.5, 0.68, -3, 0.8, 0.20, false, false, null);
        }
        if (MusicTrack.HARMONY.equals(role)) {
            double x = nth == 0 ? -0.45 : nth == 1 ? 0.45 : side;
            return new SoundSourcePlacement(track.id(), x, 0.48, -4, 1.0, 0.40, false, false, null);
        }
        double x = nth == 0 ? 0.6 : nth == 1 ? -0.6 : side;
        return new SoundSourcePlacement(track.id(), x, 0.82, -7, 1.0, 0.55, false, false, null);
    }
}
