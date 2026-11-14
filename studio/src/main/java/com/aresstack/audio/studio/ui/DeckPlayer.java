package com.aresstack.audio.studio.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plays one WAV file and knows what it looks like: its loudness in hundredths of a
 * second, for the waveform and for the level meters. Used from the event thread.
 */
final class DeckPlayer {
    /** How many times a second the loudness of a file is measured. */
    static final int WINDOWS_PER_SECOND = 100;

    /** The loudness of a file over time, left and right, each value between 0 and 1. */
    static final class Envelope {
        final float[] left;
        final float[] right;
        final double seconds;

        Envelope(float[] left, float[] right, double seconds) {
            this.left = left;
            this.right = right;
            this.seconds = seconds;
        }

        /** The loudest of both sides around a moment, for a meter that follows the playback. */
        float at(double second, boolean rightSide) {
            float[] side = rightSide ? right : left;
            int index = (int) (second * WINDOWS_PER_SECOND);
            float loudest = 0f;
            for (int i = Math.max(0, index - 2); i <= index && i < side.length; i++) {
                loudest = Math.max(loudest, side[i]);
            }
            return loudest;
        }
    }

    private Clip clip;
    private Envelope envelope;
    private Path file;

    /** Measures a WAV file; {@code null} if it is missing or cannot be read. */
    static Envelope measure(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (AudioInputStream original = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat source = original.getFormat();
            AudioFormat plain = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.getSampleRate(), 16,
                    source.getChannels(), source.getChannels() * 2, source.getSampleRate(), false);
            try (AudioInputStream audio = AudioSystem.getAudioInputStream(plain, original)) {
                int channels = plain.getChannels();
                int perWindow = Math.max(1, Math.round(plain.getSampleRate() / WINDOWS_PER_SECOND));
                java.util.ArrayList<float[]> windows = new java.util.ArrayList<float[]>();
                byte[] buffer = new byte[perWindow * channels * 2];
                long frames = 0;
                while (true) {
                    int filled = 0;
                    while (filled < buffer.length) {
                        int read = audio.read(buffer, filled, buffer.length - filled);
                        if (read < 0) {
                            break;
                        }
                        filled += read;
                    }
                    int complete = filled / (channels * 2);
                    if (complete == 0) {
                        break;
                    }
                    float left = 0f;
                    float right = 0f;
                    for (int frame = 0; frame < complete; frame++) {
                        int at = frame * channels * 2;
                        float first = Math.abs((short) ((buffer[at] & 0xFF) | (buffer[at + 1] << 8))) / 32768f;
                        float second = channels < 2 ? first
                                : Math.abs((short) ((buffer[at + 2] & 0xFF) | (buffer[at + 3] << 8))) / 32768f;
                        left = Math.max(left, first);
                        right = Math.max(right, second);
                    }
                    windows.add(new float[]{left, right});
                    frames += complete;
                    if (filled < buffer.length) {
                        break;
                    }
                }
                float[] left = new float[windows.size()];
                float[] right = new float[windows.size()];
                for (int i = 0; i < left.length; i++) {
                    left[i] = windows.get(i)[0];
                    right[i] = windows.get(i)[1];
                }
                return new Envelope(left, right, frames / (double) plain.getSampleRate());
            }
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** Gets a file ready to play; whatever played before is stopped. */
    void open(Path chosen) throws Exception {
        close();
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(chosen.toFile())) {
            Clip opened = AudioSystem.getClip();
            opened.open(audio);
            clip = opened;
        }
        file = chosen;
        envelope = measure(chosen);
    }

    /** Shows a file without a sound card: its waveform, but nothing to play. */
    void show(Path chosen) {
        close();
        file = chosen;
        envelope = measure(chosen);
    }

    void play() {
        if (clip != null) {
            if (clip.getFramePosition() >= clip.getFrameLength()) {
                clip.setFramePosition(0);
            }
            clip.start();
        }
    }

    void pause() {
        if (clip != null) {
            clip.stop();
        }
    }

    void close() {
        if (clip != null) {
            clip.stop();
            clip.close();
            clip = null;
        }
        envelope = null;
        file = null;
    }

    boolean playing() {
        return clip != null && clip.isRunning();
    }

    boolean ready() {
        return clip != null;
    }

    Path file() {
        return file;
    }

    Envelope envelope() {
        return envelope;
    }

    double seconds() {
        return clip == null ? 0 : clip.getMicrosecondPosition() / 1e6;
    }

    double length() {
        return clip != null ? clip.getMicrosecondLength() / 1e6 : envelope != null ? envelope.seconds : 0;
    }

    /** Jumps to a share of the file between 0 and 1. */
    void seek(double share) {
        if (clip != null) {
            clip.setFramePosition((int) Math.max(0, Math.min(clip.getFrameLength() - 1, share * clip.getFrameLength())));
        }
    }
}
