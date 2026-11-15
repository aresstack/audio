package com.aresstack.audio.core.wav;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.conv.ImpulseResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reads a measured impulse response from a WAV file (mono or stereo, 16/24/32 bit as
 * {@link WavFileSource} reads it): mono gives the same response on both sides. Responses are
 * read off the audio thread, before a mix or a live graph is made.
 */
public final class ImpulseResponseFile {
    /** Longer responses are cut: a room that rings longer than this is no stage. */
    public static final double MAX_SECONDS = 12;

    private ImpulseResponseFile() {
    }

    public static ImpulseResponse read(Path file) throws IOException {
        try (WavFileSource source = new WavFileSource(file)) {
            int rate = Math.round(source.sampleRate());
            int most = (int) (MAX_SECONDS * rate);
            AudioBlock block = new AudioBlock(8192);
            source.prepare(rate, block.capacity());
            float[] left = new float[Math.min(most, 1 << 16)];
            float[] right = new float[left.length];
            int frames = 0;
            int got;
            do {
                block.setFrames(block.capacity());
                got = source.read(block);
                int take = Math.min(got, most - frames);
                if (frames + take > left.length) {
                    int grown = Math.min(most, Math.max(frames + take, left.length * 2));
                    left = Arrays.copyOf(left, grown);
                    right = Arrays.copyOf(right, grown);
                }
                System.arraycopy(block.left, 0, left, frames, take);
                System.arraycopy(block.right, 0, right, frames, take);
                frames += take;
            } while (got == block.capacity() && frames < most);
            if (frames == 0) {
                throw new IOException("Die Impulsantwort " + file.getFileName() + " ist leer.");
            }
            return new ImpulseResponse(Arrays.copyOf(left, frames), Arrays.copyOf(right, frames), rate);
        }
    }
}
