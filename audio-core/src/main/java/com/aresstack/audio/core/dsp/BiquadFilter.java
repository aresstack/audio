package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.BiquadCoefficients;

/**
 * One channel of a second-order filter in transposed direct form II, the state kept in double
 * from one call to the next. New coefficients take effect with the next call and keep the
 * state, so that a changed setting does not click.
 *
 * <p>Very small state values are set to zero at the end of each call, so that a long silence
 * does not run into denormal numbers. The threshold lies so far below anything a float sample
 * can carry that the result is the same as if the whole signal had been filtered in one call.
 */
public final class BiquadFilter {
    /** Far below the smallest float (1.4e-45) and far above the smallest normal double (2.2e-308). */
    private static final double FLUSH = 1e-200;

    private volatile BiquadCoefficients coefficients;
    private double z1;
    private double z2;

    public BiquadFilter(BiquadCoefficients coefficients) {
        setCoefficients(coefficients);
    }

    /** Takes effect with the next call of {@link #process}; the state stays. */
    public void setCoefficients(BiquadCoefficients coefficients) {
        if (coefficients == null) {
            throw new IllegalArgumentException("A filter needs coefficients.");
        }
        this.coefficients = coefficients;
    }

    public BiquadCoefficients coefficients() {
        return coefficients;
    }

    /** Filters {@code count} samples from {@code from} on, in place. */
    public void process(float[] samples, int from, int count) {
        process(samples, from, count, coefficients);
    }

    /**
     * Filters with coefficients the caller has taken once for the whole block, so that filters
     * that belong together (the two sides of {@link StereoBiquad}) use the same set.
     */
    void process(float[] samples, int from, int count, BiquadCoefficients c) {
        double b0 = c.b0;
        double b1 = c.b1;
        double b2 = c.b2;
        double a1 = c.a1;
        double a2 = c.a2;
        double s1 = z1;
        double s2 = z2;
        for (int i = from, end = from + count; i < end; i++) {
            double in = samples[i];
            double out = b0 * in + s1;
            s1 = b1 * in - a1 * out + s2;
            s2 = b2 * in - a2 * out;
            samples[i] = (float) out;
        }
        z1 = Math.abs(s1) < FLUSH ? 0 : s1;
        z2 = Math.abs(s2) < FLUSH ? 0 : s2;
    }

    /** Forgets what was heard before. */
    public void reset() {
        z1 = 0;
        z2 = 0;
    }
}
