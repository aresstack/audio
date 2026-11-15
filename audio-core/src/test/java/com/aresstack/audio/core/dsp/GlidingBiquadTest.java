package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.TestSignals;
import org.junit.Assert;
import org.junit.Test;

/**
 * Gliding coefficients: settled the same as {@link StereoBiquad}, stable on the way between any
 * two stable sets, without a jump in the signal, and without allocation.
 */
public final class GlidingBiquadTest {
    private static final int RATE = 44100;

    @Test
    public void aSettledFilterComputesExactlyLikeTheStereoBiquad() {
        BiquadCoefficients shelf = BiquadCoefficients.highShelf(RATE, 4500, -6, 1);
        GlidingBiquad gliding = new GlidingBiquad(shelf);
        StereoBiquad fixed = new StereoBiquad(shelf);
        gliding.prepare(RATE, 512);
        fixed.prepare(RATE, 512);
        float[] left = TestSignals.noise(RATE, 1, 0.8);
        float[] right = TestSignals.noise(RATE, 2, 0.8);
        AudioBlock a = new AudioBlock(777);
        AudioBlock b = new AudioBlock(777);
        for (int at = 0; at < RATE; at += 777) {
            int count = Math.min(777, RATE - at);
            for (AudioBlock block : new AudioBlock[]{a, b}) {
                block.setFrames(count);
                System.arraycopy(left, at, block.left, 0, count);
                System.arraycopy(right, at, block.right, 0, count);
            }
            gliding.process(a);
            fixed.process(b);
            for (int i = 0; i < count; i++) {
                Assert.assertEquals(b.left[i], a.left[i], 0f);
                Assert.assertEquals(b.right[i], a.right[i], 0f);
            }
        }
    }

    @Test
    public void withoutCoefficientsTheSoundPassesUnchanged() {
        GlidingBiquad none = new GlidingBiquad(null);
        none.prepare(RATE, 64);
        AudioBlock block = new AudioBlock(64);
        float[] noise = TestSignals.noise(64, 3, 1);
        System.arraycopy(noise, 0, block.left, 0, 64);
        System.arraycopy(noise, 0, block.right, 0, 64);
        none.process(block);
        Assert.assertArrayEquals(noise, block.left, 0f);
        Assert.assertArrayEquals(noise, block.right, 0f);
    }

    @Test
    public void glidingBetweenFarApartSetsStaysStableAndEndsOnTheTarget() {
        // A low-pass at 80 Hz with high Q to a high shelf of +12 dB and back, again and again: each glide
        // passes through sets no designer would make, but every one of them is stable (convex triangle).
        BiquadCoefficients dull = BiquadCoefficients.lowPass(RATE, 80, 8);
        BiquadCoefficients bright = BiquadCoefficients.highShelf(RATE, 9000, 12, 1);
        GlidingBiquad filter = new GlidingBiquad(dull);
        filter.prepare(RATE, 256);
        AudioBlock block = new AudioBlock(256);
        float[] noise = TestSignals.noise(256 * 400, 4, 0.5);
        float most = 0;
        for (int b = 0; b < 400; b++) {
            if (b % 20 == 0) {
                filter.glideTo(b % 40 == 0 ? bright : dull, 441);
            }
            System.arraycopy(noise, b * 256, block.left, 0, 256);
            System.arraycopy(noise, b * 256, block.right, 0, 256);
            filter.process(block);
            for (int i = 0; i < 256; i++) {
                Assert.assertTrue(Float.isFinite(block.left[i]));
                most = Math.max(most, Math.abs(block.left[i]));
            }
        }
        Assert.assertTrue("peak " + most, most < 20);
        Assert.assertTrue(filter.settled());
        Assert.assertSame(dull, filter.target());
    }

    @Test
    public void aChangedShelfGlidesWithoutAJumpWhereSwitchingJumps() {
        // A high tone that the shelf turns down from -0.5 to -9 dB: gliding, the output never moves faster
        // than the louder steady tone does; switching may.
        BiquadCoefficients near = BiquadCoefficients.highShelf(RATE, 4500, -0.5, 1);
        BiquadCoefficients far = BiquadCoefficients.highShelf(RATE, 4500, -9, 1);
        float glided = largestStep(near, far, 882);
        float switched = largestStep(near, far, 0);
        float steady = largestStep(near, near, 0);
        Assert.assertTrue("glided " + glided + ", steady " + steady, glided <= steady * 1.05f + 1e-4f);
        Assert.assertTrue("switched " + switched + ", steady " + steady, switched >= glided);
    }

    private static float largestStep(BiquadCoefficients from, BiquadCoefficients to, int glide) {
        GlidingBiquad filter = new GlidingBiquad(from);
        filter.prepare(RATE, 512);
        float[] tone = TestSignals.sine(RATE, 6000, RATE, 0.7);
        AudioBlock block = new AudioBlock(512);
        float last = 0;
        float most = 0;
        for (int at = 0; at + 512 <= RATE; at += 512) {
            if (at == 512 * 40) {
                filter.glideTo(to, glide);
            }
            System.arraycopy(tone, at, block.left, 0, 512);
            System.arraycopy(tone, at, block.right, 0, 512);
            filter.process(block);
            for (int i = 0; i < 512; i++) {
                if (at > 512 * 10) {
                    most = Math.max(most, Math.abs(block.left[i] - last));
                }
                last = block.left[i];
            }
        }
        return most;
    }

    @Test
    public void glidingDoesNotAllocate() throws Exception {
        BiquadCoefficients a = BiquadCoefficients.highShelf(RATE, 4500, -2, 1);
        BiquadCoefficients b = BiquadCoefficients.highShelf(RATE, 4500, -8, 1);
        GlidingBiquad filter = new GlidingBiquad(a);
        filter.prepare(RATE, 512);
        AudioBlock block = new AudioBlock(512);
        long bytes = Allocation.of(() -> {
            for (int i = 0; i < 2000; i++) {
                if (i % 10 == 0) {
                    filter.glideTo(i % 20 == 0 ? b : a, 300);
                }
                filter.process(block);
            }
        });
        Assert.assertTrue(bytes + " B", bytes < 256);
    }
}
