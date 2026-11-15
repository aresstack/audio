package com.aresstack.audio.core.live;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.TestSignals;
import com.aresstack.audio.core.dsp.EarlyReflections;
import com.aresstack.audio.core.dsp.RoomReverb;
import com.aresstack.audio.core.dsp.StereoBiquad;
import com.aresstack.audio.core.dsp.StereoPanner;
import com.aresstack.audio.core.dsp.StereoStrip;
import com.aresstack.audio.core.mix.MixGraph;
import com.aresstack.audio.core.mix.StemChannel;
import com.aresstack.audio.core.stream.ArraySource;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * The live graph (work package D2, acceptance 7.2): with parameters that do not change it is the
 * file mix, sample for sample; a parameter change never makes the signal jump; the audio thread
 * never allocates, also not while channels are moved; a jump goes on at the right frame.
 */
public final class LiveGraphTest {
    private static final int RATE = 44100;
    private static final BiquadCoefficients WALLS = BiquadCoefficients.lowPass(RATE, 6000, Math.sqrt(0.5));
    private static final RoomReverb.Settings ROOM = new RoomReverb.Settings(1.6, 0.4, 0.02);

    private static ChannelParameters placed(double position, float distanceGain, double shelfDb, boolean curve,
                                            int echo) {
        StereoPanner panner = new StereoPanner(position, distanceGain);
        return ChannelParameters.builder()
                .strip(0.8f, 0.6f, curve ? frame -> (float) Math.pow(10, -6 * Math.sin(frame * 3e-5) / 20) : null)
                .pan(panner.toLeft(), panner.toRight())
                .send(0.3f * (float) (1 - 0.35 * position), 0.3f * (float) (1 + 0.35 * position))
                .shelf(shelfDb < -0.2 ? BiquadCoefficients.highShelf(RATE, 4500, shelfDb, 1) : null)
                .echoes(new int[]{echo, echo * 2, 1700}, new float[]{0.2f, 0.05f, 0.1f}, new float[]{0.05f, 0.2f, 0.1f})
                .build();
    }

    private static final ChannelParameters[] STAGE = {
            placed(-0.7, 0.9f, -3, true, 420),
            placed(0.2, 0.7f, -0.1, false, 900),
            placed(0.9, 0.5f, -7, true, 260),
    };

    private static float[][] sound(int index, int frames) {
        return new float[][]{TestSignals.noise(frames, 10 + index, 0.4), TestSignals.noise(frames, 20 + index, 0.4)};
    }

    private static MixGraph offline(int frames, long total) {
        List<StemChannel> channels = new ArrayList<StemChannel>();
        for (int i = 0; i < STAGE.length; i++) {
            ChannelParameters p = STAGE[i];
            float[][] sound = sound(i, frames - i * 1000);
            List<EarlyReflections.Tap> taps = new ArrayList<EarlyReflections.Tap>();
            for (int wall = 0; wall < p.echoes(); wall++) {
                taps.add(new EarlyReflections.Tap(p.echoDelay(wall), p.echoLeft(wall), p.echoRight(wall)));
            }
            channels.add(new StemChannel(new ArraySource(sound[0], sound[1], RATE), frames,
                    new StereoStrip(new StereoStrip.Settings(p.level(), p.width(), p.curve())), p.sendLeft(),
                    p.sendRight(), p.shelf() == null ? null : new StereoBiquad(p.shelf()),
                    StereoPanner.ofGains(p.toLeft(), p.toRight()), new EarlyReflections(taps, WALLS)));
        }
        return new MixGraph(channels, new RoomReverb(ROOM), 0.35f, total);
    }

    private static LiveGraph live(int frames, long total, double glideMs) {
        List<LiveChannel> channels = new ArrayList<LiveChannel>();
        for (int i = 0; i < STAGE.length; i++) {
            float[][] sound = sound(i, frames - i * 1000);
            channels.add(new LiveChannel(new ArraySource(sound[0], sound[1], RATE), frames, STAGE[i], 4000, WALLS));
        }
        return new LiveGraph(channels, new RoomReverb(ROOM), 0.35f, total, glideMs);
    }

    private static float[][] drain(com.aresstack.audio.core.AudioSource graph, int total, int blockFrames)
            throws Exception {
        float[] left = new float[total];
        float[] right = new float[total];
        AudioBlock block = new AudioBlock(blockFrames);
        graph.prepare(RATE, blockFrames);
        int at = 0;
        int got;
        do {
            block.setFrames(blockFrames);
            got = graph.read(block);
            System.arraycopy(block.left, 0, left, at, got);
            System.arraycopy(block.right, 0, right, at, got);
            at += got;
        } while (got == blockFrames);
        return new float[][]{left, right};
    }

    @Test
    public void withoutChangesTheLiveGraphIsTheFileMixSampleForSample() throws Exception {
        int frames = 3 * RATE;
        int total = frames + RATE;
        float[][] reference = drain(offline(frames, total), total, 512);
        for (int block : new int[]{64, 512, 777}) {
            float[][] live = drain(live(frames, total, LiveGraph.DEFAULT_GLIDE_MS), total, block);
            Assert.assertArrayEquals("left, block " + block, reference[0], live[0], 0f);
            Assert.assertArrayEquals("right, block " + block, reference[1], live[1], 0f);
        }
    }

    /** The largest difference of two neighbouring samples on either side, from {@code from} on. */
    private static float largestStep(float[][] sound, int from) {
        float most = 0;
        for (float[] side : sound) {
            for (int i = Math.max(1, from); i < side.length; i++) {
                most = Math.max(most, Math.abs(side[i] - side[i - 1]));
            }
        }
        return most;
    }

    /** A low sine on one channel, moved from hard left and near to hard right, far, muted and back, mid-block. */
    private static float[][] moved(double glideMs) throws Exception {
        int frames = 2 * RATE;
        float[] tone = TestSignals.sine(frames, 110, RATE, 0.5);
        ChannelParameters left = placed(-1, 1f, -0.1, false, 300);
        ChannelParameters right = placed(1, 0.6f, -9, false, 2900);
        ChannelParameters silent = right.toBuilder().level(0).build();
        LiveChannel channel = new LiveChannel(new ArraySource(tone, tone.clone(), RATE), frames, left, 4000, WALLS);
        LiveGraph graph = new LiveGraph(List.of(channel), null, 0, frames, glideMs);
        float[][] out = new float[][]{new float[frames], new float[frames]};
        AudioBlock block = new AudioBlock(300);
        graph.prepare(RATE, 300);
        int at = 0;
        int got;
        int step = 0;
        do {
            if (at > step * RATE / 4 && step < 6) {
                channel.set(step % 3 == 0 ? right : step % 3 == 1 ? silent : left);
                step++;
            }
            block.setFrames(300);
            got = graph.read(block);
            System.arraycopy(block.left, 0, out[0], at, got);
            System.arraycopy(block.right, 0, out[1], at, got);
            at += got;
        } while (got == 300);
        return out;
    }

    @Test
    public void aParameterChangeNeverMakesTheSignalJump() throws Exception {
        // A 110 Hz sine of 0.5 moves by at most 2π·110/44100·0.5 ≈ 0.0078 per sample; with the panner's
        // +3 dB at the side and the echoes on top that is about 0.016. Switched without a glide, hard left
        // to hard right is a jump of up to 0.7.
        float glided = largestStep(moved(LiveGraph.DEFAULT_GLIDE_MS), 0);
        float switched = largestStep(moved(0), 0);
        Assert.assertTrue("glided " + glided, glided < 0.03f);
        Assert.assertTrue("switched " + switched + " (the test must be able to see a jump)", switched > 0.2f);
    }

    @Test
    public void theAudioThreadDoesNotAllocateWhileChannelsAreMoved() throws Exception {
        int frames = 30 * RATE;
        LiveGraph graph = live(frames, frames, LiveGraph.DEFAULT_GLIDE_MS);
        // The parameters are made beforehand, on "the event thread"; the audio thread only takes them.
        ChannelParameters[] moves = new ChannelParameters[16];
        for (int i = 0; i < moves.length; i++) {
            moves[i] = placed(-1 + i / 8.0, 0.5f + i / 40f, -i * 0.6, i % 2 == 0, 200 + i * 150);
        }
        AudioBlock block = new AudioBlock(512);
        graph.prepare(RATE, 512);
        int[] turn = {0};
        Allocation.ThrowingRunnable play = () -> {
            for (int b = 0; b < 500; b++) {
                if (b % 7 == 0) {
                    graph.channel(b % 3).set(moves[turn[0]++ % moves.length]);
                }
                if (b % 50 == 0) {
                    graph.setRoomGain(b % 100 == 0 ? 0.1f : 0.5f);
                }
                if (b == 250) {
                    graph.seek(RATE);
                }
                block.setFrames(512);
                graph.read(block);
            }
        };
        // Rarely taken branches (a seek, a glide that starts) are compiled late; until then the JIT may
        // deoptimize, which counts a few bytes on this thread. Measured is the run after that.
        Allocation.of(play);
        long bytes = Allocation.of(play);
        Assert.assertTrue(bytes + " B over 500 blocks", bytes < 256);
    }

    @Test
    public void aSeekGoesOnAtThatFrame() throws Exception {
        int frames = RATE;
        float[] left = TestSignals.noise(frames, 5, 0.5);
        float[] right = TestSignals.noise(frames, 6, 0.5);
        ChannelParameters dry = ChannelParameters.builder().strip(1, 1, null).pan(1, 1).build();
        LiveGraph graph = new LiveGraph(List.of(new LiveChannel(new ArraySource(left, right, RATE), frames, dry, 0,
                null)), null, 0, frames);
        AudioBlock block = new AudioBlock(256);
        graph.prepare(RATE, 256);
        block.setFrames(256);
        graph.read(block);
        graph.seek(30000);
        block.setFrames(256);
        graph.read(block);
        for (int i = 0; i < 256; i++) {
            // width 1 folds to middle and side and back: equal up to the rounding of float
            Assert.assertEquals(left[30000 + i], block.left[i], 1e-7f);
            Assert.assertEquals(right[30000 + i], block.right[i], 1e-7f);
        }
        Assert.assertEquals(30256, graph.position());
    }

    @Test
    public void aMovedChannelIsHeardWithinOneGlide() throws Exception {
        int frames = RATE;
        float[] tone = TestSignals.sine(frames, 440, RATE, 0.5);
        StereoPanner hardLeft = new StereoPanner(-1, 1);
        StereoPanner hardRight = new StereoPanner(1, 1);
        LiveChannel channel = new LiveChannel(new ArraySource(tone, tone.clone(), RATE), frames,
                ChannelParameters.builder().pan(hardLeft.toLeft(), hardLeft.toRight()).build(), 0, null);
        LiveGraph graph = new LiveGraph(List.of(channel), null, 0, frames);
        AudioBlock block = new AudioBlock(512);
        graph.prepare(RATE, 512);
        for (int b = 0; b < 10; b++) {
            block.setFrames(512);
            graph.read(block);
        }
        Assert.assertEquals(0, TestSignals.rms(block.right, 0, 512), 1e-6);
        channel.set(ChannelParameters.builder().pan(hardRight.toLeft(), hardRight.toRight()).build());
        // The block right after the move already turns over; after the glide (20 ms = 882 frames) only the right side sounds.
        block.setFrames(512);
        graph.read(block);
        Assert.assertTrue(TestSignals.rms(block.right, 256, 512) > 0.05);
        block.setFrames(512);
        graph.read(block);
        Assert.assertEquals(0, TestSignals.rms(block.left, 400, 512), 1e-6);
        Assert.assertTrue(channel.settled());
    }

    @Test
    public void echoDelaysThatChangeFasterThanAFadeNeverJump() throws Exception {
        // Dragging sends a new place every block, faster than the 20 ms fade of a changed echo delay.
        int frames = RATE;
        float[] tone = TestSignals.sine(frames, 110, RATE, 0.5);
        ChannelParameters base = ChannelParameters.builder().pan(0, 0)
                .echoes(new int[]{100}, new float[]{1f}, new float[]{1f}).build();
        LiveChannel channel = new LiveChannel(new ArraySource(tone, tone.clone(), RATE), frames, base, 4000, WALLS);
        LiveGraph graph = new LiveGraph(List.of(channel), null, 0, frames);
        int[] delays = {300, 301, 900, 120, 2000, 2001, 50};
        float[] out = new float[frames];
        AudioBlock block = new AudioBlock(512);
        graph.prepare(RATE, 512);
        int at = 0;
        int got;
        int turn = 0;
        do {
            if (at > RATE / 4 && turn < delays.length) {
                channel.set(base.toBuilder().echoes(new int[]{delays[turn++]}, new float[]{1f}, new float[]{1f}).build());
            }
            block.setFrames(512);
            got = graph.read(block);
            System.arraycopy(block.left, 0, out, at, got);
            at += got;
        } while (got == 512);
        float most = largestStep(new float[][]{out}, 4096);
        // the echo is the low-passed sine: it moves by at most about 0.008 per sample
        Assert.assertTrue("largest step " + most, most < 0.012f);
    }

    @Test
    public void aSeekSilencesTheRoomOfThePassageLeftBehind() throws Exception {
        int frames = 2 * RATE;
        float[] left = new float[frames];
        left[100] = 1;
        ChannelParameters loud = ChannelParameters.builder().pan(0, 0).send(1, 1).build();
        LiveGraph graph = new LiveGraph(List.of(new LiveChannel(new ArraySource(left, left.clone(), RATE), frames,
                loud, 0, null)), new RoomReverb(ROOM), 1f, frames);
        AudioBlock block = new AudioBlock(512);
        graph.prepare(RATE, 512);
        float before = 0;
        for (int b = 0; b < 20; b++) {
            block.setFrames(512);
            graph.read(block);
            for (int i = 0; i < 512; i++) {
                before = Math.max(before, Math.abs(block.left[i]));
            }
        }
        Assert.assertTrue("the room rings " + before, before > 1e-3);
        graph.seek(RATE);
        block.setFrames(512);
        graph.read(block);
        for (int i = 0; i < 512; i++) {
            Assert.assertEquals(0f, block.left[i], 0f);
            Assert.assertEquals(0f, block.right[i], 0f);
        }
    }

    @Test
    public void aLoopStartsAgainWithoutAGap() throws Exception {
        int frames = 1000;
        float[] ramp = new float[frames];
        for (int i = 0; i < frames; i++) {
            ramp[i] = 0.1f + i * 0.0005f;
        }
        LiveGraph graph = new LiveGraph(List.of(new LiveChannel(new ArraySource(ramp, ramp.clone(), RATE), frames,
                ChannelParameters.builder().build(), 0, null)), null, 0, frames + 500);
        graph.setLoop(frames);
        AudioBlock block = new AudioBlock(300);
        graph.prepare(RATE, 300);
        float[] out = new float[3600];
        for (int at = 0; at < out.length; at += 300) {
            block.setFrames(300);
            Assert.assertEquals("a loop never ends", 300, graph.read(block));
            System.arraycopy(block.left, 0, out, at, 300);
        }
        for (int i = 0; i < out.length; i++) {
            Assert.assertEquals("frame " + i, ramp[i % frames], out[i], 1e-6f);
        }
        graph.setLoop(0);
        int got;
        long total = graph.position();
        do {
            block.setFrames(300);
            got = graph.read(block);
            total += got;
        } while (got == 300);
        Assert.assertEquals("without the loop it ends with the ring", frames + 500, total);
    }

    @Test
    public void smallEchoDelayChangesGlideAndDoNotCancelATone() throws Exception {
        // A tone and one strong echo; the echo's delay creeps by 2 frames every 10 ms, as when dragging.
        // Gliding the delay moves the comb of tone and echo smoothly; it never mixes the echo with a
        // shifted copy of itself, so the level only follows the slowly moving interference.
        int frames = 2 * RATE;
        float[] tone = TestSignals.sine(frames, 330, RATE, 0.3);
        ChannelParameters base = ChannelParameters.builder().pan(0, 0)
                .echoes(new int[]{500}, new float[]{0.5f}, new float[]{0.5f}).build();
        LiveChannel channel = new LiveChannel(new ArraySource(tone, tone.clone(), RATE), frames, base, 4000, WALLS);
        LiveGraph graph = new LiveGraph(List.of(channel), null, 0, frames);
        AudioBlock block = new AudioBlock(441);
        graph.prepare(RATE, 441);
        float[] out = new float[frames];
        for (int at = 0, step = 0; at + 441 <= frames; at += 441, step++) {
            channel.set(base.toBuilder().echoes(new int[]{500 + 2 * step}, new float[]{0.5f}, new float[]{0.5f}).build());
            block.setFrames(441);
            graph.read(block);
            System.arraycopy(block.left, 0, out, at, 441);
        }
        // level per 5 ms against its neighbours: no short holes
        double[] level = new double[frames / 220];
        for (int w = 0; w < level.length; w++) {
            level[w] = 10 * Math.log10(TestSignals.rms(out, w * 220, (w + 1) * 220) + 1e-9) * 2;
        }
        for (int w = 20; w < level.length - 2; w++) {
            double around = (level[w - 2] + level[w + 2]) / 2;
            Assert.assertTrue("hole of " + (level[w] - around) + " dB at window " + w, level[w] > around - 1);
        }
    }
}
