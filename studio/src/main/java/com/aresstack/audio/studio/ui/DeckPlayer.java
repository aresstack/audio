package com.aresstack.audio.studio.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plays one WAV file, which can be exchanged while it plays, and knows what it looks like: its loudness in hundredths of a
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

    /** The sound of a file, decoded to 16-bit samples as the sound card takes them. */
    private static final class Sound {
        final byte[] data;
        final AudioFormat format;

        Sound(byte[] data, AudioFormat format) {
            this.data = data;
            this.format = format;
        }

        long frames() {
            return data.length / format.getFrameSize();
        }

        boolean sameShape(Sound other) {
            return other != null && format.getChannels() == other.format.getChannels()
                    && Math.abs(format.getSampleRate() - other.format.getSampleRate()) < 0.5f;
        }
    }

    // The sound is written to the sound card piece by piece by a thread of its own. So what is
    // played can be exchanged while it plays (a mix made anew) and goes on at the same place
    // without a gap: the thread simply takes its next piece from the new sound.
    private final Object lock = new Object();
    private volatile Sound sound;
    private javax.sound.sampled.SourceDataLine line;
    private Thread feeder;
    /** The frame the feeder writes next. */
    private volatile long next;
    /** What was written to the line before the last jump or pause, to tell what is being heard. */
    private volatile long writtenSince;
    private volatile long heardBase;
    private volatile boolean running;
    private volatile boolean closed = true;
    private Envelope envelope;
    private Path file;

    private static Sound load(Path chosen) throws Exception {
        try (AudioInputStream original = AudioSystem.getAudioInputStream(chosen.toFile())) {
            AudioFormat source = original.getFormat();
            AudioFormat plain = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.getSampleRate(), 16,
                    source.getChannels(), source.getChannels() * 2, source.getSampleRate(), false);
            try (AudioInputStream audio = AudioSystem.getAudioInputStream(plain, original)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] block = new byte[1 << 16];
                for (int count = audio.read(block); count >= 0; count = audio.read(block)) {
                    bytes.write(block, 0, count);
                }
                byte[] data = bytes.toByteArray();
                int whole = data.length - data.length % plain.getFrameSize();
                return new Sound(whole == data.length ? data : java.util.Arrays.copyOf(data, whole), plain);
            }
        }
    }

    /** Gets a file ready to play; whatever played before is stopped. */
    void open(Path chosen) throws Exception {
        close();
        Sound loaded = load(chosen);
        javax.sound.sampled.SourceDataLine opened = AudioSystem.getSourceDataLine(loaded.format);
        // A short buffer: a jump, a pause or a new mix is heard within a tenth of a second.
        opened.open(loaded.format, Math.max(4096, (int) (loaded.format.getSampleRate() / 10)
                * loaded.format.getFrameSize()));
        synchronized (lock) {
            sound = loaded;
            line = opened;
            next = 0;
            heardBase = 0;
            writtenSince = 0;
            running = false;
            closed = false;
        }
        file = chosen;
        envelope = measure(chosen);
        feeder = new Thread(this::feed, "deck-player");
        feeder.setDaemon(true);
        feeder.start();
    }

    /**
     * Exchanges what is played for another file and goes on at the same place, playing or not.
     * Without a gap if the new file has the same shape (channels, rate); else it is opened anew.
     */
    void swap(Path chosen) throws Exception {
        Sound loaded = load(chosen);
        boolean played = playing();
        double at = seconds();
        if (line == null || !loaded.sameShape(sound)) {
            open(chosen);
            seekSeconds(at);
            if (played) {
                play();
            }
            return;
        }
        synchronized (lock) {
            sound = loaded;
            next = Math.min(next, loaded.frames());
        }
        file = chosen;
        envelope = measure(chosen);
    }

    /** The thread that feeds the sound card: a fiftieth of a second at a time while playing. */
    private void feed() {
        byte[] piece = new byte[0];
        while (!closed) {
            javax.sound.sampled.SourceDataLine out;
            Sound playing;
            int count = 0;
            synchronized (lock) {
                out = line;
                playing = sound;
                if (out == null || playing == null) {
                    return;
                }
                if (running) {
                    int frame = playing.format.getFrameSize();
                    int wanted = Math.max(frame, (int) (playing.format.getSampleRate() / 50) * frame);
                    long from = next * frame;
                    count = (int) Math.max(0, Math.min(wanted, playing.data.length - from));
                    if (count > 0) {
                        if (piece.length < count) {
                            piece = new byte[wanted];
                        }
                        System.arraycopy(playing.data, (int) from, piece, 0, count);
                        next += count / frame;
                        writtenSince += count / frame;
                    } else if (heard() >= playing.frames()) {
                        running = false; // played to the end
                        out.stop();
                    }
                }
            }
            if (count > 0) {
                out.write(piece, 0, count); // waits while the card has enough
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException stopped) {
                    return;
                }
            }
        }
    }

    /** The frame that is being heard: what was written, less what still waits in the sound card. */
    private long heard() {
        javax.sound.sampled.SourceDataLine out = line;
        Sound playing = sound;
        if (out == null || playing == null) {
            return 0;
        }
        long waiting = (out.getBufferSize() - out.available()) / playing.format.getFrameSize();
        return Math.max(0, Math.min(playing.frames(), heardBase + writtenSince - Math.max(0, waiting)));
    }

    /** Shows a file without a sound card: its waveform, but nothing to play. */
    void show(Path chosen) {
        close();
        file = chosen;
        envelope = measure(chosen);
    }

    void play() {
        synchronized (lock) {
            if (line == null || sound == null) {
                return;
            }
            if (next >= sound.frames()) {
                jump(0);
            }
            line.start();
            running = true;
        }
    }

    void pause() {
        synchronized (lock) {
            if (line == null) {
                return;
            }
            long at = heard();
            running = false;
            line.stop();
            line.flush();
            jump(at); // what waited in the card is played again when it goes on
        }
    }

    /** Sets where the feeder goes on; what still waited in the card is dropped. Called with the lock held. */
    private void jump(long frame) {
        line.flush();
        next = Math.max(0, Math.min(sound.frames(), frame));
        heardBase = next;
        writtenSince = 0;
    }

    void close() {
        Thread feeding;
        synchronized (lock) {
            closed = true;
            running = false;
            if (line != null) {
                line.stop();
                line.flush();
                line.close();
                line = null;
            }
            sound = null;
            feeding = feeder;
            feeder = null;
        }
        if (feeding != null) {
            feeding.interrupt();
        }
        envelope = null;
        file = null;
    }

    boolean playing() {
        return line != null && running;
    }

    boolean ready() {
        return line != null;
    }

    Path file() {
        return file;
    }

    Envelope envelope() {
        return envelope;
    }

    double seconds() {
        Sound playing = sound;
        return line == null || playing == null ? 0 : heard() / (double) playing.format.getSampleRate();
    }

    double length() {
        Sound playing = sound;
        return line != null && playing != null ? playing.frames() / (double) playing.format.getSampleRate()
                : envelope != null ? envelope.seconds : 0;
    }

    /** Jumps to a share of the file between 0 and 1. */
    void seek(double share) {
        synchronized (lock) {
            if (line != null && sound != null) {
                jump((long) (Math.max(0, Math.min(1, share)) * sound.frames()));
            }
        }
    }

    private void seekSeconds(double second) {
        synchronized (lock) {
            if (line != null && sound != null) {
                jump((long) (second * sound.format.getSampleRate()));
            }
        }
    }
}
