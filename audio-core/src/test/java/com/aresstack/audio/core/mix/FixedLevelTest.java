package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSink;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.TestSignals;
import com.aresstack.audio.core.dsp.LookaheadLimiter;
import com.aresstack.audio.core.dsp.LoudnessMeter;
import com.aresstack.audio.core.stream.ArraySource;
import org.junit.Assert;
import org.junit.Test;

/**
 * D1b, the level strategy, tested apart from D1a: in {@link LevelMode#FIXED} the mix never
 * overdrives, the export lies frame for frame where the graph lies, live and export give the
 * same samples, and a normalized export reaches its target. {@link LevelMode#LEGACY_PEAK} is
 * left exactly as D1a made it (see {@code MixGraphTest} and the equivalence test of the studio).
 */
public final class FixedLevelTest {
    private static final int RATE = 44100;
    private static final float CEILING = LookaheadLimiter.Settings.DEFAULT.ceiling();

    @Test
    public void noTestSignalOverdrivesTheFixedMix() throws Exception {
        Signal[] signals = {
                signal(TestSignals.noise(3 * RATE, 1, 4.0), TestSignals.noise(3 * RATE, 2, 4.0)),
                signal(TestSignals.sine(3 * RATE, 60, RATE, 8.0), TestSignals.sine(3 * RATE, 61, RATE, 8.0)),
                signal(clicks(3 * RATE, 17.0), clicks(3 * RATE, -17.0)),
                signal(TestSignals.sine(3 * RATE, 1000, RATE, 0.95), TestSignals.sine(3 * RATE, 1000, RATE, 0.95)),
        };
        for (Signal signal : signals) {
            Recorded out = renderFixed(signal, MasterBus.Settings.fixed(0), ExportNormalization.NONE, 512);
            Assert.assertEquals(signal.left.length, out.left.length);
            Assert.assertTrue("highest " + out.peak(), out.peak() <= CEILING);
        }
    }

    @Test
    public void belowTheCeilingTheExportLiesExactlyWhereTheGraphLies() throws Exception {
        float[] left = TestSignals.sine(2 * RATE, 440, RATE, 0.5);
        float[] right = TestSignals.noise(2 * RATE, 5, 0.5);
        Recorded out = renderFixed(signal(left, right), MasterBus.Settings.fixed(0), ExportNormalization.NONE, 333);
        Assert.assertArrayEquals(left, out.left, 0f);
        Assert.assertArrayEquals(right, out.right, 0f);
    }

    @Test
    public void theMasterLevelIsAFixedFactor() throws Exception {
        float[] left = TestSignals.sine(RATE, 440, RATE, 0.5);
        Recorded out = renderFixed(signal(left, left), MasterBus.Settings.fixed(-6), ExportNormalization.NONE, 512);
        float gain = (float) Math.pow(10, -6 / 20.0);
        for (int i = 0; i < left.length; i++) {
            Assert.assertEquals(left[i] * gain, out.left[i], 0f);
        }
    }

    @Test
    public void theMakeUpGainComesBeforeTheMasterLevel() {
        Assert.assertEquals(Math.pow(10, 9 / 20.0), MasterBus.Settings.fixed(12, -3, LookaheadLimiter.Settings.DEFAULT).gain(), 1e-6);
        Assert.assertEquals(Math.pow(10, 12 / 20.0), MasterBus.Settings.fixed(12, 4, LookaheadLimiter.Settings.DEFAULT).gain(), 1e-6);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aMakeUpGainAbove24DbIsRefused() {
        MasterBus.Settings.fixed(25, 0, LookaheadLimiter.Settings.DEFAULT);
    }

    @Test
    public void liveAndExportGiveTheSameSamples() throws Exception {
        // live: the master bus behind the graph, block by block, late by its latency
        Signal signal = signal(TestSignals.noise(2 * RATE, 8, 2.5), TestSignals.noise(2 * RATE, 9, 2.5));
        MasterBus bus = new MasterBus(MasterBus.Settings.fixed(-2));
        AudioSource graph = signal.open();
        graph.prepare(RATE, 256);
        bus.prepare(RATE, 256);
        AudioBlock block = new AudioBlock(256);
        int frames = signal.left.length + bus.latencyFrames();
        float[] live = new float[frames];
        for (int at = 0; at < frames; at += 256) {
            block.setFrames(Math.min(256, frames - at));
            graph.read(block);
            bus.process(block);
            System.arraycopy(block.left, 0, live, at, block.frames());
        }
        Recorded export = renderFixed(signal, MasterBus.Settings.fixed(-2), ExportNormalization.NONE, 4096);
        for (int i = 0; i < export.left.length; i++) {
            Assert.assertEquals("frame " + i, live[i + bus.latencyFrames()], export.left[i], 0f);
        }
    }

    @Test
    public void theResultDoesNotDependOnTheBlockSize() throws Exception {
        Signal signal = signal(TestSignals.noise(3 * RATE, 3, 3.0), TestSignals.noise(3 * RATE, 4, 1.0));
        Recorded reference = renderFixed(signal, MasterBus.Settings.fixed(0), ExportNormalization.peak(-0.5), 4096);
        for (int block : new int[]{1, 64, 512, 777}) {
            Recorded cut = renderFixed(signal, MasterBus.Settings.fixed(0), ExportNormalization.peak(-0.5), block);
            Assert.assertArrayEquals("block " + block, reference.left, cut.left, 0f);
            Assert.assertArrayEquals("block " + block, reference.right, cut.right, 0f);
        }
    }

    @Test
    public void aPeakNormalizedExportReachesItsTarget() throws Exception {
        for (double target : new double[]{-0.1, -3, -12}) {
            for (float height : new float[]{0.05f, 0.5f, 5f}) {
                Signal signal = signal(TestSignals.noise(2 * RATE, 6, height), TestSignals.noise(2 * RATE, 7, height));
                Recorded out = renderFixed(signal, MasterBus.Settings.fixed(0), ExportNormalization.peak(target), 512);
                Assert.assertEquals("target " + target + ", height " + height,
                        target, 20 * Math.log10(out.peak()), 0.001);
            }
        }
    }

    @Test
    public void aLoudnessNormalizedExportReachesItsTarget() throws Exception {
        // music-like: a sine with a slow swell and some noise, quiet and loud, both raised and lowered
        for (double height : new double[]{0.02, 0.3}) {
            float[] left = new float[20 * RATE];
            float[] right = new float[20 * RATE];
            float[] noise = TestSignals.noise(20 * RATE, 10, 1);
            for (int i = 0; i < left.length; i++) {
                double swell = 0.6 + 0.4 * Math.sin(2 * Math.PI * i / (5.0 * RATE));
                left[i] = (float) (height * swell * (Math.sin(2 * Math.PI * 220 * i / RATE) + 0.2 * noise[i]));
                right[i] = (float) (height * swell * (Math.sin(2 * Math.PI * 330 * i / RATE) + 0.2 * noise[i]));
            }
            for (double target : new double[]{-23, -16}) {
                OfflineMix.Result[] result = new OfflineMix.Result[1];
                Recorded out = renderFixed(signal(left, right), MasterBus.Settings.fixed(0),
                        ExportNormalization.loudness(target), 512, result);
                Assert.assertEquals("height " + height + ", target " + target, target, loudness(out), 0.05);
                Assert.assertTrue(out.peak() <= CEILING);
                Assert.assertEquals(target, result[0].loudnessBeforeNormalization()
                        + 20 * Math.log10(result[0].normalizationGain()), 0.05);
            }
        }
    }

    @Test
    public void aLoudnessTargetStopsAtThePeakCeiling() throws Exception {
        // a single click in silence: reaching -10 LUFS would need far more than the peak allows
        float[] left = new float[5 * RATE];
        for (int i = 0; i < left.length; i += RATE / 2) {
            left[i] = 0.5f;
        }
        Recorded out = renderFixed(signal(left, left.clone()), MasterBus.Settings.fixed(0),
                ExportNormalization.loudness(-10, -1), 512);
        Assert.assertEquals(CEILING, out.peak(), 1e-6f);
        Assert.assertTrue(loudness(out) < -10);
    }

    @Test
    public void silenceStaysSilenceWhenNormalized() throws Exception {
        float[] quiet = new float[RATE];
        Recorded out = renderFixed(signal(quiet, quiet), MasterBus.Settings.fixed(0), ExportNormalization.loudness(-14), 512);
        Assert.assertEquals(0f, out.peak(), 0f);
    }

    @Test
    public void theLegacyPreviewUsesTheFactorOfTheLastOfflineMix() throws Exception {
        // what LEGACY_PEAK found offline is applied as a fixed gain live; the guard limiter at 0 dBFS never acts
        float[] left = TestSignals.noise(RATE, 1, 0.3);
        float factor = LegacyPeakNormalization.gain(0.3f, -3);
        Recorded out = renderFixed(signal(left, left), MasterBus.Settings.legacy(factor), ExportNormalization.NONE, 512);
        for (int i = 0; i < left.length; i++) {
            Assert.assertEquals(left[i] * factor, out.left[i], 0f);
        }
        Assert.assertEquals(LevelMode.LEGACY_PEAK, MasterBus.Settings.legacy(factor).mode());
    }

    @Test
    public void aNewMasterLevelIsReachedAsARampWithinOneBlock() {
        MasterBus bus = new MasterBus(MasterBus.Settings.fixed(0));
        bus.prepare(RATE, 1000);
        AudioBlock block = new AudioBlock(1000);
        java.util.Arrays.fill(block.left, 0.5f);
        java.util.Arrays.fill(block.right, 0.5f);
        bus.process(block);
        bus.setSettings(MasterBus.Settings.fixed(-6));
        java.util.Arrays.fill(block.left, 0.5f);
        java.util.Arrays.fill(block.right, 0.5f);
        bus.process(block);
        float target = (float) (0.5 * Math.pow(10, -6 / 20.0));
        int late = bus.latencyFrames();
        // the output is late by the look-ahead: the ramp of the second block starts there, without a step
        Assert.assertEquals(0.5f, block.left[late - 1], 0f);
        for (int i = late + 1; i < 1000; i++) {
            Assert.assertTrue("frame " + i, block.left[i] < block.left[i - 1]);
            Assert.assertTrue(block.left[i - 1] - block.left[i] < 0.001f);
        }
        Assert.assertTrue(block.left[999] > target);
    }

    @Test
    public void aLoudnessTargetIsReachedWhenRaisingLetsInPassagesBelowTheAbsoluteGate() throws Exception {
        // 1 s at -65 dBFS, then 10 s at -75 dBFS: before the gain only the first second counts,
        // raised to -23 LUFS the quiet part passes the -70 LUFS gate and counts too
        float[] left = new float[11 * RATE];
        for (int i = 0; i < left.length; i++) {
            double height = Math.pow(10, (i < RATE ? -65 : -75) / 20.0);
            left[i] = (float) (height * Math.sin(2 * Math.PI * 1000 * i / RATE));
        }
        Recorded out = renderFixed(signal(left, left.clone()), MasterBus.Settings.fixed(0),
                ExportNormalization.loudness(-23), 512);
        Assert.assertEquals(-23, loudness(out), 0.05);
    }

    @Test
    public void aGainChangeIsNotLostOnAnEmptyBlock() {
        MasterBus bus = new MasterBus(MasterBus.Settings.fixed(0));
        bus.prepare(RATE, 512);
        AudioBlock block = new AudioBlock(512);
        bus.setSettings(MasterBus.Settings.fixed(-6));
        block.setFrames(0);
        bus.process(block);
        block.setFrames(512);
        for (int round = 0; round < 3; round++) {
            java.util.Arrays.fill(block.left, 0.5f);
            java.util.Arrays.fill(block.right, 0.5f);
            bus.process(block);
        }
        Assert.assertEquals(0.5 * Math.pow(10, -6 / 20.0), block.left[511], 1e-6);
    }

    // ---- helpers

    private static float[] clicks(int frames, double height) {
        float[] samples = new float[frames];
        for (int i = 1000; i < frames; i += 7919) {
            samples[i] = (float) height;
        }
        return samples;
    }

    private static double loudness(Recorded out) {
        LoudnessMeter meter = new LoudnessMeter();
        meter.prepare(RATE, out.left.length);
        AudioBlock block = new AudioBlock(out.left.length);
        System.arraycopy(out.left, 0, block.left, 0, out.left.length);
        System.arraycopy(out.right, 0, block.right, 0, out.left.length);
        meter.process(block);
        return meter.integrated();
    }

    private static final class Signal {
        final float[] left;
        final float[] right;

        Signal(float[] left, float[] right) {
            this.left = left;
            this.right = right;
        }

        AudioSource open() {
            return new ArraySource(left, right, RATE);
        }
    }

    private static Signal signal(float[] left, float[] right) {
        return new Signal(left, right);
    }

    private static final class Recorded {
        final float[] left;
        final float[] right;

        Recorded(float[] left, float[] right) {
            this.left = left;
            this.right = right;
        }

        float peak() {
            float peak = 0;
            for (int i = 0; i < left.length; i++) {
                peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
            }
            return peak;
        }
    }

    private static Recorded renderFixed(Signal signal, MasterBus.Settings settings, ExportNormalization normalization,
                                        int blockFrames) throws Exception {
        return renderFixed(signal, settings, normalization, blockFrames, new OfflineMix.Result[1]);
    }

    private static Recorded renderFixed(Signal signal, MasterBus.Settings settings, ExportNormalization normalization,
                                        int blockFrames, OfflineMix.Result[] result) throws Exception {
        float[] left = new float[signal.left.length + 8192];
        float[] right = new float[left.length];
        int[] at = new int[1];
        result[0] = OfflineMix.renderFixed(signal::open, RATE, blockFrames, settings, normalization, new AudioSink() {
            @Override
            public void write(AudioBlock block) {
                System.arraycopy(block.left, 0, left, at[0], block.frames());
                System.arraycopy(block.right, 0, right, at[0], block.frames());
                at[0] += block.frames();
            }
        });
        return new Recorded(java.util.Arrays.copyOf(left, at[0]), java.util.Arrays.copyOf(right, at[0]));
    }
}
