package com.aresstack.audio.core.dsp;

import static com.aresstack.audio.core.TestSignals.rms;
import static com.aresstack.audio.core.TestSignals.sine;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.TestSignals;
import org.junit.Assert;
import org.junit.Test;

/**
 * The second-order filter on float blocks. The cases follow {@code BiquadEqualizerTest} of
 * AskAI's audio-dsp module, moved from 16-bit samples to float, plus what the core adds:
 * blocks, changing coefficients and silence.
 */
public final class BiquadFilterTest {
    private static final int RATE = 44100;

    @Test
    public void aHighShelfRaisesTheHighsMoreThanTheLows() {
        BiquadCoefficients boost = BiquadCoefficients.highShelf(RATE, 4000, 9, 1);
        double low = ratio(boost, 150);
        double high = ratio(boost, 12000);
        Assert.assertTrue("highs " + high + ", lows " + low, high > 2.4 && low < 1.1);
    }

    @Test
    public void aLowPassLetsTheLowsThroughAndDullsTheHighs() {
        BiquadCoefficients dull = BiquadCoefficients.lowPass(RATE, 3000, Math.sqrt(0.5));
        Assert.assertEquals(1, ratio(dull, 200), 0.02);
        Assert.assertTrue(ratio(dull, 15000) < 0.1);
    }

    @Test
    public void aShelfOfZeroDecibelsChangesNothing() {
        float[] input = TestSignals.noise(RATE, 3, 0.5);
        float[] output = input.clone();
        new BiquadFilter(BiquadCoefficients.highShelf(RATE, 4500, 0, 1)).process(output, 0, output.length);
        for (int i = 0; i < input.length; i++) {
            Assert.assertEquals(input[i], output[i], 1e-6f);
        }
    }

    @Test
    public void theResultDoesNotDependOnTheBlocks() {
        float[] input = TestSignals.noise(10000, 4, 0.8);
        float[] whole = input.clone();
        BiquadCoefficients c = BiquadCoefficients.highShelf(RATE, 4500, -6, 1);
        new BiquadFilter(c).process(whole, 0, whole.length);
        for (int block : new int[]{1, 64, 333, 4096}) {
            float[] cut = input.clone();
            BiquadFilter filter = new BiquadFilter(c);
            for (int at = 0; at < cut.length; at += block) {
                filter.process(cut, at, Math.min(block, cut.length - at));
            }
            Assert.assertArrayEquals("block " + block, whole, cut, 0f);
        }
    }

    @Test
    public void anImpulseRingsOutAndALongSilenceStaysExactlyZero() {
        float[] samples = new float[RATE * 20];
        samples[0] = 1;
        BiquadFilter filter = new BiquadFilter(BiquadCoefficients.lowPass(RATE, 9000, Math.sqrt(0.5)));
        for (int at = 0; at < samples.length; at += 512) {
            filter.process(samples, at, Math.min(512, samples.length - at));
        }
        for (float sample : samples) {
            Assert.assertTrue(Float.isFinite(sample));
        }
        for (int i = RATE; i < samples.length; i++) {
            Assert.assertEquals(0f, samples[i], 0f);
        }
    }

    @Test
    public void newCoefficientsKeepTheStateSoTheSignalDoesNotJump() {
        float[] samples = sine(4096, 300, RATE, 0.5);
        BiquadFilter filter = new BiquadFilter(BiquadCoefficients.highShelf(RATE, 4500, -3, 1));
        filter.process(samples, 0, 2048);
        filter.setCoefficients(BiquadCoefficients.highShelf(RATE, 4500, -4, 1));
        filter.process(samples, 2048, 2048);
        double largest = 0;
        for (int i = 1; i < samples.length; i++) {
            largest = Math.max(largest, Math.abs(samples[i] - samples[i - 1]));
        }
        // A 300 Hz sine at 0.5 changes by at most 2·π·300/44100·0.5 ≈ 0.021 per sample; the switch adds a
        // small transient (about 0.033), a filter starting afresh would jump by the whole sample value (≈ 0.4).
        // Gliding coefficients come with live parameter changes (D2).
        Assert.assertTrue("largest step " + largest, largest < 0.05);
    }

    @Test
    public void freshFiltersGiveTheSameResult() {
        float[] first = TestSignals.noise(5000, 5, 0.5);
        float[] second = first.clone();
        BiquadCoefficients c = BiquadCoefficients.lowPass(RATE, 5000, Math.sqrt(0.5));
        new BiquadFilter(c).process(first, 0, first.length);
        new BiquadFilter(c).process(second, 0, second.length);
        Assert.assertArrayEquals(first, second, 0f);
    }

    @Test
    public void bothSidesOfAStereoFilterAlwaysUseTheSameCoefficients() throws Exception {
        StereoBiquad filter = new StereoBiquad(BiquadCoefficients.highShelf(RATE, 4500, -1, 1));
        filter.prepare(RATE, 512);
        BiquadCoefficients[] sets = {BiquadCoefficients.highShelf(RATE, 4500, -9, 1),
                BiquadCoefficients.highShelf(RATE, 4500, -1, 1)};
        Thread changer = new Thread(() -> {
            for (int i = 0; !Thread.currentThread().isInterrupted(); i++) {
                filter.setCoefficients(sets[i & 1]);
            }
        });
        changer.start();
        try {
            AudioBlock block = new AudioBlock(512);
            float[] noise = TestSignals.noise(512, 6, 0.5);
            for (int round = 0; round < 20000; round++) {
                System.arraycopy(noise, 0, block.left, 0, 512);
                System.arraycopy(noise, 0, block.right, 0, 512);
                filter.process(block);
                Assert.assertArrayEquals("round " + round, block.left, block.right, 0f);
            }
        } finally {
            changer.interrupt();
            changer.join();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aFrequencyAtNyquistIsRefusedWhenTheCoefficientsAreMade() {
        BiquadCoefficients.highShelf(RATE, RATE / 2.0, -3, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aFilterWithoutCoefficientsIsRefused() {
        new BiquadFilter(null);
    }

    private static double ratio(BiquadCoefficients coefficients, double frequency) {
        float[] input = sine(RATE, frequency, RATE, 0.25);
        float[] output = input.clone();
        new BiquadFilter(coefficients).process(output, 0, output.length);
        return rms(output, RATE / 2, RATE) / rms(input, RATE / 2, RATE);
    }
}
