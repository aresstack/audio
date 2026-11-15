package com.aresstack.audio.studio.application;

import com.aresstack.audio.core.line.LineSink;
import com.aresstack.audio.core.live.LivePlayback;
import com.aresstack.audio.core.live.LiveSink;
import com.aresstack.audio.core.mix.MasterBus;
import com.aresstack.audio.studio.mix.LiveStage;
import com.aresstack.audio.studio.mix.MixLevel;
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
 * <p>A live deck is made for one room and one way of setting the level. Another room, or a piece
 * switched between the peak and the fixed level, needs a new deck ({@link #fits}); the window
 * makes one in the background and goes on at the place that was heard.
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
    private final boolean fixedLevel;
    /** In the compatibility mode: the factor of the last file mix, and the master level it was found with. */
    private volatile float legacyFactor;
    private volatile double legacyMasterDb;
    private volatile SpatialScene scene;
    /** Where a jump went, until the audio thread has rendered from there. */
    private volatile double sought = -1;
    private volatile long soughtAtBlock;

    private LiveDeck(LiveStage stage, LivePlayback playback, SpatialScene scene, float legacyFactor) {
        this.stage = stage;
        this.playback = playback;
        this.scene = scene;
        this.fixedLevel = scene.fixedLevel();
        this.legacyFactor = legacyFactor;
        this.legacyMasterDb = scene.masterDb();
    }

    /**
     * Reads the rendered tracks of a piece and gets them ready to play on its stage, paused at the
     * start. A track without a sound file is left out if it is muted and named if it is not, as
     * the file mix does.
     *
     * @param only         the track that is heard alone, or {@code null} for the whole mix
     * @param legacyFactor in the compatibility mode the factor the last file mix of this stage
     *                     found ({@link MixOutcome#legacyFactor()}); if it is not known (not a
     *                     number), the piece is mixed to its file once to find it, so that live
     *                     playback is as loud as the file
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
        if (!fitting.fixedLevel() && !(factor > 0 && Float.isFinite(factor))) {
            factor = Float.NaN;
            try {
                StageMixService mixing = new StageMixService();
                mixing.mix(arrangement, directory, fitting, message -> { });
                factor = mixing.outcome().legacyFactor();
            } catch (Exception unmixed) {
                // then the piece plays at its master level alone
            }
        }
        LiveStage stage = LiveStage.load(files, placements, fitting.room(),
                4 * 60.0 / StageMixService.tempo(arrangement));
        try {
            LivePlayback playback = stage.playback(master(fitting, factor, fitting.masterDb()), sinks.open());
            return new LiveDeck(stage, playback, fitting, factor);
        } catch (Exception noSound) {
            stage.close();
            throw noSound;
        }
    }

    /** Alone means: the others are silent, and the one is heard even if it is muted in the mix. */
    private static SoundSourcePlacement heard(SoundSourcePlacement placement, String only) {
        return only == null ? placement : placement.withMuted(!only.equals(placement.trackId()));
    }

    /** Whether this deck can play a stage: the same room and the same way of setting the level. */
    public boolean fits(SpatialScene wanted) {
        AcousticRoom mine = stage.room();
        AcousticRoom theirs = wanted.room();
        return wanted.fixedLevel() == fixedLevel && mine.kind().equals(theirs.kind())
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
            stage.move(heard(placement, only));
        }
        scene = wanted;
        playback.master().setSettings(master(wanted));
        return true;
    }

    /** One track moved; the others stay. {@code false} if the stage has no such track. */
    public boolean move(SoundSourcePlacement placement, String only) {
        return stage.move(heard(placement, only));
    }

    /**
     * The file of the stage was mixed again in the compatibility mode: live playback takes the
     * factor it found, so that it stays as loud as the file.
     *
     * @param masterDb the master level of the stage that was mixed
     */
    public void legacyFactor(float factor, double masterDb) {
        if (fixedLevel || !(factor > 0 && Float.isFinite(factor))) {
            return;
        }
        legacyFactor = factor;
        legacyMasterDb = masterDb;
        playback.master().setSettings(master(scene));
    }

    /** The master bus for a stage: the fixed level, or the factor of the file mix moved with the master fader. */
    private MasterBus.Settings master(SpatialScene wanted) {
        return master(wanted, legacyFactor, legacyMasterDb);
    }

    private static MasterBus.Settings master(SpatialScene wanted, float factor, double legacyMasterDb) {
        if (wanted.fixedLevel()) {
            return LiveStage.master(StageMixService.level(wanted, null).live(), Float.NaN);
        }
        float moved = factor > 0 && Float.isFinite(factor)
                ? (float) (factor * Math.pow(10, (wanted.masterDb() - legacyMasterDb) / 20)) : Float.NaN;
        return LiveStage.master(MixLevel.legacy(wanted.masterDb()), moved);
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
