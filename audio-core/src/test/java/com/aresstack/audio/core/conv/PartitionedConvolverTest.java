package com.aresstack.audio.core.conv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.TestSignals;
import org.junit.Test;

public class PartitionedConvolverTest {
    /** Acceptance criterion of D4 (architecture review 7.2): convolution with a Dirac impulse gives the input. */
    @Test
    public void aDiracGivesTheInputBack() {
        float[] dirac = new float[30000];
        dirac[0] = 1;
        float[] left = TestSignals.noise(50000, 1, 0.8);
        float[] right = TestSignals.noise(50000, 2, 0.8);
        float[][] out = run(new PartitionedConvolver(dirac, dirac, 64, 4096), left, right, 512);
        for (int i = 0; i < left.length; i++) {
            assertEquals(left[i], out[0][i], 1e-6);
            assertEquals(right[i], out[1][i], 1e-6);
        }
    }

    @Test
    public void aDelayedDiracDelaysWithoutLatencyOfItsOwn() {
        for (int delay : new int[] {0, 5, 63, 64, 65, 300, 1024, 5000, 17000}) {
            float[] ir = new float[20000];
            ir[delay] = 0.5f;
            float[] in = TestSignals.noise(30000, delay, 0.8);
            float[][] out = run(new PartitionedConvolver(ir, ir, 16, 1024), in, in, 333);
            for (int i = 0; i < in.length; i++) {
                float want = i >= delay ? in[i - delay] * 0.5f : 0;
                assertEquals("delay " + delay + " at " + i, want, out[0][i], 1e-6);
            }
        }
    }

    @Test
    public void matchesDirectConvolutionWithTwoDifferentResponses() {
        float[] irLeft = decaying(9000, 11);
        float[] irRight = decaying(9000, 12);
        float[] left = TestSignals.noise(20000, 3, 0.5);
        float[] right = TestSignals.noise(20000, 4, 0.5);
        float[][] out = run(new PartitionedConvolver(irLeft, irRight, 32, 1024), left, right, 100);
        double worst = 0;
        for (int i = 0; i < left.length; i += 7) {
            double wantLeft = 0;
            double wantRight = 0;
            for (int k = 0; k < irLeft.length && k <= i; k++) {
                wantLeft += irLeft[k] * left[i - k];
                wantRight += irRight[k] * right[i - k];
            }
            worst = Math.max(worst, Math.max(Math.abs(wantLeft - out[0][i]), Math.abs(wantRight - out[1][i])));
        }
        assertTrue("worst difference " + worst, worst < 2e-5);
    }

    @Test
    public void theResultDoesNotDependOnTheBlockSize() {
        float[] ir = decaying(12000, 5);
        float[] in = TestSignals.noise(40000, 6, 0.7);
        float[][] a = run(new PartitionedConvolver(ir, ir, 64, 2048), in, in, 512);
        float[][] b = run(new PartitionedConvolver(ir, ir, 64, 2048), in, in, 37);
        float[][] c = run(new PartitionedConvolver(ir, ir, 64, 2048), in, in, 1);
        assertArrayEquals(a[0], b[0], 0f);
        assertArrayEquals(a[0], c[0], 0f);
        assertArrayEquals(a[1], b[1], 0f);
    }

    @Test
    public void nonUniformPartitionsCutTheProductsPerSample() {
        float[] ir = new float[110250];
        ir[0] = 1;
        PartitionedConvolver convolver = new PartitionedConvolver(ir, ir, 64, 4096);
        double uniform = Math.ceil((ir.length - 64) / 64.0) * 65 / 64.0;
        assertTrue(convolver.productsPerSample() + " vs " + uniform, convolver.productsPerSample() < uniform / 40);
    }

    @Test
    public void allocatesNothingWhileProcessing() throws Exception {
        float[] ir = decaying(20000, 8);
        PartitionedConvolver convolver = new PartitionedConvolver(ir, ir, 64, 4096);
        convolver.prepare(512);
        float[] in = TestSignals.noise(512, 9, 0.5);
        float[] outLeft = new float[512];
        float[] outRight = new float[512];
        long bytes = Allocation.of(() -> {
            for (int i = 0; i < 64; i++) {
                convolver.process(in, in, outLeft, outRight, 512);
            }
        });
        assertTrue("allocated " + bytes + " bytes", bytes < 512);
    }

    private static float[] decaying(int length, long seed) {
        float[] noise = TestSignals.noise(length, seed, 1);
        for (int i = 0; i < length; i++) {
            noise[i] *= (float) Math.exp(-5.0 * i / length);
        }
        return noise;
    }

    static float[][] run(PartitionedConvolver convolver, float[] left, float[] right, int block) {
        convolver.prepare(block);
        float[] outLeft = new float[left.length];
        float[] outRight = new float[left.length];
        float[] inL = new float[block];
        float[] inR = new float[block];
        float[] oL = new float[block];
        float[] oR = new float[block];
        for (int at = 0; at < left.length; at += block) {
            int count = Math.min(block, left.length - at);
            System.arraycopy(left, at, inL, 0, count);
            System.arraycopy(right, at, inR, 0, count);
            convolver.process(inL, inR, oL, oR, count);
            System.arraycopy(oL, 0, outLeft, at, count);
            System.arraycopy(oR, 0, outRight, at, count);
        }
        return new float[][] {outLeft, outRight};
    }
}
