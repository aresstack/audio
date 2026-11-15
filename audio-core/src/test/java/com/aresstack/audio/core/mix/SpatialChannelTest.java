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
import com.aresstack.audio.core.spatial.HrirSet;
import com.aresstack.audio.core.spatial.SphericalHeadModel;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Work package D4, moving sources that are followed fully: shelf, echoes and (for headphones)
 * the direction move with the source. Standing still on loudspeakers the channel sounds exactly
 * like a standing {@link StemChannel}, moving only its gains exactly like the moving channel of
 * D3; it glides without clicks, does not depend on the block size and allocates nothing.
 */
public final class SpatialChannelTest {
    private static final int RATE = 44100;
    private static final int FRAMES = 2 * RATE;
    private static final BiquadCoefficients SHELF = BiquadCoefficients.highShelf(RATE, 4500, -3, 1);
    private static final BiquadCoefficients LOW_PASS = BiquadCoefficients.lowPass(RATE, 7000, Math.sqrt(0.5));
    private static final HrirSet HEAD = SphericalHeadModel.hrirs(RATE);

    private interface Channels {
        MixChannel make(AudioSource source);
    }

    private static StereoStrip strip() {
        return new StereoStrip(new StereoStrip.Settings(0.8f, 0.6f));
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

    /** Standing: the same numbers as the standing channel below. */
    private static SpatialMotion standing() {
        return (frame, seat) -> {
            seat.toLeft = 0.7f;
            seat.toRight = 0.4f;
            seat.sendLeft = 0.2f;
            seat.sendRight = 0.3f;
            seat.shelf = SHELF;
            seat.echoDelay[0] = 700;
            seat.echoLeft[0] = 0.2f;
            seat.echoRight[0] = 0.1f;
        };
    }

    @Test
    public void standingStillItSoundsExactlyLikeAStandingChannel() throws Exception {
        float[] standing = render(source -> new StemChannel(source, FRAMES, strip(), 0.2f, 0.3f,
                new StereoBiquad(SHELF), StereoPanner.ofGains(0.7f, 0.4f),
                new EarlyReflections(List.of(new EarlyReflections.Tap(700, 0.2f, 0.1f)), LOW_PASS)), 512);
        float[] spatial = render(source -> new SpatialChannel(source, FRAMES, strip(), standing(), 1, 2000, LOW_PASS,
                null), 512);
        Assert.assertArrayEquals(standing, spatial, 0f);
    }

    @Test
    public void movingOnlyItsGainsItSoundsExactlyLikeTheMovingChannelOfD3() throws Exception {
        AutomationCurve pan = AutomationCurve.of(new double[] {0, 2}, new double[] {-1, 1}, RATE);
        ChannelMotion gains = (frame, g) -> {
            double position = pan.valueAt(frame);
            g[ChannelMotion.TO_LEFT] = StereoPanner.leftGain(position, 0.8f);
            g[ChannelMotion.TO_RIGHT] = StereoPanner.rightGain(position, 0.8f);
            g[ChannelMotion.SEND_LEFT] = (float) (0.1 + 0.1 * position);
            g[ChannelMotion.SEND_RIGHT] = 0.2f;
        };
        float[] scratch = new float[ChannelMotion.GAINS];
        SpatialMotion same = (frame, seat) -> {
            gains.gainsAt(frame, scratch);
            seat.toLeft = scratch[ChannelMotion.TO_LEFT];
            seat.toRight = scratch[ChannelMotion.TO_RIGHT];
            seat.sendLeft = scratch[ChannelMotion.SEND_LEFT];
            seat.sendRight = scratch[ChannelMotion.SEND_RIGHT];
            seat.shelf = SHELF;
            seat.echoDelay[0] = 700;
            seat.echoLeft[0] = 0.2f;
            seat.echoRight[0] = 0.1f;
        };
        float[] d3 = render(source -> new StemChannel(source, FRAMES, strip(), new StereoBiquad(SHELF), gains,
                new EarlyReflections(List.of(new EarlyReflections.Tap(700, 0.2f, 0.1f)), LOW_PASS)), 300);
        float[] d4 = render(source -> new SpatialChannel(source, FRAMES, strip(), same, 1, 2000, LOW_PASS, null), 300);
        Assert.assertArrayEquals(d3, d4, 0f);
    }

    /** Walking away: farther, duller, the echo ever later and softer, the room larger. */
    private static SpatialMotion walkingAway(BiquadCoefficients[] shelves) {
        AutomationCurve far = AutomationCurve.of(new double[] {0, 2}, new double[] {0, 1}, RATE);
        return (frame, seat) -> {
            double f = far.valueAt(frame);
            float near = (float) (1 - 0.6 * f);
            seat.toLeft = near;
            seat.toRight = near * 0.8f;
            seat.sendLeft = (float) (0.1 + 0.3 * f);
            seat.sendRight = seat.sendLeft;
            seat.shelf = shelves[(int) Math.round(f * (shelves.length - 1))];
            seat.echoDelay[0] = (float) (300 + 900 * f);
            seat.echoLeft[0] = (float) (0.3 - 0.2 * f);
            seat.echoRight[0] = (float) (0.2 - 0.1 * f);
            seat.echoDelay[1] = (float) (1200 - 500 * f);
            seat.echoLeft[1] = 0.1f;
            seat.echoRight[1] = 0.15f;
            seat.azimuth = 30 + 120 * f;
            seat.elevation = 40 * f;
        };
    }

    private static BiquadCoefficients[] shelves() {
        BiquadCoefficients[] table = new BiquadCoefficients[19];
        table[0] = null;
        for (int i = 1; i < table.length; i++) {
            table[i] = BiquadCoefficients.highShelf(RATE, 4500, -0.5 * i, 1);
        }
        return table;
    }

    @Test
    public void movingFullyItDoesNotDependOnTheBlockSize() throws Exception {
        BiquadCoefficients[] shelves = shelves();
        for (HrirSet head : new HrirSet[] {null, HEAD}) {
            float[] large = render(source -> new SpatialChannel(source, FRAMES, strip(), walkingAway(shelves), 2, 2000,
                    LOW_PASS, head), 512);
            float[] odd = render(source -> new SpatialChannel(source, FRAMES, strip(), walkingAway(shelves), 2, 2000,
                    LOW_PASS, head), 37);
            Assert.assertArrayEquals(large, odd, 0f);
        }
    }

    @Test
    public void movingFullyItNeverClicks() throws Exception {
        BiquadCoefficients[] shelves = shelves();
        for (HrirSet head : new HrirSet[] {null, HEAD}) {
            float[] out = render(source -> new SpatialChannel(source, FRAMES, strip(), walkingAway(shelves), 2, 2000,
                    LOW_PASS, head), 512);
            float worst = 0;
            for (int i = 2 * 2000; i < 2 * (FRAMES - 10); i++) {
                worst = Math.max(worst, Math.abs(out[i] - out[i - 2]));
            }
            // A 330 Hz sine of amplitude 1 moves by at most 0.047 per sample; the mix of direct
            // sound, echoes and room stays within a few times that.
            Assert.assertTrue((head == null ? "stereo" : "binaural") + ": largest step " + worst, worst < 0.2f);
        }
    }

    @Test
    public void theEchoFollowsTheSource() throws Exception {
        // A click at the start; the echo is heard at the delay the source has when it is due.
        int frames = RATE;
        float[] click = new float[frames];
        click[0] = 1;
        AutomationCurve delay = AutomationCurve.of(new double[] {0, 1}, new double[] {200, 1200}, RATE);
        SpatialMotion motion = (frame, seat) -> {
            seat.toLeft = 0;
            seat.toRight = 0;
            seat.echoDelay[0] = (float) delay.valueAt(frame);
            seat.echoLeft[0] = 1;
            seat.echoRight[0] = 1;
        };
        BiquadCoefficients through = BiquadCoefficients.lowPass(RATE, 20000 * 0.9, Math.sqrt(0.5));
        SpatialChannel channel = new SpatialChannel(new com.aresstack.audio.core.stream.ArraySource(click, click, RATE),
                frames, new StereoStrip(new StereoStrip.Settings(1f, 1f)), motion, 1, 2000, through, null);
        MixGraph graph = new MixGraph(List.of(channel), null, 0, frames);
        graph.prepare(RATE, 256);
        AudioBlock block = new AudioBlock(256);
        int loudest = -1;
        float most = 0;
        for (int at = 0; at < frames; at += 256) {
            block.setFrames(256);
            int got = graph.read(block);
            for (int i = 0; i < got; i++) {
                if (Math.abs(block.left[i]) > most) {
                    most = Math.abs(block.left[i]);
                    loudest = at + i;
                }
            }
        }
        // The echo of frame 0 arrives at t where t = delay(t) = 200 + 1000·t/RATE.
        double expected = 200 / (1 - 1000.0 / RATE);
        Assert.assertEquals(expected, loudest, 3);
    }

    @Test
    public void theAudioThreadAllocatesNothing() throws Exception {
        BiquadCoefficients[] shelves = shelves();
        SpatialChannel channel = new SpatialChannel(new TestSignals.SineSource(330, RATE, 50 * RATE), 50 * RATE,
                strip(), walkingAway(shelves), 2, 2000, LOW_PASS, HEAD);
        MixGraph graph = new MixGraph(List.of(channel), new RoomReverb(new RoomReverb.Settings(1, 0.4, 0.01)), 0.3f,
                50 * RATE);
        graph.prepare(RATE, 512);
        AudioBlock block = new AudioBlock(512);
        long bytes = Allocation.of(() -> {
            for (int i = 0; i < 40; i++) {
                block.setFrames(512);
                graph.read(block);
            }
        });
        Assert.assertTrue("allocated " + bytes + " bytes", bytes < 512);
    }
}
