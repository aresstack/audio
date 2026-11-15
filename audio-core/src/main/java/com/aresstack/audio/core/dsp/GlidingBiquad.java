package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioProcessor;
import com.aresstack.audio.core.BiquadCoefficients;

/**
 * The same second-order filter on both sides whose coefficients glide instead of jumping: a new
 * set is reached in a straight line over a number of frames, sample by sample, while the state of
 * both sides stays. A shelf that moves with the distance of a source therefore neither clicks nor
 * zips.
 *
 * <p><b>Why gliding is safe.</b> A biquad is stable exactly when its denominator {@code (a1, a2)}
 * lies inside the triangle {@code |a2| < 1, |a1| < 1 + a2}. The triangle is convex, so every point
 * on the straight line between two stable sets is stable as well: no step of the glide can make
 * the filter run away. The numerator only scales and shapes; it cannot make it unstable. At the
 * end of the glide the coefficients are exactly the new set, so a settled filter computes the same
 * as {@link StereoBiquad} with that set.
 *
 * <p>The target is handed over on the audio thread (from an immutable value that was made
 * elsewhere); nothing here allocates, locks or throws after {@link #prepare}.
 */
public final class GlidingBiquad implements AudioProcessor {
    private double b0 = 1;
    private double b1;
    private double b2;
    private double a1;
    private double a2;
    private double db0;
    private double db1;
    private double db2;
    private double da1;
    private double da2;
    private BiquadCoefficients target;
    private int remaining;
    private double l1;
    private double l2;
    private double r1;
    private double r2;

    /** A filter that starts with this set; {@code null} lets the sound through unchanged. */
    public GlidingBiquad(BiquadCoefficients start) {
        jumpTo(start);
    }

    /** Takes a set at once, without gliding (before playback, or after a seek); the state stays. */
    public void jumpTo(BiquadCoefficients set) {
        target = set;
        remaining = 0;
        if (set == null) {
            b0 = 1;
            b1 = 0;
            b2 = 0;
            a1 = 0;
            a2 = 0;
        } else {
            b0 = set.b0;
            b1 = set.b1;
            b2 = set.b2;
            a1 = set.a1;
            a2 = set.a2;
        }
    }

    /**
     * Glides to a set over this many frames, starting with the next frame; {@code null} is the
     * filter that changes nothing. The same set as the last target does not restart the glide.
     */
    public void glideTo(BiquadCoefficients set, int frames) {
        if (set == target) {
            return;
        }
        if (frames <= 0) {
            jumpTo(set);
            return;
        }
        target = set;
        remaining = frames;
        db0 = ((set == null ? 1 : set.b0) - b0) / frames;
        db1 = ((set == null ? 0 : set.b1) - b1) / frames;
        db2 = ((set == null ? 0 : set.b2) - b2) / frames;
        da1 = ((set == null ? 0 : set.a1) - a1) / frames;
        da2 = ((set == null ? 0 : set.a2) - a2) / frames;
    }

    /** The set the filter glides to or has reached; {@code null} for no change. */
    public BiquadCoefficients target() {
        return target;
    }

    /** Whether the coefficients have reached their target. */
    public boolean settled() {
        return remaining == 0;
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        reset();
    }

    /** Forgets what was heard before; the coefficients stay. */
    public void reset() {
        l1 = 0;
        l2 = 0;
        r1 = 0;
        r2 = 0;
    }

    @Override
    public void process(AudioBlock block) {
        process(block, block.frames());
    }

    /** Filters the first {@code count} frames of the block in place. */
    public void process(AudioBlock block, int count) {
        process(block, 0, count);
    }

    /** Filters {@code count} frames of the block from {@code from} on, in place. */
    public void process(AudioBlock block, int from, int count) {
        count += from;
        float[] left = block.left;
        float[] right = block.right;
        double sl1 = l1;
        double sl2 = l2;
        double sr1 = r1;
        double sr2 = r2;
        int i = from;
        // While gliding, the coefficients move by one step per frame.
        while (remaining > 0 && i < count) {
            if (--remaining == 0) {
                jumpTo(target);
            } else {
                b0 += db0;
                b1 += db1;
                b2 += db2;
                a1 += da1;
                a2 += da2;
            }
            double in = left[i];
            double out = b0 * in + sl1;
            sl1 = b1 * in - a1 * out + sl2;
            sl2 = b2 * in - a2 * out;
            left[i] = (float) out;
            in = right[i];
            out = b0 * in + sr1;
            sr1 = b1 * in - a1 * out + sr2;
            sr2 = b2 * in - a2 * out;
            right[i] = (float) out;
            i++;
        }
        if (i < count) {
            // Settled: the same loop as BiquadFilter, one side after the other. Without a set
            // (b0 = 1, the rest 0) the sound passes unchanged once the state has run out.
            double c0 = b0;
            double c1 = b1;
            double c2 = b2;
            double d1 = a1;
            double d2 = a2;
            for (int j = i; j < count; j++) {
                double in = left[j];
                double out = c0 * in + sl1;
                sl1 = c1 * in - d1 * out + sl2;
                sl2 = c2 * in - d2 * out;
                left[j] = (float) out;
            }
            for (int j = i; j < count; j++) {
                double in = right[j];
                double out = c0 * in + sr1;
                sr1 = c1 * in - d1 * out + sr2;
                sr2 = c2 * in - d2 * out;
                right[j] = (float) out;
            }
        }
        l1 = flush(sl1);
        l2 = flush(sl2);
        r1 = flush(sr1);
        r2 = flush(sr2);
    }

    private static double flush(double state) {
        return Math.abs(state) < 1e-200 ? 0 : state;
    }
}
