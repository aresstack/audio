package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package D3 in the mix of the studio: a movement saved in {@code mix.json} is heard when
 * the stage is mixed again, without rendering anything; the level curves stay those of the stage
 * window, and a piece without such a lane mixes exactly as before.
 */
public final class StageMixAutomationTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final long BAR = 1920;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** A piece of four seconds at 120 (two bars) with one melody, noise on both sides. */
    private Path piece() throws Exception {
        Path song = folder.newFolder().toPath();
        Files.write(song.resolve("arrangement.tsv"), Arrays.asList("tempo\t120", "track\tmelody\t73\t0\tmelody"),
                StandardCharsets.UTF_8);
        java.util.Random random = new java.util.Random(5);
        float[] left = new float[4 * RATE];
        float[] right = new float[4 * RATE];
        for (int i = 0; i < left.length; i++) {
            left[i] = (float) (random.nextGaussian() * 0.15);
            right[i] = (float) (random.nextGaussian() * 0.15);
        }
        byte[] pcm = new byte[left.length * 4];
        for (int i = 0; i < left.length; i++) {
            short l = (short) Math.round(left[i] * 32767);
            short r = (short) Math.round(right[i] * 32767);
            pcm[4 * i] = (byte) l;
            pcm[4 * i + 1] = (byte) (l >> 8);
            pcm[4 * i + 2] = (byte) r;
            pcm[4 * i + 3] = (byte) (r >> 8);
        }
        javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(RATE, 16, 2, true, false);
        javax.sound.sampled.AudioSystem.write(new javax.sound.sampled.AudioInputStream(
                        new java.io.ByteArrayInputStream(pcm), format, left.length),
                javax.sound.sampled.AudioFileFormat.Type.WAVE, song.resolve("melody.wav").toFile());
        return song;
    }

    private static void saveMix(Path song, AutomationLane... lanes) throws Exception {
        MixChannel melody = new MixChannel("melody-ch", MixChannel.Owner.part("melody"), 0, 0.3, 0, 1, 0.3, false,
                false);
        MixJson.save(song, new MixScene(new Room("chamber", 1, 0.3), 0, LevelMode.LEGACY_PEAK, List.of(melody),
                List.of(lanes)));
    }

    private static AutomationLane lane(String parameter, long[] ticks, double[] values) {
        List<AutomationLane.Point> points = new ArrayList<>();
        for (int i = 0; i < ticks.length; i++) {
            points.add(new AutomationLane.Point(ticks[i], values[i]));
        }
        return new AutomationLane("melody-" + parameter, "melody-ch", parameter, points);
    }

    @Test
    public void aMovementSavedInTheMixIsHeardWhenTheStageIsMixedAgain() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        StageMixService service = new StageMixService();
        SpatialStemMixer.Signal still = SpatialStemMixer.read(service.mix(arrangement, song, message -> { }));
        Assert.assertEquals("the usual seating puts the melody in the middle", 1,
                power(still, true, 0, RATE / 2) / power(still, false, 0, RATE / 2), 0.2);

        saveMix(song, lane(AutomationLane.X, new long[] {0, 2 * BAR}, new double[] {-1, 1}));
        long rendered = Files.getLastModifiedTime(song.resolve("melody.wav")).toMillis();
        SpatialStemMixer.Signal moving = SpatialStemMixer.read(service.mix(arrangement, song, message -> { }));

        Assert.assertTrue("left at the start",
                power(moving, true, 0, RATE / 4) > 3 * power(moving, false, 0, RATE / 4));
        Assert.assertTrue("right at the end", power(moving, false, 4 * RATE - RATE / 4, 4 * RATE)
                > 3 * power(moving, true, 4 * RATE - RATE / 4, 4 * RATE));
        Assert.assertEquals("nothing is rendered again", rendered,
                Files.getLastModifiedTime(song.resolve("melody.wav")).toMillis());
    }

    @Test
    public void aMixWithoutMovementsSoundsExactlyAsBefore() throws Exception {
        Path song = piece();
        Path arrangement = song.resolve("arrangement.tsv");
        StageMixService service = new StageMixService();
        SpatialScene scene = service.scene(arrangement, song);
        new SpatialSceneStore().save(song, scene.with(scene.placement("melody").withDynamics(Arrays.asList(
                new SoundSourcePlacement.LevelPoint(0, -9), new SoundSourcePlacement.LevelPoint(1.5, 0)))));
        byte[] before = Files.readAllBytes(service.mix(arrangement, song, message -> { }));
        // A level lane in mix.json (as the import writes it) does not take the place of the stage's curve.
        saveMix(song, lane(AutomationLane.LEVEL, new long[] {0}, new double[] {-30}));
        Assert.assertArrayEquals(before, Files.readAllBytes(service.mix(arrangement, song, message -> { })));
        Files.write(song.resolve(MixJson.FILE_NAME), "{not json".getBytes(StandardCharsets.UTF_8));
        List<String> said = new ArrayList<>();
        Assert.assertArrayEquals("an unreadable mix.json does not stop the mix", before,
                Files.readAllBytes(service.mix(arrangement, song, said::add)));
        Assert.assertTrue(said.toString(), said.stream().anyMatch(line -> line.contains("mix.json")));
    }

    private static double power(SpatialStemMixer.Signal signal, boolean left, int from, int to) {
        float[] side = left ? signal.left : signal.right;
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += side[i] * side[i];
        }
        return sum;
    }
}
