package com.aresstack.audio.studio.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.conv.ImpulseResponse;
import com.aresstack.audio.core.conv.SyntheticRoom;
import com.aresstack.audio.core.dsp.ConvolutionReverb;
import com.aresstack.audio.core.dsp.EarlyReflections;
import com.aresstack.audio.core.dsp.RoomBus;
import com.aresstack.audio.core.dsp.RoomReverb;
import com.aresstack.audio.core.dsp.StereoBiquad;
import com.aresstack.audio.core.dsp.StereoPanner;
import com.aresstack.audio.core.dsp.StereoStrip;
import com.aresstack.audio.core.live.ChannelParameters;
import com.aresstack.audio.core.mix.ChannelMotion;
import com.aresstack.audio.core.mix.LegacyPeakNormalization;
import com.aresstack.audio.core.mix.LevelMode;
import com.aresstack.audio.core.mix.MixChannel;
import com.aresstack.audio.core.mix.MixGraph;
import com.aresstack.audio.core.mix.Seat;
import com.aresstack.audio.core.mix.SpatialChannel;
import com.aresstack.audio.core.mix.SpatialMotion;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final StageSound sound;

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
        this(blockFrames, null, StageSound.LOUDSPEAKERS);
    }

    private SpatialStemMixer(int blockFrames, StageAutomation automation, StageSound sound) {
        if (blockFrames <= 0) {
            throw new IllegalArgumentException("A block holds at least one frame.");
        }
        if (sound == null) {
            throw new IllegalArgumentException("How the stage is heard is StageSound.LOUDSPEAKERS, not null.");
        }
        this.blockFrames = blockFrames;
        this.automation = automation;
        this.sound = sound;
    }

    /**
     * The same mixer, heard as {@code sound} says: on headphones and/or with a convolved room
     * (work package D4). {@link StageSound#LOUDSPEAKERS} is the mix as before.
     */
    public SpatialStemMixer withSound(StageSound sound) {
        return new SpatialStemMixer(blockFrames, automation, sound);
    }

    public StageSound sound() {
        return sound;
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
        return new SpatialStemMixer(blockFrames, automation, sound);
    }

    /** Where a placement is heard: -1 is hard left, +1 hard right. */
    public static double pan(SoundSourcePlacement placement) {
        return pan(placement.x(), placement.depth(), placement.height());
    }

    /** Where a place on the stage is heard: -1 is hard left, +1 hard right. */
    public static double pan(double x, double depth) {
        double angle = Math.atan2(x * HALF_WIDTH, LISTENER + depth * DEPTH);
        return Math.max(-1, Math.min(1, angle / Math.toRadians(60)));
    }

    /**
     * Where a place with height is heard on loudspeakers: by the angle to the side, so a source
     * high above drifts to the middle. At height 0 exactly {@link #pan(double, double)}.
     */
    public static double pan(double x, double depth, double height) {
        if (height == 0) {
            return pan(x, depth);
        }
        double angle = Math.atan2(x * HALF_WIDTH, Math.hypot(LISTENER + depth * DEPTH, height * TOP));
        return Math.max(-1, Math.min(1, angle / Math.toRadians(60)));
    }

    /** The direction of a place for headphones: degrees clockwise from straight ahead. */
    public static double azimuth(double x, double depth) {
        double degrees = Math.toDegrees(Math.atan2(x * HALF_WIDTH, LISTENER + depth * DEPTH));
        return degrees < 0 ? degrees + 360 : degrees;
    }

    /** The direction of a place for headphones: degrees above the ears. */
    public static double elevation(double x, double depth, double height) {
        return Math.toDegrees(Math.atan2(height * TOP, Math.hypot(x * HALF_WIDTH, LISTENER + depth * DEPTH)));
    }

    /** How far a placement is from the listener, in metres. */
    public static double distance(SoundSourcePlacement placement) {
        return distance(placement.x(), placement.depth(), placement.height());
    }

    /** How far a place on the stage is from the listener, in metres. */
    public static double distance(double x, double depth) {
        return Math.hypot(x * HALF_WIDTH, LISTENER + depth * DEPTH);
    }

    /** How far a place with height is from the listener, in metres; at height 0 exactly {@link #distance(double, double)}. */
    public static double distance(double x, double depth, double height) {
        return height == 0 ? distance(x, depth) : Math.hypot(distance(x, depth), height * TOP);
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
        long total = length + tail(length, room, sound);
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
        long total = length + tail(length, room, sound);
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

    /** As {@link #tail(long, AcousticRoom)}, long enough for the impulse response of a convolved room. */
    static long tail(long length, AcousticRoom room, StageSound sound) {
        long algorithmic = tail(length, room);
        if (length == 0 || !sound.convolution() || !rings(room)) {
            return algorithmic;
        }
        double seconds = Math.min(12, impulse(room, sound).seconds() + 0.3);
        return Math.max(algorithmic, (long) (RATE * seconds));
    }

    /** The graph of the audio core for the tracks on this stage in this room. */
    private MixGraph graph(int count, List<SoundSourcePlacement> placements, AcousticRoom room,
                           double secondsPerBar, long length, long total, Sources sources) throws IOException {
        StageAutomation moving = automation != null ? automation : StageAutomation.of(placements, secondsPerBar);
        List<MixChannel> channels = new ArrayList<MixChannel>();
        try {
            for (int i = 0; i < count; i++) {
                SoundSourcePlacement placement = placements.get(i);
                if (!placement.muted()) {
                    channels.add(channel(sources.open(i), placement, moving.of(placement.trackId()), room, sound,
                            length));
                }
            }
        } catch (IOException | RuntimeException failed) {
            new MixGraph(channels, null, 0, 0).close();
            throw failed;
        }
        return new MixGraph(channels, roomBus(room, sound), (float) room.amount(), total);
    }

    /**
     * One track on the stage as a channel of the audio core. A standing track on loudspeakers is
     * a {@link StemChannel}, exactly as before work package D4; a moving track, and every track on
     * headphones, is a {@link SpatialChannel}, whose shelf, wall echoes and direction follow it.
     */
    private static MixChannel channel(AudioSource source, SoundSourcePlacement placement,
                                      StageAutomation.Lanes lanes, AcousticRoom room, StageSound sound, long length) {
        StereoStrip.LevelCurve curve = lanes.level() == null ? null
                : frame -> (float) Math.pow(10, lanes.level().valueAt(frame) / 20);
        if (!lanes.moves() && !sound.headphones()) {
            ChannelParameters p = parameters(placement, room, curve);
            // What the mix document says beyond the range of the placement (D3) goes into the strip.
            float level = lanes.trimDb() == 0 ? p.level()
                    : (float) Math.pow(10, (placement.levelDb() + lanes.trimDb()) / 20);
            StereoStrip strip = new StereoStrip(new StereoStrip.Settings(level, p.width(), p.curve()));
            StereoBiquad shelf = p.shelf() != null ? new StereoBiquad(p.shelf()) : null;
            EarlyReflections reflections = null;
            if (p.echoes() > 0) {
                List<EarlyReflections.Tap> taps = new ArrayList<EarlyReflections.Tap>();
                for (int wall = 0; wall < p.echoes(); wall++) {
                    taps.add(new EarlyReflections.Tap(p.echoDelay(wall), p.echoLeft(wall), p.echoRight(wall)));
                }
                reflections = new EarlyReflections(taps, echoLowPass(room));
            }
            StereoPanner panner = StereoPanner.ofGains(p.toLeft(), p.toRight());
            return new StemChannel(source, length, strip, p.sendLeft(), p.sendRight(), shelf, panner, reflections);
        }
        StereoStrip strip = new StereoStrip(new StereoStrip.Settings(
                (float) Math.pow(10, (placement.levelDb() + lanes.trimDb()) / 20),
                (float) placement.width(), curve));
        int walls = walls(room, sound);
        // Where the track stands when nothing moves, with exactly the numbers of a standing track.
        Seat standing = standing(parameters(placement, room, curve, sound));
        SpatialMotion motion;
        if (lanes.moves()) {
            float[] scratch = new float[ChannelMotion.GAINS];
            motion = (frame, seat) -> {
                double x = lanes.x() == null ? placement.x() : lanes.x().valueAt(frame);
                double depth = lanes.depth() == null ? placement.depth() : lanes.depth().valueAt(frame);
                double height = lanes.height() == null ? placement.height() : lanes.height().valueAt(frame);
                double reverb = lanes.reverb() == null ? placement.reverb() : lanes.reverb().valueAt(frame);
                if (x == placement.x() && depth == placement.depth() && height == placement.height()) {
                    // At its own place the track sounds exactly as standing; only the room share may move.
                    seat.copyFrom(standing);
                    gains(x, depth, height, reverb, rings(room), sound.headphones(), scratch);
                    seat.sendLeft = scratch[ChannelMotion.SEND_LEFT];
                    seat.sendRight = scratch[ChannelMotion.SEND_RIGHT];
                } else {
                    seat(x, depth, height, reverb, room, sound, seat);
                }
            };
        } else {
            motion = (frame, seat) -> seat.copyFrom(standing);
        }
        return new SpatialChannel(source, length, strip, motion, walls, walls > 0 ? longestEcho(room) : 0,
                walls > 0 ? echoLowPass(room) : null, sound.headphones() ? sound.head() : null);
    }

    /** The numbers of a standing track as a seat of a {@link SpatialChannel}. */
    private static Seat standing(ChannelParameters p) {
        Seat seat = new Seat(p.echoes());
        seat.toLeft = p.toLeft();
        seat.toRight = p.toRight();
        seat.sendLeft = p.sendLeft();
        seat.sendRight = p.sendRight();
        seat.shelf = p.shelf();
        seat.azimuth = p.azimuth();
        seat.elevation = p.elevation();
        for (int wall = 0; wall < p.echoes(); wall++) {
            seat.echoDelay[wall] = p.echoDelay(wall);
            seat.echoLeft[wall] = p.echoLeft(wall);
            seat.echoRight[wall] = p.echoRight(wall);
        }
        return seat;
    }

    /** Whether the room rings at all. */
    static boolean rings(AcousticRoom room) {
        return room.amount() > 0.001;
    }

    /** Whether the walls of the room answer with early echoes. */
    static boolean reflects(AcousticRoom room) {
        return room.reflections() > 0.001 && room.amount() > 0.001;
    }

    /**
     * How many walls answer: none in a dry room; on loudspeakers the left, right and back wall as
     * always; on headphones also floor and ceiling, which tell the ear how high a source stands.
     */
    static int walls(AcousticRoom room, StageSound sound) {
        return !reflects(room) ? 0 : sound.headphones() ? 5 : 3;
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
        return new RoomReverb(settings(room));
    }

    private static RoomReverb.Settings settings(AcousticRoom room) {
        double preDelay = Math.min(0.045, room.depthMetres() / SOUND_SPEED * 0.35);
        return new RoomReverb.Settings(room.reverbSeconds(), room.damping(), preDelay);
    }

    /** The shared room as it is heard: algorithmic or convolved; {@code null} if it does not ring. */
    static RoomBus roomBus(AcousticRoom room, StageSound sound) {
        if (!rings(room)) {
            return null;
        }
        return sound.convolution() ? new ConvolutionReverb(impulse(room, sound)) : reverb(room);
    }

    private static final Map<RoomReverb.Settings, ImpulseResponse> MADE = new ConcurrentHashMap<>();

    /**
     * The impulse response of the room: the measured one of the sound, or one made from the
     * numbers of the room ({@link SyntheticRoom}) with the energy of the algorithmic room, so that
     * switching between the two keeps the share of the room about as loud.
     */
    static ImpulseResponse impulse(AcousticRoom room, StageSound sound) {
        if (sound.impulseResponse() != null) {
            return sound.impulseResponse();
        }
        return MADE.computeIfAbsent(settings(room), settings -> {
            ImpulseResponse made = SyntheticRoom.generate(settings.seconds(), settings.damping(),
                    settings.preDelaySeconds(), RATE, 0x5EA7L);
            return made.withEnergy(ringEnergy(settings));
        });
    }

    /** The energy of the algorithmic room's answer to a click on both sides. */
    private static double ringEnergy(RoomReverb.Settings settings) {
        RoomReverb reverb = new RoomReverb(settings);
        int frames = (int) (RATE * Math.min(12, settings.seconds() * 1.2 + settings.preDelaySeconds()));
        reverb.prepare(RATE, BLOCK);
        AudioBlock send = new AudioBlock(BLOCK);
        AudioBlock ring = new AudioBlock(BLOCK);
        double energy = 0;
        for (int at = 0; at < frames; at += BLOCK) {
            int count = Math.min(BLOCK, frames - at);
            send.clear(0, BLOCK);
            ring.clear(0, BLOCK);
            if (at == 0) {
                send.left[0] = 1;
                send.right[0] = 1;
            }
            reverb.process(send, count, ring, 1f);
            for (int i = 0; i < count; i++) {
                energy += (double) ring.left[i] * ring.left[i] + (double) ring.right[i] * ring.right[i];
            }
        }
        // The convolution feeds each side 0.8 + 0.2 of a click on both sides, that is the full click.
        return energy;
    }

    /**
     * Where a track stands on the stage, as the numbers of a channel of the audio core: level
     * (also over time) and width, place between left and right with the distance gain, send to
     * the room, distance shelf and wall echoes. The file mix and live playback both take their
     * channels from here, so the stage sounds the same in both. A muted track is not handled
     * here; the caller leaves it out (file) or turns it down (live).
     */
    static ChannelParameters parameters(SoundSourcePlacement placement, AcousticRoom room, double secondsPerBar) {
        return parameters(placement, room, secondsPerBar, StageSound.LOUDSPEAKERS);
    }

    /** As {@link #parameters(SoundSourcePlacement, AcousticRoom, double)}, heard as {@code sound} says. */
    static ChannelParameters parameters(SoundSourcePlacement placement, AcousticRoom room, double secondsPerBar,
                                        StageSound sound) {
        boolean moves = !placement.dynamics().isEmpty() && secondsPerBar > 0;
        StereoStrip.LevelCurve curve = moves
                ? frame -> (float) Math.pow(10, placement.dynamicsAt(frame / (RATE * secondsPerBar)) / 20)
                : null;
        return parameters(placement, room, curve, sound);
    }

    /** As {@link #parameters(SoundSourcePlacement, AcousticRoom, double)}, with the level curve given. */
    static ChannelParameters parameters(SoundSourcePlacement placement, AcousticRoom room,
                                        StereoStrip.LevelCurve curve) {
        return parameters(placement, room, curve, StageSound.LOUDSPEAKERS);
    }

    private static ChannelParameters parameters(SoundSourcePlacement placement, AcousticRoom room,
                                                StereoStrip.LevelCurve curve, StageSound sound) {
        // Level, also over time, and width: the middle stays, the difference of the sides is scaled.
        float level = (float) Math.pow(10, placement.levelDb() / 20);
        float width = (float) placement.width();
        double x = placement.x();
        double depth = placement.depth();
        double height = placement.height();
        double distance = distance(x, depth, height);
        // Distance: duller in the heights.
        double dull = -Math.min(9, (distance - LISTENER) * 0.55 * (0.6 + room.damping()));
        BiquadCoefficients shelf = dull < -0.2 ? BiquadCoefficients.highShelf(RATE, below(4500), dull, 1) : null;
        float[] gains = new float[ChannelMotion.GAINS];
        gains(x, depth, height, placement.reverb(), rings(room), sound.headphones(), gains);
        ChannelParameters.Builder parameters = ChannelParameters.builder()
                .strip(level, width, curve)
                .pan(gains[ChannelMotion.TO_LEFT], gains[ChannelMotion.TO_RIGHT])
                .send(gains[ChannelMotion.SEND_LEFT], gains[ChannelMotion.SEND_RIGHT])
                .shelf(shelf)
                .direction(azimuth(x, depth), elevation(x, depth, height));
        int walls = walls(room, sound);
        if (walls > 0) {
            int[] delays = new int[walls];
            float[] toLeft = new float[walls];
            float[] toRight = new float[walls];
            float[] echo = new float[3];
            for (int wall = 0; wall < walls; wall++) {
                echo(wall, x, depth, height, distance, room, echo);
                delays[wall] = Math.round(echo[0]);
                toLeft[wall] = echo[1];
                toRight[wall] = echo[2];
            }
            parameters.echoes(delays, toLeft, toRight);
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
        gains(x, depth, 0, reverb, rings, false, gains);
    }

    /**
     * As {@link #seat(double, double, double, boolean, float[])}, with height. On headphones the
     * direct gains are the distance gain on both sides; the direction does the rest.
     */
    static void gains(double x, double depth, double height, double reverb, boolean rings, boolean headphones,
                      float[] gains) {
        double distance = distance(x, depth, height);
        double far = Math.max(0, Math.min(1, (distance - LISTENER) / DEPTH));
        double position = pan(x, depth, height);
        float sendToLeft = 0;
        float sendToRight = 0;
        if (rings) {
            float send = (float) (reverb * (0.45 + 0.9 * far));
            sendToLeft = (float) (send * (1 - 0.35 * position));
            sendToRight = (float) (send * (1 + 0.35 * position));
        }
        float near = near(distance);
        gains[ChannelMotion.TO_LEFT] = headphones ? near : StereoPanner.leftGain(position, near);
        gains[ChannelMotion.TO_RIGHT] = headphones ? near : StereoPanner.rightGain(position, near);
        gains[ChannelMotion.SEND_LEFT] = sendToLeft;
        gains[ChannelMotion.SEND_RIGHT] = sendToRight;
    }

    /** Distance shelves of a moving track, in steps of 0.1 dB from 0 to -9 dB: made once, glided between. */
    private static final BiquadCoefficients[] SHELVES = new BiquadCoefficients[91];

    static {
        for (int i = 3; i < SHELVES.length; i++) {
            SHELVES[i] = BiquadCoefficients.highShelf(RATE, below(4500), -0.1 * i, 1);
        }
    }

    /**
     * Everything a {@link SpatialChannel} needs at one place: gains, send, shelf, direction and wall
     * echoes. Used on the rendering thread by moving tracks; allocates nothing.
     */
    static void seat(double x, double depth, double height, double reverb, AcousticRoom room, StageSound sound,
                     Seat seat) {
        double distance = distance(x, depth, height);
        double dull = -Math.min(9, (distance - LISTENER) * 0.55 * (0.6 + room.damping()));
        seat.shelf = SHELVES[(int) Math.min(SHELVES.length - 1, Math.round(-dull * 10))];
        boolean rings = rings(room);
        double far = Math.max(0, Math.min(1, (distance - LISTENER) / DEPTH));
        double position = pan(x, depth, height);
        float send = rings ? (float) (reverb * (0.45 + 0.9 * far)) : 0;
        seat.sendLeft = rings ? (float) (send * (1 - 0.35 * position)) : 0;
        seat.sendRight = rings ? (float) (send * (1 + 0.35 * position)) : 0;
        float near = near(distance);
        seat.toLeft = sound.headphones() ? near : StereoPanner.leftGain(position, near);
        seat.toRight = sound.headphones() ? near : StereoPanner.rightGain(position, near);
        seat.azimuth = azimuth(x, depth);
        seat.elevation = elevation(x, depth, height);
        for (int wall = 0; wall < seat.walls(); wall++) {
            echoInto(wall, x, depth, height, distance, room, seat);
        }
    }

    private static void echoInto(int wall, double x, double depth, double height, double direct, AcousticRoom room,
                                 Seat seat) {
        double[] image = IMAGE.get();
        mirror(wall, x, depth, height, room, image);
        double distance = length(image);
        seat.echoDelay[wall] = (float) ((distance - direct) / SOUND_SPEED * RATE);
        float gain = (float) (room.reflections() * room.amount() * 1.6 * Math.pow(LISTENER / distance, 0.45));
        double position = Math.max(-1, Math.min(1, Math.atan2(image[0],
                image[2] == 0 ? image[1] : Math.hypot(image[1], image[2])) / Math.toRadians(60)));
        double angle = (position + 1) * Math.PI / 4;
        seat.echoLeft[wall] = (float) Math.cos(angle) * gain;
        seat.echoRight[wall] = (float) Math.sin(angle) * gain;
    }

    /** The distance of an image from the ears; in the plane of the ears exactly as before work package D4. */
    private static double length(double[] image) {
        double flat = Math.hypot(image[0], image[1]);
        return image[2] == 0 ? flat : Math.hypot(flat, image[2]);
    }

    /** Scratch for the mirror image of a wall echo, one per rendering thread. */
    private static final ThreadLocal<double[]> IMAGE = ThreadLocal.withInitial(() -> new double[3]);

    /** Distance makes a track quieter. */
    private static float near(double distance) {
        return (float) Math.pow(LISTENER / distance, 0.45);
    }

    /** How far below the ears the floor is, in metres. */
    private static final double EARS = 1.2;
    /** How high the stage reaches above the ears at height 1, in metres. */
    private static final double TOP = 6;

    /**
     * The mirror image of the track in a wall, in metres from the ears (x right, y ahead, z up):
     * 0 left wall, 1 right wall, 2 back wall, 3 floor, 4 ceiling.
     */
    private static void mirror(int wall, double x, double depth, double height, AcousticRoom room, double[] image) {
        double px = x * HALF_WIDTH;
        double py = LISTENER + depth * DEPTH;
        double pz = height * TOP;
        double side = Math.max(HALF_WIDTH + 0.5, room.widthMetres() / 2);
        double back = LISTENER + DEPTH + Math.max(0.5, room.depthMetres() * 0.15);
        double ceiling = Math.max(TOP + 0.5, room.heightMetres() - EARS);
        image[0] = px;
        image[1] = py;
        image[2] = pz;
        switch (wall) {
            case 0 -> image[0] = -2 * side - px;
            case 1 -> image[0] = 2 * side - px;
            case 2 -> image[1] = 2 * back - py;
            case 3 -> image[2] = -2 * EARS - pz;
            default -> image[2] = 2 * ceiling - pz;
        }
    }

    /** One wall echo of a standing track: delay in frames, gain to the left, gain to the right. */
    private static void echo(int wall, double x, double depth, double height, double direct, AcousticRoom room,
                             float[] echo) {
        double[] image = new double[3];
        mirror(wall, x, depth, height, room, image);
        double distance = length(image);
        echo[0] = (float) Math.round((distance - direct) / SOUND_SPEED * RATE);
        float gain = (float) (room.reflections() * room.amount() * 1.6 * Math.pow(LISTENER / distance, 0.45));
        double position = Math.max(-1, Math.min(1, Math.atan2(image[0],
                image[2] == 0 ? image[1] : Math.hypot(image[1], image[2])) / Math.toRadians(60)));
        double angle = (position + 1) * Math.PI / 4;
        echo[1] = (float) Math.cos(angle) * gain;
        echo[2] = (float) Math.sin(angle) * gain;
    }

    /**
     * The longest echo delay any place on the stage can have in this room, in frames: what a live
     * or moving channel must keep of its history so that a track can be moved anywhere.
     */
    static int longestEcho(AcousticRoom room) {
        double side = Math.max(HALF_WIDTH + 0.5, room.widthMetres() / 2);
        double back = LISTENER + DEPTH + Math.max(0.5, room.depthMetres() * 0.15);
        double ceiling = Math.max(TOP + 0.5, room.heightMetres() - EARS);
        double up = Math.max(2 * ceiling, 2 * EARS + TOP);
        // The farthest image: from a corner of the stage to the farther side wall, the back wall or the ceiling.
        double sideImage = Math.hypot(Math.hypot(2 * side + HALF_WIDTH, LISTENER + DEPTH), TOP);
        double rear = Math.hypot(Math.hypot(HALF_WIDTH, 2 * back - LISTENER), TOP);
        double vertical = Math.hypot(Math.hypot(HALF_WIDTH, LISTENER + DEPTH), up);
        return (int) Math.ceil(Math.max(sideImage, Math.max(rear, vertical)) / SOUND_SPEED * RATE) + 1;
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
