package com.aresstack.audio.core.stream;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import java.io.IOException;

/**
 * Brings a source to another sample rate by straight-line interpolation between neighbouring
 * frames, as the mixer of Audio Studio always did, but block by block instead of on the whole
 * signal. Output frame {@code i} lies at source position {@code i · from / to}; the position is
 * computed exactly as before (the product in float), so that the sound stays the same to the
 * last bit. A source of {@code n} frames gives {@link #outputFrames} frames.
 *
 * <p>A better interpolation with a low-pass in front is a change of sound of its own and comes
 * as a separate step.
 */
public final class LinearResampler implements AudioSource {
    /** Room for the rounding of the position on very long signals. */
    private static final int MARGIN = 256;

    private final AudioSource source;
    private final float from;
    private final int to;
    private AudioBlock incoming;
    /** Source frames in memory, from the absolute source frame {@code start} on. */
    private float[] left = new float[0];
    private float[] right = new float[0];
    private long start;
    private int held;
    private boolean ended;
    private long output;

    public LinearResampler(AudioSource source, float from, int to) {
        if (!(from > 0) || to <= 0) {
            throw new IllegalArgumentException("Sample rates are positive.");
        }
        this.source = source;
        this.from = from;
        this.to = to;
    }

    /** Whether two rates are so close that the signal is taken as it is (as before: within half a hertz). */
    public static boolean same(float from, int to) {
        return Math.abs(from - to) < 0.5f;
    }

    /** How many frames a source of {@code frames} frames has at the other rate. */
    public static long outputFrames(long frames, float from, int to) {
        return same(from, to) ? frames : (long) Math.ceil(frames * (double) to / from);
    }

    /** The source itself if the rates are the same, else the source brought to the rate {@code to}. */
    public static AudioSource at(AudioSource source, float from, int to) {
        return same(from, to) ? source : new LinearResampler(source, from, to);
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        int window = (int) Math.ceil(maxBlockFrames * (double) from / to) + 4;
        incoming = new AudioBlock(window);
        left = new float[2 * window + MARGIN];
        right = new float[2 * window + MARGIN];
        start = 0;
        held = 0;
        ended = false;
        output = 0;
        source.prepare((int) from, window);
    }

    @Override
    public int read(AudioBlock block) throws IOException {
        int wanted = block.frames();
        int count = 0;
        while (count < wanted) {
            double position = (output * from) / (double) to; // the product in float, as before
            long index = (long) position;
            // The frame after the index is needed too, unless the source has ended before it. And so
            // many frames must be known that this output frame surely lies within the output length
            // (the product in float can be off by a few frames on very long signals).
            long needed = Math.max(index + 2, (long) ((output + 1) * (double) from / to) + 2);
            while (!ended && start + held < needed) {
                fill(index);
            }
            if (ended) {
                long total = start + held;
                if (output >= outputFrames(total, from, to)) {
                    break;
                }
                if (index > total - 1) {
                    block.left[count] = 0;
                    block.right[count] = 0;
                    count++;
                    output++;
                    continue;
                }
            }
            long last = start + held - 1;
            int at = (int) (index - start);
            int following = (int) (Math.min(index + 1, last) - start);
            float fraction = (float) (position - index);
            block.left[count] = left[at] + (left[following] - left[at]) * fraction;
            block.right[count] = right[at] + (right[following] - right[at]) * fraction;
            count++;
            output++;
        }
        block.clear(count, wanted - count);
        return count;
    }

    /** Drops the frames before {@code keep} and reads the next block of the source behind the rest. */
    private void fill(long keep) throws IOException {
        int drop = (int) Math.max(0, Math.min(held, keep - start));
        if (drop > 0) {
            System.arraycopy(left, drop, left, 0, held - drop);
            System.arraycopy(right, drop, right, 0, held - drop);
            start += drop;
            held -= drop;
        }
        int room = Math.min(incoming.capacity(), left.length - held);
        incoming.setFrames(room);
        int got = source.read(incoming);
        System.arraycopy(incoming.left, 0, left, held, got);
        System.arraycopy(incoming.right, 0, right, held, got);
        held += got;
        if (got < room) {
            ended = true;
        }
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
