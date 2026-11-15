package com.aresstack.audio.studio.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.dsp.EarlyReflections;
import com.aresstack.audio.core.dsp.RoomReverb;
import com.aresstack.audio.core.dsp.StereoBiquad;
import com.aresstack.audio.core.dsp.StereoPanner;
import com.aresstack.audio.core.dsp.StereoStrip;
import com.aresstack.audio.core.automation.AutomationCurve;
import com.aresstack.audio.core.live.ChannelParameters;
import com.aresstack.audio.core.mix.ChannelMotion;
import com.aresstack.audio.core.mix.LegacyPeakNormalization;
import com.aresstack.audio.core.mix.LevelMode;
import com.aresstack.audio.core.mix.MixGraph;
import com.aresstack.audio.core.mix.OfflineMix;
import com.aresstack.audio.core.mix.StemChannel;
import com.aresstack.audio.core.stream.ArraySource;
import com.aresstack.audio.core.stream.LinearResampler;
import com.aresstack.audio.core.wav.WavFileSink;
import com.aresstack.audio.core.wav.WavFileSource;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;

/**
 * Mixes the rendered tracks as they stand on the stage. Each track gets its level
 * (also moving over time), its width and its place between left and right; the
 * farther back it stands, the quieter and duller its direct sound and the larger
 * the share of the room. The walls answer with a few early echoes, and all tracks
 * ring in one shared room. Everything is calculated in float and only the finished
 * mix is written as whole numbers. The same input always gives the same output.
 *
 * <p>The sound itself is made by the graph of the audio core ({@code audio-core}); this class
 * maps the stage of the studio onto it: where a track stands becomes level, place, distance
 * shelf, send and wall echoes of a {@link StemChannel}. The mix of files streams block by block,
 * so a piece may be of any length and needs the memory of one block per track.
 *
 * <p>What moves over time, the level and (work package D3) the place and the share of the room,
 * comes from one {@link StageAutomation}: by default the level curves of the placements, or the
 * automation of a mix document given with {@link #withAutomation(StageAutomation)}.
 */
public final class SpatialStemMixer {
    public static final int RATE = 44100;
    /** How many frames are mixed at a time. The result does not depend on it. */
    public static final int BLOCK = 512;
    /** The stage as the listener sees it: 12 m wide, 9 m deep, its front edge 3 m away. */
    private static final double HALF_WIDTH = 6;
    private static final double DEPTH = 9;
    private static final double LISTENER = 3;
    private static final double SOUND_SPEED = 343;

    private final int blockFrames;
    /** What moves; {@code null} for the level curves of the placements. */
    private final StageAutomation automation;

    /** One track as sound: the samples of both sides, between -1 and 1. */
    public static final class Signal {
        public final float[] left;
        public final float[] right;
        public final float rate;

        public Signal(float[] left, float[] right, float rate) {
            if (left.length != right.length) {
                throw new IllegalArgumentException("Both sides of a signal must be equally long.");
            }
            this.left = left;
            this.right = right;
            this.rate = rate;
        }
    }

    /** The finished mix in float, both sides equally long. */
    public static final class Mix {
        public final float[] left;
        public final float[] right;

        Mix(float[] left, float[] right) {
            this.left = left;
            this.right = right;
        }

        public float peak() {
            float peak = 0;
            for (int i = 0; i < left.length; i++) {
                peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
            }
            return peak;
        }
    }

    public SpatialStemMixer() {
        this(BLOCK);
    }

    /** A mixer that works in blocks of this many frames; for tests that show the result does not depend on it. */
    public SpatialStemMixer(int blockFrames) {
        this(blockFrames, null);
    }

    private SpatialStemMixer(int blockFrames, StageAutomation automation) {
        if (blockFrames <= 0) {
            throw new IllegalArgumentException("A block holds at least one frame.");
        }
        this.blockFrames = blockFrames;
        this.automation = automation;
    }

    /**
     * The same mixer, hearing this automation instead of the level curves of the placements: for
     * a mix document, {@link StageAutomation#of(com.aresstack.audio.composition.mix.MixScene,
     * com.aresstack.audio.composition.TimeMap)}. Tracks are found by the ids of their placements;
     * {@code secondsPerBar} is then not needed for the movement.
     */
    public SpatialStemMixer withAutomation(StageAutomation automation) {
        if (automation == null) {
            throw new IllegalArgumentException("Automation is StageAutomation.NONE, not null.");
        }
        return new SpatialStemMixer(blockFrames, automation);
    }

    /** Where a placement is heard: -1 is hard left, +1 hard right. */
    public static double pan(SoundSourcePlacement placement) {
        return pan(placement.x(), placement.depth());
    }

    /** Where a place on the stage is heard: -1 is hard left, +1 hard right. */
    public static double pan(double x, double depth) {
        double angle = Math.atan2(x * HALF_WIDTH, LISTENER + depth * DEPTH);
        return Math.max(-1, Math.min(1, angle / Math.toRadians(60)));
    }

    /** How far a placement is from the listener, in metres. */
    public static double distance(SoundSourcePlacement placement) {
        return distance(placement.x(), placement.depth());
    }

    /** How far a place on the stage is from the listener, in metres. */
    public static double distance(double x, double depth) {
        return Math.hypot(x * HALF_WIDTH, LISTENER + depth * DEPTH);
    }

    /** Mixes the files of the tracks and writes the mix as a WAV of 16 or 24 bits. */
    public void mix(List<Path> files, List<SoundSourcePlacement> placements, AcousticRoom room,
                    double secondsPerBar, Path output, int bits) throws Exception {
        mix(files, placements, room, secondsPerBar, 0, output, bits);
    }

    /**
     * Mixes the files of the tracks in the compatibility mode ({@link MixLevel#legacy}): the
     * finished mix normalized to its peak as before, then the master level.
     */
    public void mix(List<Path> files, List<SoundSourcePlacement> placements, AcousticRoom room,
                    double secondsPerBar, double masterDb, Path output, int bits) throws Exception {
        mix(files, placements, room, secondsPerBar, MixLevel.legacy(masterDb), output, bits);
    }

    /**
     * Mixes the files of the tracks with this level strategy and writes the mix as a WAV of 16
     * or 24 bits.
     *
     * @return what the mix found out about its level, for the preview of the same piece
     */
    public MixOutcome mix(List<Path> files, List<SoundSourcePlacement> placements, AcousticRoom room,
                          double secondsPerBar, MixLevel level, Path output, int bits) throws Exception {
        if (level == null) {
            throw new IllegalArgumentException("A mix needs its level strategy.");
        }
        if (files.isEmpty() || files.size() != placements.size()) {
            throw new IllegalArgumentException("Every audio stem needs its placement.");
        }
        if (bits != 16 && bits != 24) {
            throw new IllegalArgumentException("A WAV is written with 16 or 24 bits.");
        }
        // How long the mix gets is taken from the heads of the files. A muted track is not even read.
        long length = 0;
        for (int i = 0; i < files.size(); i++) {
            if (!placements.get(i).muted()) {
                AudioFileFormat head = AudioSystem.getAudioFileFormat(files.get(i).toFile());
                long frames = head.getFrameLength();
                float rate = head.getFormat().getSampleRate();
                if (frames < 0 || rate <= 0) {
                    // A file that does not say: read it through once.
                    WavFileSource probe = new WavFileSource(files.get(i));
                    float own;
                    try {
                        own = probe.sampleRate();
                    } finally {
                        probe.close();
                    }
                    frames = LinearResampler.outputFrames(WavFileSource.countFrames(files.get(i)), own, RATE);
                    rate = RATE;
                }
                length = Math.max(length, (long) Math.ceil(frames * (double) RATE / rate));
            }
        }
        long heard = length;
        long total = length + tail(length, room);
        OfflineMix.GraphFactory graph = () -> graph(files.size(), placements, room, secondsPerBar, heard, total,
                index -> {
                    WavFileSource source = new WavFileSource(files.get(index));
                    return LinearResampler.at(source, source.sampleRate(), RATE);
                });
        // The mix goes to a file of its own first and replaces the output only when it is finished: the
        // stems are read while the mix is written (twice, if the level needs a first pass), and the output
        // may be one of them.
        Path fresh = output.toAbsolutePath().resolveSibling(output.getFileName() + "." + System.nanoTime() + ".part");
        try {
            MixOutcome outcome;
            try (WavFileSink sink = new WavFileSink(fresh, RATE, bits, blockFrames)) {
                if (level.mode() == LevelMode.FIXED) {
                    outcome = MixOutcome.fixed(OfflineMix.renderFixed(graph, RATE, blockFrames, level.master(),
                            level.normalization(), sink));
                } else {
                    outcome = MixOutcome.legacy(OfflineMix.renderNormalized(graph, RATE, blockFrames,
                            level.masterDb(), sink));
                }
            }
            Files.move(fresh, output, StandardCopyOption.REPLACE_EXISTING);
            return outcome;
        } finally {
            Files.deleteIfExists(fresh);
        }
    }

    /**
     * Mixes signals in memory, without the last step that sets the loudness of the whole;
     * the mix is as long as the longest track plus the ring of the room.
     */
    public Mix render(List<Signal> signals, List<SoundSourcePlacement> placements, AcousticRoom room,
                      double secondsPerBar) throws IOException {
        long length = 0;
        for (Signal signal : signals) {
            length = Math.max(length, LinearResampler.outputFrames(signal.left.length, signal.rate, RATE));
        }
        long total = length + tail(length, room);
        if (total > Integer.MAX_VALUE) {
            throw new IOException("Ein so langes Stück passt nicht in den Speicher; es wird als Datei gemischt.");
        }
        float[] left = new float[(int) total];
        float[] right = new float[(int) total];
        AudioBlock block = new AudioBlock(blockFrames);
        try (AudioSource graph = graph(signals.size(), placements, room, secondsPerBar, length, total, index -> {
            Signal signal = signals.get(index);
            return LinearResampler.at(new ArraySource(signal.left, signal.right, signal.rate), signal.rate, RATE);
        })) {
            graph.prepare(RATE, blockFrames);
            int at = 0;
            int got;
            do {
                block.setFrames(blockFrames);
                got = graph.read(block);
                System.arraycopy(block.left, 0, left, at, got);
                System.arraycopy(block.right, 0, right, at, got);
                at += got;
            } while (got == blockFrames);
        }
        return new Mix(left, right);
    }

    /** Opens the sound of the track at a place of the list, at its own rate or already at the rate of the mix. */
    private interface Sources {
        AudioSource open(int index) throws IOException;
    }

    /** How long the room rings after the last track has ended, in frames. */
    static long tail(long length, AcousticRoom room) {
        return length == 0 ? 0 : (int) (RATE * (rings(room) ? Math.min(8, room.reverbSeconds() + 0.4) : 0.3));
    }

    /** The graph of the audio core for the tracks on this stage in this room. */
    private MixGraph graph(int count, List<SoundSourcePlacement> placements, AcousticRoom room,
                           double secondsPerBar, long length, long total, Sources sources) throws IOException {
        StageAutomation moving = automation != null ? automation : StageAutomation.of(placements, secondsPerBar);
        List<StemChannel> channels = new ArrayList<StemChannel>();
        try {
            for (int i = 0; i < count; i++) {
                SoundSourcePlacement placement = placements.get(i);
                if (!placement.muted()) {
                    channels.add(channel(sources.open(i), placement, moving.of(placement.trackId()), room, length));
                }
            }
        } catch (IOException | RuntimeException failed) {
            new MixGraph(channels, null, 0, 0).close();
            throw failed;
        }
        RoomReverb reverb = reverb(room);
        return new MixGraph(channels, reverb, (float) room.amount(), total);
    }

    /** One track on the stage as a channel of the audio core. */
    private static StemChannel channel(AudioSource source, SoundSourcePlacement placement,
                                       StageAutomation.Lanes lanes, AcousticRoom room, long length) {
        ChannelParameters p = parameters(placement, lanes, room);
        StereoStrip strip = new StereoStrip(new StereoStrip.Settings(p.level(), p.width(), p.curve()));
        StereoBiquad shelf = p.shelf() != null ? new StereoBiquad(p.shelf()) : null;
        EarlyReflections reflections = null;
        if (p.echoes() > 0) {
            List<EarlyReflections.Tap> taps = new ArrayList<EarlyReflections.Tap>();
            for (int wall = 0; wall < p.echoes(); wall++) {
                taps.add(new EarlyReflections.Tap(p.echoDelay(wall), p.echoLeft(wall), p.echoRight(wall)));
            }
            reflections = new EarlyReflections(taps, echoLowPass(room));
        }
        if (p.motion() != null) {
            // A moving source: its panorama, its distance gain and its send follow the curves; the
            // shelf and the echoes of the walls stay where it is set up (see StemChannel).
            return new StemChannel(source, length, strip, shelf, p.motion(), reflections);
        }
        StereoPanner panner = StereoPanner.ofGains(p.toLeft(), p.toRight());
        return new StemChannel(source, length, strip, p.sendLeft(), p.sendRight(), shelf, panner, reflections);
    }

    /** Whether the room rings at all. */
    static boolean rings(AcousticRoom room) {
        return room.amount() > 0.001;
    }

    /** Whether the walls of the room answer with early echoes. */
    static boolean reflects(AcousticRoom room) {
        return room.reflections() > 0.001 && room.amount() > 0.001;
    }

    /** How the walls dull the echoes. */
    static BiquadCoefficients echoLowPass(AcousticRoom room) {
        return BiquadCoefficients.lowPass(RATE, below(9000 - 6000 * room.damping()), Math.sqrt(0.5));
    }

    /** The shared room of the stage, or {@code null} if it does not ring. */
    static RoomReverb reverb(AcousticRoom room) {
        if (!rings(room)) {
            return null;
        }
        double preDelay = Math.min(0.045, room.depthMetres() / SOUND_SPEED * 0.35);
        return new RoomReverb(new RoomReverb.Settings(room.reverbSeconds(), room.damping(), preDelay));
    }

    /**
     * Where a track stands on the stage with the level curve of its placement ({@code dynamics},
     * in bars) and nothing else moving: what live playback takes while the user edits the stage.
     */
    static ChannelParameters parameters(SoundSourcePlacement placement, AcousticRoom room, double secondsPerBar) {
        return parameters(placement, StageAutomation.Lanes.STILL, room, secondsPerBar);
    }

    /**
     * As above, with the level curve of the placement and the movements of the place and of the
     * room share from elsewhere (the mix document): live playback of a stage with moving sources.
     */
    static ChannelParameters parameters(SoundSourcePlacement placement, StageAutomation.Lanes movements,
                                        AcousticRoom room, double secondsPerBar) {
        AutomationCurve level = StageAutomation.of(List.of(placement), secondsPerBar).of(placement.trackId()).level();
        return parameters(placement, new StageAutomation.Lanes(level, movements.x(), movements.depth(),
                movements.reverb()), room);
    }

    /**
     * Where a track stands on the stage, as the numbers of a channel of the audio core: level
     * (also over time) and width, place between left and right with the distance gain, send to
     * the room, distance shelf and wall echoes; for a moving source also its motion. The file mix
     * and live playback both take their channels from here, so the stage sounds the same in both.
     * A muted track is not handled here; the caller leaves it out (file) or turns it down (live).
     */
    static ChannelParameters parameters(SoundSourcePlacement placement, StageAutomation.Lanes lanes,
                                        AcousticRoom room) {
        boolean rings = rings(room);
        // Level, also over time, and width: the middle stays, the difference of the sides is scaled.
        float level = (float) Math.pow(10, placement.levelDb() / 20);
        float width = (float) placement.width();
        StereoStrip.LevelCurve curve = lanes.level() == null ? null
                : frame -> (float) Math.pow(10, lanes.level().valueAt(frame) / 20);
        double distance = distance(placement);
        // Distance: duller in the heights.
        double dull = -Math.min(9, (distance - LISTENER) * 0.55 * (0.6 + room.damping()));
        BiquadCoefficients shelf = dull < -0.2 ? BiquadCoefficients.highShelf(RATE, below(4500), dull, 1) : null;
        // Where it stands when nothing moves: the same formulas as for a moving source.
        float[] seat = new float[ChannelMotion.GAINS];
        seat(placement.x(), placement.depth(), placement.reverb(), rings, seat);
        StereoPanner panner = new StereoPanner(pan(placement), near(distance));
        ChannelParameters.Builder parameters = ChannelParameters.builder()
                .strip(level, width, curve)
                .pan(panner.toLeft(), panner.toRight())
                .send(seat[ChannelMotion.SEND_LEFT], seat[ChannelMotion.SEND_RIGHT])
                .shelf(shelf);
        if (lanes.moves()) {
            parameters.motion((frame, gains) -> seat(
                    lanes.x() == null ? placement.x() : lanes.x().valueAt(frame),
                    lanes.depth() == null ? placement.depth() : lanes.depth().valueAt(frame),
                    lanes.reverb() == null ? placement.reverb() : lanes.reverb().valueAt(frame), rings, gains));
        }
        if (reflects(room)) {
            reflections(placement, room, distance, parameters);
        }
        return parameters.build();
    }

    /**
     * The gains of a track at a place on the stage with a share of the room, as {@link
     * ChannelMotion} wants them: a standing and a moving track use the same formulas. The room
     * hears the track before the distance takes its toll: the direct sound fades, the ring stays.
     * Left and right have equal power, so a track sounds equally loud wherever it stands.
     */
    static void seat(double x, double depth, double reverb, boolean rings, float[] gains) {
        double distance = distance(x, depth);
        double far = Math.max(0, Math.min(1, (distance - LISTENER) / DEPTH));
        double position = pan(x, depth);
        float sendToLeft = 0;
        float sendToRight = 0;
        if (rings) {
            float send = (float) (reverb * (0.45 + 0.9 * far));
            sendToLeft = (float) (send * (1 - 0.35 * position));
            sendToRight = (float) (send * (1 + 0.35 * position));
        }
        float near = near(distance);
        gains[ChannelMotion.TO_LEFT] = StereoPanner.leftGain(position, near);
        gains[ChannelMotion.TO_RIGHT] = StereoPanner.rightGain(position, near);
        gains[ChannelMotion.SEND_LEFT] = sendToLeft;
        gains[ChannelMotion.SEND_RIGHT] = sendToRight;
    }

    /** Distance makes a track quieter. */
    private static float near(double distance) {
        return (float) Math.pow(LISTENER / distance, 0.45);
    }

    /** The first echoes: the track mirrored in the left wall, the right wall and the back wall. */
    private static void reflections(SoundSourcePlacement placement, AcousticRoom room, double direct,
                                    ChannelParameters.Builder parameters) {
        double x = placement.x() * HALF_WIDTH;
        double y = LISTENER + placement.depth() * DEPTH;
        double wall = Math.max(HALF_WIDTH + 0.5, room.widthMetres() / 2);
        double back = LISTENER + DEPTH + Math.max(0.5, room.depthMetres() * 0.15);
        double[][] images = {{-2 * wall - x, y}, {2 * wall - x, y}, {x, 2 * back - y}};
        int[] delays = new int[images.length];
        float[] toLeft = new float[images.length];
        float[] toRight = new float[images.length];
        for (int i = 0; i < images.length; i++) {
            double[] image = images[i];
            double distance = Math.hypot(image[0], image[1]);
            delays[i] = (int) Math.round((distance - direct) / SOUND_SPEED * RATE);
            float gain = (float) (room.reflections() * room.amount() * 1.6 * Math.pow(LISTENER / distance, 0.45));
            double position = Math.max(-1, Math.min(1, Math.atan2(image[0], image[1]) / Math.toRadians(60)));
            double angle = (position + 1) * Math.PI / 4;
            toLeft[i] = (float) Math.cos(angle) * gain;
            toRight[i] = (float) Math.sin(angle) * gain;
        }
        parameters.echoes(delays, toLeft, toRight);
    }

    /**
     * The longest echo delay any place on the stage can have in this room, in frames: what a live
     * channel must keep of its history so that a track can be moved anywhere.
     */
    static int longestEcho(AcousticRoom room) {
        double wall = Math.max(HALF_WIDTH + 0.5, room.widthMetres() / 2);
        double back = LISTENER + DEPTH + Math.max(0.5, room.depthMetres() * 0.15);
        // The farthest image: from a corner of the stage to the farther side wall or to the back wall.
        double side = Math.hypot(2 * wall + HALF_WIDTH, LISTENER + DEPTH);
        double rear = Math.hypot(HALF_WIDTH, 2 * back - LISTENER);
        return (int) Math.ceil(Math.max(side, rear) / SOUND_SPEED * RATE) + 1;
    }

    /** The studio has always kept its filters below 0.45 of the rate. */
    private static double below(double frequency) {
        return Math.min(frequency, RATE * 0.45);
    }

    /**
     * The last step: the finished mix is brought to one loudness as a whole, never squeezed.
     * Nothing may clip, and a mix of quiet tracks is raised (by 12 dB at most) so that it does
     * not come out thin. The balance between the tracks stays as it was set.
     */
    public static Mix master(Mix mix) {
        return master(mix, 0);
    }

    /**
     * @param masterDb how far below full level the mix shall be, 0 or less
     */
    public static Mix master(Mix mix, double masterDb) {
        float peak = mix.peak();
        if (peak > 1e-4f) {
            float gain = LegacyPeakNormalization.gain(peak, masterDb);
            for (int i = 0; i < mix.left.length; i++) {
                mix.left[i] *= gain;
                mix.right[i] *= gain;
            }
        }
        return mix;
    }

    /** Writes a mix as a WAV of 16 or 24 bits. */
    public static void write(Mix mix, Path output, int bits) throws IOException {
        if (bits != 16 && bits != 24) {
            throw new IllegalArgumentException("A WAV is written with 16 or 24 bits.");
        }
        AudioBlock block = new AudioBlock(BLOCK);
        try (WavFileSink sink = new WavFileSink(output, RATE, bits, BLOCK)) {
            for (int at = 0; at < mix.left.length; at += BLOCK) {
                int count = Math.min(BLOCK, mix.left.length - at);
                block.setFrames(count);
                System.arraycopy(mix.left, at, block.left, 0, count);
                System.arraycopy(mix.right, at, block.right, 0, count);
                sink.write(block);
            }
        }
    }

    /**
     * Reads a whole WAV of whole numbers (16, 24 or 32 bits) or of 32-bit floats as it is,
     * without rounding it first. For mixing, files are streamed instead.
     */
    public static Signal read(Path file) throws Exception {
        try (WavFileSource source = new WavFileSource(file)) {
            AudioBlock block = new AudioBlock(16384);
            source.prepare((int) source.sampleRate(), block.capacity());
            float[] left = new float[block.capacity()];
            float[] right = new float[block.capacity()];
            int frames = 0;
            int got;
            do {
                block.setFrames(block.capacity());
                got = source.read(block);
                if (frames + got > left.length) {
                    left = Arrays.copyOf(left, Math.max(frames + got, left.length * 2));
                    right = Arrays.copyOf(right, left.length);
                }
                System.arraycopy(block.left, 0, left, frames, got);
                System.arraycopy(block.right, 0, right, frames, got);
                frames += got;
            } while (got == block.capacity());
            return new Signal(Arrays.copyOf(left, frames), Arrays.copyOf(right, frames), source.sampleRate());
        }
    }
}
