package com.aresstack.audio.sounds;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

/** Render bounded game-event descriptions to mono 16-bit WAV without native dependencies. */
public final class SoundSynthesizer {
    private static final int SAMPLE_RATE = 16000;

    public void render(SoundDesign design, Path outputFile) throws IOException {
        int sampleCount = (int) ((long) design.durationMs() * SAMPLE_RATE / 1000);
        double[] mixed = new double[sampleCount];
        for (ToneEvent event : design.events()) {
            mixEvent(event, mixed);
        }
        writeWave(mixed, outputFile);
    }

    private void mixEvent(ToneEvent event, double[] mixed) {
        int start = (int) ((long) event.startMs() * SAMPLE_RATE / 1000);
        int length = (int) ((long) event.durationMs() * SAMPLE_RATE / 1000);
        double phase = 0;
        for (int i = 0; i < length && start + i < mixed.length; i++) {
            double progress = i / (double) length;
            double frequency = event.startHz() + (event.endHz() - event.startHz()) * progress;
            phase += frequency / SAMPLE_RATE;
            phase -= Math.floor(phase);
            double attack = event.attackMs() == 0 ? 1 : Math.min(1, i * 1000.0 / (event.attackMs() * SAMPLE_RATE));
            double release = event.releaseMs() == 0 ? 1 : Math.min(1,
                    (length - i) * 1000.0 / (event.releaseMs() * SAMPLE_RATE));
            double envelope = Math.max(0, Math.min(attack, release));
            mixed[start + i] += event.waveform().sample(phase) * event.volume() * envelope;
        }
    }

    private void writeWave(double[] samples, Path outputFile) throws IOException {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            double sample = Math.max(-1.0, Math.min(1.0, samples[i] * 0.65));
            short value = (short) Math.round(sample * 32767);
            pcm[2 * i] = (byte) value;
            pcm[2 * i + 1] = (byte) (value >>> 8);
        }
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, samples.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, outputFile.toFile());
        }
    }
}
