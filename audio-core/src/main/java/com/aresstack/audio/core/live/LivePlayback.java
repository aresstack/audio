package com.aresstack.audio.core.live;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.dsp.PeakMeter;
import com.aresstack.audio.core.mix.MasterBus;
import java.util.concurrent.locks.LockSupport;

/**
 * Live playback: one audio thread pulls block after block from a {@link LiveGraph}, through the
 * {@link MasterBus} and a {@link PeakMeter}, into a {@link LiveSink}. The sink's wait for room is
 * the clock; the thread never sleeps while it plays.
 *
 * <pre>
 * LiveGraph ─► MasterBus (gain, limiter) ─► PeakMeter ─► LiveSink
 * </pre>
 *
 * <p>Other threads steer it: {@link #play}, {@link #pause}, {@link #seek}, and they move the
 * channels of the graph directly ({@link LiveChannel#set}); a change is heard after the block that
 * is being rendered and what waits in the sink. The audio thread is the only one that touches the
 * graph, the master bus and the sink once playback is made; after {@link #start} its loop neither
 * allocates nor locks (rule 1 of the package {@code core}).
 *
 * <p>It measures itself (single writer, read from any thread without locks): how long a block
 * took to render at worst and on average, against the time the block lasts, and how often a block
 * took longer than that, which on a sound card would be a dropout. That is the measurement on
 * target hardware the architecture review asks for (risk "real time under Java").
 */
public final class LivePlayback implements AutoCloseable {
    /** Where playback stands. */
    public enum State {
        PAUSED, PLAYING, ENDED, FAILED, CLOSED
    }

    private final LiveGraph graph;
    private final MasterBus master;
    private final LiveSink sink;
    private final PeakMeter meter = new PeakMeter();
    private final int sampleRate;
    private final int blockFrames;
    private final long blockNanos;
    private final AudioBlock block;
    private final Thread thread;
    private volatile boolean wantPlaying;
    private volatile boolean flushWanted;
    private volatile boolean closing;
    private volatile State state = State.PAUSED;
    private volatile Throwable failure;
    private boolean outputRunning;
    // measurements, written only by the audio thread
    private volatile long blocks;
    private volatile long worstNanos;
    private volatile long totalNanos;
    private volatile long overruns;

    /**
     * Prepares graph, master bus and meter on the calling thread and makes the audio thread; it
     * starts paused at the first frame. Call {@link #start} next.
     */
    public LivePlayback(LiveGraph graph, MasterBus master, LiveSink sink, int sampleRate, int blockFrames) {
        if (graph == null || master == null || sink == null) {
            throw new IllegalArgumentException("Live playback needs a graph, a master bus and a sink.");
        }
        if (sampleRate <= 0 || blockFrames <= 0) {
            throw new IllegalArgumentException("Live playback needs a rate and a block size above 0.");
        }
        this.graph = graph;
        this.master = master;
        this.sink = sink;
        this.sampleRate = sampleRate;
        this.blockFrames = blockFrames;
        this.blockNanos = blockFrames * 1_000_000_000L / sampleRate;
        this.block = new AudioBlock(blockFrames);
        graph.prepare(sampleRate, blockFrames);
        master.prepare(sampleRate, blockFrames);
        meter.prepare(sampleRate, blockFrames);
        thread = new Thread(this::run, "live-playback");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
    }

    /** Starts the audio thread (paused). */
    public LivePlayback start() {
        thread.start();
        return this;
    }

    public LiveGraph graph() {
        return graph;
    }

    public MasterBus master() {
        return master;
    }

    /** The peaks of what goes to the sink, after the limiter. */
    public PeakMeter meter() {
        return meter;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int blockFrames() {
        return blockFrames;
    }

    /**
     * Plays from where playback stands; from the start if it had played to the end. The state is
     * {@link State#PLAYING} when this returns.
     */
    public void play() {
        if (state == State.CLOSED || state == State.FAILED) {
            return;
        }
        if (!graph.seekPending() && graph.position() >= graph.frames()) {
            graph.seek(0);
        }
        wantPlaying = true;
        // Playing from the caller's point of view at once, not only once the audio thread has woken up.
        state = State.PLAYING;
        LockSupport.unpark(thread);
    }

    /** Stops where the listener is; {@link #play} goes on there. */
    public void pause() {
        wantPlaying = false;
        LockSupport.unpark(thread);
    }

    /** Goes on at this frame; what waits in the sink is dropped, so the jump is heard at once. */
    public void seek(long frame) {
        graph.seek(frame);
        flushWanted = true;
        LockSupport.unpark(thread);
    }

    public boolean playing() {
        return state == State.PLAYING;
    }

    public State state() {
        return state;
    }

    /** What stopped the audio thread, if it failed. */
    public Throwable failure() {
        return failure;
    }

    /** The frame the listener hears now: what was rendered, less what waits in the sink and in the limiter. */
    public long heardFrame() {
        long at = graph.position() - sink.bufferedFrames() - master.latencyFrames();
        return Math.max(0, Math.min(graph.frames(), at));
    }

    public double heardSeconds() {
        return heardFrame() / (double) sampleRate;
    }

    /** How many blocks were rendered while playing. */
    public long blocks() {
        return blocks;
    }

    /** The longest time one block took to render, in nanoseconds. */
    public long worstBlockNanos() {
        return worstNanos;
    }

    /** The time one block lasts when played, in nanoseconds: the budget of the audio thread. */
    public long blockBudgetNanos() {
        return blockNanos;
    }

    /** How much of its budget the audio thread used on average, 0..1 (more means it cannot keep up). */
    public double averageLoad() {
        long count = blocks;
        return count == 0 ? 0 : totalNanos / (double) count / blockNanos;
    }

    /** How many blocks took longer to render than they last: dropouts on a sound card. */
    public long overruns() {
        return overruns;
    }

    /** Stops the audio thread and closes the sink and the graph's sources. */
    @Override
    public void close() {
        closing = true;
        wantPlaying = false;
        LockSupport.unpark(thread);
        if (thread.isAlive() && Thread.currentThread() != thread) {
            try {
                thread.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (!thread.isAlive() || Thread.currentThread() == thread) {
            try {
                sink.close();
            } catch (Exception ignored) {
                // closing anyway
            }
            try {
                graph.close();
            } catch (Exception ignored) {
                // closing anyway
            }
        }
        state = State.CLOSED;
    }

    private void run() {
        try {
            while (!closing) {
                if (flushWanted) {
                    flushWanted = false;
                    sink.flush();
                }
                if (!wantPlaying) {
                    if (outputRunning) {
                        stopOutput();
                    }
                    if (state == State.PLAYING) {
                        state = State.PAUSED;
                    }
                    LockSupport.parkNanos(this, 50_000_000L);
                    continue;
                }
                if (!outputRunning) {
                    sink.start();
                    outputRunning = true;
                    state = State.PLAYING;
                }
                long started = System.nanoTime();
                block.setFrames(blockFrames);
                int count = graph.read(block);
                block.setFrames(count);
                master.process(block);
                meter.process(block);
                long took = System.nanoTime() - started;
                measure(took);
                if (count > 0) {
                    sink.write(block);
                }
                if (count < blockFrames) {
                    finish();
                }
            }
        } catch (Throwable failed) {
            failure = failed;
            state = State.FAILED;
            wantPlaying = false;
            try {
                sink.pause();
                sink.flush();
            } catch (Throwable ignored) {
                // the failure above is what counts
            }
            return;
        }
        if (outputRunning) {
            sink.pause();
            sink.flush();
        }
    }

    /** Pauses the output where the listener is and lets the graph go on there later. */
    private void stopOutput() {
        long heard = heardFrame();
        sink.pause();
        sink.flush();
        graph.seek(heard);
        outputRunning = false;
    }

    /** The piece has ended: the limiter's look-ahead is pushed out with silence, the sink played out. */
    private void finish() {
        for (int rest = master.latencyFrames(); rest > 0; rest -= blockFrames) {
            block.setFrames(Math.min(blockFrames, rest));
            block.clear();
            master.process(block);
            meter.process(block);
            sink.write(block);
        }
        sink.drain();
        sink.pause();
        outputRunning = false;
        wantPlaying = false;
        state = State.ENDED;
    }

    private void measure(long took) {
        blocks = blocks + 1;
        totalNanos = totalNanos + took;
        if (took > worstNanos) {
            worstNanos = took;
        }
        if (took > blockNanos) {
            overruns = overruns + 1;
        }
    }
}
