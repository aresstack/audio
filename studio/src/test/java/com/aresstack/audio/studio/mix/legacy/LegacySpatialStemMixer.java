package com.aresstack.audio.studio.mix.legacy;

import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
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

/**
 * The mixer of Audio Studio as it was before the audio core (main at fe47e35 / F0), kept unchanged
 * except for its name as the reference for the equivalence tests of D1a. Not used by the studio.
 */
/*
 * Mixes the rendered tracks as they stand on the stage. Each track gets its level
 * (also moving over time), its width and its place between left and right; the
 * farther back it stands, the quieter and duller its direct sound and the larger
 * the share of the room. The walls answer with a few early echoes, and all tracks
 * ring in one shared room. Everything is calculated in float and only the finished
 * mix is written as whole numbers. The same input always gives the same output.
 */
public final class LegacySpatialStemMixer {
    public static final int RATE = 44100;
    /** The longest piece that is mixed: ten minutes. Longer ones would need more memory than is sensible. */
    public static final int MAX_SECONDS = 600;
    /** The stage as the listener sees it: 12 m wide, 9 m deep, its front edge 3 m away. */
    private static final double HALF_WIDTH = 6;
    private static final double DEPTH = 9;
    private static final double LISTENER = 3;
    private static final double SOUND_SPEED = 343;
    private static final double CEILING = 0.92;

    /** One track as sound: the samples of both sides, between -1 and 1. */
    public static final class Signal {
        public final float[] left;
        public final float[] right;
        public final float rate;

        public Signal(float[] left, float[] right, float rate) {
            if (left.length != right.length) {
                throw new IllegalArgumentException("Both sides of a signal must be equally long.");
            }
            this.left = left;
            this.right = right;
            this.rate = rate;
        }
    }

    /** The finished mix in float, both sides equally long. */
    public static final class Mix {
        public final float[] left;
        public final float[] right;

        Mix(float[] left, float[] right) {
            this.left = left;
            this.right = right;
        }

        public float peak() {
            float peak = 0;
            for (int i = 0; i < left.length; i++) {
                peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
            }
            return peak;
        }
    }

    /** Where a placement is heard: -1 is hard left, +1 hard right. */
    public static double pan(SoundSourcePlacement placement) {
        double angle = Math.atan2(placement.x() * HALF_WIDTH, LISTENER + placement.depth() * DEPTH);
        return Math.max(-1, Math.min(1, angle / Math.toRadians(60)));
    }

    /** How far a placement is from the listener, in metres. */
    public static double distance(SoundSourcePlacement placement) {
        return Math.hypot(placement.x() * HALF_WIDTH, LISTENER + placement.depth() * DEPTH);
    }

    /** Mixes the files of the tracks and writes the mix as a WAV of 16 or 24 bits. */
    public void mix(List<Path> files, List<SoundSourcePlacement> placements, AcousticRoom room,
                    double secondsPerBar, Path output, int bits) throws Exception {
        mix(files, placements, room, secondsPerBar, 0, output, bits);
    }

    public void mix(List<Path> files, List<SoundSourcePlacement> placements, AcousticRoom room,
                    double secondsPerBar, double masterDb, Path output, int bits) throws Exception {
        if (files.isEmpty() || files.size() != placements.size()) {
            throw new IllegalArgumentException("Every audio stem needs its placement.");
        }
        // The tracks are read one after the other and each is let go once it is in the mix, so that
        // a long piece with many tracks needs the memory of one track, not of all. How long the mix
        // gets is taken from the heads of the files first. A muted track is not even read.
        int length = 0;
        for (int i = 0; i < files.size(); i++) {
            if (!placements.get(i).muted()) {
                javax.sound.sampled.AudioFileFormat head = AudioSystem.getAudioFileFormat(files.get(i).toFile());
                long frames = head.getFrameLength();
                float rate = head.getFormat().getSampleRate();
                if (frames < 0 || rate <= 0) {
                    frames = resample(read(files.get(i))).left.length; // a file that does not say: read it
                    rate = RATE;
                }
                length = (int) Math.max(length, Math.min(Integer.MAX_VALUE, (long) Math.ceil(frames * (double) RATE / rate)));
            }
        }
        write(master(render(files.size(), length, index -> resample(read(files.get(index))), placements, room,
                secondsPerBar), masterDb), output, bits);
    }

    /** Gives the sound of the track at a place of the list, at the rate of the mix. */
    private interface Loader {
        Signal load(int index) throws Exception;
    }

    /**
     * Mixes signals in memory, without the last step that sets the loudness of the whole;
     * the mix is as long as the longest track plus the ring of the room.
     */
    public Mix render(List<Signal> signals, List<SoundSourcePlacement> placements, AcousticRoom room,
                      double secondsPerBar) throws IOException {
        int length = 0;
        final List<Signal> sources = new ArrayList<Signal>();
        for (Signal signal : signals) {
            Signal resampled = resample(signal);
            sources.add(resampled);
            length = Math.max(length, resampled.left.length);
        }
        try {
            return render(sources.size(), length, sources::get, placements, room, secondsPerBar);
        } catch (IOException failed) {
            throw failed;
        } catch (Exception impossible) {
            throw new IOException(impossible);
        }
    }

    private Mix render(int count, int length, Loader loader, List<SoundSourcePlacement> placements,
                       AcousticRoom room, double secondsPerBar) throws Exception {
        if (length > RATE * MAX_SECONDS) {
            throw new IOException("Das Stück ist länger als zehn Minuten; so lange Stücke werden nicht gemischt.");
        }
        boolean rings = room.amount() > 0.001;
        int tail = length == 0 ? 0 : (int) (RATE * (rings ? Math.min(8, room.reverbSeconds() + 0.4) : 0.3));
        int frames = length + tail;
        float[] left = new float[frames];
        float[] right = new float[frames];
        float[] sendLeft = new float[rings ? length : 0];
        float[] sendRight = new float[rings ? length : 0];
        for (int i = 0; i < count; i++) {
            SoundSourcePlacement placement = placements.get(i);
            if (placement.muted()) {
                continue;
            }
            Signal source = loader.load(i);
            if (source.left.length > 0) {
                place(source, placement, room, secondsPerBar, length, left, right, sendLeft, sendRight);
            }
        }
        if (rings) {
            double preDelay = Math.min(0.045, room.depthMetres() / SOUND_SPEED * 0.35);
            new LegacyRoomReverb(RATE, room.reverbSeconds(), room.damping(), preDelay)
                    .process(sendLeft, sendRight, left, right, (float) room.amount());
        }
        return new Mix(left, right);
    }

    /**
     * The last step: the finished mix is brought to one loudness as a whole, never squeezed.
     * Nothing may clip, and a mix of quiet tracks is raised (by 12 dB at most) so that it does
     * not come out thin. The balance between the tracks stays as it was set.
     */
    public static Mix master(Mix mix) {
        return master(mix, 0);
    }

    /**
     * @param masterDb how far below full level the mix shall be, 0 or less
     */
    public static Mix master(Mix mix, double masterDb) {
        float peak = mix.peak();
        if (peak > 1e-4f) {
            float gain = (float) (Math.min(4, CEILING / peak) * Math.pow(10, Math.min(0, masterDb) / 20));
            for (int i = 0; i < mix.left.length; i++) {
                mix.left[i] *= gain;
                mix.right[i] *= gain;
            }
        }
        return mix;
    }

    private static void place(Signal signal, SoundSourcePlacement placement, AcousticRoom room, double secondsPerBar,
                              int length, float[] outLeft, float[] outRight, float[] sendLeft, float[] sendRight) {
        int frames = Math.min(length, signal.left.length);
        float[] left = new float[frames];
        float[] right = new float[frames];
        // Level, also over time, and width: the middle stays, the difference of the sides is scaled.
        float level = (float) Math.pow(10, placement.levelDb() / 20);
        float width = (float) placement.width();
        boolean moves = !placement.dynamics().isEmpty() && secondsPerBar > 0;
        float gain = level;
        float step = 0;
        for (int i = 0; i < frames; i++) {
            if (moves && (i & 63) == 0) {
                // The level is set every 64 samples and glides in between, so that it never clicks.
                float now = level * (float) Math.pow(10, placement.dynamicsAt(i / (RATE * secondsPerBar)) / 20);
                float then = level * (float) Math.pow(10,
                        placement.dynamicsAt((i + 64) / (RATE * secondsPerBar)) / 20);
                gain = now;
                step = (then - now) / 64;
            }
            float middle = (signal.left[i] + signal.right[i]) * 0.5f;
            float side = (signal.left[i] - signal.right[i]) * 0.5f * width;
            left[i] = (middle + side) * gain;
            right[i] = (middle - side) * gain;
            gain += step;
        }
        double distance = distance(placement);
        double far = Math.max(0, Math.min(1, (distance - LISTENER) / DEPTH));
        // The room hears the track before the distance takes its toll: the direct sound fades, the ring stays.
        if (sendLeft.length > 0) {
            float send = (float) (placement.reverb() * (0.45 + 0.9 * far));
            double position = pan(placement);
            float sendToLeft = (float) (send * (1 - 0.35 * position));
            float sendToRight = (float) (send * (1 + 0.35 * position));
            for (int i = 0; i < frames; i++) {
                sendLeft[i] += left[i] * sendToLeft;
                sendRight[i] += right[i] * sendToRight;
            }
        }
        // Distance: quieter, and duller in the heights.
        float near = (float) Math.pow(LISTENER / distance, 0.45);
        double dull = -Math.min(9, (distance - LISTENER) * 0.55 * (0.6 + room.damping()));
        if (dull < -0.2) {
            LegacyBiquad shelf = LegacyBiquad.highShelf(RATE, 4500, dull);
            shelf.process(left);
            shelf.again().process(right);
        }
        // Left and right with equal power: a track sounds equally loud wherever it stands.
        double angle = (pan(placement) + 1) * Math.PI / 4;
        float toLeft = (float) (Math.cos(angle) * Math.sqrt(2)) * near;
        float toRight = (float) (Math.sin(angle) * Math.sqrt(2)) * near;
        for (int i = 0; i < frames; i++) {
            outLeft[i] += left[i] * toLeft;
            outRight[i] += right[i] * toRight;
        }
        if (room.reflections() > 0.001 && room.amount() > 0.001) {
            reflect(left, right, placement, room, distance, outLeft, outRight);
        }
    }

    /** The first echoes: the track mirrored in the left wall, the right wall and the back wall. */
    private static void reflect(float[] left, float[] right, SoundSourcePlacement placement, AcousticRoom room,
                                double direct, float[] outLeft, float[] outRight) {
        double x = placement.x() * HALF_WIDTH;
        double y = LISTENER + placement.depth() * DEPTH;
        double wall = Math.max(HALF_WIDTH + 0.5, room.widthMetres() / 2);
        double back = LISTENER + DEPTH + Math.max(0.5, room.depthMetres() * 0.15);
        double[][] images = {{-2 * wall - x, y}, {2 * wall - x, y}, {x, 2 * back - y}};
        int frames = left.length;
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) {
            mono[i] = (left[i] + right[i]) * 0.5f;
        }
        LegacyBiquad.lowPass(RATE, 9000 - 6000 * room.damping()).process(mono);
        for (double[] image : images) {
            double distance = Math.hypot(image[0], image[1]);
            int delay = (int) Math.round((distance - direct) / SOUND_SPEED * RATE);
            float gain = (float) (room.reflections() * room.amount() * 1.6 * Math.pow(LISTENER / distance, 0.45));
            double position = Math.max(-1, Math.min(1, Math.atan2(image[0], image[1]) / Math.toRadians(60)));
            double angle = (position + 1) * Math.PI / 4;
            float toLeft = (float) Math.cos(angle) * gain;
            float toRight = (float) Math.sin(angle) * gain;
            for (int i = 0; i < frames && i + delay < outLeft.length; i++) {
                outLeft[i + delay] += mono[i] * toLeft;
                outRight[i + delay] += mono[i] * toRight;
            }
        }
    }

    private static Signal resample(Signal signal) {
        if (Math.abs(signal.rate - RATE) < 0.5f) {
            return signal;
        }
        int frames = (int) Math.ceil(signal.left.length * (double) RATE / signal.rate);
        float[] left = new float[frames];
        float[] right = new float[frames];
        int last = signal.left.length - 1;
        for (int i = 0; i < frames; i++) {
            double position = i * signal.rate / (double) RATE;
            int index = (int) position;
            if (index <= last) {
                float fraction = (float) (position - index);
                int following = Math.min(index + 1, last);
                left[i] = signal.left[index] + (signal.left[following] - signal.left[index]) * fraction;
                right[i] = signal.right[index] + (signal.right[following] - signal.right[index]) * fraction;
            }
        }
        return new Signal(left, right, RATE);
    }

    /** Writes a mix as a WAV of 16 or 24 bits. */
    public static void write(Mix mix, Path output, int bits) throws IOException {
        if (bits != 16 && bits != 24) {
            throw new IllegalArgumentException("A WAV is written with 16 or 24 bits.");
        }
        int frames = mix.left.length;
        int bytes = bits / 8;
        byte[] data = new byte[frames * 2 * bytes];
        double most = bits == 16 ? 32767 : 8388607;
        for (int i = 0; i < frames; i++) {
            put(data, i * 2 * bytes, bytes, Math.round(Math.max(-1, Math.min(1, mix.left[i])) * most));
            put(data, (i * 2 + 1) * bytes, bytes, Math.round(Math.max(-1, Math.min(1, mix.right[i])) * most));
        }
        AudioFormat format = new AudioFormat(RATE, bits, 2, true, false);
        try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(data), format, frames)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
    }

    private static void put(byte[] data, int offset, int bytes, long value) {
        for (int i = 0; i < bytes; i++) {
            data[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    /** Reads a WAV of whole numbers (16, 24 or 32 bits) or of 32-bit floats as it is, without rounding it first. */
    public static Signal read(Path file) throws Exception {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat format = input.getFormat();
            int bits = format.getSampleSizeInBits();
            boolean whole = AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    && (bits == 16 || bits == 24 || bits == 32);
            boolean floating = AudioFormat.Encoding.PCM_FLOAT.equals(format.getEncoding()) && bits == 32;
            if (format.getChannels() < 1 || format.getChannels() > 2 || !(whole || floating)) {
                AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), 16,
                        Math.min(2, Math.max(1, format.getChannels())),
                        Math.min(2, Math.max(1, format.getChannels())) * 2, format.getSampleRate(), false);
                if (!AudioSystem.isConversionSupported(target, format)) {
                    throw new IOException("Unsupported WAV sample format for " + file + ": " + format);
                }
                try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, input)) {
                    return decode(converted, target, false);
                }
            }
            return decode(input, format, floating);
        }
    }

    private static Signal decode(AudioInputStream audio, AudioFormat format, boolean floating) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] block = new byte[16384];
        int count;
        long limit = (long) Math.ceil(format.getSampleRate()) * MAX_SECONDS * format.getFrameSize();
        while ((count = audio.read(block)) != -1) {
            buffer.write(block, 0, count);
            if (buffer.size() > limit) {
                throw new IOException("WAV stem exceeds size limit.");
            }
        }
        byte[] data = buffer.toByteArray();
        int channels = format.getChannels();
        int bytes = format.getSampleSizeInBits() / 8;
        int frames = data.length / (bytes * channels);
        float[] left = new float[frames];
        float[] right = new float[frames];
        for (int i = 0; i < frames; i++) {
            int offset = i * bytes * channels;
            left[i] = sample(data, offset, bytes, format.isBigEndian(), floating);
            right[i] = channels == 2 ? sample(data, offset + bytes, bytes, format.isBigEndian(), floating) : left[i];
        }
        return new Signal(left, right, format.getSampleRate());
    }

    private static float sample(byte[] data, int offset, int bytes, boolean bigEndian, boolean floating) {
        int value = 0;
        for (int i = 0; i < bytes; i++) {
            int part = data[offset + (bigEndian ? bytes - 1 - i : i)] & 255;
            value |= part << (8 * i);
        }
        if (floating) {
            return Float.intBitsToFloat(value);
        }
        int shift = 32 - 8 * bytes;
        return (value << shift >> shift) / (float) (1L << (8 * bytes - 1));
    }
}
