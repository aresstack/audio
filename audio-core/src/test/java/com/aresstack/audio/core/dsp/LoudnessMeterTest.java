package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.TestSignals;
import org.junit.Assert;
import org.junit.Test;

/**
 * Integrated loudness against the test signals of EBU Tech 3341 (minimum requirements, ±0.1 LU),
 * which BS.1770 meters must meet, at the rate of the studio and at 48 kHz.
 */
public final class LoudnessMeterTest {
    @Test
    public void aSineOf1kHzAtMinus23DbfsIsMinus23Lufs() {
        for (int rate : new int[]{44100, 48000}) {
            Assert.assertEquals("at " + rate, -23.0, measure(rate, new double[][]{{-23, 20}}), 0.1);
        }
    }

    @Test
    public void aSineOf1kHzAtMinus33DbfsIsMinus33Lufs() {
        Assert.assertEquals(-33.0, measure(44100, new double[][]{{-33, 20}}), 0.1);
    }

    @Test
    public void theRelativeGateLeavesOutQuietPassages() {
        // EBU Tech 3341 case 3: -36, -23, -36 dBFS for 10, 60, 10 s gives -23 LUFS
        Assert.assertEquals(-23.0, measure(44100, new double[][]{{-36, 10}, {-23, 60}, {-36, 10}}), 0.1);
    }

    @Test
    public void theAbsoluteGateLeavesOutSilence() {
        // EBU Tech 3341 case 4: -72, -36, -23, -36, -72 dBFS for 10, 10, 60, 10, 10 s gives -23 LUFS
        Assert.assertEquals(-23.0, measure(44100,
                new double[][]{{-72, 10}, {-36, 10}, {-23, 60}, {-36, 10}, {-72, 10}}), 0.1);
    }

    @Test
    public void silenceAndTooShortSoundHaveNoLoudness() {
        Assert.assertEquals(Double.NEGATIVE_INFINITY, measure(44100, new double[][]{{-200, 5}}), 0);
        Assert.assertEquals(Double.NEGATIVE_INFINITY, measure(44100, new double[][]{{-20, 0.3}}), 0);
    }

    @Test
    public void theSoundPassesUnchanged() {
        float[] sine = TestSignals.sine(1000, 1000, 44100, 0.5);
        AudioBlock block = new AudioBlock(1000);
        System.arraycopy(sine, 0, block.left, 0, 1000);
        System.arraycopy(sine, 0, block.right, 0, 1000);
        LoudnessMeter meter = new LoudnessMeter();
        meter.prepare(44100, 1000);
        meter.process(block);
        Assert.assertArrayEquals(sine, java.util.Arrays.copyOf(block.left, 1000), 0f);
    }

    /** A stereo sine of 1 kHz in sections of {level dBFS, seconds}, both sides in phase. */
    private static double measure(int rate, double[][] sections) {
        LoudnessMeter meter = new LoudnessMeter();
        int blockFrames = 1000;
        meter.prepare(rate, blockFrames);
        AudioBlock block = new AudioBlock(blockFrames);
        long position = 0;
        for (double[] section : sections) {
            double height = Math.pow(10, section[0] / 20);
            long end = position + Math.round(section[1] * rate);
            while (position < end) {
                int count = (int) Math.min(blockFrames, end - position);
                for (int i = 0; i < count; i++) {
                    float value = (float) (height * Math.sin(2 * Math.PI * 1000 * (position + i) / rate));
                    block.left[i] = value;
                    block.right[i] = value;
                }
                block.setFrames(count);
                meter.process(block);
                position += count;
            }
        }
        return meter.integrated();
    }
}
