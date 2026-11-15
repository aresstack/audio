package com.aresstack.audio.core.live;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.TestSignals;
import com.aresstack.audio.core.dsp.StereoPanner;
import com.aresstack.audio.core.mix.MasterBus;
import com.aresstack.audio.core.stream.ArraySource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

/**
 * Live playback with its own audio thread, into a sink that stands in for the sound card: it
 * plays to the end, pauses where the listener is, is steered from another thread while it plays,
 * and its loop allocates nothing.
 */
public final class LivePlaybackTest {
    private static final int RATE = 44100;
    private static final int BLOCK = 512;

    /** A sink that keeps what it gets and, if asked, takes as long as a sound card would. */
    private static final class Recorder implements LiveSink {
        final float[] left;
        final float[] right;
        final boolean realTime;
        volatile int written;
        volatile int started;
        volatile int paused;
        volatile long allocatedAtBlock50 = -1;
        volatile long allocatedAtBlock1000 = -1;
        int blocks;

        Recorder(int capacity, boolean realTime) {
            left = new float[capacity];
            right = new float[capacity];
            this.realTime = realTime;
        }

        @Override
        public void write(AudioBlock block) {
            int count = Math.min(block.frames(), left.length - written);
            System.arraycopy(block.left, 0, left, written, count);
            System.arraycopy(block.right, 0, right, written, count);
            written += count;
            blocks++;
            if (blocks == 50) {
                allocatedAtBlock50 = Allocation.sofar();
            } else if (blocks == 1000) {
                allocatedAtBlock1000 = Allocation.sofar();
            }
            if (realTime) {
                java.util.concurrent.locks.LockSupport.parkNanos(block.frames() * 1_000_000_000L / RATE);
            }
        }

        @Override
        public void start() {
            started++;
        }

        @Override
        public void pause() {
            paused++;
        }
    }

    private static LiveChannel tone(int frames, double position) {
        float[] sine = TestSignals.sine(frames, 330, RATE, 0.3);
        StereoPanner panner = new StereoPanner(position, 1);
        return new LiveChannel(new ArraySource(sine, sine.clone(), RATE), frames,
                ChannelParameters.builder().pan(panner.toLeft(), panner.toRight()).build(), 0, null);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long seconds) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                Assert.fail("timed out");
            }
            Thread.sleep(2);
        }
    }

    @Test
    public void playsToTheEndAndPushesOutTheLimiter() throws Exception {
        int frames = RATE / 2 + 123;
        LiveGraph graph = new LiveGraph(List.of(tone(frames, 0)), null, 0, frames);
        Recorder sink = new Recorder(3 * frames, false);
        MasterBus master = new MasterBus(MasterBus.Settings.fixed(0));
        try (LivePlayback playback = new LivePlayback(graph, master, sink, RATE, BLOCK).start()) {
            playback.play();
            waitFor(() -> playback.state() == LivePlayback.State.ENDED, 10);
            Assert.assertEquals(frames + master.latencyFrames(), sink.written);
            Assert.assertEquals(frames, graph.position());
            Assert.assertTrue(playback.blocks() > 0);
            Assert.assertTrue(playback.averageLoad() >= 0);
            // played to the end, it starts again from the beginning
            playback.play();
            waitFor(() -> sink.written >= 2 * frames, 10);
        }
    }

    @Test
    public void pauseStopsWhereTheListenerIsAndPlayGoesOnThere() throws Exception {
        int frames = 4 * RATE;
        LiveGraph graph = new LiveGraph(List.of(tone(frames, 0)), null, 0, frames);
        Recorder sink = new Recorder(2 * frames, true);
        try (LivePlayback playback = new LivePlayback(graph, new MasterBus(MasterBus.Settings.fixed(0)), sink, RATE,
                BLOCK).start()) {
            playback.play();
            waitFor(() -> sink.written > RATE / 4, 10);
            playback.pause();
            waitFor(() -> playback.state() == LivePlayback.State.PAUSED, 10);
            long at = playback.heardFrame();
            int written = sink.written;
            Thread.sleep(50);
            Assert.assertEquals("paused, nothing more is written", written, sink.written);
            Assert.assertTrue(sink.paused >= 1);
            Assert.assertTrue(at > 0 && at <= written);
            playback.play();
            waitFor(() -> sink.written > written + BLOCK, 10);
            Assert.assertEquals(2, sink.started);
        }
    }

    @Test
    public void aMoveFromAnotherThreadIsHeardAfterOneBlockAndAGlide() throws Exception {
        int frames = 4 * RATE;
        LiveChannel channel = tone(frames, -1);
        LiveGraph graph = new LiveGraph(List.of(channel), null, 0, frames);
        Recorder sink = new Recorder(2 * frames, true);
        StereoPanner right = new StereoPanner(1, 1);
        try (LivePlayback playback = new LivePlayback(graph, new MasterBus(MasterBus.Settings.fixed(0)), sink, RATE,
                BLOCK).start()) {
            playback.play();
            waitFor(() -> sink.written > RATE / 2, 10);
            int before = sink.written;
            channel.set(ChannelParameters.builder().pan(right.toLeft(), right.toRight()).build());
            // At most the block being rendered, the master's look-ahead and the glide later, only the right side sounds.
            int heard = before + 2 * BLOCK + playback.master().latencyFrames() + graph.glideFrames();
            waitFor(() -> sink.written > heard + BLOCK, 10);
            Assert.assertTrue("left still sounds", TestSignals.rms(sink.left, heard, heard + BLOCK) < 1e-4);
            Assert.assertTrue("right is silent", TestSignals.rms(sink.right, heard, heard + BLOCK) > 0.1);
            Assert.assertTrue("before the move only the left side sounded",
                    TestSignals.rms(sink.right, before - BLOCK, before) < 1e-4);
        }
    }

    @Test
    public void theLoopOfTheAudioThreadDoesNotAllocate() throws Exception {
        int frames = 60 * RATE;
        LiveChannel a = tone(frames, -0.5);
        LiveChannel b = tone(frames, 0.5);
        LiveGraph graph = new LiveGraph(List.of(a, b), null, 0, frames);
        Recorder sink = new Recorder(frames + 4 * BLOCK, false);
        ChannelParameters[] moves = new ChannelParameters[8];
        for (int i = 0; i < moves.length; i++) {
            StereoPanner panner = new StereoPanner(-1 + i / 4.0, 0.5f + i / 16f);
            moves[i] = ChannelParameters.builder().level(0.5f + i / 10f).pan(panner.toLeft(), panner.toRight()).build();
        }
        try (LivePlayback playback = new LivePlayback(graph, new MasterBus(MasterBus.Settings.fixed(-3)), sink, RATE,
                BLOCK).start()) {
            playback.play();
            int turn = 0;
            while (sink.allocatedAtBlock1000 < 0 && playback.state() != LivePlayback.State.ENDED) {
                (turn % 2 == 0 ? a : b).set(moves[turn % moves.length]);
                if (turn % 5 == 0) {
                    playback.master().setSettings(MasterBus.Settings.fixed(-(turn % 7)));
                }
                turn++;
                Thread.sleep(1);
            }
            Assert.assertTrue("moves while playing: " + turn, turn > 3);
            long bytes = sink.allocatedAtBlock1000 - sink.allocatedAtBlock50;
            Assert.assertTrue(bytes + " B over 950 blocks", bytes < 1024);
        }
    }
}
