package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioProcessor;
import com.aresstack.audio.core.AudioSource;
import java.io.IOException;

/**
 * A source run through a processor with latency, compensated for the offline mix: the first
 * {@code latency} frames that come out are dropped, and after the source has ended as many
 * frames of silence are pushed in. What comes out is exactly as long as the source and lies
 * frame for frame where the source lies. Live playback does not do this; there the whole master
 * is simply late by the latency.
 */
public final class MasteredSource implements AudioSource {
    private final AudioSource source;
    private final AudioProcessor processor;
    private AudioBlock work;
    private int skip;
    private int flush;
    private boolean sourceEnded;
    private int readyFrom;
    private int readyTo;

    public MasteredSource(AudioSource source, AudioProcessor processor) {
        if (source == null || processor == null) {
            throw new IllegalArgumentException("A mastered source needs a source and a processor.");
        }
        this.source = source;
        this.processor = processor;
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        source.prepare(sampleRate, maxBlockFrames);
        processor.prepare(sampleRate, maxBlockFrames);
        work = new AudioBlock(maxBlockFrames);
        skip = processor.latencyFrames();
        flush = skip;
        sourceEnded = false;
        readyFrom = 0;
        readyTo = 0;
    }

    @Override
    public int read(AudioBlock block) throws IOException {
        int wanted = block.frames();
        int out = 0;
        while (out < wanted) {
            if (readyFrom == readyTo && !produce(wanted - out)) {
                break;
            }
            int count = Math.min(readyTo - readyFrom, wanted - out);
            System.arraycopy(work.left, readyFrom, block.left, out, count);
            System.arraycopy(work.right, readyFrom, block.right, out, count);
            readyFrom += count;
            out += count;
        }
        block.clear(out, wanted - out);
        return out;
    }

    /** Runs up to {@code chunk} frames through the processor; false when nothing is left. */
    private boolean produce(int chunk) throws IOException {
        int input;
        if (!sourceEnded) {
            work.setFrames(chunk);
            int got = source.read(work);
            input = got;
            if (got < chunk) {
                sourceEnded = true;
                int silence = Math.min(chunk - got, flush);
                work.clear(got, chunk - got);
                flush -= silence;
                input += silence;
            }
        } else {
            input = Math.min(chunk, flush);
            flush -= input;
            work.clear(0, input);
        }
        if (input == 0) {
            return false;
        }
        work.setFrames(input);
        processor.process(work);
        int dropped = Math.min(skip, input);
        skip -= dropped;
        readyFrom = dropped;
        readyTo = input;
        return true;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
