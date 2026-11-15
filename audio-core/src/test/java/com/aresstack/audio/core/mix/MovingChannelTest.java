package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.TestSignals;
import com.aresstack.audio.core.automation.AutomationCurve;
import com.aresstack.audio.core.dsp.EarlyReflections;
import com.aresstack.audio.core.dsp.RoomReverb;
import com.aresstack.audio.core.dsp.StereoBiquad;
import com.aresstack.audio.core.dsp.StereoPanner;
import com.aresstack.audio.core.dsp.StereoStrip;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Work package D3, moving sources prepared: a channel whose panorama, distance gain and send
 * follow a {@link ChannelMotion}. Standing still it sounds exactly like a standing channel; it
 * glides without a jump, the result does not depend on the block size, and the audio thread
 * allocates nothing.
 */
public final class MovingChannelTest {
    private static final int RATE = 44100;
    private static final int FRAMES = 2 * RATE;

    private interface Channels {
        StemChannel make(AudioSource source);
    }

    private static StereoStrip strip() {
        return new StereoStrip(new StereoStrip.Settings(0.8f, 0.6f));
    }

    private static StereoBiquad shelf() {
        return new StereoBiquad(BiquadCoefficients.highShelf(RATE, 4500, -3, 1));
    }

    private static EarlyReflections echoes() {
        return new EarlyReflections(List.of(new EarlyReflections.Tap(700, 0.2f, 0.1f)),
                BiquadCoefficients.lowPass(RATE, 7000, Math.sqrt(0.5)));
    }

    private static float[] render(Channels channels, int block) throws Exception {
        AudioSource source = new TestSignals.SineSource(330, RATE, FRAMES);
        RoomReverb room = new RoomReverb(new RoomReverb.Settings(1.5, 0.4, 0.02));
        MixGraph graph = new MixGraph(List.of(channels.make(source)), room, 0.3f, FRAMES + RATE / 2);
        float[] out = new float[(int) graph.frames() * 2];
        AudioBlock buffer = new AudioBlock(block);
        graph.prepare(RATE, block);
        int at = 0;
        int got;
        do {
            buffer.setFrames(block);
            got = graph.read(buffer);
            for (int i = 0; i < got; i++) {
                out[2 * (at + i)] = buffer.left[i];
                out[2 * (at + i) + 1] = buffer.right[i];
            }
            at += got;
        } while (got == block);
        return out;
    }

    /** From hard left to hard right over the piece, nearer at the end, and ever more into the room. */
    private static ChannelMotion across() {
        AutomationCurve pan = AutomationCurve.of(new double[] {0, 2}, new double[] {-1, 1}, RATE);
        AutomationCurve near = AutomationCurve.of(new double[] {0, 2}, new double[] {0.5, 1}, RATE);
        AutomationCurve send = AutomationCurve.of(new double[] {0.5, 1.5}, new double[] {0, 0.6}, RATE);
        return (frame, gains) -> {
            double position = pan.valueAt(frame);
            float distance = (float) near.valueAt(frame);
            gains[ChannelMotion.TO_LEFT] = StereoPanner.leftGain(position, distance);
            gains[ChannelMotion.TO_RIGHT] = StereoPanner.rightGain(position, distance);
            gains[ChannelMotion.SEND_LEFT] = (float) send.valueAt(frame);
            gains[ChannelMotion.SEND_RIGHT] = (float) send.valueAt(frame);
        };
    }

    @Test
    public void aMotionThatStandsStillSoundsExactlyLikeAStandingChannel() throws Exception {
        StereoPanner panner = new StereoPanner(0.3, 0.7f);
        float[] standing = render(source -> new StemChannel(source, FRAMES, strip(), 0.25f, 0.35f, shelf(), panner,
                echoes()), 512);
        float[] still = render(source -> new StemChannel(source, FRAMES, strip(), shelf(), (frame, gains) -> {
            gains[ChannelMotion.TO_LEFT] = panner.toLeft();
            gains[ChannelMotion.TO_RIGHT] = panner.toRight();
            gains[ChannelMotion.SEND_LEFT] = 0.25f;
            gains[ChannelMotion.SEND_RIGHT] = 0.35f;
        }, echoes()), 512);
        Assert.assertArrayEquals(standing, still, 0f);
    }

    @Test
    public void theResultOfAMovingChannelDoesNotDependOnTheBlockSize() throws Exception {
        float[] reference = render(source -> new StemChannel(source, FRAMES, strip(), shelf(), across(), echoes()), 4096);
        for (int block : new int[] {1, 64, 300, 512}) {
            float[] cut = render(source -> new StemChannel(source, FRAMES, strip(), shelf(), across(), echoes()), block);
            Assert.assertArrayEquals("block " + block, reference, cut, 0f);
        }
    }

    @Test
    public void aSourceMovingFromLeftToRightIsHeardThere() throws Exception {
        float[] mix = render(source -> new StemChannel(source, FRAMES, strip(), null, across(), null), 512);
        double[] start = sides(mix, 0, RATE / 10);
        double[] end = sides(mix, FRAMES - RATE / 10, FRAMES);
        Assert.assertTrue("left at the start: " + Arrays.toString(start), start[0] > 10 * start[1]);
        Assert.assertTrue("right at the end: " + Arrays.toString(end), end[1] > 10 * end[0]);
        Assert.assertTrue("nearer, so louder", end[1] > start[0]);
    }

    @Test
    public void aMovingSourceGlidesWithoutAJump() throws Exception {
        // A jump of the place from hard left to hard right at one frame becomes a glide over 64 frames.
        AutomationCurve jump = AutomationCurve.of(new double[] {RATE, RATE}, new double[] {-1, 1}, 1);
        float[] mix = render(source -> new StemChannel(source, FRAMES, strip(), null, (frame, gains) -> {
            double position = jump.valueAt(frame);
            gains[ChannelMotion.TO_LEFT] = StereoPanner.leftGain(position, 1);
            gains[ChannelMotion.TO_RIGHT] = StereoPanner.rightGain(position, 1);
            gains[ChannelMotion.SEND_LEFT] = 0;
            gains[ChannelMotion.SEND_RIGHT] = 0;
        }, null), 512);
        float largest = 0;
        for (int i = 2 * (RATE - 256); i < 2 * (RATE + 256); i += 2) {
            largest = Math.max(largest, Math.abs(mix[i + 2] - mix[i]));
            largest = Math.max(largest, Math.abs(mix[i + 3] - mix[i + 1]));
        }
        // A sine of 330 Hz at 0.4 * 0.8 * sqrt(2) changes by at most about 0.021 per frame; the glide adds little.
        Assert.assertTrue("largest step " + largest, largest < 0.04f);
    }

    @Test
    public void aMovingChannelAllocatesNothingWhileItPlays() throws Exception {
        int length = 200 * 512;
        MixGraph graph = new MixGraph(List.of(new StemChannel(new TestSignals.SineSource(330, RATE, length), length,
                strip(), shelf(), across(), echoes())), new RoomReverb(new RoomReverb.Settings(1.5, 0.4, 0.02)), 0.3f,
                length);
        AudioBlock block = new AudioBlock(512);
        long bytes = Allocation.of(() -> {
            graph.prepare(RATE, 512);
            long before = Allocation.sofar();
            for (int i = 0; i < 200; i++) {
                block.setFrames(512);
                graph.read(block);
            }
            if (Allocation.sofar() - before > 256) {
                throw new AssertionError("allocated " + (Allocation.sofar() - before) + " bytes while playing");
            }
        });
        Assert.assertTrue(bytes >= 0);
    }

    private static double[] sides(float[] mix, int from, int to) {
        double left = 0;
        double right = 0;
        for (int i = from; i < to; i++) {
            left += mix[2 * i] * mix[2 * i];
            right += mix[2 * i + 1] * mix[2 * i + 1];
        }
        return new double[] {left, right};
    }
}
