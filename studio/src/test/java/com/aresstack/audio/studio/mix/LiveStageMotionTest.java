package com.aresstack.audio.studio.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.live.LiveGraph;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.Assert;
import org.junit.Test;

/**
 * How a moved instrument travels between the ears (A13 listening report: "partly jerky"). Measured
 * in windows of 10 ms on a dry stage: the balance (right against left, in dB) and the loudness.
 * A jump with the 20 ms channel glide is a cut: the balance crosses in one or two windows. Dragging
 * (a new place every 16 ms) and a glide on the stage ({@link LiveStage#glide}) move it a little
 * per window and keep the loudness, also in the middle.
 */
public final class LiveStageMotionTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final int WINDOW = RATE / 100;
    private static final AcousticRoom DRY = AcousticRoom.STUDIO.withAmount(0);
    private static final SoundSourcePlacement LEFT = new SoundSourcePlacement("violin", -1, 0.1, 0, 1, 0, false, false,
            null);
    private static final SoundSourcePlacement RIGHT = LEFT.at(1, 0.1);

    /** Balance (dB) and loudness (dB) per 10 ms window, while {@code placeAt} decides the place at each frame. */
    static double[][] travel(AcousticRoom room, IntFunction<SoundSourcePlacement> placeAt, int stepFrames)
            throws Exception {
        float[] noise = new float[4 * RATE];
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (float) (random.nextGaussian() * 0.1);
        }
        try (LiveStage stage = LiveStage.of(List.of(new SpatialStemMixer.Signal(noise, noise, RATE)),
                List.of(placeAt.apply(0)), room, 2)) {
            LiveGraph graph = stage.graph();
            int frames = noise.length;
            float[] left = new float[frames];
            float[] right = new float[frames];
            // blocks as small as the steps, so that each step is handed over on time, as the audio thread would take it
            AudioBlock block = new AudioBlock(stepFrames);
            graph.prepare(RATE, stepFrames);
            SoundSourcePlacement last = null;
            for (int at = 0; at < frames; at += stepFrames) {
                SoundSourcePlacement now = placeAt.apply(at);
                if (now != last) {
                    stage.move(0, now);
                    last = now;
                }
                block.setFrames(Math.min(stepFrames, frames - at));
                int got = graph.read(block);
                System.arraycopy(block.left, 0, left, at, got);
                System.arraycopy(block.right, 0, right, at, got);
            }
            int windows = frames / WINDOW;
            double[][] series = new double[2][windows];
            for (int w = 0; w < windows; w++) {
                double l = 0;
                double r = 0;
                for (int i = w * WINDOW; i < (w + 1) * WINDOW; i++) {
                    l += left[i] * (double) left[i];
                    r += right[i] * (double) right[i];
                }
                series[0][w] = 10 * Math.log10((r + 1e-12) / (l + 1e-12));
                series[1][w] = 10 * Math.log10((l + r) / WINDOW + 1e-12);
            }
            return series;
        }
    }

    /** The largest change of the balance from one window to the next, after the first 100 ms. */
    private static double largestTurn(double[] balance) {
        double most = 0;
        for (int w = 11; w < balance.length; w++) {
            // beyond ±30 dB one side is silent; a change out there is not heard as movement
            double a = Math.max(-30, Math.min(30, balance[w - 1]));
            double b = Math.max(-30, Math.min(30, balance[w]));
            most = Math.max(most, Math.abs(b - a));
        }
        return most;
    }

    private static double lowestLoudness(double[] loudness) {
        double least = Double.MAX_VALUE;
        for (int w = 11; w < loudness.length; w++) {
            least = Math.min(least, loudness[w]);
        }
        return least;
    }

    static final IntFunction<SoundSourcePlacement> JUMP = frame -> frame < RATE ? LEFT : RIGHT;

    /** Dragging: the mouse sends a new place every 16 ms, from left to right in 0.8 s. */
    static final IntFunction<SoundSourcePlacement> DRAG = frame -> {
        int step = 706; // 16 ms
        double t = Math.max(0, Math.min(1, ((frame / step) * step - RATE) / (0.8 * RATE)));
        return LiveStage.between(LEFT, RIGHT, t);
    };

    /** A glide on the stage of 600 ms, in steps of 10 ms as {@link LiveStage#glide} hands them over. */
    static final IntFunction<SoundSourcePlacement> GLIDE = frame -> {
        int step = (int) (RATE * LiveStage.GLIDE_STEP_MS / 1000);
        double t = Math.max(0, Math.min(1, ((frame / step) * step - RATE) / (0.6 * RATE)));
        return LiveStage.between(LEFT, RIGHT, t);
    };

    @Test
    public void aJumpWithTheChannelGlideAloneIsACut() throws Exception {
        double[][] jump = travel(DRY, JUMP, 441);
        Assert.assertTrue("turn " + largestTurn(jump[0]), largestTurn(jump[0]) > 15);
    }

    @Test
    public void draggingMovesTheInstrumentLittleByLittleAtEvenLoudness() throws Exception {
        double[][] drag = travel(DRY, DRAG, 353);
        Assert.assertTrue("turn " + largestTurn(drag[0]), largestTurn(drag[0]) < 3);
        double end = drag[1][drag[1].length - 1];
        Assert.assertTrue("dip " + (lowestLoudness(drag[1]) - end), lowestLoudness(drag[1]) > end - 1.5);
    }

    @Test
    public void aGlideOnTheStageCrossesSmoothlyWithoutAHoleInTheMiddle() throws Exception {
        double[][] glide = travel(DRY, GLIDE, 441);
        Assert.assertTrue("turn " + largestTurn(glide[0]), largestTurn(glide[0]) < 3);
        double end = glide[1][glide[1].length - 1];
        Assert.assertTrue("dip " + (lowestLoudness(glide[1]) - end), lowestLoudness(glide[1]) > end - 1.5);
        // and it does arrive: the left side is silent at the end
        Assert.assertTrue("balance at the end " + glide[0][glide[0].length - 1], glide[0][glide[0].length - 1] > 15);
    }

    @Test
    public void glidesInARoomStayFreeOfJumpsToo() throws Exception {
        double[][] glide = travel(AcousticRoom.CHAMBER, GLIDE, 441);
        Assert.assertTrue("turn " + largestTurn(glide[0]), largestTurn(glide[0]) < 3);
    }

    @Test
    public void betweenIsSmoothAndEndsOnTheTarget() {
        Assert.assertSame(RIGHT, LiveStage.between(LEFT, RIGHT, 1));
        Assert.assertEquals(-1, LiveStage.between(LEFT, RIGHT, 0).x(), 0);
        Assert.assertEquals(0, LiveStage.between(LEFT, RIGHT, 0.5).x(), 1e-12);
        // slow at both ends
        Assert.assertTrue(LiveStage.between(LEFT, RIGHT, 0.05).x() + 1 < 0.1 * 0.5);
    }

    @Test
    public void glideReachesTheTargetOnTheWallClock() throws Exception {
        float[] noise = new float[RATE];
        try (LiveStage stage = LiveStage.of(List.of(new SpatialStemMixer.Signal(noise, noise, RATE)), List.of(LEFT), DRY,
                2)) {
            stage.glide(0, RIGHT, 100);
            long until = System.nanoTime() + 5_000_000_000L;
            while (stage.graph().channel(0).parameters().toLeft() > 1e-3 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            Assert.assertEquals(stage.graph().channel(0).parameters().toLeft(),
                    SpatialStemMixer.parameters(RIGHT, DRY, 2).toLeft(), 0f);
        }
    }
}
