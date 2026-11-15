package com.aresstack.audio.core.mix;

/**
 * The loudness of the whole as Audio Studio has always set it: the finished mix is scaled so
 * that its highest sample lies at 0.92, never squeezed, and a quiet mix is raised by 12 dB at
 * most; then the master level (0 dB or less) is applied. This needs the peak of the whole mix
 * before the first sample is written, so it only works offline. It is the {@code LEGACY_PEAK}
 * level mode of the architecture review; a fixed level structure with a limiter follows apart.
 */
public final class LegacyPeakNormalization {
    /** Where the highest sample of the mix ends up. */
    public static final double CEILING = 0.92;
    /** The most a quiet mix is raised: a factor of 4, i.e. 12 dB. */
    public static final double MOST = 4;

    private LegacyPeakNormalization() {
    }

    /**
     * The factor for a mix with this peak and master level. A mix that is practically silent
     * (peak at most 1e-4) stays as it is.
     */
    public static float gain(float peak, double masterDb) {
        if (peak > 1e-4f) {
            return (float) (Math.min(MOST, CEILING / peak) * Math.pow(10, Math.min(0, masterDb) / 20));
        }
        return 1f;
    }
}
