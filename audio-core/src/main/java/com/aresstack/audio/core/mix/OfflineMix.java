package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSink;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.dsp.LoudnessMeter;
import com.aresstack.audio.core.dsp.PeakMeter;
import java.io.IOException;

/**
 * Renders a graph to a sink offline, in one of the two {@link LevelMode level modes}.
 *
 * <p>{@link LevelMode#LEGACY_PEAK} ({@link #renderNormalized}) sets the loudness of the whole as
 * before ({@link LegacyPeakNormalization}). The peak of the whole mix must be known before the
 * first sample is written; the graph is therefore rendered twice: once only to measure, once to
 * write. Both passes give the same samples, because the graph is deterministic.
 *
 * <p>{@link LevelMode#FIXED} ({@link #renderFixed}) runs the graph through the same
 * {@link MasterBus} as live playback, with its latency compensated so the file lies frame for
 * frame where the graph lies. Without {@link ExportNormalization} this takes one pass; with it,
 * a first pass measures what comes out of the limiter and the second writes it scaled.
 *
 * <p>Memory stays that of one block per channel, whatever the length of the piece.
 */
public final class OfflineMix {
    /** Makes a fresh graph, with its sources at the start. Each call must give the same samples. */
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

    /** What a mix in {@link LevelMode#FIXED} measured and applied. */
    public static final class Result {
        private final float peakBeforeNormalization;
        private final double loudnessBeforeNormalization;
        private final double normalizationGain;
        private final float lowestLimiterGain;

        Result(float peak, double loudness, double gain, float lowestLimiterGain) {
            this.peakBeforeNormalization = peak;
            this.loudnessBeforeNormalization = loudness;
            this.normalizationGain = gain;
            this.lowestLimiterGain = lowestLimiterGain;
        }

        /** The highest sample after the limiter, before the normalization; 0 if not measured. */
        public float peakBeforeNormalization() {
            return peakBeforeNormalization;
        }

        /** The integrated loudness after the limiter, before the normalization, in LUFS; NaN if not measured. */
        public double loudnessBeforeNormalization() {
            return loudnessBeforeNormalization;
        }

        /** The factor of the normalization; 1 without one. */
        public double normalizationGain() {
            return normalizationGain;
        }

        /** The lowest gain of the limiter in the pass that was written; 1 means it never acted. */
        public float lowestLimiterGain() {
            return lowestLimiterGain;
        }
    }

    /**
     * Renders the graph through a fresh {@link MasterBus} with these settings and, if asked,
     * normalizes the export at the very end.
     */
    public static Result renderFixed(GraphFactory graph, int sampleRate, int blockFrames, MasterBus.Settings master,
                                     ExportNormalization normalization, AudioSink sink) throws IOException {
        if (master == null || normalization == null || sink == null) {
            throw new IllegalArgumentException("A fixed mix needs master settings, a normalization and a sink.");
        }
        float peak = 0;
        double loudness = Double.NaN;
        double gain = 1;
        if (normalization.kind() != ExportNormalization.Kind.NONE) {
            PeakMeter meter = new PeakMeter();
            LoudnessMeter loudnessMeter = new LoudnessMeter();
            meter.prepare(sampleRate, blockFrames);
            loudnessMeter.prepare(sampleRate, blockFrames);
            boolean wantsLoudness = normalization.kind() == ExportNormalization.Kind.LOUDNESS;
            pull(new MasteredSource(graph.create(), new MasterBus(master)), sampleRate, blockFrames, block -> {
                meter.process(block);
                if (wantsLoudness) {
                    loudnessMeter.process(block);
                }
            });
            peak = meter.peak();
            loudness = wantsLoudness ? loudnessMeter.integrated() : Double.NaN;
            gain = normalization.gain(peak, loudness);
        }
        float factor = (float) gain;
        MasterBus bus = new MasterBus(master);
        pull(new MasteredSource(graph.create(), bus), sampleRate, blockFrames, block -> {
            if (factor != 1f) {
                block.scale(factor);
            }
            sink.write(block);
        });
        return new Result(peak, loudness, gain, bus.limiter().lowestGain());
    }

    /** Takes each block that comes out of a source. */
    @FunctionalInterface
    private interface BlockConsumer {
        void accept(AudioBlock block) throws IOException;
    }

    private static void pull(AudioSource source, int sampleRate, int blockFrames, BlockConsumer consumer)
            throws IOException {
        AudioBlock block = new AudioBlock(blockFrames);
        try (AudioSource open = source) {
            open.prepare(sampleRate, blockFrames);
            int got;
            do {
                block.setFrames(blockFrames);
                got = open.read(block);
                if (got > 0) {
                    block.setFrames(got);
                    consumer.accept(block);
                }
            } while (got == blockFrames);
        }
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
