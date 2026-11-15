package com.aresstack.audio.studio.mix;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.live.LiveGraph;
import com.aresstack.audio.studio.golden.Golden;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * "Lauf B" (D2, listening report "a hole in the tone" while a source glides), reproducible and for
 * every room preset: the melody of district-dawn glides four times in 600 ms into the corners of
 * the stage, live, in blocks of 512 frames. At every 20 ms of each glide (exactly k · 2.5 s +
 * i · 20 ms), the 10 ms of sound there are compared with the same music rendered with the source
 * standing still at the place where the moving source is heard then. A hole is a window more
 * than 1.5 dB quieter.
 *
 * <p>The moving source is heard about {@link #HEARD_LATE} after it is placed (20 ms glide of the
 * channel, 10 ms steps, the next block of 512): that is the fair place to compare with (decision
 * of the product owner). The count at the moment of placing and the old sound (wall echoes at
 * level 1) are reported too, not hidden: {@code -Dwall.echo.report=true}.
 *
 * <p>Decision of the product owner (PR #22, 2026-11-15): wall echoes at 0.5, no hole in the
 * studio, the chamber and the hall. The cathedral is judged on its own, because its 4.5 s of
 * reverberation still carry the places where the moving source stood before, which a standing
 * source never has: under the same long reverberation and motion, no hole may be new against the
 * old sound or deeper than before. Its early echoes alone (late reverberation off) and the
 * reverberation alone (no wall echoes) are measured for diagnosis. No room may show a
 * drop-out (a window far below both neighbours) that the standing source does not have.
 *
 * <p>Listening samples before and after, the moving melody in every room, also at equal loudness:
 * {@code -Dwall.echo.samples=<folder>}.
 */
public final class WallEchoMotionTest {
    static final int RATE = 44100;
    static final int BLOCK = 512;
    static final int WINDOW = 441;
    static final int STEPS = 40;
    /** How much later the moving source is heard where it is placed. */
    static final double HEARD_LATE = 0.020;
    static final double[][] CORNERS = {{-1, 0.1}, {1, 0.1}, {1, 0.9}, {-1, 0.9}};
    static final SoundSourcePlacement START = new SoundSourcePlacement("melody", -0.8, 0.3);
    /** The level of the wall echoes as decided (PR #22). */
    static final double LEVEL_DECIDED = 0.5;
    static final double LEVEL = LEVEL_DECIDED;

    private static Path home;
    private static Path melody;

    @BeforeClass
    public static void renderTheMelody() throws Exception {
        home = Files.createTempDirectory("lauf-b");
        if (System.getProperty("wall.echo.melody") != null) { // the stem of the D2 measurement, if given
            melody = Path.of(System.getProperty("wall.echo.melody"));
            return;
        }
        Path arrangement = home.resolve("district-dawn.tsv");
        Files.copy(Golden.fixture("district-dawn"), arrangement);
        Path output = home.resolve("render");
        Golden.renderReference(arrangement, output);
        try (Stream<Path> files = Files.walk(output)) {
            melody = files.filter(p -> p.getFileName().toString().equals("melody.wav")).findFirst()
                    .orElseThrow(() -> new AssertionError("no melody.wav in " + output));
        }
    }

    @AfterClass
    public static void cleanUp() throws Exception {
        SpatialStemMixer.wallEchoLevel = LEVEL;
        if (home != null) {
            try (Stream<Path> files = Files.walk(home)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** The holes of a run: which points (index over the four glides) and the deepest difference. */
    record Result(List<Integer> holes, double deepest) {
        int count() {
            return holes.size();
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d / %.2f dB", holes.size(), deepest);
        }
    }

    @Test
    public void aGlidingSourceLeavesNoHoleWhereItIsHeard() throws Exception {
        StringBuilder table = new StringBuilder("room | echoes | wet, where heard | wet, when placed"
                + " | early echoes only | reverberation only | level by place: std, max-min, step\n");
        boolean report = Boolean.getBoolean("wall.echo.report");
        List<String> failures = new ArrayList<String>();
        for (AcousticRoom room : AcousticRoom.PRESETS) {
            Result wet = at(LEVEL, () -> holes(room, HEARD_LATE, true));
            if (room != AcousticRoom.CATHEDRAL) {
                if (wet.count() > 0) {
                    failures.add(room.kind() + ": " + wet);
                }
            } else {
                // The cathedral: compared with the old sound under the same long reverberation and
                // the same motion, no hole may be new and none deeper than before.
                Result old = at(1.0, () -> holes(room, HEARD_LATE, true));
                if (!old.holes().containsAll(wet.holes())) {
                    failures.add("cathedral: holes " + wet.holes() + " that the old sound " + old.holes() + " has not");
                }
                if (wet.deepest() < old.deepest()) {
                    failures.add("cathedral: deeper than before, " + wet + " against " + old);
                }
            }
            for (double level : report ? new double[] {1.0, LEVEL} : new double[0]) {
                table.append(room.kind()).append(" | x").append(level)
                        .append(" | ").append(level == LEVEL ? wet : at(level, () -> holes(room, HEARD_LATE, true)))
                        .append(" | ").append(at(level, () -> holes(room, 0, true)))
                        .append(" | ").append(at(level, () -> holes(room, HEARD_LATE, false)))
                        .append(" | ").append(at(0, () -> holes(room, HEARD_LATE, true)))
                        .append(" | ").append(at(level, () -> levelByPlace(room))).append('\n');
            }
        }
        System.out.print(table);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void noRoomDropsOutWhileTheSourceGlides() throws Exception {
        for (AcousticRoom room : AcousticRoom.PRESETS) {
            float[][] moving = render(room, null, 10 * RATE, BLOCK, true);
            for (int k = 0; k < 4; k++) {
                int first = (int) (k * 2.5 * RATE / WINDOW) + 1;
                for (int w = first; w < first + 90; w++) {
                    double around = (db(moving, w - 1) + db(moving, w + 1)) / 2;
                    double dip = db(moving, w) - around;
                    if (dip < -6) {
                        // A window this far below both neighbours must be in the music as well.
                        float[][] standing = render(room, at(Math.max(k * 2.5, w * WINDOW / (double) RATE
                                - HEARD_LATE)), (w + 2) * WINDOW + BLOCK, BLOCK, true);
                        double still = db(standing, w) - (db(standing, w - 1) + db(standing, w + 1)) / 2;
                        assertTrue(room.kind() + ": drop-out of " + dip + " dB at window " + w, still < dip + 3);
                    }
                }
            }
        }
    }

    @Test
    public void aStandingSourceSoundsTheSameInAnyBlockSize() throws Exception {
        for (AcousticRoom room : AcousticRoom.PRESETS) {
            SoundSourcePlacement corner = START.at(1, 0.9);
            float[][] large = render(room, corner, 172 * 512, 512, true);
            float[][] small = render(room, corner, 172 * 512, 128, true);
            assertArrayEquals(room.kind(), large[0], small[0], 0f);
            assertArrayEquals(room.kind(), large[1], small[1], 0f);
        }
    }

    @Test
    public void listeningSamplesBeforeAndAfter() throws Exception {
        String folder = System.getProperty("wall.echo.samples");
        Assume.assumeTrue("Write with -Dwall.echo.samples=<folder>", folder != null);
        Path out = Files.createDirectories(Path.of(folder));
        for (AcousticRoom room : AcousticRoom.PRESETS) {
            float[][] before = at(1.0, () -> render(room, null, 10 * RATE, BLOCK, true));
            float[][] after = at(LEVEL, () -> render(room, null, 10 * RATE, BLOCK, true));
            write(out.resolve("lauf-b-" + room.kind() + "-vorher.wav"), before, 1);
            write(out.resolve("lauf-b-" + room.kind() + "-nachher.wav"), after, 1);
            // the same loudness as before, so that only the early echoes differ
            write(out.resolve("lauf-b-" + room.kind() + "-nachher-gleich-laut.wav"), after,
                    Math.sqrt(energy(before) / energy(after)));
        }
    }

    @Test
    public void theEchoesOfTheWallsAreQuieterNotGone() {
        assertEquals(LEVEL_DECIDED, SpatialStemMixer.wallEchoLevel, 0);
    }

    interface Run<T> {
        T run() throws Exception;
    }

    /** Runs with the wall echoes at a level, then puts the level back. */
    static <T> T at(double level, Run<T> run) throws Exception {
        SpatialStemMixer.wallEchoLevel = level;
        try {
            return run.run();
        } finally {
            SpatialStemMixer.wallEchoLevel = LEVEL;
        }
    }

    /** Where the source should be at second {@code s}: four glides of 600 ms in 10 ms steps (smoothstep). */
    static SoundSourcePlacement at(double s) {
        SoundSourcePlacement from = START;
        for (int k = 0; k < 4; k++) {
            double t0 = k * 2.5;
            SoundSourcePlacement to = START.at(CORNERS[k][0], CORNERS[k][1]);
            if (s < t0) {
                return from;
            }
            double share = Math.min(1, Math.floor((s - t0) / 0.01 + 1e-9) * 0.01 / 0.6);
            if (share < 1) {
                return LiveStage.between(from, to, share);
            }
            from = to;
        }
        return from;
    }

    /**
     * @param late   how much earlier on the way the standing source is put
     * @param reverb whether the late reverberation is heard (else the early echoes only)
     */
    static Result holes(AcousticRoom room, double late, boolean reverb) throws Exception {
        float[][] moving = render(room, null, 10 * RATE, BLOCK, reverb);
        List<Integer> holes = new ArrayList<Integer>();
        double deepest = 0;
        for (int k = 0; k < 4; k++) {
            for (int i = 0; i < STEPS; i++) {
                double s = k * 2.5 + i * 0.02;
                int window = (int) (s * RATE / WINDOW);
                float[][] standing = render(room, at(Math.max(k * 2.5, s - late)), (int) ((s + 0.3) * RATE) + BLOCK,
                        BLOCK, reverb);
                double difference = db(moving, window) - db(standing, window);
                if (difference < -1.5) {
                    holes.add(k * STEPS + i);
                }
                deepest = Math.min(deepest, difference);
            }
        }
        return new Result(holes, deepest);
    }

    /**
     * How much the level of a standing source changes from place to place along the four ways,
     * against the same music without wall echoes (so the music itself drops out).
     */
    static String levelByPlace(AcousticRoom room) throws Exception {
        double keep = SpatialStemMixer.wallEchoLevel;
        double sum = 0;
        double squares = 0;
        double most = -1e9;
        double least = 1e9;
        double step = 0;
        int n = 0;
        for (int k = 0; k < 4; k++) {
            Double before = null;
            for (int i = 0; i < STEPS; i++) {
                double s = k * 2.5 + i * 0.02;
                int frames = (int) ((s + 0.3) * RATE) + BLOCK;
                int window = (int) (s * RATE / WINDOW);
                SpatialStemMixer.wallEchoLevel = keep;
                double with = db(render(room, at(s), frames, BLOCK, true), window);
                SpatialStemMixer.wallEchoLevel = 0;
                double dry = db(render(room, at(s), frames, BLOCK, true), window);
                SpatialStemMixer.wallEchoLevel = keep;
                double d = with - dry;
                sum += d;
                squares += d * d;
                n++;
                most = Math.max(most, d);
                least = Math.min(least, d);
                if (before != null) {
                    step = Math.max(step, Math.abs(d - before));
                }
                before = d;
            }
        }
        double mean = sum / n;
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f dB", Math.sqrt(squares / n - mean * mean), most - least, step);
    }

    /** The melody on the stage, live: moving along the glides ({@code standing == null}) or standing there. */
    static float[][] render(AcousticRoom room, SoundSourcePlacement standing, int frames, int block, boolean reverb)
            throws Exception {
        boolean moving = standing == null;
        try (LiveStage stage = LiveStage.load(List.of(melody), List.of(moving ? START : standing), room, 2)) {
            LiveGraph graph = stage.graph();
            if (!reverb) {
                graph.setRoomGain(0);
            }
            graph.prepare(RATE, block);
            float[][] out = {new float[frames], new float[frames]};
            AudioBlock buffer = new AudioBlock(block);
            SoundSourcePlacement last = null;
            for (int at = 0; at + block <= frames; at += block) {
                if (moving) {
                    SoundSourcePlacement p = at(at / (double) RATE);
                    if (!p.equals(last)) {
                        stage.move(0, p);
                        last = p;
                    }
                }
                buffer.setFrames(block);
                graph.read(buffer);
                System.arraycopy(buffer.left, 0, out[0], at, block);
                System.arraycopy(buffer.right, 0, out[1], at, block);
            }
            return out;
        }
    }

    static double db(float[][] sound, int window) {
        double sum = 0;
        for (int i = window * WINDOW; i < (window + 1) * WINDOW; i++) {
            sum += sound[0][i] * (double) sound[0][i] + sound[1][i] * (double) sound[1][i];
        }
        return 10 * Math.log10(sum / WINDOW + 1e-12);
    }

    static double energy(float[][] sound) {
        double sum = 0;
        for (int i = 0; i < sound[0].length; i++) {
            sum += sound[0][i] * (double) sound[0][i] + sound[1][i] * (double) sound[1][i];
        }
        return sum;
    }

    /** 16-bit stereo PCM, scaled by {@code gain} and otherwise as heard. */
    static void write(Path file, float[][] sound, double gain) throws java.io.IOException {
        int frames = sound[0].length;
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(44 + frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        data.putInt(0x46464952).putInt(36 + frames * 4).putInt(0x45564157);
        data.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 2).putInt(RATE).putInt(RATE * 4)
                .putShort((short) 4).putShort((short) 16);
        data.putInt(0x61746164).putInt(frames * 4);
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < 2; c++) {
                data.putShort((short) Math.round(Math.max(-1, Math.min(1, sound[c][i] * gain)) * 32767));
            }
        }
        Files.write(file, data.array());
    }
}
