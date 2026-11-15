package com.aresstack.audio.studio.mix.legacy;

/**
 * A second-order filter on float samples. The coefficients follow the formulas of
 * the Audio EQ Cookbook, as the equalizers of AskAI's audio-dsp module do; that
 * module works on 16-bit speech, so the formulas were taken over and the float
 * processing written anew for music.
 */
final class LegacyBiquad {
    private final double b0;
    private final double b1;
    private final double b2;
    private final double a1;
    private final double a2;
    private double z1;
    private double z2;

    private LegacyBiquad(double b0, double b1, double b2, double a0, double a1, double a2) {
        this.b0 = b0 / a0;
        this.b1 = b1 / a0;
        this.b2 = b2 / a0;
        this.a1 = a1 / a0;
        this.a2 = a2 / a0;
    }

    /** Raises or lowers everything above a frequency by so many dB. */
    static LegacyBiquad highShelf(int sampleRate, double frequency, double gainDb) {
        double a = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * Math.min(frequency, sampleRate * 0.45) / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / 2 * Math.sqrt(2);
        double root = 2 * Math.sqrt(a) * alpha;
        return new LegacyBiquad(a * ((a + 1) + (a - 1) * cos + root),
                -2 * a * ((a - 1) + (a + 1) * cos),
                a * ((a + 1) + (a - 1) * cos - root),
                (a + 1) - (a - 1) * cos + root,
                2 * ((a - 1) - (a + 1) * cos),
                (a + 1) - (a - 1) * cos - root);
    }

    /** Lets through what lies below a frequency. */
    static LegacyBiquad lowPass(int sampleRate, double frequency) {
        double w0 = 2 * Math.PI * Math.min(frequency, sampleRate * 0.45) / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * Math.sqrt(0.5));
        return new LegacyBiquad((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    /** A filter with the same coefficients and a state of its own, for the other channel. */
    LegacyBiquad again() {
        return new LegacyBiquad(b0, b1, b2, 1, a1, a2);
    }

    void process(float[] samples) {
        double s1 = z1;
        double s2 = z2;
        for (int i = 0; i < samples.length; i++) {
            double in = samples[i];
            double out = b0 * in + s1;
            s1 = b1 * in - a1 * out + s2;
            s2 = b2 * in - a2 * out;
            samples[i] = (float) out;
        }
        z1 = Math.abs(s1) < 1e-30 ? 0 : s1;
        z2 = Math.abs(s2) < 1e-30 ? 0 : s2;
    }
}
