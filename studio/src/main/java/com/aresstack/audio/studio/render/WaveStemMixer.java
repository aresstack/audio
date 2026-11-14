package com.aresstack.audio.studio.render;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Resample and mix independently rendered instrumental WAVs to normalized stereo PCM. */
public final class WaveStemMixer {
    private static final int TARGET_RATE = 44100;
    private static final int MAX_SECONDS = 180;

    public void mix(List<Path> files, Path output) throws Exception {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No audio stems provided.");
        }
        List<Stem> stems = new ArrayList<Stem>();
        int frames = 0;
        for (Path file : files) {
            Stem stem = read(file);
            stems.add(stem);
            frames = Math.max(frames, (int) Math.ceil(stem.length() * (double) TARGET_RATE / stem.sampleRate));
        }
        if (frames > TARGET_RATE * MAX_SECONDS) {
            throw new IOException("Audio mix exceeds three minutes.");
        }
        double[] left = new double[frames];
        double[] right = new double[frames];
        for (Stem stem : stems) {
            for (int i = 0; i < frames; i++) {
                double samplePosition = i * stem.sampleRate / (double) TARGET_RATE;
                int index = (int) samplePosition;
                if (index < stem.length()) {
                    double fraction = samplePosition - index;
                    int following = Math.min(index + 1, stem.length() - 1);
                    left[i] += interpolate(stem.left[index], stem.left[following], fraction);
                    right[i] += interpolate(stem.right[index], stem.right[following], fraction);
                }
            }
        }
        double peak = 0;
        for (int i = 0; i < frames; i++) {
            peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
        }
        double gain = peak > 0 ? Math.min(1.0, 0.92 / peak) : 1.0;
        byte[] data = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            put16(data, i * 4, left[i] * gain);
            put16(data, i * 4 + 2, right[i] * gain);
        }
        AudioFormat format = new AudioFormat(TARGET_RATE, 16, 2, true, false);
        try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(data), format, frames)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
    }

    private static double interpolate(double first, double second, double fraction) {
        return first + (second - first) * fraction;
    }

    private static void put16(byte[] data, int offset, double value) {
        short sample = (short) Math.round(Math.max(-1, Math.min(1, value)) * 32767);
        data[offset] = (byte) sample;
        data[offset + 1] = (byte) (sample >>> 8);
    }

    private static Stem read(Path file) throws Exception {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat format = input.getFormat();
            int channels = format.getChannels();
            int bits = format.getSampleSizeInBits();
            if (channels < 1 || channels > 2 || bits != 16
                    || !AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())) {
                // SFZ samplers can output float or 24-bit WAV; request PCM16 conversion when supported.
                AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        format.getSampleRate(), 16, channels, channels * 2,
                        format.getSampleRate(), false);
                if (!AudioSystem.isConversionSupported(target, format)) {
                    throw new IOException("Unsupported WAV sample format for " + file + ": " + format);
                }
                try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, input)) {
                    return readPcm(converted, target);
                }
            }
            return readPcm(input, format);
        }
    }

    private static Stem readPcm(AudioInputStream audio, AudioFormat format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = audio.read(block)) != -1) {
            output.write(block, 0, count);
            if (output.size() > 44100 * MAX_SECONDS * 8) {
                throw new IOException("WAV stem exceeds size limit.");
            }
        }
        byte[] data = output.toByteArray();
        int channels = format.getChannels();
        int frameSize = format.getFrameSize();
        int frames = data.length / frameSize;
        double[] left = new double[frames];
        double[] right = new double[frames];
        for (int i = 0; i < frames; i++) {
            int offset = i * frameSize;
            left[i] = sample(data, offset, format.isBigEndian());
            right[i] = channels == 2 ? sample(data, offset + 2, format.isBigEndian()) : left[i];
        }
        return new Stem(left, right, format.getSampleRate());
    }

    private static double sample(byte[] data, int offset, boolean bigEndian) {
        int low = data[bigEndian ? offset + 1 : offset] & 255;
        int high = data[bigEndian ? offset : offset + 1];
        short value = (short) ((high << 8) | low);
        return value / 32768.0;
    }

    private static final class Stem {
        private final double[] left;
        private final double[] right;
        private final float sampleRate;

        private Stem(double[] left, double[] right, float sampleRate) {
            this.left = left;
            this.right = right;
            this.sampleRate = sampleRate;
        }

        private int length() {
            return left.length;
        }
    }
}
