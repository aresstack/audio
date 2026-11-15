package com.aresstack.audio.music.synth;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * The plain Java sound of the studio: a few sine partials per note, shaped by the role of the
 * part, and noise for drums. It knows neither arrangement files nor the model; whoever calls
 * it says where each note sounds in seconds. The reference renderer of this module and the
 * stem renderer of the studio both play through it, so that both sound alike to the sample.
 */
public final class BuiltinSynthesis {
    /** Samples per second of every stem the built-in sound writes (mono, 16 bit). */
    public static final int SAMPLE_RATE = 22050;
    /** What rings on after the end of a piece. */
    public static final double TAIL_SECONDS = 0.75;
    /** Changes whenever the sound changes for the same notes; the stem cache keys on it. */
    public static final String VERSION = "builtin-1";
    private static final double TWO_PI = Math.PI * 2.0;

    /**
     * One note as it sounds.
     *
     * @param start   where it begins, in seconds from the start of the piece
     * @param seconds how long it is held
     * @param seed    the tick it was written at; the noise of drums depends on it
     */
    public record Tone(double start, double seconds, int pitch, int velocity, long seed) {
    }

    private BuiltinSynthesis() {
    }

    /** The number of samples of a stem for a piece of this length, with the tail. */
    public static int frames(double pieceSeconds) {
        double frames = Math.ceil((pieceSeconds + TAIL_SECONDS) * SAMPLE_RATE);
        if (frames > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Piece too long for the built-in sound: " + pieceSeconds + " s");
        }
        return (int) frames;
    }

    /** The level a part of this role has in the mix of the built-in sound. */
    public static double gain(String role) {
        return "harmony".equals(role) ? 0.55 : "drums".equals(role) ? 0.67 : 0.75;
    }

    /** The samples of one part, its level already applied. */
    public static double[] part(String role, List<Tone> tones, int frames) {
        double[] samples = new double[frames];
        for (Tone tone : tones) {
            synthesize(role, tone, samples);
        }
        double gain = gain(role);
        for (int i = 0; i < frames; i++) {
            samples[i] *= gain;
        }
        return samples;
    }

    /** Adds one note to the samples. */
    public static void synthesize(String role, Tone tone, double[] samples) {
        double startSeconds = tone.start();
        double noteSeconds = tone.seconds();
        int first = (int) Math.round(startSeconds * SAMPLE_RATE);
        double frequency = 440.0 * Math.pow(2.0, (tone.pitch() - 69) / 12.0);
        double volume = (tone.velocity() / 127.0) * 0.22;
        double tail = "harmony".equals(role) ? 0.30 : 0.14;
        int count = Math.min(samples.length - first, (int) ((noteSeconds + tail) * SAMPLE_RATE));
        Random noise = new Random(tone.pitch() * 97L + tone.seed() * 31L);
        double previousNoise = 0;
        double kickPhase = 0;
        for (int i = 0; i < count; i++) {
            double t = i / (double) SAMPLE_RATE;
            double env = envelope(role, t, noteSeconds, tail);
            double value;
            if ("drums".equals(role)) {
                double white = noise.nextDouble() * 2.0 - 1.0;
                double highPassed = white - previousNoise * 0.85;
                previousNoise = white;
                if (tone.pitch() == 36) {
                    kickPhase += TWO_PI * (55 + 105 * Math.exp(-t * 22)) / SAMPLE_RATE;
                    value = Math.sin(kickPhase) * Math.exp(-t * 19);
                } else if (tone.pitch() == 38) {
                    value = (highPassed * 0.75 + Math.sin(TWO_PI * 175 * t) * 0.25)
                            * Math.exp(-t * 18);
                } else {
                    value = highPassed * Math.exp(-t * 45) * 0.43;
                }
                env = 1.0;
            } else if ("melody".equals(role)) {
                value = (Math.sin(TWO_PI * frequency * t) * 0.70
                        + Math.sin(TWO_PI * frequency * 2.01 * t) * 0.22
                        + Math.sin(TWO_PI * frequency * 3.98 * t) * 0.08);
                env *= Math.exp(-t * 2.2);
            } else if ("harmony".equals(role)) {
                value = Math.sin(TWO_PI * frequency * t) * 0.71
                        + Math.sin(TWO_PI * frequency * 2.003 * t) * 0.18
                        + Math.sin(TWO_PI * frequency * 0.999 * t + 0.4) * 0.11;
            } else {
                value = Math.sin(TWO_PI * frequency * t) * 0.80
                        + Math.sin(TWO_PI * frequency * 2 * t) * 0.20;
            }
            samples[first + i] += value * volume * env;
        }
    }

    private static double envelope(String role, double t, double duration, double tail) {
        double attack = "harmony".equals(role) ? 0.15 : 0.012;
        double value = Math.min(1.0, t / attack);
        if (t > duration) {
            value *= Math.max(0.0, 1.0 - (t - duration) / tail);
        }
        return value;
    }

    /** Writes mono samples as 16-bit WAV; louder than 0.93 is turned down to that peak. */
    public static void writeWave(Path output, double[] samples) throws IOException {
        double peak = 0;
        for (double sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double gain = peak > 0.93 ? 0.93 / peak : 1.0;
        byte[] data = new byte[Math.multiplyExact(samples.length, 2)];
        for (int i = 0; i < samples.length; i++) {
            double sample = Math.max(-1.0, Math.min(1.0, samples[i] * gain));
            short pcm = (short) Math.round(sample * 32767.0);
            data[i * 2] = (byte) pcm;
            data[i * 2 + 1] = (byte) (pcm >> 8);
        }
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (ByteArrayInputStream input = new ByteArrayInputStream(data);
             AudioInputStream audio = new AudioInputStream(input, format, samples.length)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
    }
}
