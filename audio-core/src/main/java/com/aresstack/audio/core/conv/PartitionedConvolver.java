package com.aresstack.audio.core.conv;

import com.aresstack.audio.core.fft.RadixTwoFourierTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * Convolution of two signals (left and right) with two long impulse responses, without latency
 * and with a cost that grows slowly with the length of the response: the classic non-uniformly
 * partitioned convolution (work package D4, architecture review 4b.2: the spectral multiplication,
 * not the FFT, sets the cost; it is cut by partitioning).
 *
 * <ul>
 *   <li><b>Head.</b> The first {@code head} samples of the response are convolved directly, sample
 *       by sample. That is what makes the convolution free of latency.</li>
 *   <li><b>Segments.</b> The rest is cut into segments of growing partition size ({@code head},
 *       {@code 4·head}, {@code 16·head}, … up to {@code largestPartition}). Each segment is a
 *       uniformly partitioned overlap-save convolution with a frequency-domain delay line. A
 *       segment whose partitions are {@code P} long starts at least {@code P} samples into the
 *       response, so its contribution is ready before it is due. For 2.5 s at 44.1 kHz with a
 *       head of 64 the spectral multiplication needs about 19 complex products per sample
 *       instead of 1 700 with uniform partitions of 64.</li>
 *   <li><b>Stereo in one transform.</b> Left goes into the real part and right into the imaginary
 *       part of one complex FFT; the two spectra are separated by symmetry, multiplied with their
 *       own response and put together again for one inverse transform (4b.2, item 3).</li>
 * </ul>
 *
 * <p>The result does not depend on how the input is cut into blocks: segments work on the grid of
 * their partition size, counted from the start. The large partitions do their work in the block
 * in which they complete, so the CPU load per block has peaks every {@code largestPartition}
 * samples (at 4096: one FFT pair of 8192 points and the products of that segment, about 1 ms);
 * they stay far inside the budget of a block of 512 frames (11.6 ms).
 *
 * <p>{@link #prepare} allocates everything; {@link #process} neither allocates nor locks nor
 * throws (audio-thread rules, architecture review 4b.1).
 */
public final class PartitionedConvolver {
    /** The head that suits block sizes from 64 frames on. */
    public static final int DEFAULT_HEAD = 64;
    /** The largest partition: larger ones save little and make the peaks longer. */
    public static final int DEFAULT_LARGEST_PARTITION = 4096;

    private final float[] left;
    private final float[] right;
    private final int head;
    private final int largest;
    private final Segment[] segments;
    // Head: the last `head` input samples twice over, so that they can be read in one run.
    private float[] headLeft;
    private float[] headRight;
    private int headAt;
    // Input history for the segments and the ring the segments add their results to.
    private float[] historyLeft;
    private float[] historyRight;
    private int historyMask;
    private double[] outLeft;
    private double[] outRight;
    private int outMask;
    private long time;

    /** A convolver with the default head and largest partition. */
    public PartitionedConvolver(float[] left, float[] right) {
        this(left, right, DEFAULT_HEAD, DEFAULT_LARGEST_PARTITION);
    }

    /**
     * @param left             the response for the left signal
     * @param right            the response for the right signal, as long as the left one (may
     *                         be the same array)
     * @param head             how many samples are convolved directly; a power of two
     * @param largestPartition the largest partition; a power of two, at least {@code head}
     */
    public PartitionedConvolver(float[] left, float[] right, int head, int largestPartition) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            throw new IllegalArgumentException("A convolution needs two responses of the same, non-zero length.");
        }
        if (head < 1 || Integer.bitCount(head) != 1 || largestPartition < head
                || Integer.bitCount(largestPartition) != 1) {
            throw new IllegalArgumentException("Head and largest partition are powers of two, the head no larger.");
        }
        for (int i = 0; i < left.length; i++) {
            if (!Float.isFinite(left[i]) || !Float.isFinite(right[i])) {
                throw new IllegalArgumentException("An impulse response holds finite numbers only.");
            }
        }
        this.left = left.clone();
        this.right = right == left ? this.left : right.clone();
        this.head = head;
        this.largest = largestPartition;
        segments = plan(this.left, this.right, head, largestPartition);
    }

    /** The segments of growing partition size behind the head. */
    private static Segment[] plan(float[] left, float[] right, int head, int largest) {
        List<Segment> plan = new ArrayList<Segment>();
        int length = left.length;
        int offset = head;
        int size = head;
        while (offset < length) {
            int end;
            if (size == largest) {
                end = length;
            } else {
                int next = Math.min(size * 4, largest);
                end = Math.max(next, roundUp(offset + size, next));
            }
            int partitions = (int) Math.ceil((Math.min(end, length) - offset) / (double) size);
            plan.add(new Segment(left, right, offset, size, partitions));
            offset += partitions * size;
            size = Math.min(size * 4, largest);
        }
        return plan.toArray(new Segment[0]);
    }

    private static int roundUp(int value, int step) {
        return (value + step - 1) / step * step;
    }

    /** How long the responses are, in samples. */
    public int length() {
        return left.length;
    }

    /** The convolution adds no latency. */
    public int latencyFrames() {
        return 0;
    }

    /** How many complex products per input sample the segments need (for measurements and tests). */
    public double productsPerSample() {
        double sum = 0;
        for (Segment segment : segments) {
            sum += segment.partitions * (segment.size + 1.0) / segment.size;
        }
        return sum;
    }

    /** Allocates the delay lines; the block size does not matter for the result. */
    public void prepare(int maxBlockFrames) {
        headLeft = new float[2 * head];
        headRight = new float[2 * head];
        int history = 2 * largest;
        historyLeft = new float[history];
        historyRight = new float[history];
        historyMask = history - 1;
        int reach = head;
        for (Segment segment : segments) {
            segment.prepare();
            reach = Math.max(reach, segment.offset + segment.size);
        }
        int out = Integer.highestOneBit(reach * 2 - 1) * 2;
        outLeft = new double[out];
        outRight = new double[out];
        outMask = out - 1;
        reset();
    }

    /** Forgets everything heard so far. */
    public void reset() {
        java.util.Arrays.fill(headLeft, 0f);
        java.util.Arrays.fill(headRight, 0f);
        java.util.Arrays.fill(historyLeft, 0f);
        java.util.Arrays.fill(historyRight, 0f);
        java.util.Arrays.fill(outLeft, 0);
        java.util.Arrays.fill(outRight, 0);
        for (Segment segment : segments) {
            segment.reset();
        }
        headAt = 0;
        time = 0;
    }

    /**
     * Convolves the next {@code count} samples of both signals; the result overwrites
     * {@code outLeft} and {@code outRight} (which may be the input arrays).
     */
    public void process(float[] inLeft, float[] inRight, float[] resultLeft, float[] resultRight, int count) {
        float[] hl = headLeft;
        float[] hr = headRight;
        float[] l = left;
        float[] r = right;
        int h = head;
        for (int i = 0; i < count; i++) {
            float xl = inLeft[i];
            float xr = inRight[i];
            // Head: newest sample at headAt + h - 1 ... oldest at headAt, both copies kept equal.
            hl[headAt] = xl;
            hl[headAt + h] = xl;
            hr[headAt] = xr;
            hr[headAt + h] = xr;
            int newest = headAt + h;
            double sl = 0;
            double sr = 0;
            for (int k = 0; k < h; k++) {
                sl += l[k] * hl[newest - k];
                sr += r[k] * hr[newest - k];
            }
            headAt = headAt + 1 == h ? 0 : headAt + 1;
            int at = (int) (time & historyMask);
            historyLeft[at] = xl;
            historyRight[at] = xr;
            int out = (int) (time & outMask);
            sl += outLeft[out];
            sr += outRight[out];
            outLeft[out] = 0;
            outRight[out] = 0;
            resultLeft[i] = (float) sl;
            resultRight[i] = (float) sr;
            time++;
            for (Segment segment : segments) {
                if ((time & (segment.size - 1)) == 0) {
                    segment.complete(time, historyLeft, historyRight, historyMask, outLeft, outRight, outMask);
                }
            }
        }
    }

    /** One run of equal partitions: an overlap-save convolution with a frequency-domain delay line. */
    private static final class Segment {
        final int offset;
        final int size;
        final int partitions;
        final int bins;
        final RadixTwoFourierTransform fft;
        // Spectra of the response partitions, bins 0..size (the rest follows by symmetry).
        final double[][] responseLeftRe;
        final double[][] responseLeftIm;
        final double[][] responseRightRe;
        final double[][] responseRightIm;
        double[][] inputLeftRe;
        double[][] inputLeftIm;
        double[][] inputRightRe;
        double[][] inputRightIm;
        double[] re;
        double[] im;
        double[] sumLeftRe;
        double[] sumLeftIm;
        double[] sumRightRe;
        double[] sumRightIm;
        int newest;

        Segment(float[] left, float[] right, int offset, int size, int partitions) {
            this.offset = offset;
            this.size = size;
            this.partitions = partitions;
            bins = size + 1;
            fft = new RadixTwoFourierTransform(2 * size);
            responseLeftRe = new double[partitions][bins];
            responseLeftIm = new double[partitions][bins];
            responseRightRe = new double[partitions][bins];
            responseRightIm = new double[partitions][bins];
            double[] re = new double[2 * size];
            double[] im = new double[2 * size];
            for (int k = 0; k < partitions; k++) {
                for (int side = 0; side < 2; side++) {
                    float[] response = side == 0 ? left : right;
                    java.util.Arrays.fill(re, 0);
                    java.util.Arrays.fill(im, 0);
                    int from = offset + k * size;
                    for (int i = 0; i < size && from + i < response.length; i++) {
                        re[i] = response[from + i];
                    }
                    fft.forward(re, im);
                    System.arraycopy(re, 0, side == 0 ? responseLeftRe[k] : responseRightRe[k], 0, bins);
                    System.arraycopy(im, 0, side == 0 ? responseLeftIm[k] : responseRightIm[k], 0, bins);
                }
            }
        }

        void prepare() {
            inputLeftRe = new double[partitions][bins];
            inputLeftIm = new double[partitions][bins];
            inputRightRe = new double[partitions][bins];
            inputRightIm = new double[partitions][bins];
            re = new double[2 * size];
            im = new double[2 * size];
            sumLeftRe = new double[bins];
            sumLeftIm = new double[bins];
            sumRightRe = new double[bins];
            sumRightIm = new double[bins];
        }

        void reset() {
            for (int k = 0; k < partitions; k++) {
                java.util.Arrays.fill(inputLeftRe[k], 0);
                java.util.Arrays.fill(inputLeftIm[k], 0);
                java.util.Arrays.fill(inputRightRe[k], 0);
                java.util.Arrays.fill(inputRightIm[k], 0);
            }
            newest = 0;
        }

        /**
         * A partition of input has completed at {@code time} (exclusive): transform the last two
         * partitions, push the spectrum into the delay line, multiply and add, transform back and
         * add the valid half to the output ring where it is due ({@code offset} samples later).
         */
        void complete(long time, float[] historyLeft, float[] historyRight, int historyMask,
                      double[] outLeft, double[] outRight, int outMask) {
            int n = 2 * size;
            long first = time - n;
            for (int i = 0; i < n; i++) {
                long t = first + i;
                if (t < 0) {
                    re[i] = 0;
                    im[i] = 0;
                } else {
                    int at = (int) (t & historyMask);
                    re[i] = historyLeft[at];
                    im[i] = historyRight[at];
                }
            }
            fft.forward(re, im);
            newest = newest + 1 == partitions ? 0 : newest + 1;
            double[] lr = inputLeftRe[newest];
            double[] li = inputLeftIm[newest];
            double[] rr = inputRightRe[newest];
            double[] ri = inputRightIm[newest];
            // Separate: X_L(k) = (Z(k) + conj Z(n-k)) / 2, X_R(k) = (Z(k) - conj Z(n-k)) / 2i.
            for (int k = 0; k < bins; k++) {
                int m = k == 0 ? 0 : n - k;
                double zr = re[k];
                double zi = im[k];
                double cr = re[m];
                double ci = -im[m];
                lr[k] = (zr + cr) * 0.5;
                li[k] = (zi + ci) * 0.5;
                rr[k] = (zi - ci) * 0.5;
                ri[k] = -(zr - cr) * 0.5;
            }
            java.util.Arrays.fill(sumLeftRe, 0);
            java.util.Arrays.fill(sumLeftIm, 0);
            java.util.Arrays.fill(sumRightRe, 0);
            java.util.Arrays.fill(sumRightIm, 0);
            int slot = newest;
            for (int p = 0; p < partitions; p++) {
                double[] xlr = inputLeftRe[slot];
                double[] xli = inputLeftIm[slot];
                double[] xrr = inputRightRe[slot];
                double[] xri = inputRightIm[slot];
                double[] hlr = responseLeftRe[p];
                double[] hli = responseLeftIm[p];
                double[] hrr = responseRightRe[p];
                double[] hri = responseRightIm[p];
                for (int k = 0; k < bins; k++) {
                    sumLeftRe[k] += xlr[k] * hlr[k] - xli[k] * hli[k];
                    sumLeftIm[k] += xlr[k] * hli[k] + xli[k] * hlr[k];
                    sumRightRe[k] += xrr[k] * hrr[k] - xri[k] * hri[k];
                    sumRightIm[k] += xrr[k] * hri[k] + xri[k] * hrr[k];
                }
                slot = slot == 0 ? partitions - 1 : slot - 1;
            }
            // Pack both real results into one complex spectrum: Y = Y_L + i·Y_R.
            for (int k = 0; k < bins; k++) {
                re[k] = sumLeftRe[k] - sumRightIm[k];
                im[k] = sumLeftIm[k] + sumRightRe[k];
            }
            for (int k = bins; k < n; k++) {
                int m = n - k;
                // conj(Y_L(m)) + i·conj(Y_R(m))
                re[k] = sumLeftRe[m] + sumRightIm[m];
                im[k] = -sumLeftIm[m] + sumRightRe[m];
            }
            fft.inverse(re, im);
            long due = time - size + offset;
            for (int i = 0; i < size; i++) {
                int at = (int) ((due + i) & outMask);
                outLeft[at] += re[size + i];
                outRight[at] += im[size + i];
            }
        }
    }
}
