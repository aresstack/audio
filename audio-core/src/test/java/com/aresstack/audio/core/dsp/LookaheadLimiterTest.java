package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.TestSignals;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

/**
 * The master limiter against the acceptance criteria of D1b (architecture review 7.2 and 4b.3):
 * the output never lies above the ceiling, a sine below the ceiling passes without any
 * distortion, and the gain moves without steps. Plus what a processor of the core must keep:
 * the same result for every block size, latency reported, state kept when settings change.
 */
public final class LookaheadLimiterTest {
    private static final int RATE = 44100;
    private static final float CEILING = LookaheadLimiter.Settings.DEFAULT.ceiling();

    @Test
    public void theOutputNeverLiesAboveTheCeiling() {
        // 30 s of drum-like bursts, dense material and single clicks up to +25.5 dBFS
        int frames = 30 * RATE;
        float[] left = new float[frames];
        float[] right = new float[frames];
        Random random = new Random(7);
        float loudest = 0;
        for (int s = 0; s < frames; s++) {
            double t = (double) s / RATE;
            double envelope = s % 22050 < 400 ? 8.0 : 0.6 + 0.5 * Math.sin(t * 0.7);
            double v = envelope * (0.5 * Math.sin(2 * Math.PI * 110 * t) + 0.4 * random.nextGaussian());
            if (random.nextInt(20000) == 0) {
                v = (random.nextBoolean() ? 1 : -1) * 18.8;
            }
            left[s] = (float) v;
            right[s] = (float) (v * 0.8 + 0.1 * random.nextGaussian());
            loudest = Math.max(loudest, Math.max(Math.abs(left[s]), Math.abs(right[s])));
        }
        Assert.assertTrue("the test reaches +25 dBFS", 20 * Math.log10(loudest) > 25);
        float[][] out = run(new LookaheadLimiter(), left, right, 512);
        assertBelow(out, CEILING);
    }

    @Test
    public void densePeaksOnEverySampleStayBelowTheCeiling() {
        // The prototype showed it: with a queue of n+1 places the head is overwritten just before it
        // is dropped, and peaks ran up to 0.11 above the ceiling. Falling and rising peaks on every
        // sample keep the queue full.
        int frames = 5 * RATE;
        float[] left = new float[frames];
        float[] right = new float[frames];
        Random random = new Random(3);
        for (int s = 0; s < frames; s++) {
            int phase = s % 300;
            double height = phase < 150 ? 6 - phase * 0.035 : 0.8 + (phase - 150) * 0.04;
            left[s] = (float) ((random.nextBoolean() ? 1 : -1) * height * (0.5 + 0.5 * random.nextDouble()));
            right[s] = (float) (left[s] * -0.9);
        }
        for (int block : new int[]{1, 97, 512}) {
            assertBelow(run(new LookaheadLimiter(), left, right, block), CEILING);
        }
    }

    @Test
    public void aSineBelowTheCeilingPassesBitForBit() {
        float[] sine = TestSignals.sine(3 * RATE, 997, RATE, 0.7071);
        LookaheadLimiter limiter = new LookaheadLimiter();
        float[][] out = run(limiter, sine, sine.clone(), 512);
        int delay = limiter.latencyFrames();
        for (int i = 0; i < delay; i++) {
            Assert.assertEquals("silence while the look-ahead fills", 0f, out[0][i], 0f);
        }
        for (int i = delay; i < sine.length; i++) {
            Assert.assertEquals("frame " + i, sine[i - delay], out[0][i], 0f);
            Assert.assertEquals("frame " + i, sine[i - delay], out[1][i], 0f);
        }
        Assert.assertEquals(1f, limiter.lowestGain(), 0f);
    }

    @Test
    public void afterAPeakTheSineComesBackBitForBit() {
        int burst = RATE;
        int quiet = 3 * RATE;
        float[] input = new float[burst + quiet];
        float[] loud = TestSignals.sine(burst, 300, RATE, 6.0);
        float[] sine = TestSignals.sine(quiet, 997, RATE, 0.7071);
        System.arraycopy(loud, 0, input, 0, burst);
        System.arraycopy(sine, 0, input, burst, quiet);
        LookaheadLimiter limiter = new LookaheadLimiter();
        float[][] out = run(limiter, input, input.clone(), 333);
        int delay = limiter.latencyFrames();
        int lastDifferent = -1;
        for (int i = burst + delay; i < input.length; i++) {
            if (out[0][i] != input[i - delay]) {
                lastDifferent = i;
            }
        }
        double after = (lastDifferent - burst - delay) / (double) RATE;
        // release 80 ms down to 1e-6 below unity: about 1.1 s
        Assert.assertTrue("bit-identical again " + after + " s after the peak", after < 1.3);
        Assert.assertTrue(limiter.lowestGain() < 0.2f);
    }

    @Test
    public void theGainMovesWithoutSteps() {
        // a step from quiet to +12 dBFS: the gain goes down as a ramp over the look-ahead
        float[] input = new float[RATE];
        for (int i = 0; i < RATE; i++) {
            input[i] = (float) ((i < RATE / 2 ? 0.1 : 4.0) * Math.sin(2 * Math.PI * 1000 * i / RATE));
        }
        LookaheadLimiter limiter = new LookaheadLimiter();
        limiter.prepare(RATE, 1);
        AudioBlock one = new AudioBlock(1);
        double previous = 1;
        double largestStep = 0;
        double lowest = 1;
        for (float sample : input) {
            one.left[0] = sample;
            one.right[0] = sample;
            limiter.process(one);
            double gain = limiter.gain();
            largestStep = Math.max(largestStep, Math.abs(gain - previous));
            lowest = Math.min(lowest, gain);
            previous = gain;
        }
        double drop = 1 - lowest;
        Assert.assertTrue("gain went down to " + lowest, lowest < 0.25);
        // a ramp over n frames moves at most drop/n per frame, plus the release in between
        Assert.assertTrue("largest gain change per frame " + largestStep,
                largestStep <= drop / limiter.latencyFrames() * 1.05);
    }

    @Test
    public void bothSidesGetTheSameGain() {
        float[] loud = TestSignals.sine(RATE, 200, RATE, 3.0);
        float[] soft = TestSignals.sine(RATE, 200, RATE, 0.3);
        float[][] out = run(new LookaheadLimiter(), loud, soft, 512);
        int delay = LookaheadLimiter.lookaheadFrames(LookaheadLimiter.DEFAULT_LOOKAHEAD_MS, RATE);
        for (int i = delay + 2000; i < RATE; i++) {
            if (Math.abs(soft[i - delay]) > 0.05) {
                double gainLeft = out[0][i] / loud[i - delay];
                double gainRight = out[1][i] / soft[i - delay];
                Assert.assertEquals("frame " + i, gainLeft, gainRight, 1e-5);
            }
        }
    }

    @Test
    public void theResultDoesNotDependOnTheBlockSize() {
        float[] left = TestSignals.noise(4 * RATE, 11, 3.0);
        float[] right = TestSignals.noise(4 * RATE, 12, 1.5);
        float[][] reference = run(new LookaheadLimiter(), left, right, 4096);
        for (int block : new int[]{1, 64, 512, 777}) {
            float[][] cut = run(new LookaheadLimiter(), left, right, block);
            Assert.assertArrayEquals("block " + block, reference[0], cut[0], 0f);
            Assert.assertArrayEquals("block " + block, reference[1], cut[1], 0f);
        }
    }

    @Test
    public void theLatencyIsTheLookAhead() {
        LookaheadLimiter limiter = new LookaheadLimiter();
        limiter.prepare(RATE, 512);
        Assert.assertEquals(88, limiter.latencyFrames());
        limiter.prepare(48000, 512);
        Assert.assertEquals(96, limiter.latencyFrames());
    }

    @Test
    public void aNewCeilingIsUsedWithoutLosingTheState() {
        float[] input = TestSignals.sine(2 * RATE, 440, RATE, 2.0);
        LookaheadLimiter limiter = new LookaheadLimiter();
        limiter.prepare(RATE, 512);
        AudioBlock block = new AudioBlock(512);
        float lower = new LookaheadLimiter.Settings(-6, 80).ceiling();
        float highestAfter = 0;
        for (int at = 0; at + 512 <= input.length; at += 512) {
            if (at == RATE / 512 * 512) {
                limiter.setSettings(new LookaheadLimiter.Settings(-6, 80));
            }
            System.arraycopy(input, at, block.left, 0, 512);
            System.arraycopy(input, at, block.right, 0, 512);
            block.setFrames(512);
            limiter.process(block);
            if (at > RATE + 4096) {
                for (int i = 0; i < 512; i++) {
                    highestAfter = Math.max(highestAfter, Math.abs(block.left[i]));
                }
            }
        }
        Assert.assertEquals(lower, highestAfter, 1e-3f);
        Assert.assertTrue(highestAfter <= lower);
    }

    @Test
    public void aLoweredCeilingHoldsAlsoForWhatIsAlreadyInTheDelayLine() {
        LookaheadLimiter limiter = new LookaheadLimiter();
        limiter.prepare(RATE, 512);
        AudioBlock block = new AudioBlock(512);
        java.util.Arrays.fill(block.left, 0.8f);
        java.util.Arrays.fill(block.right, 0.8f);
        limiter.process(block);
        LookaheadLimiter.Settings lower = new LookaheadLimiter.Settings(-6, 80);
        limiter.setSettings(lower);
        java.util.Arrays.fill(block.left, 0f);
        java.util.Arrays.fill(block.right, 0f);
        limiter.process(block);
        for (int i = 0; i < 512; i++) {
            Assert.assertTrue("frame " + i + ": " + block.left[i], Math.abs(block.left[i]) <= lower.ceiling());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aCeilingAboveSixDbIsRefused() {
        new LookaheadLimiter.Settings(7, 80);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aReleaseOfZeroIsRefused() {
        new LookaheadLimiter.Settings(-1, 0);
    }

    private static void assertBelow(float[][] out, float ceiling) {
        long over = 0;
        float highest = 0;
        for (float[] side : out) {
            for (float sample : side) {
                highest = Math.max(highest, Math.abs(sample));
                if (Math.abs(sample) > ceiling) {
                    over++;
                }
            }
        }
        Assert.assertEquals("samples above the ceiling (highest " + highest + ")", 0, over);
        Assert.assertTrue("the limiter acted", highest > ceiling * 0.9f);
    }

    /** Runs both sides through the limiter in blocks of the given size. */
    static float[][] run(LookaheadLimiter limiter, float[] left, float[] right, int blockFrames) {
        limiter.prepare(RATE, blockFrames);
        AudioBlock block = new AudioBlock(blockFrames);
        float[] outLeft = new float[left.length];
        float[] outRight = new float[right.length];
        for (int at = 0; at < left.length; at += blockFrames) {
            int count = Math.min(blockFrames, left.length - at);
            block.setFrames(count);
            System.arraycopy(left, at, block.left, 0, count);
            System.arraycopy(right, at, block.right, 0, count);
            limiter.process(block);
            System.arraycopy(block.left, 0, outLeft, at, count);
            System.arraycopy(block.right, 0, outRight, at, count);
        }
        return new float[][]{outLeft, outRight};
    }
}
