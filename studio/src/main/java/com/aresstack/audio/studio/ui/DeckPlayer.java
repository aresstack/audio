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


    /** The file measured last, so that showing it and then playing it reads it once, not twice. */
    private static Path measuredFile;
    private static java.nio.file.attribute.FileTime measuredTime;
    private static long measuredSize;
    private static Envelope measuredEnvelope;

    /** Measures a WAV file; {@code null} if it is missing or cannot be read. */
    static Envelope measure(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        java.nio.file.attribute.FileTime time;
        long size;
        try {
            time = Files.getLastModifiedTime(file);
            size = Files.size(file);
        } catch (java.io.IOException unreadable) {
            return null;
        }
        synchronized (DeckPlayer.class) {
            if (file.equals(measuredFile) && time.equals(measuredTime) && size == measuredSize) {
                return measuredEnvelope;
            }
        }
        Envelope measured = measureNow(file);
        synchronized (DeckPlayer.class) {
            measuredFile = file;
            measuredTime = time;
            measuredSize = size;
            measuredEnvelope = measured;
        }
        return measured;
    }

    private static Envelope measureNow(Path file) {
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

    /**
     * The sound of a file as 16-bit samples, as the sound card takes them. A plain 16-bit WAV file
     * (what the studio writes) is read from the disk while it plays, so that playing starts at once;
     * any other file is decoded into memory first.
     */
    private abstract static class Sound {
        final AudioFormat format;

        Sound(AudioFormat format) {
            this.format = format;
        }

        abstract long bytes();

        /** Reads {@code count} bytes from {@code from} on; fewer only at the end. */
        abstract int read(long from, byte[] into, int count) throws java.io.IOException;

        void close() {
        }

        long frames() {
            return bytes() / format.getFrameSize();
        }

        boolean sameShape(Sound other) {
            return other != null && format.getChannels() == other.format.getChannels()
                    && Math.abs(format.getSampleRate() - other.format.getSampleRate()) < 0.5f;
        }
    }

    /** A sound decoded into memory. */
    private static final class Decoded extends Sound {
        final byte[] data;

        Decoded(byte[] data, AudioFormat format) {
            super(format);
            this.data = data;
        }

        @Override
        long bytes() {
            return data.length;
        }

        @Override
        int read(long from, byte[] into, int count) {
            int length = (int) Math.max(0, Math.min(count, data.length - from));
            System.arraycopy(data, (int) from, into, 0, length);
            return length;
        }
    }

    /**
     * A plain 16-bit WAV file read from the disk while it plays. The channel lets the file be
     * replaced meanwhile (a mix made anew is moved over it); what plays reads on in the old one.
     */
    private static final class Streamed extends Sound {
        final java.nio.channels.FileChannel channel;
        final long start;
        final long length;

        Streamed(java.nio.channels.FileChannel channel, AudioFormat format, long start, long length) {
            super(format);
            this.channel = channel;
            this.start = start;
            this.length = length;
        }

        @Override
        long bytes() {
            return length;
        }

        @Override
        int read(long from, byte[] into, int count) throws java.io.IOException {
            int wanted = (int) Math.max(0, Math.min(count, length - from));
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(into, 0, wanted);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, start + from + buffer.position());
                if (read < 0) {
                    break;
                }
            }
            return buffer.position();
        }

        @Override
        void close() {
            try {
                channel.close();
            } catch (java.io.IOException ignored) {
                // nothing more is read from it
            }
        }
    }

    /** Opens a plain 16-bit PCM WAV file to be read while it plays; {@code null} for any other file. */
    private static Sound streamed(Path chosen) throws java.io.IOException {
        java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(chosen,
                java.nio.file.StandardOpenOption.READ);
        try {
            long size = channel.size();
            java.nio.ByteBuffer head = chunk(channel, 0, 12);
            if (head == null || !"RIFF".equals(ascii(head, 0)) || !"WAVE".equals(ascii(head, 8))) {
                channel.close();
                return null;
            }
            int encoding = 0;
            int channels = 0;
            int rate = 0;
            int bits = 0;
            long at = 12;
            while (at + 8 <= size) {
                java.nio.ByteBuffer header = chunk(channel, at, 8);
                if (header == null) {
                    break;
                }
                String id = ascii(header, 0);
                long length = header.getInt(4) & 0xFFFFFFFFL;
                if ("fmt ".equals(id) && length >= 16) {
                    java.nio.ByteBuffer described = chunk(channel, at + 8, (int) Math.min(length, 40));
                    if (described == null) {
                        break;
                    }
                    encoding = described.getShort(0) & 0xFFFF;
                    channels = described.getShort(2) & 0xFFFF;
                    rate = described.getInt(4);
                    bits = described.getShort(14) & 0xFFFF;
                    if (encoding == WAVE_EXTENSIBLE && length >= 26) {
                        encoding = described.getShort(24) & 0xFFFF; // the first two bytes of the sub format
                    }
                } else if ("data".equals(id)) {
                    if (encoding != WAVE_PCM || bits != 16 || channels < 1 || rate <= 0) {
                        break;
                    }
                    long start = at + 8;
                    long data = length == 0 || length == 0xFFFFFFFFL || start + length > size ? size - start : length;
                    AudioFormat plain = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels,
                            channels * 2, rate, false);
                    return new Streamed(channel, plain, start, data - data % plain.getFrameSize());
                }
                at += 8 + length + (length & 1);
            }
            channel.close();
            return null;
        } catch (java.io.IOException | RuntimeException unreadable) {
            channel.close();
            return null;
        }
    }

    private static final int WAVE_PCM = 1;
    private static final int WAVE_EXTENSIBLE = 0xFFFE;

    private static java.nio.ByteBuffer chunk(java.nio.channels.FileChannel channel, long at, int count)
            throws java.io.IOException {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(count).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, at + buffer.position()) < 0) {
                return null;
            }
        }
        return buffer;
    }

    private static String ascii(java.nio.ByteBuffer buffer, int at) {
        char[] letters = new char[4];
        for (int i = 0; i < 4; i++) {
            letters[i] = (char) (buffer.get(at + i) & 0xFF);
        }
        return new String(letters);
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
        Sound streaming = streamed(chosen);
        if (streaming != null) {
            return streaming; // plays at once, nothing is read ahead
        }
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
                return new Decoded(whole == data.length ? data : java.util.Arrays.copyOf(data, whole), plain);
            }
        }
    }

    /** Gets a file ready to play; whatever played before is stopped. */
    void open(Path chosen) throws Exception {
        close();
        Sound loaded = load(chosen);
        javax.sound.sampled.SourceDataLine opened;
        try {
            opened = AudioSystem.getSourceDataLine(loaded.format);
            // A short buffer: a jump, a pause or a new mix is heard within a tenth of a second.
            opened.open(loaded.format, Math.max(4096, (int) (loaded.format.getSampleRate() / 10)
                    * loaded.format.getFrameSize()));
        } catch (Exception | Error noSound) {
            loaded.close();
            throw noSound;
        }
        synchronized (lock) {
            sound = loaded;
            line = opened;
            next = 0;
            heardBase = 0;
            writtenSince = 0;
            running = false;
            closed = false;
            gain = 1f;
            gainTarget = 1f;
            gainStep = 0f;
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
            loaded.close();
            open(chosen);
            seekSeconds(at);
            if (played) {
                play();
            }
            return;
        }
        synchronized (lock) {
            Sound old = sound;
            sound = loaded;
            next = Math.min(next, loaded.frames());
            if (old != null && old != loaded) {
                old.close();
            }
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
                    count = (int) Math.max(0, Math.min(wanted, playing.bytes() - from));
                    if (count > 0) {
                        if (piece.length < count) {
                            piece = new byte[wanted];
                        }
                        try {
                            count = playing.read(from, piece, count);
                        } catch (java.io.IOException unreadable) {
                            count = 0; // the file is gone: it ends here
                        }
                        count -= count % frame;
                    }
                    if (count > 0) {
                        louder(piece, count, playing.format.getChannels());
                        tap.write16(piece, 0, count, playing.format.getChannels(), 1f); // as heard, after the fade
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

    /** The gain the samples are played with now, between 0 and 1; changed a little each frame on a fade. */
    private float gain = 1f;
    private float gainTarget = 1f;
    private float gainStep;

    /**
     * Fades from the gain now to {@code target} (0 to 1) over {@code seconds} of playing; at once
     * for 0 seconds. Used for the overlap of two titles of an album.
     */
    void fade(float target, double seconds) {
        synchronized (lock) {
            float to = Math.max(0f, Math.min(1f, target));
            Sound playing = sound;
            double frames = playing == null ? 0 : seconds * playing.format.getSampleRate();
            gainTarget = to;
            if (!(frames >= 1)) {
                gain = to;
                gainStep = 0f;
            } else {
                gainStep = (float) ((to - gain) / frames);
            }
        }
    }

    /** Starts silent: the next fade brings the sound in. */
    void silent() {
        synchronized (lock) {
            gain = 0f;
            gainTarget = 0f;
            gainStep = 0f;
        }
    }

    /** Whether a fade to silence has ended. */
    boolean fadedOut() {
        synchronized (lock) {
            return gain <= 0f && gainTarget <= 0f;
        }
    }

    /** Plays the samples with the gain, frame by frame along a fade. Called with the lock held. */
    private void louder(byte[] samples, int count, int channels) {
        if (gain >= 1f && gainTarget >= 1f) {
            return; // the usual case: untouched
        }
        int frameBytes = channels * 2;
        for (int at = 0; at + frameBytes <= count; at += frameBytes) {
            if (gain != gainTarget) {
                gain += gainStep;
                if ((gainStep >= 0 && gain >= gainTarget) || (gainStep <= 0 && gain <= gainTarget)) {
                    gain = gainTarget;
                }
            }
            for (int channel = 0; channel < channels; channel++) {
                int i = at + channel * 2;
                int sample = (short) ((samples[i] & 0xFF) | (samples[i + 1] << 8));
                int scaled = Math.round(sample * gain);
                samples[i] = (byte) scaled;
                samples[i + 1] = (byte) (scaled >> 8);
            }
        }
    }

    /** How many seconds of sound the tap of the visualizer keeps at the highest usual rate. */
    static final double TAP_SECONDS = 1.5;
    /** The highest sample rate the tap is made for; a faster file only leaves the visualizer a shorter window. */
    static final int TAP_RATE = 48000;
    /** What went to the sound card, for the visualizer: written by the feeder, after the fade. */
    private final com.aresstack.audio.core.scope.SoundTap tap =
            new com.aresstack.audio.core.scope.SoundTap((int) Math.ceil(TAP_SECONDS * TAP_RATE));

    /** The sound that is heard right now, for pictures of it; silent while nothing plays. */
    com.aresstack.audio.studio.visual.SoundFeed soundFeed() {
        return soundFeed;
    }

    private final com.aresstack.audio.studio.visual.SoundFeed soundFeed = new com.aresstack.audio.studio.visual.SoundFeed() {
        @Override
        public boolean window(float[] left, float[] right, int count) {
            javax.sound.sampled.SourceDataLine out = line;
            Sound playing = sound;
            if (!running || out == null || playing == null) {
                return false;
            }
            try {
                long waiting = Math.max(0, (out.getBufferSize() - out.available()) / playing.format.getFrameSize());
                return tap.read(tap.written() - waiting, left, right, count);
            } catch (RuntimeException closedMeanwhile) {
                return false; // the line was closed while the picture asked: one picture without sound
            }
        }

        @Override
        public int sampleRate() {
            Sound playing = sound;
            return playing == null ? TAP_RATE : Math.round(playing.format.getSampleRate());
        }
    };

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
            if (sound != null) {
                sound.close();
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
