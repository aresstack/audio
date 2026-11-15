package com.aresstack.audio.core.stream;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.TestSignals;
import org.junit.Assert;
import org.junit.Test;

/** The streaming resampler gives what the old one gave on the whole signal, for every block size. */
public final class LinearResamplerTest {
    private static final int TO = 44100;

    @Test
    public void blockByBlockEqualsTheWholeSignalAtOnce() throws Exception {
        float[][] rates = {{22050, 22050}, {48000, 48007}, {8000, 8001}, {96000, 30000}, {44100.3f, 1000},
                {11025, 1}, {32000, 0}};
        for (float[] rate : rates) {
            float from = rate[0];
            int frames = (int) rate[1];
            float[] left = TestSignals.noise(frames, 1, 0.5);
            float[] right = TestSignals.noise(frames, 2, 0.5);
            float[][] expected = whole(left, right, from);
            for (int block : new int[]{1, 64, 512, 4096, 999}) {
                float[][] actual = streamed(left, right, from, block);
                String what = from + " Hz, " + frames + " frames, block " + block;
                Assert.assertArrayEquals(what, expected[0], actual[0], 0f);
                Assert.assertArrayEquals(what, expected[1], actual[1], 0f);
            }
        }
    }

    @Test
    public void theNumberOfFramesIsKnownBeforehand() {
        Assert.assertEquals(44100, LinearResampler.outputFrames(22050, 22050, TO));
        Assert.assertEquals(1000, LinearResampler.outputFrames(1000, 44100.3f, TO));
        Assert.assertEquals((long) Math.ceil(48007 * 44100.0 / 48000), LinearResampler.outputFrames(48007, 48000, TO));
    }

    @Test
    public void readingDoesNotAllocate() throws Exception {
        long frames = 48000L * 60;
        AudioSource source = new LinearResampler(new TestSignals.SineSource(440, 48000, frames), 48000, TO);
        AudioBlock block = new AudioBlock(512);
        long bytes = Allocation.of(() -> {
            source.prepare(TO, 512);
            for (int i = 0; i < 1000; i++) {
                source.read(block);
            }
        });
        // prepare allocates its window (a few kilobytes); the thousand blocks after it nothing.
        Assert.assertTrue(bytes + " bytes", bytes < 16 * 1024);
    }

    /** The resampling of the old mixer, on the whole signal. */
    private static float[][] whole(float[] signalLeft, float[] signalRight, float rate) {
        if (Math.abs(rate - TO) < 0.5f) {
            return new float[][]{signalLeft, signalRight};
        }
        int frames = (int) Math.ceil(signalLeft.length * (double) TO / rate);
        float[] left = new float[frames];
        float[] right = new float[frames];
        int last = signalLeft.length - 1;
        for (int i = 0; i < frames; i++) {
            double position = i * rate / (double) TO;
            int index = (int) position;
            if (index <= last) {
                float fraction = (float) (position - index);
                int following = Math.min(index + 1, last);
                left[i] = signalLeft[index] + (signalLeft[following] - signalLeft[index]) * fraction;
                right[i] = signalRight[index] + (signalRight[following] - signalRight[index]) * fraction;
            }
        }
        return new float[][]{left, right};
    }

    private static float[][] streamed(float[] left, float[] right, float rate, int blockFrames) throws Exception {
        int frames = (int) LinearResampler.outputFrames(left.length, rate, TO);
        float[][] out = {new float[frames], new float[frames]};
        try (AudioSource source = LinearResampler.at(new ArraySource(left, right, rate), rate, TO)) {
            source.prepare(TO, blockFrames);
            AudioBlock block = new AudioBlock(blockFrames);
            int at = 0;
            int got;
            do {
                block.setFrames(blockFrames);
                got = source.read(block);
                Assert.assertTrue("more frames than announced", at + got <= frames);
                System.arraycopy(block.left, 0, out[0], at, got);
                System.arraycopy(block.right, 0, out[1], at, got);
                at += got;
            } while (got == blockFrames);
            Assert.assertEquals(frames, at);
        }
        return out;
    }
}
