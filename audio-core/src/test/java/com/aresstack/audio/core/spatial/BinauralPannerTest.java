package com.aresstack.audio.core.spatial;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.TestSignals;
import org.junit.Test;

public class BinauralPannerTest {
    private static final int RATE = 44100;
    private static final HrirSet MODEL = SphericalHeadModel.hrirs(RATE);

    /** Acceptance criterion of D4 (architecture review 7.2): left and right are symmetric. */
    @Test
    public void leftAndRightAreMirrorImages() {
        float[] in = TestSignals.noise(20000, 1, 0.5);
        for (double azimuth : new double[] {10, 30, 65, 90, 120, 175}) {
            for (double elevation : new double[] {-30, 0, 25, 60}) {
                float[][] right = place(in, azimuth, elevation);
                float[][] left = place(in, 360 - azimuth, elevation);
                assertArrayEquals(azimuth + "/" + elevation, right[0], left[1], 1e-6f);
                assertArrayEquals(azimuth + "/" + elevation, right[1], left[0], 1e-6f);
            }
        }
    }

    @Test
    public void straightAheadBothEarsHearTheSame() {
        float[][] out = place(TestSignals.noise(10000, 2, 0.5), 0, 0);
        assertArrayEquals(out[0], out[1], 1e-6f);
    }

    @Test
    public void aSourceOnTheRightReachesTheRightEarFirstAndLouder() {
        float[] click = new float[2000];
        click[100] = 1;
        float[][] out = place(click, 90, 0);
        int firstLeft = first(out[0]);
        int firstRight = first(out[1]);
        double itd = (firstLeft - firstRight) / (double) RATE * 1000;
        assertTrue("difference " + itd + " ms", itd > 0.5 && itd < 0.8);
        float[] noise = TestSignals.noise(40000, 3, 0.5);
        float[][] heard = place(noise, 90, 0);
        double ild = 20 * Math.log10(TestSignals.rms(heard[1], 1000, 40000) / TestSignals.rms(heard[0], 1000, 40000));
        assertTrue("level difference " + ild + " dB", ild > 3);
    }

    @Test
    public void heightChangesTheColourOfTheSound() {
        float[] l0 = new float[64];
        float[] r0 = new float[64];
        float[] l1 = new float[64];
        float[] r1 = new float[64];
        float[] delays = new float[2];
        MODEL.lookup(0, 0, l0, r0, delays);
        MODEL.lookup(0, 60, l1, r1, delays);
        double difference = 0;
        for (int k = 0; k < 64; k++) {
            difference += Math.abs(l0[k] - l1[k]);
        }
        assertTrue("difference " + difference, difference > 0.1);
    }

    @Test
    public void aSourceCirclingTheHeadNeverClicks() {
        int frames = 64 * 1378;
        float[] tone = TestSignals.sine(frames, 220, RATE, 0.5);
        BinauralPanner panner = new BinauralPanner(MODEL);
        panner.prepare(RATE, 64);
        panner.jumpTo(270, 0);
        AudioBlock block = new AudioBlock(64);
        float[] mono = new float[64];
        float previousLeft = 0;
        float previousRight = 0;
        float worst = 0;
        double azimuth = 270;
        for (int at = 0; at < frames; at += 64) {
            azimuth += 360.0 * 64 / frames;
            panner.aimAt(azimuth, 20 * Math.sin(at * 3e-5), 64);
            System.arraycopy(tone, at, mono, 0, 64);
            block.clear(0, 64);
            panner.process(mono, 64, block);
            for (int i = 0; i < 64; i++) {
                if (at + i > 200) {
                    worst = Math.max(worst, Math.abs(block.left[i] - previousLeft));
                    worst = Math.max(worst, Math.abs(block.right[i] - previousRight));
                }
                previousLeft = block.left[i];
                previousRight = block.right[i];
            }
        }
        // A 220 Hz tone of 0.5 moves by at most 2π·220/44100·0.5·gain per sample; the ear
        // facing the source hears up to about twice the level.
        float tone220 = (float) (2 * Math.PI * 220 / RATE * 0.5);
        assertTrue("largest step " + worst, worst < tone220 * 3);
    }

    @Test
    public void processingAndAimingAllocateNothing() throws Exception {
        BinauralPanner panner = new BinauralPanner(MODEL);
        panner.prepare(RATE, 512);
        float[] mono = TestSignals.noise(512, 4, 0.5);
        AudioBlock bus = new AudioBlock(512);
        double[] azimuth = {0};
        long bytes = Allocation.of(() -> {
            for (int i = 0; i < 16; i++) {
                azimuth[0] += 7;
                panner.aimAt(azimuth[0], 10, 256);
                panner.process(mono, 512, bus);
            }
        });
        assertTrue("allocated " + bytes + " bytes", bytes < 256);
    }

    @Test
    public void lookupBetweenGridPointsInterpolates() {
        float[] a = new float[64];
        float[] b = new float[64];
        float[] m = new float[64];
        float[] scratch = new float[64];
        float[] da = new float[2];
        float[] db = new float[2];
        float[] dm = new float[2];
        MODEL.lookup(30, 0, a, scratch, da);
        MODEL.lookup(35, 0, b, scratch, db);
        MODEL.lookup(32.5, 0, m, scratch, dm);
        for (int k = 0; k < 64; k++) {
            assertEquals((a[k] + b[k]) / 2, m[k], 1e-6);
        }
        assertEquals((da[1] + db[1]) / 2, dm[1], 1e-5);
    }

    private static int first(float[] samples) {
        float peak = 0;
        for (float s : samples) {
            peak = Math.max(peak, Math.abs(s));
        }
        for (int i = 0; i < samples.length; i++) {
            if (Math.abs(samples[i]) > peak * 0.3f) {
                return i;
            }
        }
        return -1;
    }

    static float[][] place(float[] in, double azimuth, double elevation) {
        BinauralPanner panner = new BinauralPanner(MODEL);
        panner.prepare(RATE, 256);
        panner.jumpTo(azimuth, elevation);
        float[][] out = new float[2][in.length];
        AudioBlock block = new AudioBlock(256);
        float[] mono = new float[256];
        for (int at = 0; at < in.length; at += 256) {
            int count = Math.min(256, in.length - at);
            System.arraycopy(in, at, mono, 0, count);
            block.clear(0, 256);
            panner.process(mono, count, block);
            System.arraycopy(block.left, 0, out[0], at, count);
            System.arraycopy(block.right, 0, out[1], at, count);
        }
        return out;
    }
}
