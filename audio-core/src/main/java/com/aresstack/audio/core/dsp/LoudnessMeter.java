package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioProcessor;

/**
 * Integrated loudness after ITU-R BS.1770-4 (as used by EBU R 128), in LUFS, for the export:
 * K-weighting (a high shelf and a high pass), mean square per channel over blocks of 400 ms that
 * overlap by 75 %, an absolute gate at −70 LUFS and a relative gate 10 LU below the loudness of
 * what passed the first gate. Left and right count with weight 1. The sound passes unchanged.
 *
 * <p>This is a tap for the offline mix, not for the audio thread: it keeps the energy of every
 * 100 ms step until the end, because the relative gate needs all of them, and its store grows
 * now and then (by doubling; 12 minutes are 7 200 values).
 */
public final class LoudnessMeter implements AudioProcessor {
    private static final double ABSOLUTE_GATE = -70;
    private static final double RELATIVE_GATE = -10;
    private static final double OFFSET = -0.691;

    // K-weighting, two biquads per side in transposed direct form II, in double
    private double s1b0, s1b1, s1b2, s1a1, s1a2;
    private double s2b0, s2b1, s2b2, s2a1, s2a2;
    private double l1z1, l1z2, l2z1, l2z2, r1z1, r1z2, r2z1, r2z2;

    private int step;
    private int inStep;
    private double stepSum;
    private double[] steps = new double[0];
    private int stepCount;

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("A rate is more than 0 frames per second.");
        }
        // Coefficients for any rate, as in libebur128 (equal to the tables of BS.1770 at 48 kHz).
        double f0 = 1681.974450955533;
        double gainDb = 3.999843853973347;
        double q = 0.7071752369554196;
        double k = Math.tan(Math.PI * f0 / sampleRate);
        double vh = Math.pow(10, gainDb / 20);
        double vb = Math.pow(vh, 0.4996667741545416);
        double a0 = 1 + k / q + k * k;
        s1b0 = (vh + vb * k / q + k * k) / a0;
        s1b1 = 2 * (k * k - vh) / a0;
        s1b2 = (vh - vb * k / q + k * k) / a0;
        s1a1 = 2 * (k * k - 1) / a0;
        s1a2 = (1 - k / q + k * k) / a0;
        f0 = 38.13547087602444;
        q = 0.5003270373238773;
        k = Math.tan(Math.PI * f0 / sampleRate);
        a0 = 1 + k / q + k * k;
        s2b0 = 1;
        s2b1 = -2;
        s2b2 = 1;
        s2a1 = 2 * (k * k - 1) / a0;
        s2a2 = (1 - k / q + k * k) / a0;
        l1z1 = l1z2 = l2z1 = l2z2 = r1z1 = r1z2 = r2z1 = r2z2 = 0;
        step = Math.max(1, (int) Math.round(sampleRate * 0.1));
        inStep = 0;
        stepSum = 0;
        steps = new double[1024];
        stepCount = 0;
    }

    @Override
    public void process(AudioBlock block) {
        float[] left = block.left;
        float[] right = block.right;
        for (int i = 0, frames = block.frames(); i < frames; i++) {
            double x = left[i];
            double y = s1b0 * x + l1z1;
            l1z1 = s1b1 * x - s1a1 * y + l1z2;
            l1z2 = s1b2 * x - s1a2 * y;
            double zl = s2b0 * y + l2z1;
            l2z1 = s2b1 * y - s2a1 * zl + l2z2;
            l2z2 = s2b2 * y - s2a2 * zl;
            x = right[i];
            y = s1b0 * x + r1z1;
            r1z1 = s1b1 * x - s1a1 * y + r1z2;
            r1z2 = s1b2 * x - s1a2 * y;
            double zr = s2b0 * y + r2z1;
            r2z1 = s2b1 * y - s2a1 * zr + r2z2;
            r2z2 = s2b2 * y - s2a2 * zr;
            stepSum += zl * zl + zr * zr;
            if (++inStep == step) {
                if (stepCount == steps.length) {
                    steps = java.util.Arrays.copyOf(steps, steps.length * 2);
                }
                steps[stepCount++] = stepSum;
                stepSum = 0;
                inStep = 0;
            }
        }
    }

    /**
     * The integrated loudness of everything since {@link #prepare}, in LUFS; negative infinity
     * if nothing passed the gates, for instance silence or less than 400 ms of sound. An
     * unfinished last step of less than 100 ms is not counted.
     */
    public double integrated() {
        int blocks = stepCount - 3;
        if (blocks <= 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double[] energy = new double[blocks];
        double norm = 4.0 * step;
        double sum = 0;
        int passed = 0;
        for (int b = 0; b < blocks; b++) {
            energy[b] = (steps[b] + steps[b + 1] + steps[b + 2] + steps[b + 3]) / norm;
            if (loudness(energy[b]) > ABSOLUTE_GATE) {
                sum += energy[b];
                passed++;
            }
        }
        if (passed == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double relative = loudness(sum / passed) + RELATIVE_GATE;
        sum = 0;
        passed = 0;
        for (int b = 0; b < blocks; b++) {
            double l = loudness(energy[b]);
            if (l > ABSOLUTE_GATE && l > relative) {
                sum += energy[b];
                passed++;
            }
        }
        return passed == 0 ? Double.NEGATIVE_INFINITY : loudness(sum / passed);
    }

    private static double loudness(double energy) {
        return energy > 0 ? OFFSET + 10 * Math.log10(energy) : Double.NEGATIVE_INFINITY;
    }
}
