package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSink;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.TestSignals;
import com.aresstack.audio.core.dsp.EarlyReflections;
import com.aresstack.audio.core.dsp.RoomReverb;
import com.aresstack.audio.core.dsp.StereoBiquad;
import com.aresstack.audio.core.dsp.StereoPanner;
import com.aresstack.audio.core.dsp.StereoStrip;
import com.aresstack.audio.core.stream.ArraySource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * The mix graph: the same result for every block size, memory that does not grow with the
 * length of the piece, no upper limit on the length, and the old loudness of the whole.
 */
public final class MixGraphTest {
    private static final int RATE = 44100;

    @Test
    public void theResultDoesNotDependOnTheBlockSize() throws Exception {
        float[] reference = render(4096, 3 * RATE);
        for (int block : new int[]{1, 64, 512, 1000}) {
            float[] cut = render(block, 3 * RATE);
            Assert.assertArrayEquals("block " + block, reference, cut, 0f);
        }
    }

    @Test
    public void memoryDoesNotGrowWithTheLengthOfThePiece() throws Exception {
        long shortPiece = Allocation.of(() -> drain(graph(sine(RATE * 20), RATE * 20 + RATE), 512));
        long longPiece = Allocation.of(() -> drain(graph(sine(RATE * 200), RATE * 200 + RATE), 512));
        // Both allocate only while the graph is built and prepared (delay lines, the room): the same few
        // hundred kilobytes, whether the piece lasts twenty seconds or two hundred. Two hundred seconds of
        // stereo float alone would be 70 MB.
        Assert.assertTrue("short " + shortPiece + " B, long " + longPiece + " B",
                longPiece < 2 * 1024 * 1024 && Math.abs(longPiece - shortPiece) < 64 * 1024);
    }

    @Test
    public void aPieceLongerThanTenMinutesIsMixed() throws Exception {
        long frames = RATE * 60L * 11;
        MixGraph graph = graph(sine(frames), frames + RATE);
        long[] count = new long[1];
        float gain = OfflineMix.renderNormalized(() -> graph(sine(frames), frames + RATE), RATE, 4096, 0,
                block -> count[0] += block.frames());
        Assert.assertEquals(frames + RATE, count[0]);
        Assert.assertTrue(gain > 0);
        Assert.assertEquals(frames + RATE, graph.frames());
    }

    @Test
    public void theLoudnessOfTheWholeIsSetAsBefore() throws Exception {
        float[] mix = render(512, RATE);
        float peak = 0;
        for (float sample : mix) {
            peak = Math.max(peak, Math.abs(sample));
        }
        List<float[]> written = new ArrayList<float[]>();
        float gain = OfflineMix.renderNormalized(() -> graph(noise(RATE), RATE + RATE / 2), RATE, 512, -6,
                new AudioSink() {
                    @Override
                    public void write(AudioBlock block) {
                        written.add(Arrays.copyOf(block.left, block.frames()));
                    }
                });
        float expected = (float) (Math.min(4, 0.92 / peak) * Math.pow(10, -6 / 20.0));
        Assert.assertEquals(expected, gain, 0f);
        Assert.assertEquals(mix[100] * gain, written.get(0)[100], 0f);
        Assert.assertEquals(1f, LegacyPeakNormalization.gain(1e-5f, 0), 0f);
        Assert.assertEquals(4f, LegacyPeakNormalization.gain(0.01f, 0), 0f);
    }

    /** The left side of a graph rendered in blocks of the given size. */
    private static float[] render(int blockFrames, int frames) throws Exception {
        MixGraph graph = graph(noise(frames), frames + RATE / 2);
        float[] left = new float[(int) graph.frames()];
        graph.prepare(RATE, blockFrames);
        AudioBlock block = new AudioBlock(blockFrames);
        int at = 0;
        int got;
        do {
            block.setFrames(blockFrames);
            got = graph.read(block);
            System.arraycopy(block.left, 0, left, at, got);
            at += got;
        } while (got == blockFrames);
        Assert.assertEquals(left.length, at);
        return left;
    }

    private static void drain(AudioSource graph, int blockFrames) throws Exception {
        graph.prepare(RATE, blockFrames);
        AudioBlock block = new AudioBlock(blockFrames);
        while (graph.read(block) == blockFrames) {
            block.setFrames(blockFrames);
        }
    }

    private interface Sources {
        AudioSource open();
    }

    private static Sources noise(int frames) {
        float[] left = TestSignals.noise(frames, 1, 0.4);
        float[] right = TestSignals.noise(frames, 2, 0.4);
        return () -> new ArraySource(left, right, RATE);
    }

    private static Sources sine(long frames) {
        return () -> new TestSignals.SineSource(220, RATE, frames);
    }

    /** Two channels with everything a channel can have, in a ringing room. */
    private static MixGraph graph(Sources sources, long frames) {
        List<StemChannel> channels = new ArrayList<StemChannel>();
        for (int i = 0; i < 2; i++) {
            float position = i == 0 ? -0.6f : 0.4f;
            StereoStrip strip = new StereoStrip(new StereoStrip.Settings(0.7f, 0.6f,
                    frame -> (float) Math.pow(10, -3 * Math.sin(frame / 30000.0) / 20)));
            EarlyReflections reflections = new EarlyReflections(Arrays.asList(new EarlyReflections.Tap(700 + i, 0.2f, 0.1f),
                    new EarlyReflections.Tap(1900, 0.05f, 0.15f), new EarlyReflections.Tap(3100 - i, 0.1f, 0.1f)),
                    BiquadCoefficients.lowPass(RATE, 6000, Math.sqrt(0.5)));
            channels.add(new StemChannel(sources.open(), frames, strip, 0.3f, 0.25f,
                    new StereoBiquad(BiquadCoefficients.highShelf(RATE, 4500, -4, 1)),
                    new StereoPanner(position, 0.8f), reflections));
        }
        return new MixGraph(channels, new RoomReverb(new RoomReverb.Settings(1.5, 0.5, 0.02)), 0.35f, frames);
    }
}
