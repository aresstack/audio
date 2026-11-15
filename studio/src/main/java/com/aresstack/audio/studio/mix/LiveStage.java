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
 * while playing. The room is fixed for a live stage; another room means another live stage.
 *
 * <p>Thread use: made off the event thread (it reads and resamples the stems); {@link #move} and
 * {@link #place} may be called from any thread, typically the event thread while dragging.
 */
public final class LiveStage implements AutoCloseable {
    private final AcousticRoom room;
    private final double secondsPerBar;
    private final List<String> trackIds;
    private final LiveGraph graph;

    private LiveStage(List<float[][]> sounds, List<SoundSourcePlacement> placements, AcousticRoom room,
                      double secondsPerBar) {
        if (sounds.isEmpty() || sounds.size() != placements.size()) {
            throw new IllegalArgumentException("Every track on a live stage needs its placement.");
        }
        this.room = room;
        this.secondsPerBar = secondsPerBar;
        long length = 0;
        for (float[][] sound : sounds) {
            length = Math.max(length, sound[0].length);
        }
        boolean reflects = SpatialStemMixer.reflects(room);
        int capacity = reflects ? SpatialStemMixer.longestEcho(room) : 0;
        List<LiveChannel> channels = new ArrayList<LiveChannel>();
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < sounds.size(); i++) {
            float[][] sound = sounds.get(i);
            SoundSourcePlacement placement = placements.get(i);
            ids.add(placement.trackId());
            channels.add(new LiveChannel(new ArraySource(sound[0], sound[1], SpatialStemMixer.RATE), length,
                    parameters(placement), capacity, reflects ? SpatialStemMixer.echoLowPass(room) : null));
        }
        this.trackIds = List.copyOf(ids);
        this.graph = new LiveGraph(channels, SpatialStemMixer.reverb(room), (float) room.amount(),
                length + SpatialStemMixer.tail(length, room));
    }

    /** A live stage of tracks that are in memory already. */
    public static LiveStage of(List<SpatialStemMixer.Signal> signals, List<SoundSourcePlacement> placements,
                               AcousticRoom room, double secondsPerBar) throws IOException {
        List<float[][]> sounds = new ArrayList<float[][]>();
        for (SpatialStemMixer.Signal signal : signals) {
            sounds.add(atMixRate(LinearResampler.at(new ArraySource(signal.left, signal.right, signal.rate),
                    signal.rate, SpatialStemMixer.RATE), LinearResampler.outputFrames(signal.left.length, signal.rate,
                    SpatialStemMixer.RATE)));
        }
        return new LiveStage(sounds, placements, room, secondsPerBar);
    }

    /** A live stage of the rendered stem files of a piece; reads them whole, so call it off the event thread. */
    public static LiveStage load(List<Path> files, List<SoundSourcePlacement> placements, AcousticRoom room,
                                 double secondsPerBar) throws Exception {
        List<SpatialStemMixer.Signal> signals = new ArrayList<SpatialStemMixer.Signal>();
        for (Path file : files) {
            signals.add(SpatialStemMixer.read(file));
        }
        return of(signals, placements, room, secondsPerBar);
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
        ChannelParameters parameters = SpatialStemMixer.parameters(placement, room, secondsPerBar);
        return placement.muted() ? parameters.toBuilder().level(0).build() : parameters;
    }

    public LiveGraph graph() {
        return graph;
    }

    public AcousticRoom room() {
        return room;
    }

    /** The ids of the tracks, in the order of the channels. */
    public List<String> trackIds() {
        return trackIds;
    }

    /** Moves the track at this place of the list; heard from the next block on. */
    public void move(int track, SoundSourcePlacement placement) {
        graph.channel(track).set(parameters(placement));
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
        graph.close();
    }
}
