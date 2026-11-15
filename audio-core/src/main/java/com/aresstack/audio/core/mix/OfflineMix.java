package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSink;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.dsp.PeakMeter;
import java.io.IOException;

/**
 * Renders a graph to a sink offline, with the loudness of the whole set as before
 * ({@link LegacyPeakNormalization}). The peak of the whole mix must be known before the first
 * sample is written; the graph is therefore rendered twice: once only to measure, once to
 * write. Both passes give the same samples, because the graph is deterministic. Memory stays
 * that of one block per channel, whatever the length of the piece.
 */
public final class OfflineMix {
    /** Makes a fresh graph, with its sources at the start. */
    @FunctionalInterface
    public interface GraphFactory {
        AudioSource create() throws IOException;
    }

    private OfflineMix() {
    }

    /**
     * Measures the graph, then renders it again scaled to the sink.
     *
     * @return the factor that was applied
     */
    public static float renderNormalized(GraphFactory graph, int sampleRate, int blockFrames, double masterDb,
                                         AudioSink sink) throws IOException {
        float peak = measure(graph, sampleRate, blockFrames);
        float gain = LegacyPeakNormalization.gain(peak, masterDb);
        AudioBlock block = new AudioBlock(blockFrames);
        try (AudioSource source = graph.create()) {
            source.prepare(sampleRate, blockFrames);
            int got;
            do {
                block.setFrames(blockFrames);
                got = source.read(block);
                if (got > 0) {
                    block.setFrames(got);
                    block.scale(gain);
                    sink.write(block);
                }
            } while (got == blockFrames);
        }
        return gain;
    }

    /** The highest absolute sample value of everything the graph delivers. */
    public static float measure(GraphFactory graph, int sampleRate, int blockFrames) throws IOException {
        AudioBlock block = new AudioBlock(blockFrames);
        PeakMeter meter = new PeakMeter();
        meter.prepare(sampleRate, blockFrames);
        try (AudioSource source = graph.create()) {
            source.prepare(sampleRate, blockFrames);
            int got;
            do {
                block.setFrames(blockFrames);
                got = source.read(block);
                block.setFrames(got);
                meter.process(block);
            } while (got == blockFrames);
        }
        return meter.peak();
    }
}
