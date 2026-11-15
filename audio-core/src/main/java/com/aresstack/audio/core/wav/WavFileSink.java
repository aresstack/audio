package com.aresstack.audio.core.wav;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSink;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes stereo sound block by block to a WAV file of 16 or 24 bits. Here, and only here, the
 * float samples are clamped to -1..1 and rounded to whole numbers. The head of the file is
 * written first with empty sizes and filled in on {@link #close()}; the bytes are the same as
 * those Java Sound writes for the whole sound at once.
 */
public final class WavFileSink implements AudioSink {
    private static final int HEAD = 44;

    private final FileChannel channel;
    private final int sampleRate;
    private final int bytes;
    private final double most;
    private final ByteBuffer buffer;
    private long frames;
    private boolean closed;

    public WavFileSink(Path file, int sampleRate, int bits, int maxBlockFrames) throws IOException {
        if (bits != 16 && bits != 24) {
            throw new IllegalArgumentException("A WAV is written with 16 or 24 bits.");
        }
        this.sampleRate = sampleRate;
        this.bytes = bits / 8;
        this.most = bits == 16 ? 32767 : 8388607;
        this.buffer = ByteBuffer.allocate(maxBlockFrames * 2 * bytes).order(ByteOrder.LITTLE_ENDIAN);
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            channel.write(head(0), 0);
            channel.position(HEAD);
        } catch (IOException failed) {
            channel.close();
            throw failed;
        }
    }

    @Override
    public void write(AudioBlock block) throws IOException {
        int count = block.frames();
        if (count * 2 * bytes > buffer.capacity()) {
            throw new IllegalArgumentException("The block is larger than this sink was made for.");
        }
        buffer.clear();
        byte[] data = buffer.array();
        int at = 0;
        for (int i = 0; i < count; i++) {
            at = put(data, at, Math.round(Math.max(-1, Math.min(1, block.left[i])) * most));
            at = put(data, at, Math.round(Math.max(-1, Math.min(1, block.right[i])) * most));
        }
        buffer.limit(at);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        frames += count;
    }

    private int put(byte[] data, int offset, long value) {
        for (int i = 0; i < bytes; i++) {
            data[offset + i] = (byte) (value >>> (8 * i));
        }
        return offset + bytes;
    }

    /** The frames written so far. */
    public long frames() {
        return frames;
    }

    private ByteBuffer head(long dataBytes) {
        if (dataBytes > 0xFFFFFFFFL - 36) {
            throw new IllegalStateException("A WAV file holds at most 4 GB of sound.");
        }
        int blockAlign = 2 * bytes;
        ByteBuffer head = ByteBuffer.allocate(HEAD).order(ByteOrder.LITTLE_ENDIAN);
        head.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (36 + dataBytes));
        head.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16);
        head.putShort((short) 1).putShort((short) 2).putInt(sampleRate).putInt(sampleRate * blockAlign);
        head.putShort((short) blockAlign).putShort((short) (8 * bytes));
        head.put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) dataBytes);
        head.flip();
        return head;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            ByteBuffer head = head(frames * 2 * bytes);
            while (head.hasRemaining()) {
                channel.write(head, head.position());
            }
        } finally {
            channel.close();
        }
    }
}
