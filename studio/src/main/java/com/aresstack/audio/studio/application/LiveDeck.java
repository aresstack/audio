package com.aresstack.audio.studio.application;

import com.aresstack.audio.core.line.LineSink;
import com.aresstack.audio.core.live.LivePlayback;
import com.aresstack.audio.core.live.LiveSink;
import com.aresstack.audio.core.mix.MasterBus;
import com.aresstack.audio.studio.mix.LiveStage;
import com.aresstack.audio.studio.mix.MixOutcome;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The stage of a piece as it is heard live (work package D2, acceptance A13): the rendered tracks
 * of the piece in memory on a {@link LiveStage}, played by the audio thread of the core. What the
 * user moves on the stage is handed over with {@link #stage} and heard from the next block on,
 * gliding; no file is mixed for it.
 *
 * <p>A live deck is made for one room. Another room needs a new deck ({@link #fits}); the window
 * makes one in the background and goes on at the place that was heard. Its level is the one of
 * the file ("export old, live new"): the factor the last file mix found as a fixed gain, with
 * the live limiter behind it ({@link LiveStage#master}).
 *
 * <p>Thread use: {@link #open} reads the tracks and belongs off the event thread. Everything else
 * may be called from any thread; it only hands numbers to the audio thread.
 */
public final class LiveDeck implements AutoCloseable {
    /** Where a live deck plays: the sound card, or a sink of a test. */
    @FunctionalInterface
    public interface Sinks {
        LiveSink open() throws Exception;
    }

    /** The default sound card, with the short buffer of the core. */
    public static final Sinks SOUND_CARD = () -> LineSink.open(SpatialStemMixer.RATE, SpatialStemMixer.BLOCK,
            LineSink.DEFAULT_BUFFER_FRAMES);

    private final LiveStage stage;
    private final LivePlayback playback;
    /** Where the mix document of the piece lies, and the tempo its stems were rendered at. */
    private final Path directory;
    private final int tempo;
    /** The factor of the last file mix, and the master level it was found with. */
    private volatile float legacyFactor;
    private volatile double legacyMasterDb;
    private volatile SpatialScene scene;
    /** Where a jump went, until the audio thread has rendered from there. */
    private volatile double sought = -1;
    private volatile long soughtAtBlock;

    private LiveDeck(LiveStage stage, LivePlayback playback, SpatialScene scene, float legacyFactor,
                     Path directory, int tempo) {
        this.directory = directory;
        this.tempo = tempo;
        this.stage = stage;
        this.playback = playback;
        this.scene = scene;
        this.legacyFactor = legacyFactor;
        this.legacyMasterDb = scene.masterDb();
        for (SoundSourcePlacement placement : scene.placements()) {
            sent.put(placement.trackId(), placement); // where the stage was loaded: a jump from there glides
        }
    }

    /**
     * Reads the rendered tracks of a piece and gets them ready to play on its stage, paused at the
     * start. A track without a sound file is left out if it is muted and named if it is not, as
     * the file mix does.
     *
     * @param only         the track that is heard alone, or {@code null} for the whole mix
     * @param legacyFactor the factor the last file mix of this stage
     *                     found ({@link MixOutcome#legacyFactor()}), of the whole mix or of the
     *                     track alone, as {@code only} says; if it is not known (not a number),
     *                     it is measured here, so that live playback is as loud as the file
     */
    public static LiveDeck open(Path arrangement, Path directory, SpatialScene scene, String only,
                                float legacyFactor, Sinks sinks) throws Exception {
        List<MusicTrack> tracks = StageMixService.tracks(arrangement);
        SpatialScene fitting = scene.fitting(tracks);
        List<Path> files = new ArrayList<Path>();
        List<SoundSourcePlacement> placements = new ArrayList<SoundSourcePlacement>();
        for (MusicTrack track : tracks) {
            Path file = StageMixService.stem(directory, track.id());
            SoundSourcePlacement placement = heard(fitting.placement(track.id()), only);
            if (!Files.isRegularFile(file)) {
                if (!placement.muted()) {
                    throw new IOException("Die Spur " + track.id() + " ist noch nicht gerendert: "
                            + file.getFileName());
                }
                continue;
            }
            files.add(file);
            placements.add(placement);
        }
        if (files.isEmpty()) {
            throw new IOException("Von diesem Stück ist noch keine Spur gerendert.");
        }
        float factor = legacyFactor;
        if (!(factor > 0 && Float.isFinite(factor))) {
            factor = measuredFactor(arrangement, directory, fitting, only);
        }
        // How the tracks move with the piece (place and room share) comes from its mix document, as for the file.
        int tempo = StageMixService.tempo(arrangement);
        LiveStage stage = LiveStage.load(files, placements, fitting.room(), 4 * 60.0 / tempo,
                StageMixService.movements(directory, tempo, message -> { }));
        try {
            LivePlayback playback = stage.playback(master(fitting, factor, fitting.masterDb()), sinks.open());
            return new LiveDeck(stage, playback, fitting, factor, directory, tempo);
        } catch (Exception noSound) {
            stage.close();
            throw noSound;
        }
    }

    /**
     * The factor a file mix of this stage is brought to its peak with, for
     * the whole mix or for the track that is heard alone, as the file of each is. The mix is
     * measured into a file of its own that is thrown away: the mixed file of the piece belongs to
     * the window, which may have mixed a newer stage by now. Not a number if it cannot be mixed;
     * the piece then plays at its master level alone.
     */
    static float measuredFactor(Path arrangement, Path directory, SpatialScene fitting, String only) {
        SpatialScene measured = fitting;
        for (SoundSourcePlacement placement : fitting.placements()) {
            measured = measured.with(heard(placement, only));
        }
        Path scratch = null;
        try {
            scratch = Files.createTempFile(directory, "live-level-", ".part");
            StageMixService mixing = new StageMixService();
            mixing.export(arrangement, directory, measured, scratch, 16);
            return mixing.legacyFactor();
        } catch (Exception unmixed) {
            return Float.NaN;
        } finally {
            if (scratch != null) {
                try {
                    Files.deleteIfExists(scratch);
                } catch (IOException kept) {
                    // a file of a few seconds that is in nobody's way
                }
            }
        }
    }

    /** Alone means: the others are silent, and the one is heard even if it is muted in the mix. */
    private static SoundSourcePlacement heard(SoundSourcePlacement placement, String only) {
        return only == null ? placement : placement.withMuted(!only.equals(placement.trackId()));
    }

    /** Whether this deck can play a stage: the same room. */
    public boolean fits(SpatialScene wanted) {
        AcousticRoom mine = stage.room();
        AcousticRoom theirs = wanted.room();
        return mine.kind().equals(theirs.kind())
                && mine.reverbSeconds() == theirs.reverbSeconds() && mine.amount() == theirs.amount();
    }

    /**
     * The stage as it stands now, also in the middle of a drag: every track goes to its place,
     * level, width and send, and the master to its level; heard from the next block on, gliding.
     *
     * @param only the track that is heard alone, or {@code null}
     * @return {@code false} if this deck cannot play that stage ({@link #fits}); nothing was changed then
     */
    public boolean stage(SpatialScene wanted, String only) {
        if (!fits(wanted)) {
            return false;
        }
        for (SoundSourcePlacement placement : wanted.placements()) {
            hear(heard(placement, only));
        }
        scene = wanted;
        playback.master().setSettings(master(wanted));
        return true;
    }

    /** One track moved; the others stay. {@code false} if the stage has no such track. */
    public boolean move(SoundSourcePlacement placement, String only) {
        return hear(heard(placement, only));
    }

    /** From this share of the stage on, a change in one step is a jump and not a drag. */
    static final double JUMP = 0.1;

    /** Where each track was sent last, and until when it is on its way there. */
    private final java.util.Map<String, SoundSourcePlacement> sent = new java.util.HashMap<String, SoundSourcePlacement>();
    private final java.util.Map<String, Long> onItsWayUntil = new java.util.HashMap<String, Long>();

    /**
     * Sends a track to where it stands now. Dragging brings the places in small steps, and each is
     * taken at once (20 ms glide of the channel). A jump (a step undone, a proposal taken, the
     * usual seating, another scene) would sound like a cut that way: it glides across the stage
     * itself in 300 to 600 ms, longer the farther it goes. While a track is on such a way, what
     * comes next glides on from where it is, so that a drag begun then does not cut either.
     */
    private synchronized boolean hear(SoundSourcePlacement placement) {
        String id = placement.trackId();
        SoundSourcePlacement before = sent.get(id);
        if (before == placement) {
            return true; // nothing new for this track
        }
        sent.put(id, placement);
        long now = System.nanoTime();
        Long until = onItsWayUntil.get(id);
        boolean underway = until != null && now < until;
        double far = before == null ? 0 : far(before, placement);
        if (far < JUMP && !underway) {
            return stage.move(placement);
        }
        double milliseconds = far < JUMP ? 150 : 300 + 300 * Math.min(1, far);
        onItsWayUntil.put(id, now + (long) (milliseconds * 1e6));
        return stage.glide(placement, milliseconds);
    }

    /** Whether a track is on its way across the stage right now (a jump, not a drag). */
    synchronized boolean gliding(String trackId) {
        Long until = onItsWayUntil.get(trackId);
        return until != null && System.nanoTime() < until;
    }

    /** How far two placements are apart, as a share of what each number can span; the largest counts. */
    static double far(SoundSourcePlacement from, SoundSourcePlacement to) {
        double far = Math.abs(to.x() - from.x()) / 2;
        far = Math.max(far, Math.abs(to.depth() - from.depth()));
        far = Math.max(far, Math.abs(to.levelDb() - from.levelDb()) / 24);
        far = Math.max(far, Math.abs(to.width() - from.width()));
        return Math.max(far, Math.abs(to.reverb() - from.reverb()));
    }

    /**
     * A curve of the place or of the room share was drawn and saved (work package D3): the mix
     * document is read again and every track follows its movements from the next block on. Reads
     * files, so it belongs off the event thread.
     */
    public void reloadMovements() {
        stage.moving(StageMixService.movements(directory, tempo, message -> { }));
    }

    /**
     * The file of the stage was mixed again: live playback takes the
     * factor it found, so that it stays as loud as the file.
     *
     * @param masterDb the master level of the stage that was mixed
     */
    public void legacyFactor(float factor, double masterDb) {
        if (!(factor > 0 && Float.isFinite(factor))) {
            return;
        }
        legacyFactor = factor;
        legacyMasterDb = masterDb;
        playback.master().setSettings(master(scene));
    }

    /** The master bus for a stage: the factor of the file mix, moved with the master fader, and the live limiter. */
    private MasterBus.Settings master(SpatialScene wanted) {
        return master(wanted, legacyFactor, legacyMasterDb);
    }

    private static MasterBus.Settings master(SpatialScene wanted, float factor, double legacyMasterDb) {
        float moved = factor > 0 && Float.isFinite(factor)
                ? (float) (factor * Math.pow(10, (wanted.masterDb() - legacyMasterDb) / 20)) : Float.NaN;
        return LiveStage.master(moved, wanted.masterDb());
    }

    /**
     * How far the limiter of live playback turns the sound down right now, as a gain: 1 while it
     * rests. One number the audio thread publishes; safe to read from any thread.
     */
    public float limiterGain() {
        return playback.master().limiter().gain();
    }

    public LiveStage liveStage() {
        return stage;
    }

    public LivePlayback playback() {
        return playback;
    }

    public void play() {
        playback.play();
    }

    public void pause() {
        playback.pause();
    }

    public boolean playing() {
        return playback.playing();
    }

    /** Whether the audio thread stopped with an error; the window then falls back to the file. */
    public boolean failed() {
        return playback.state() == LivePlayback.State.FAILED;
    }

    /** Goes on at this second, playing or not. */
    public void seek(double second) {
        double within = Math.max(0, Math.min(length(), second));
        soughtAtBlock = playback.blocks();
        sought = within;
        playback.seek(Math.round(within * playback.sampleRate()));
    }

    /** The second that is heard now. */
    public double seconds() {
        double jumped = sought;
        if (jumped >= 0) {
            if (playback.blocks() == soughtAtBlock) {
                return jumped; // the audio thread has not rendered from there yet
            }
            sought = -1;
        }
        return playback.heardSeconds();
    }

    /** How long the piece is on this stage, with the ring of the room at its end. */
    public double length() {
        return stage.graph().frames() / (double) playback.sampleRate();
    }

    /** The peak of the block that was played last, after the master bus: for the master meters. */
    public float peak() {
        return playback.meter().lastBlockPeak();
    }

    @Override
    public void close() {
        playback.close();
    }
}
