package com.aresstack.audio.core.line;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.live.LiveSink;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * The sound card as a {@link LiveSink}: blocks of float become 16-bit stereo samples (clamped and
 * rounded as {@link com.aresstack.audio.core.wav.WavFileSink} does) and go to a
 * {@code SourceDataLine}. The bytes of one block are allocated once, when the sink is opened.
 *
 * <p>The buffer of the line decides how late a change is heard: with the default of 4096 frames
 * (93 ms at 44.1 kHz) a moved instrument is heard after at most that plus one block. The sink
 * counts how often the line ran empty ({@link #underruns()}), which the render time of the audio
 * thread cannot show. This package
 * is the only one in the core besides {@code wav} that touches {@code javax.sound}; a game attaches
 * its own {@code LiveSink} instead.
 */
public final class LineSink implements LiveSink {
    /**
     * The default buffer of the line, in frames: 4096 (93 ms at 44.1 kHz). 2048 frames dropped out
     * now and then on a Windows sound card although every block was rendered in time.
     */
    public static final int DEFAULT_BUFFER_FRAMES = 4096;

    private final SourceDataLine line;
    private final byte[] bytes;
    private final int frameSize;
    // written and read only by the audio thread, published for other threads
    private boolean started;
    private int writesSinceStart;
    private volatile long underruns;
    private volatile int lowestBuffered = -1;

    /**
     * Opens the default output of the system for 16-bit stereo at this rate.
     *
     * @throws LineUnavailableException if there is no such output (also on a machine without a sound card)
     */
    public static LineSink open(int sampleRate, int maxBlockFrames, int bufferFrames) throws LineUnavailableException {
        AudioFormat format = format(sampleRate);
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(format);
        } catch (IllegalArgumentException none) {
            throw new LineUnavailableException("Keine Tonausgabe für " + format + ": " + none.getMessage());
        }
        line.open(format, Math.max(bufferFrames, maxBlockFrames) * format.getFrameSize());
        return new LineSink(line, maxBlockFrames);
    }

    /** The format the sink writes: signed 16-bit little-endian stereo. */
    public static AudioFormat format(int sampleRate) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, 2, 4, sampleRate, false);
    }

    /** A sink on a line that is open already, in the format of {@link #format}. */
    public LineSink(SourceDataLine line, int maxBlockFrames) {
        if (line == null || !line.isOpen()) {
            throw new IllegalArgumentException("A line sink needs an open line.");
        }
        AudioFormat format = line.getFormat();
        if (format.getChannels() != 2 || format.getSampleSizeInBits() != 16 || format.isBigEndian()
                || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            throw new IllegalArgumentException("A line sink writes signed 16-bit little-endian stereo: " + format);
        }
        this.line = line;
        this.frameSize = format.getFrameSize();
        this.bytes = new byte[maxBlockFrames * frameSize];
    }

    /** The line itself, for its position. */
    public SourceDataLine line() {
        return line;
    }

    @Override
    public void write(AudioBlock block) {
        int count = Math.min(block.frames(), bytes.length / frameSize);
        float[] left = block.left;
        float[] right = block.right;
        int at = 0;
        for (int i = 0; i < count; i++) {
            int l = Math.round(Math.max(-1, Math.min(1, left[i])) * 32767);
            int r = Math.round(Math.max(-1, Math.min(1, right[i])) * 32767);
            bytes[at++] = (byte) l;
            bytes[at++] = (byte) (l >> 8);
            bytes[at++] = (byte) r;
            bytes[at++] = (byte) (r >> 8);
        }
        if (started) {
            int waiting = bufferedFrames();
            // the first writes after a start fill the line; after them an empty line is a drop-out
            if (++writesSinceStart > 2) {
                if (waiting == 0) {
                    underruns = underruns + 1;
                }
                if (lowestBuffered < 0 || waiting < lowestBuffered) {
                    lowestBuffered = waiting;
                }
            }
        }
        line.write(bytes, 0, at);
    }

    @Override
    public long underruns() {
        return underruns;
    }

    @Override
    public int lowestBufferedFrames() {
        return lowestBuffered;
    }

    @Override
    public void start() {
        line.start();
        started = true;
        writesSinceStart = 0;
    }

    @Override
    public void pause() {
        line.stop();
        started = false;
    }

    @Override
    public void flush() {
        line.flush();
    }

    @Override
    public void drain() {
        line.drain();
    }

    @Override
    public int bufferedFrames() {
        return Math.max(0, (line.getBufferSize() - line.available()) / frameSize);
    }

    @Override
    public void close() {
        line.stop();
        line.flush();
        line.close();
    }
}
