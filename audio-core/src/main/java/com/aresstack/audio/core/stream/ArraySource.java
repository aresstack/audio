package com.aresstack.audio.core.stream;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.SeekableSource;

/** Sound that is already in memory, delivered block by block; it can jump to any frame. */
public final class ArraySource implements SeekableSource {
    private final float[] left;
    private final float[] right;
    private final float sampleRate;
    private int position;

    public ArraySource(float[] left, float[] right, float sampleRate) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("Both sides of a signal must be equally long.");
        }
        this.left = left;
        this.right = right;
        this.sampleRate = sampleRate;
    }

    public float sampleRate() {
        return sampleRate;
    }

    @Override
    public long frames() {
        return left.length;
    }

    @Override
    public void seek(long frame) {
        position = (int) Math.max(0, Math.min(left.length, frame));
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        position = 0;
    }

    @Override
    public int read(AudioBlock block) {
        int wanted = block.frames();
        int count = Math.min(wanted, left.length - position);
        System.arraycopy(left, position, block.left, 0, count);
        System.arraycopy(right, position, block.right, 0, count);
        block.clear(count, wanted - count);
        position += count;
        return count;
    }
}
