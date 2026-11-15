package com.aresstack.audio.core.live;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.TestSignals;
import com.aresstack.audio.core.conv.ImpulseResponse;
import com.aresstack.audio.core.conv.SyntheticRoom;
import com.aresstack.audio.core.dsp.ConvolutionReverb;
import com.aresstack.audio.core.spatial.HrirSet;
import com.aresstack.audio.core.spatial.SphericalHeadModel;
import com.aresstack.audio.core.stream.ArraySource;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** Work package D4 live: a channel on headphones, moved while it plays, in a convolution room. */
public final class LiveBinauralTest {
    private static final int RATE = 44100;
    private static final HrirSet HEAD = SphericalHeadModel.hrirs(RATE);
    private static final BiquadCoefficients WALLS = BiquadCoefficients.lowPass(RATE, 6000, Math.sqrt(0.5));

    private static ChannelParameters at(double azimuth, double elevation) {
        return ChannelParameters.builder().pan(0.8f, 0.8f).send(0.2f, 0.2f).direction(azimuth, elevation)
                .echoes(new int[] {600}, new float[] {0.1f}, new float[] {0.1f}).build();
    }

    private static LiveGraph graph(float[] tone, ChannelParameters start, ImpulseResponse room) {
        LiveChannel channel = new LiveChannel(new ArraySource(tone, tone.clone(), RATE), tone.length, start, 2000,
                WALLS, HEAD);
        return new LiveGraph(List.of(channel), room == null ? null : new ConvolutionReverb(room), 0.3f, tone.length);
    }

    @Test
    public void mirroredDirectionsGiveMirroredEarsLive() throws Exception {
        float[] tone = TestSignals.noise(RATE / 2, 1, 0.4);
        float[][] right = drain(graph(tone, at(60, 10), null), tone.length);
        float[][] left = drain(graph(tone, at(300, 10), null), tone.length);
        Assert.assertArrayEquals(right[0], left[1], 1e-5f);
        Assert.assertArrayEquals(right[1], left[0], 1e-5f);
    }

    @Test
    public void movingAroundTheHeadWhilePlayingNeverClicksAndAllocatesNothing() throws Exception {
        int frames = 12 * RATE;
        float[] tone = TestSignals.sine(frames, 220, RATE, 0.5);
        LiveGraph graph = graph(tone, at(0, 0), SyntheticRoom.generate(1.2, 0.4, 0.02, RATE, 3).scaled(0.05));
        graph.prepare(RATE, 512);
        AudioBlock block = new AudioBlock(512);
        float worst = 0;
        float previous = 0;
        double[] azimuth = {0};
        int at = 0;
        long bytes = 0;
        while (at < frames) {
            if ((at / 512) % 8 == 0) {
                azimuth[0] += 25;
            }
            ChannelParameters moved = at(azimuth[0], 15 * Math.sin(at * 1e-4));
            graph.channel(0).set(moved);
            block.setFrames(512);
            long before = Allocation.sofar();
            int got = graph.read(block);
            // Measured once the JIT has compiled the rare branches (see LiveGraphTest).
            if (at > frames * 3 / 4) {
                bytes += Allocation.sofar() - before;
            }
            for (int i = 0; i < got; i++) {
                if (at + i > 1000) {
                    worst = Math.max(worst, Math.abs(block.left[i] - previous));
                }
                previous = block.left[i];
            }
            at += got;
        }
        Assert.assertTrue("largest step " + worst, worst < 0.1f);
        Assert.assertTrue("allocated " + bytes + " bytes", bytes < 1024);
    }

    private static float[][] drain(LiveGraph graph, int frames) throws Exception {
        graph.prepare(RATE, 256);
        float[][] out = new float[2][frames];
        AudioBlock block = new AudioBlock(256);
        for (int at = 0; at < frames; at += 256) {
            block.setFrames(Math.min(256, frames - at));
            int got = graph.read(block);
            System.arraycopy(block.left, 0, out[0], at, got);
            System.arraycopy(block.right, 0, out[1], at, got);
        }
        return out;
    }
}
