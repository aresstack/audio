package com.aresstack.audio.studio.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.line.LineSink;
import com.aresstack.audio.core.live.ChannelParameters;
import com.aresstack.audio.core.live.LiveChannel;
import com.aresstack.audio.core.live.LiveGraph;
import com.aresstack.audio.core.live.LivePlayback;
import com.aresstack.audio.core.live.LiveSink;
import com.aresstack.audio.core.mix.LevelMode;
import com.aresstack.audio.core.mix.MasterBus;
import com.aresstack.audio.core.stream.ArraySource;
import com.aresstack.audio.core.stream.LinearResampler;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sound.sampled.LineUnavailableException;

/**
 * The stage of a piece, live (work package D2, acceptance A13): the rendered tracks in memory, each
 * on a {@link LiveChannel} of the audio core, so that moving an instrument, a fader or the width
 * is heard while the piece plays, without mixing a file first.
 *
 * <p>The channels take their numbers from the same place as the file mix
 * ({@code SpatialStemMixer.parameters}): as long as nothing moves, live playback sounds exactly like
 * the file. A muted track keeps its channel and is turned down, so that it can be turned up again
 * while playing. The room is fixed for a live stage; another room means another live stage, and so
 * does switching between loudspeakers and headphones or between algorithmic and convolved room
 * ({@link StageSound}, work package D4). Height and moving the source around the head glide live.
 *
 * <p>Thread use: made off the event thread (it reads and resamples the stems); {@link #move} and
 * {@link #place} may be called from any thread, typically the event thread while dragging.
 */
public final class LiveStage implements AutoCloseable {
    private final AcousticRoom room;
    private final double secondsPerBar;
    private final StageSound sound;
    private final List<String> trackIds;
    private final LiveGraph graph;
    /** How long the music is, without the ring of the room at the end. */
    private final long musicFrames;
    /** Where each track stands as last handed to its channel, for glides that start from there. */
    private final SoundSourcePlacement[] current;
    /** The running glide of each track, so that a new move or glide replaces it. */
    private final java.util.concurrent.Future<?>[] glides;
    private java.util.concurrent.ScheduledExecutorService glider;

    private LiveStage(List<float[][]> sounds, List<SoundSourcePlacement> placements, AcousticRoom room,
                      double secondsPerBar, StageSound sound) {
        if (sounds.isEmpty() || sounds.size() != placements.size()) {
            throw new IllegalArgumentException("Every track on a live stage needs its placement.");
        }
        this.room = room;
        this.secondsPerBar = secondsPerBar;
        this.sound = sound == null ? StageSound.LOUDSPEAKERS : sound;
        long length = 0;
        for (float[][] stem : sounds) {
            length = Math.max(length, stem[0].length);
        }
        boolean reflects = SpatialStemMixer.walls(room, this.sound) > 0;
        int capacity = reflects ? SpatialStemMixer.longestEcho(room) : 0;
        List<LiveChannel> channels = new ArrayList<LiveChannel>();
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < sounds.size(); i++) {
            float[][] stem = sounds.get(i);
            SoundSourcePlacement placement = placements.get(i);
            ids.add(placement.trackId());
            channels.add(new LiveChannel(new ArraySource(stem[0], stem[1], SpatialStemMixer.RATE), length,
                    parameters(placement), capacity, reflects ? SpatialStemMixer.echoLowPass(room) : null,
                    this.sound.headphones() ? this.sound.head() : null));
        }
        this.trackIds = List.copyOf(ids);
        this.current = placements.toArray(new SoundSourcePlacement[0]);
        this.glides = new java.util.concurrent.Future<?>[placements.size()];
        this.musicFrames = length;
        this.graph = new LiveGraph(channels, SpatialStemMixer.roomBus(room, this.sound), (float) room.amount(),
                length + SpatialStemMixer.tail(length, room, this.sound));
    }

    /** A live stage of tracks that are in memory already. */
    public static LiveStage of(List<SpatialStemMixer.Signal> signals, List<SoundSourcePlacement> placements,
                               AcousticRoom room, double secondsPerBar) throws IOException {
        return of(signals, placements, room, secondsPerBar, StageSound.LOUDSPEAKERS);
    }

    /** A live stage of tracks in memory, heard as {@code sound} says (headphones, convolved room; work package D4). */
    public static LiveStage of(List<SpatialStemMixer.Signal> signals, List<SoundSourcePlacement> placements,
                               AcousticRoom room, double secondsPerBar, StageSound sound) throws IOException {
        List<float[][]> sounds = new ArrayList<float[][]>();
        for (SpatialStemMixer.Signal signal : signals) {
            sounds.add(atMixRate(LinearResampler.at(new ArraySource(signal.left, signal.right, signal.rate),
                    signal.rate, SpatialStemMixer.RATE), LinearResampler.outputFrames(signal.left.length, signal.rate,
                    SpatialStemMixer.RATE)));
        }
        return new LiveStage(sounds, placements, room, secondsPerBar, sound);
    }

    /** A live stage of the rendered stem files of a piece; reads them whole, so call it off the event thread. */
    public static LiveStage load(List<Path> files, List<SoundSourcePlacement> placements, AcousticRoom room,
                                 double secondsPerBar) throws Exception {
        return load(files, placements, room, secondsPerBar, StageSound.LOUDSPEAKERS);
    }

    /** As {@link #load(List, List, AcousticRoom, double)}, heard as {@code sound} says. */
    public static LiveStage load(List<Path> files, List<SoundSourcePlacement> placements, AcousticRoom room,
                                 double secondsPerBar, StageSound sound) throws Exception {
        List<SpatialStemMixer.Signal> signals = new ArrayList<SpatialStemMixer.Signal>();
        for (Path file : files) {
            signals.add(SpatialStemMixer.read(file));
        }
        return of(signals, placements, room, secondsPerBar, sound);
    }

    /** Reads a source at the rate of the mix into memory, with the block size of the file mix. */
    private static float[][] atMixRate(AudioSource source, long frames) throws IOException {
        if (frames > Integer.MAX_VALUE) {
            throw new IOException("Ein so langes Stück passt nicht in den Speicher.");
        }
        float[] left = new float[(int) frames];
        float[] right = new float[(int) frames];
        AudioBlock block = new AudioBlock(SpatialStemMixer.BLOCK);
        try (source) {
            source.prepare(SpatialStemMixer.RATE, SpatialStemMixer.BLOCK);
            int at = 0;
            int got;
            do {
                block.setFrames((int) Math.min(SpatialStemMixer.BLOCK, Math.max(0, frames - at)));
                got = block.frames() == 0 ? 0 : source.read(block);
                System.arraycopy(block.left, 0, left, at, got);
                System.arraycopy(block.right, 0, right, at, got);
                at += got;
            } while (got == SpatialStemMixer.BLOCK && at < frames);
            return at == frames ? new float[][]{left, right}
                    : new float[][]{Arrays.copyOf(left, at), Arrays.copyOf(right, at)};
        }
    }

    /** The numbers of a channel for a placement on this stage; a muted track is turned down. */
    private ChannelParameters parameters(SoundSourcePlacement placement) {
        ChannelParameters parameters = SpatialStemMixer.parameters(placement, room, secondsPerBar, sound);
        return placement.muted() ? parameters.toBuilder().level(0).build() : parameters;
    }

    /**
     * Plays the music over and over without a gap (the room rings from the end into the start);
     * {@code false} lets it end with the ring of the room.
     */
    public void loop(boolean on) {
        graph.setLoop(on ? musicFrames : 0);
    }

    public LiveGraph graph() {
        return graph;
    }

    public AcousticRoom room() {
        return room;
    }

    /** How this stage is heard; another sound means another live stage, as another room does. */
    public StageSound sound() {
        return sound;
    }

    /** The ids of the tracks, in the order of the channels. */
    public List<String> trackIds() {
        return trackIds;
    }

    /** How often a glide hands over a new place: every 10 ms, finer than the 20 ms each step glides. */
    public static final long GLIDE_STEP_MS = 10;

    /**
     * Moves the track at this place of the list; heard from the next block on. The numbers of the
     * channel glide there in 20 ms: right for dragging, where the places come in small steps, but a
     * jump across the stage sounds like a cut. For a jump, use {@link #glide}.
     */
    public void move(int track, SoundSourcePlacement placement) {
        synchronized (glides) {
            cancelGlide(track);
            hand(track, placement);
        }
    }

    private void hand(int track, SoundSourcePlacement placement) {
        current[track] = placement;
        graph.channel(track).set(parameters(placement));
    }

    private void cancelGlide(int track) {
        if (glides[track] != null) {
            glides[track].cancel(false);
            glides[track] = null;
        }
    }

    /**
     * Moves a track over this time on the stage itself: place, depth, level, width and room share
     * go from where the track stands to the target along a smooth curve (slow at start and end),
     * and every {@link #GLIDE_STEP_MS} the channel gets the place in between. Unlike gliding the
     * gains of the channel, this keeps the loudness while the track crosses the middle (equal
     * power at every point of the way) and the echoes move with it. Mute and level curve change
     * at the start. A new move or glide of the same track replaces it.
     */
    public void glide(int track, SoundSourcePlacement target, double milliseconds) {
        if (!(milliseconds > GLIDE_STEP_MS)) {
            move(track, target);
            return;
        }
        synchronized (glides) {
            cancelGlide(track);
            SoundSourcePlacement from = current[track];
            long start = System.nanoTime();
            double nanos = milliseconds * 1e6;
            java.util.concurrent.Future<?>[] self = new java.util.concurrent.Future<?>[1];
            Runnable step = () -> {
                double share = Math.min(1, (System.nanoTime() - start) / nanos);
                synchronized (glides) {
                    if (glides[track] != self[0]) {
                        return;
                    }
                    hand(track, between(from, target, share));
                    if (share >= 1) {
                        glides[track].cancel(false);
                        glides[track] = null;
                    }
                }
            };
            self[0] = glider().scheduleAtFixedRate(step, 0, GLIDE_STEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            glides[track] = self[0];
        }
    }

    /** Glides the track with the id of the placement; {@code false} if the stage has no such track. */
    public boolean glide(SoundSourcePlacement target, double milliseconds) {
        int track = trackIds.indexOf(target.trackId());
        if (track < 0) {
            return false;
        }
        glide(track, target, milliseconds);
        return true;
    }

    /**
     * The place a share of the way from one placement to another, 0..1, on a smooth curve
     * (3s² − 2s³): numbers that change in a straight line between the two, mute and level curve
     * already those of the target.
     */
    static SoundSourcePlacement between(SoundSourcePlacement from, SoundSourcePlacement to, double share) {
        double s = Math.max(0, Math.min(1, share));
        double eased = s * s * (3 - 2 * s);
        if (s >= 1) {
            return to;
        }
        return new SoundSourcePlacement(to.trackId(), mix(from.x(), to.x(), eased), mix(from.depth(), to.depth(), eased),
                mix(from.levelDb(), to.levelDb(), eased), mix(from.width(), to.width(), eased),
                mix(from.reverb(), to.reverb(), eased), to.muted(), to.locked(), to.dynamics(),
                mix(from.height(), to.height(), eased));
    }

    private static double mix(double from, double to, double share) {
        return from + (to - from) * share;
    }

    private java.util.concurrent.ScheduledExecutorService glider() {
        if (glider == null) {
            glider = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "live-stage-glide");
                thread.setDaemon(true);
                return thread;
            });
        }
        return glider;
    }

    /** Moves the track with the id of the placement; {@code false} if the stage has no such track. */
    public boolean move(SoundSourcePlacement placement) {
        int track = trackIds.indexOf(placement.trackId());
        if (track < 0) {
            return false;
        }
        move(track, placement);
        return true;
    }

    /** Places every track anew (a whole scene, e.g. after undo); tracks the stage does not know are skipped. */
    public void place(List<SoundSourcePlacement> placements) {
        for (SoundSourcePlacement placement : placements) {
            move(placement);
        }
    }

    /**
     * The master bus for live playback with this level strategy. In {@link LevelMode#FIXED} the
     * same gain and limiter as the file; in the compatibility mode the factor the last file mix
     * found ({@link MixOutcome#legacyFactor()}), so that live playback is as loud as the file. A
     * legacy piece that was never mixed (factor not a number) plays at the master level alone.
     */
    public static MasterBus.Settings master(MixLevel level, float legacyFactor) {
        if (level.mode() == LevelMode.FIXED) {
            return level.master();
        }
        float factor = Float.isFinite(legacyFactor) && legacyFactor > 0 ? legacyFactor
                : (float) Math.pow(10, level.masterDb() / 20);
        return MasterBus.Settings.legacy(factor);
    }

    /** Live playback of this stage into a sink (a sound card, or a test), paused at the start. */
    public LivePlayback playback(MasterBus.Settings master, LiveSink sink) {
        return new LivePlayback(graph, new MasterBus(master), sink, SpatialStemMixer.RATE, SpatialStemMixer.BLOCK)
                .start();
    }

    /** Live playback of this stage on the default sound card, paused at the start. */
    public LivePlayback playOnLine(MasterBus.Settings master) throws LineUnavailableException {
        return playback(master, LineSink.open(SpatialStemMixer.RATE, SpatialStemMixer.BLOCK,
                LineSink.DEFAULT_BUFFER_FRAMES));
    }

    @Override
    public void close() throws IOException {
        synchronized (glides) {
            for (int track = 0; track < glides.length; track++) {
                cancelGlide(track);
            }
            if (glider != null) {
                glider.shutdownNow();
            }
        }
        graph.close();
    }
}
