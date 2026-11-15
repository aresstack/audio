package com.aresstack.audio.core.stream;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;

/** Sound that is already in memory, delivered block by block. */
public final class ArraySource implements AudioSource {
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

    public int frames() {
        return left.length;
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
