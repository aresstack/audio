package com.aresstack.audio.core.conv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.TestSignals;
import com.aresstack.audio.core.dsp.ConvolutionReverb;
import com.aresstack.audio.core.dsp.RoomBus;
import com.aresstack.audio.core.dsp.RoomReverb;
import org.junit.Test;

public class ConvolutionReverbTest {
    private static final int RATE = 44100;

    /**
     * The algorithmic room is linear and time-invariant, so its own answer to a click, used as
     * the response of the convolution, rings the same: the two room buses agree.
     */
    @Test
    public void ringsLikeTheAlgorithmicRoomWithItsOwnImpulseResponse() {
        RoomReverb.Settings settings = new RoomReverb.Settings(0.8, 0.5, 0.01);
        int length = (int) (RATE * 1.5);
        // The response for each side: a click sent on one side, with the cross-feed taken out
        // (left in → 0.8 left + 0.2 right, so a click on both sides gives exactly one ring per side).
        float[][] click = new float[2][length];
        click[0][0] = 1;
        click[1][0] = 1;
        float[][] ring = render(new RoomReverb(settings), click[0], click[1], 512);
        ImpulseResponse response = new ImpulseResponse(ring[0], ring[1], RATE);
        float[] left = TestSignals.noise(length, 1, 0.3);
        float[] right = TestSignals.noise(length, 2, 0.3);
        // Equal sends on both sides: the cross-feed of both buses then adds up the same way.
        float[][] algorithmic = render(new RoomReverb(settings), left, left, 512);
        float[][] convolved = render(new ConvolutionReverb(response), left, left, 512);
        double worst = 0;
        double peak = 0;
        for (int i = 0; i < length; i++) {
            worst = Math.max(worst, Math.abs(algorithmic[0][i] - convolved[0][i]));
            worst = Math.max(worst, Math.abs(algorithmic[1][i] - convolved[1][i]));
            peak = Math.max(peak, Math.abs(algorithmic[0][i]));
        }
        assertTrue("peak " + peak, peak > 0.01);
        assertTrue("worst difference " + worst + " at peak " + peak, worst < peak * 1e-4);
        assertTrue(right.length > 0);
    }

    @Test
    public void theSyntheticRoomFallsBySixtyDecibelsInTheWantedTime() {
        ImpulseResponse room = SyntheticRoom.generate(1.2, 0.4, 0.02, RATE, 7);
        float[] left = room.left();
        int pre = (int) (0.02 * RATE);
        for (int i = 0; i < pre; i++) {
            assertEquals(0, left[i], 0f);
        }
        double early = TestSignals.rms(left, pre + 2000, pre + 6000);
        int at = pre + (int) (1.2 * RATE);
        double late = TestSignals.rms(left, at - 2000, at + 2000);
        double fall = 20 * Math.log10(early / late);
        // The envelope falls 60 dB; the sinking low-pass takes some more.
        assertTrue("fell " + fall + " dB", fall > 55 && fall < 80);
        assertEquals(room.energy(), SyntheticRoom.generate(1.2, 0.4, 0.02, RATE, 7).energy(), 0);
    }

    @Test
    public void aResponseAtAnotherRateIsBroughtToTheRateOfTheMixKeepingItsGain() {
        float[] decay = new float[24000];
        for (int i = 0; i < decay.length; i++) {
            decay[i] = (float) Math.exp(-i / 2400.0) / 2400f;
        }
        ImpulseResponse room = new ImpulseResponse(decay, decay, 48000);
        ImpulseResponse fitting = room.at(RATE);
        assertEquals(room.seconds(), fitting.seconds(), 1e-3);
        // The gain for low notes (the sum of the weights) stays the same at the new rate.
        assertEquals(sum(room.left()), sum(fitting.left()), 0.01);
    }

    private static double sum(float[] samples) {
        double sum = 0;
        for (float sample : samples) {
            sum += sample;
        }
        return sum;
    }

    static float[][] render(RoomBus bus, float[] left, float[] right, int block) {
        bus.prepare(RATE, block);
        AudioBlock send = new AudioBlock(block);
        AudioBlock out = new AudioBlock(block);
        float[][] result = new float[2][left.length];
        for (int at = 0; at < left.length; at += block) {
            int count = Math.min(block, left.length - at);
            System.arraycopy(left, at, send.left, 0, count);
            System.arraycopy(right, at, send.right, 0, count);
            out.clear(0, block);
            bus.process(send, count, out, 1f);
            System.arraycopy(out.left, 0, result[0], at, count);
            System.arraycopy(out.right, 0, result[1], at, count);
        }
        return result;
    }
}
