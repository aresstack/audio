package com.aresstack.audio.studio.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.dsp.LookaheadLimiter;
import com.aresstack.audio.core.dsp.LoudnessMeter;
import com.aresstack.audio.core.mix.ExportNormalization;
import com.aresstack.audio.core.mix.LegacyPeakNormalization;
import com.aresstack.audio.core.mix.LevelMode;
import com.aresstack.audio.studio.mix.legacy.LegacySpatialStemMixer;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * D1b acceptance in the studio, apart from D1a: the compatibility mode {@code LEGACY_PEAK} still
 * gives the file of D1a (and of the old mixer), {@code FIXED} overdrives on no test signal, the
 * export with normalization reaches its target, and below the ceiling {@code FIXED} differs from
 * the unmastered mix in nothing but the rounding to whole numbers.
 */
public final class MixLevelTest {
    /**
     * The old mixer knows wall echoes at level 1 only: compared with it at that level, everything
     * but the decided level of the wall echoes (0.5, {@link WallEchoMotionTest}) stays to the bit.
     */
    @org.junit.Before
    public void wallEchoesAsTheOldMixerHadThem() {
        SpatialStemMixer.wallEchoLevel = 1.0;
    }

    @org.junit.After
    public void wallEchoesAsDecided() {
        SpatialStemMixer.wallEchoLevel = WallEchoMotionTest.LEVEL_DECIDED;
    }

    private static final int RATE = SpatialStemMixer.RATE;
    private static final AcousticRoom DRY = AcousticRoom.STUDIO.withAmount(0);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private List<Path> files;
    private List<SoundSourcePlacement> placements;

    @Before
    public void hotStems() throws Exception {
        // three loud tracks close to the listener: summed they go well over full level
        Path a = stem("a", noise(3 * RATE, 1, 0.9), noise(3 * RATE, 2, 0.9));
        Path b = stem("b", tone(3 * RATE, 110, 0.95), tone(3 * RATE, 110, 0.95));
        Path c = stem("c", clicks(3 * RATE), clicks(3 * RATE));
        files = Arrays.asList(a, b, c);
        placements = Arrays.asList(
                new SoundSourcePlacement("a", -0.2, 0, 6, 1, 0.3, false, false, null),
                new SoundSourcePlacement("b", 0, 0, 6, 1, 0.3, false, false, null),
                new SoundSourcePlacement("c", 0.3, 0, 6, 1, 0.3, false, false, null));
    }

    @Test
    public void theCompatibilityModeGivesTheFileOfTheOldMixer() throws Exception {
        for (AcousticRoom room : new AcousticRoom[]{DRY, AcousticRoom.HALL}) {
            for (double masterDb : new double[]{0, -6}) {
                Path old = file("old.wav");
                Path fresh = file("fresh.wav");
                new LegacySpatialStemMixer().mix(files, placements, room, 2, masterDb, old, 16);
                MixOutcome outcome = new SpatialStemMixer().mix(files, placements, room, 2, MixLevel.legacy(masterDb),
                        fresh, 16);
                Assert.assertArrayEquals(room.name() + ", " + masterDb + " dB", Files.readAllBytes(old),
                        Files.readAllBytes(fresh));
                Assert.assertEquals(LevelMode.LEGACY_PEAK, outcome.mode());
                // the factor to keep for the preview is the one the peak normalization applied
                float peak = render(room).peak();
                Assert.assertEquals(LegacyPeakNormalization.gain(peak, masterDb), outcome.legacyFactor(), 0f);
                Assert.assertEquals(outcome.legacyFactor(), outcome.previewMaster().gain(), 0f);
            }
        }
    }

    @Test
    public void theFixedMixNeverOverdrives() throws Exception {
        float ceiling = LookaheadLimiter.Settings.DEFAULT.ceiling();
        for (AcousticRoom room : new AcousticRoom[]{DRY, AcousticRoom.HALL, AcousticRoom.CATHEDRAL.withAmount(1)}) {
            Assert.assertTrue("the test overdrives without the limiter", render(room).peak() > 2);
            Path out = file("fixed.wav");
            MixOutcome outcome = new SpatialStemMixer().mix(files, placements, room, 2, MixLevel.fixed(0), out, 24);
            Assert.assertEquals(LevelMode.FIXED, outcome.mode());
            Assert.assertTrue(outcome.fixed().lowestLimiterGain() < 0.5f);
            Assert.assertTrue(room.name(), peak(out) <= ceiling + 1e-6f);
            Assert.assertEquals("as long as the mix", render(room).left.length, SpatialStemMixer.read(out).left.length);
        }
    }

    @Test
    public void belowTheCeilingTheFixedMixIsTheUnmasteredMix() throws Exception {
        List<Path> quiet = Arrays.asList(stem("q", tone(2 * RATE, 220, 0.2), noise(2 * RATE, 4, 0.2)));
        List<SoundSourcePlacement> place = Arrays.asList(new SoundSourcePlacement("q", 0.2, 0.4, 0, 1, 0.4, false, false, null));
        Path fixed = file("fixed-quiet.wav");
        Path reference = file("reference.wav");
        new SpatialStemMixer(333).mix(quiet, place, AcousticRoom.HALL, 2,
                MixLevel.fixed(0, 0, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.NONE), fixed, 24);
        SpatialStemMixer.Mix mix = new SpatialStemMixer().render(
                Arrays.asList(SpatialStemMixer.read(quiet.get(0))), place, AcousticRoom.HALL, 2);
        Assert.assertTrue(mix.peak() < LookaheadLimiter.Settings.DEFAULT.ceiling());
        SpatialStemMixer.write(mix, reference, 24);
        Assert.assertArrayEquals(Files.readAllBytes(reference), Files.readAllBytes(fixed));
    }

    @Test
    public void anExportNormalizedToAPeakReachesIt() throws Exception {
        for (double target : new double[]{-0.3, -6}) {
            Path out = file("peak.wav");
            new SpatialStemMixer().mix(files, placements, AcousticRoom.HALL, 2,
                    MixLevel.fixed(0, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.peak(target)), out, 24);
            Assert.assertEquals(target, 20 * Math.log10(peak(out)), 0.001);
        }
    }

    @Test
    public void anExportNormalizedToALoudnessReachesIt() throws Exception {
        for (double target : new double[]{-23, -18}) {
            Path out = file("loudness.wav");
            MixOutcome outcome = new SpatialStemMixer().mix(files, placements, AcousticRoom.HALL, 2,
                    MixLevel.fixed(0, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.loudness(target)), out, 24);
            Assert.assertEquals(target, loudness(out), 0.05);
            Assert.assertTrue(outcome.fixed().normalizationGain() < 1);
        }
    }

    @Test
    public void theMakeUpGainIsAFixedFactor() throws Exception {
        // a quiet track: with +12 dB make-up and -3 dB master it comes out 9 dB louder, the limiter untouched
        List<Path> quiet = Arrays.asList(stem("m", tone(2 * RATE, 220, 0.05), tone(2 * RATE, 220, 0.05)));
        List<SoundSourcePlacement> place = Arrays.asList(new SoundSourcePlacement("m", 0, 0, 0, 1, 0, false, false, null));
        Path plain = file("plain.wav");
        Path raised = file("raised.wav");
        new SpatialStemMixer().mix(quiet, place, DRY, 2,
                MixLevel.fixed(0, 0, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.NONE), plain, 24);
        MixOutcome outcome = new SpatialStemMixer().mix(quiet, place, DRY, 2, MixLevel.fixed(-3), raised, 24);
        Assert.assertEquals(9, 20 * Math.log10(peak(raised) / peak(plain)), 0.001);
        Assert.assertEquals(1f, outcome.fixed().lowestLimiterGain(), 0f);
        Assert.assertEquals(12, MixLevel.fixed(0).makeupDb(), 0);
    }

    @Test
    public void everyLevelIsCorrectWhenTheMixIsWrittenOverOneOfItsOwnStems() throws Exception {
        // the stems are read while the mix is written, twice with a normalization: the output must not cut them short
        MixLevel[] levels = {MixLevel.legacy(-2), MixLevel.fixed(0),
                MixLevel.fixed(0, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.peak(-1)),
                MixLevel.fixed(0, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.loudness(-16))};
        for (MixLevel level : levels) {
            Path inPlace = file("in-place.wav");
            Path apart = file("apart.wav");
            Files.copy(files.get(0), inPlace, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            List<Path> fromInPlace = Arrays.asList(inPlace, files.get(1), files.get(2));
            new SpatialStemMixer().mix(files, placements, AcousticRoom.HALL, 2, level, apart, 24);
            new SpatialStemMixer().mix(fromInPlace, placements, AcousticRoom.HALL, 2, level, inPlace, 24);
            Assert.assertArrayEquals(level.toString(), Files.readAllBytes(apart), Files.readAllBytes(inPlace));
            String[] left = folder.getRoot().list((dir, name) -> name.endsWith(".part"));
            Assert.assertEquals(level + ": no part file left behind", 0, left.length);
        }
    }

    @Test
    public void theFixedLevelHearsTheAutomationOfAMixDocument() throws Exception {
        // a swell given as automation sounds exactly like the same swell on the placement, in FIXED as well
        List<SoundSourcePlacement.LevelPoint> swell = Arrays.asList(new SoundSourcePlacement.LevelPoint(0, -18),
                new SoundSourcePlacement.LevelPoint(1, 0));
        SoundSourcePlacement still = new SoundSourcePlacement("a", -0.2, 0, 6, 1, 0.3, false, false, null);
        SoundSourcePlacement swelling = new SoundSourcePlacement("a", -0.2, 0, 6, 1, 0.3, false, false, swell);
        List<Path> one = Arrays.asList(files.get(0));
        MixLevel level = MixLevel.fixed(-2, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.peak(-1));
        Path automated = file("automated.wav");
        Path placed = file("placed.wav");
        Path plain = file("plain.wav");
        new SpatialStemMixer().withAutomation(StageAutomation.of(Arrays.asList(swelling), 2))
                .mix(one, Arrays.asList(still), AcousticRoom.HALL, 2, level, automated, 24);
        new SpatialStemMixer().mix(one, Arrays.asList(swelling), AcousticRoom.HALL, 2, level, placed, 24);
        new SpatialStemMixer().mix(one, Arrays.asList(still), AcousticRoom.HALL, 2, level, plain, 24);
        Assert.assertArrayEquals(Files.readAllBytes(placed), Files.readAllBytes(automated));
        Assert.assertFalse(Arrays.equals(Files.readAllBytes(plain), Files.readAllBytes(automated)));
    }

    @Test
    public void liveTakesTheFactorOfTheFileBehindTheSofterLimiter() throws Exception {
        MixOutcome outcome = new SpatialStemMixer().mix(files, placements, DRY, 2, MixLevel.legacy(-2), file("f.wav"), 16);
        com.aresstack.audio.core.mix.MasterBus.Settings live = MixLevel.liveMaster(outcome.legacyFactor(), -2);
        Assert.assertEquals(LevelMode.FIXED, live.mode());
        Assert.assertEquals(outcome.legacyFactor(), live.gain(), 0f);
        Assert.assertSame(LookaheadLimiter.Settings.LIVE, live.limiter());
        // before the first mix: the fixed make-up and the master level
        Assert.assertEquals(Math.pow(10, (MixLevel.MAKEUP_DB - 2) / 20), MixLevel.liveMaster(Float.NaN, -2).gain(), 1e-6);
    }

    @Test
    public void liveLevelHasNoNormalization() {
        MixLevel export = MixLevel.fixed(-3, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.loudness(-14));
        Assert.assertEquals(ExportNormalization.Kind.NONE, export.live().normalization().kind());
        Assert.assertEquals(-3, export.live().masterDb(), 0);
        Assert.assertEquals(ExportNormalization.Kind.NONE, MixLevel.legacy(-2).normalization().kind());
    }

    // ---- helpers

    private SpatialStemMixer.Mix render(AcousticRoom room) throws Exception {
        return new SpatialStemMixer().render(Arrays.asList(SpatialStemMixer.read(files.get(0)),
                SpatialStemMixer.read(files.get(1)), SpatialStemMixer.read(files.get(2))), placements, room, 2);
    }

    private Path file(String name) {
        return folder.getRoot().toPath().resolve(name);
    }

    private Path stem(String name, float[] left, float[] right) throws Exception {
        Path path = file(name + ".wav");
        // written as a WAV of 24 bits, as a renderer leaves it; the mixer reads files, not arrays
        SpatialStemMixer.Mix mix = new SpatialStemMixer.Mix(left, right);
        SpatialStemMixer.write(mix, path, 24);
        return path;
    }

    private static float peak(Path file) throws Exception {
        SpatialStemMixer.Signal signal = SpatialStemMixer.read(file);
        float peak = 0;
        for (int i = 0; i < signal.left.length; i++) {
            peak = Math.max(peak, Math.max(Math.abs(signal.left[i]), Math.abs(signal.right[i])));
        }
        return peak;
    }

    private static double loudness(Path file) throws Exception {
        SpatialStemMixer.Signal signal = SpatialStemMixer.read(file);
        LoudnessMeter meter = new LoudnessMeter();
        meter.prepare(RATE, signal.left.length);
        AudioBlock block = new AudioBlock(signal.left.length);
        System.arraycopy(signal.left, 0, block.left, 0, signal.left.length);
        System.arraycopy(signal.right, 0, block.right, 0, signal.left.length);
        meter.process(block);
        return meter.integrated();
    }

    private static float[] noise(int frames, long seed, double height) {
        Random random = new Random(seed);
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (float) ((random.nextDouble() * 2 - 1) * height);
        }
        return samples;
    }

    private static float[] tone(int frames, double frequency, double height) {
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (float) (height * Math.sin(2 * Math.PI * frequency * i / RATE));
        }
        return samples;
    }

    private static float[] clicks(int frames) {
        float[] samples = new float[frames];
        for (int i = 500; i < frames; i += 6000) {
            samples[i] = 0.99f;
        }
        return samples;
    }
}
