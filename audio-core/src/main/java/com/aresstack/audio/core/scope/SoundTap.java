package com.aresstack.audio.core.scope;

/**
 * A ring of the latest stereo frames that went to an output, for pictures of the sound
 * (spectrum, oscilloscope, visualizer). One thread writes (the audio thread, or the thread that
 * feeds a sound card), any number of threads read.
 *
 * <p>The writer neither locks nor allocates: it copies the frames into the ring and then counts
 * them in {@link #written()}. A reader copies the frames it asks for and then checks that the
 * writer has not gone round the ring over them in the meantime; if it has, the read fails and the
 * reader simply tries again with its next picture. A picture is not sound: a lost read costs one
 * frame of animation, never a click.
 *
 * <p>Frames are counted from the start of the tap. Which of them the listener hears now is
 * {@code written() - frames still waiting in the output}; the owner of the tap knows the latter.
 */
public final class SoundTap {
    private final float[] left;
    private final float[] right;
    private final int mask;
    /** Frames written in all, published after the copy: the release a reader acquires. */
    private volatile long written;
    /**
     * Frames written in all once the copy that runs now is done, published before it: a reader
     * whose frames lie within one ring of this may have seen them half overwritten.
     */
    private volatile long claimed;

    /** @param capacityFrames how many of the latest frames are kept, rounded up to a power of two */
    public SoundTap(int capacityFrames) {
        if (capacityFrames <= 0 || capacityFrames > 1 << 24) {
            throw new IllegalArgumentException("A tap keeps between 1 and 2^24 frames, not " + capacityFrames + ".");
        }
        int size = Integer.highestOneBit(capacityFrames);
        if (size < capacityFrames) {
            size <<= 1;
        }
        left = new float[size];
        right = new float[size];
        mask = size - 1;
    }

    /** How many frames the ring holds. */
    public int capacity() {
        return left.length;
    }

    /** How many frames were written since the tap was made. */
    public long written() {
        return written;
    }

    /** Writer only: the first {@code count} frames of two float channels. */
    public void write(float[] fromLeft, float[] fromRight, int count) {
        long at = written;
        claim(at + count);
        int done = 0;
        while (done < count) {
            int slot = (int) ((at + done) & mask);
            int part = Math.min(count - done, left.length - slot);
            System.arraycopy(fromLeft, done, left, slot, part);
            System.arraycopy(fromRight, done, right, slot, part);
            done += part;
        }
        written = at + count;
    }

    /**
     * Writer only: {@code bytes} bytes of signed 16-bit little-endian PCM with one or two
     * channels, as a sound card takes them; a mono frame goes to both sides, a part frame is left out.
     *
     * @param gain the share of the samples that is heard (a fade the writer applies on the way out)
     */
    public void write16(byte[] pcm, int offset, int bytes, int channels, float gain) {
        if (channels != 1 && channels != 2) {
            return;
        }
        int frameBytes = 2 * channels;
        int frames = bytes / frameBytes;
        long at = written;
        claim(at + frames);
        float scale = gain / 32768f;
        for (int i = 0; i < frames; i++) {
            int index = offset + i * frameBytes;
            float l = (short) ((pcm[index] & 0xFF) | (pcm[index + 1] << 8)) * scale;
            float r = channels == 1 ? l : (short) ((pcm[index + 2] & 0xFF) | (pcm[index + 3] << 8)) * scale;
            int slot = (int) ((at + i) & mask);
            left[slot] = l;
            right[slot] = r;
        }
        written = at + frames;
    }

    /**
     * Copies the {@code count} frames that end before frame {@code end} into the arrays; frames
     * before the start of the tap are silence.
     *
     * @return {@code false} if they are not (or no longer) in the ring: not yet written, or
     *         overwritten while they were copied; the arrays then hold nothing usable
     */
    public boolean read(long end, float[] toLeft, float[] toRight, int count) {
        if (count < 0 || count > left.length || toLeft.length < count || toRight.length < count) {
            throw new IllegalArgumentException("Cannot read " + count + " frames from a tap of " + left.length + ".");
        }
        long before = written;
        if (end > before || end - count < before - left.length) {
            return false;
        }
        long from = end - count;
        for (int i = 0; i < count; i++) {
            long frame = from + i;
            if (frame < 0) {
                toLeft[i] = 0f;
                toRight[i] = 0f;
            } else {
                int slot = (int) (frame & mask);
                toLeft[i] = left[slot];
                toRight[i] = right[slot];
            }
        }
        // The writer may have gone round meanwhile, or be overwriting now: the oldest frame copied
        // must lie within one ring of everything written or being written.
        java.lang.invoke.VarHandle.loadLoadFence();
        return from >= claimed - left.length;
    }

    /** Announces the frames about to be copied, before the first of them lands in the ring. */
    private void claim(long end) {
        claimed = end;
        java.lang.invoke.VarHandle.storeStoreFence(); // no slot is overwritten before the claim is seen
    }
}
