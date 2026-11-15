package com.aresstack.audio.core.wav;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.TestSignals;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** WAV files in and out, block by block, with the same bytes and samples as Java Sound gives for the whole. */
public final class WavFileTest {
    private static final int RATE = 44100;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theSinkWritesTheSameBytesAsJavaSoundAndClampsOnlyThere() throws Exception {
        float[] left = TestSignals.noise(10007, 1, 1.4); // over full level on purpose
        float[] right = TestSignals.sine(10007, 440, RATE, 0.7);
        for (int bits : new int[]{16, 24}) {
            Path expected = folder.getRoot().toPath().resolve("java-sound-" + bits + ".wav");
            javaSound(left, right, bits, expected);
            for (int block : new int[]{1, 512, 4096}) {
                Path actual = folder.getRoot().toPath().resolve("sink-" + bits + "-" + block + ".wav");
                try (WavFileSink sink = new WavFileSink(actual, RATE, bits, block)) {
                    AudioBlock chunk = new AudioBlock(block);
                    for (int at = 0; at < left.length; at += block) {
                        int count = Math.min(block, left.length - at);
                        chunk.setFrames(count);
                        System.arraycopy(left, at, chunk.left, 0, count);
                        System.arraycopy(right, at, chunk.right, 0, count);
                        sink.write(chunk);
                    }
                }
                Assert.assertArrayEquals(bits + " bits, block " + block, Files.readAllBytes(expected),
                        Files.readAllBytes(actual));
            }
        }
    }

    @Test
    public void anEmptySinkIsAnEmptyWav() throws Exception {
        Path expected = folder.getRoot().toPath().resolve("empty-java-sound.wav");
        Path actual = folder.getRoot().toPath().resolve("empty-sink.wav");
        javaSound(new float[0], new float[0], 16, expected);
        new WavFileSink(actual, RATE, 16, 64).close();
        Assert.assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(actual));
    }

    @Test
    public void theSourceReadsWholeNumbersAndFloatsAsTheyAreAndMonoOnBothSides() throws Exception {
        float[] left = TestSignals.noise(5001, 2, 0.9);
        float[] right = TestSignals.noise(5001, 3, 0.9);
        Path stereo = folder.getRoot().toPath().resolve("stereo.wav");
        javaSound(left, right, 24, stereo);
        float[][] read = readAll(stereo, 333);
        for (int i = 0; i < left.length; i++) {
            Assert.assertEquals(left[i], read[0][i], 1.0 / 8388607);
            Assert.assertEquals(right[i], read[1][i], 1.0 / 8388607);
        }

        Path floats = folder.getRoot().toPath().resolve("float-mono.wav");
        ByteBuffer data = ByteBuffer.allocate(left.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : left) {
            data.putFloat(sample);
        }
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 48000, 32, 1, 4, 48000, false);
        try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(data.array()), format, left.length)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, floats.toFile());
        }
        try (WavFileSource source = new WavFileSource(floats)) {
            Assert.assertEquals(48000f, source.sampleRate(), 0f);
        }
        float[][] mono = readAll(floats, 4096);
        Assert.assertArrayEquals(left, mono[0], 0f);
        Assert.assertArrayEquals(left, mono[1], 0f);
        Assert.assertEquals(left.length, WavFileSource.countFrames(floats));
    }

    private static float[][] readAll(Path file, int blockFrames) throws Exception {
        int frames = (int) WavFileSource.countFrames(file);
        float[][] out = {new float[frames], new float[frames]};
        try (WavFileSource source = new WavFileSource(file)) {
            source.prepare(RATE, blockFrames);
            AudioBlock block = new AudioBlock(blockFrames);
            int at = 0;
            int got;
            do {
                block.setFrames(blockFrames);
                got = source.read(block);
                System.arraycopy(block.left, 0, out[0], at, got);
                System.arraycopy(block.right, 0, out[1], at, got);
                at += got;
            } while (got == blockFrames);
            Assert.assertEquals(frames, at);
        }
        return out;
    }

    /** How the old mixer wrote a WAV: everything in one array, through Java Sound. */
    private static void javaSound(float[] left, float[] right, int bits, Path output) throws Exception {
        int bytes = bits / 8;
        byte[] data = new byte[left.length * 2 * bytes];
        double most = bits == 16 ? 32767 : 8388607;
        for (int i = 0; i < left.length; i++) {
            put(data, i * 2 * bytes, bytes, Math.round(Math.max(-1, Math.min(1, left[i])) * most));
            put(data, (i * 2 + 1) * bytes, bytes, Math.round(Math.max(-1, Math.min(1, right[i])) * most));
        }
        AudioFormat format = new AudioFormat(RATE, bits, 2, true, false);
        try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(data), format, left.length)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
    }

    private static void put(byte[] data, int offset, int bytes, long value) {
        for (int i = 0; i < bytes; i++) {
            data[offset + i] = (byte) (value >>> (8 * i));
        }
    }
}
