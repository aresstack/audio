package com.aresstack.audio.core.mix;

/**
 * The optional last step of a WAV export in {@link LevelMode#FIXED}: the finished mix, after the
 * limiter, is scaled by one factor so that it reaches a target. Live playback never normalizes.
 * The factor is found in a first pass over the whole mix and applied in a second; the balance
 * and the dynamics of the mix stay as they are.
 */
public final class ExportNormalization {
    public enum Kind {
        /** The mix is written as it comes out of the limiter. */
        NONE,
        /** The highest sample is brought to the target, in dBFS (sample peak). */
        PEAK,
        /**
         * The integrated loudness after ITU-R BS.1770 is brought to the target, in LUFS, but the
         * highest sample never above the peak ceiling: if the target would need more, the factor
         * stops at the ceiling and the result says how loud the mix came out.
         */
        LOUDNESS
    }

    /** Written as it comes out of the limiter. */
    public static final ExportNormalization NONE = new ExportNormalization(Kind.NONE, 0, 0);

    private final Kind kind;
    private final double target;
    private final double peakCeilingDb;

    private ExportNormalization(Kind kind, double target, double peakCeilingDb) {
        this.kind = kind;
        this.target = target;
        this.peakCeilingDb = peakCeilingDb;
    }

    /** The highest sample at {@code targetDb} dBFS (at most 0). */
    public static ExportNormalization peak(double targetDb) {
        if (!(targetDb <= 0) || targetDb < -60) {
            throw new IllegalArgumentException("A peak target lies between -60 and 0 dBFS: " + targetDb);
        }
        return new ExportNormalization(Kind.PEAK, targetDb, targetDb);
    }

    /** The integrated loudness at {@code targetLufs}, peaks at most at −1 dBFS. */
    public static ExportNormalization loudness(double targetLufs) {
        return loudness(targetLufs, -1);
    }

    /** The integrated loudness at {@code targetLufs}, peaks at most at {@code peakCeilingDb} dBFS. */
    public static ExportNormalization loudness(double targetLufs, double peakCeilingDb) {
        if (!(targetLufs <= 0) || targetLufs < -60) {
            throw new IllegalArgumentException("A loudness target lies between -60 and 0 LUFS: " + targetLufs);
        }
        if (!(peakCeilingDb <= 0) || peakCeilingDb < -60) {
            throw new IllegalArgumentException("A peak ceiling lies between -60 and 0 dBFS: " + peakCeilingDb);
        }
        return new ExportNormalization(Kind.LOUDNESS, targetLufs, peakCeilingDb);
    }

    public Kind kind() {
        return kind;
    }

    /** dBFS for {@link Kind#PEAK}, LUFS for {@link Kind#LOUDNESS}. */
    public double target() {
        return target;
    }

    /** The highest sample the normalization may produce, in dBFS. */
    public double peakCeilingDb() {
        return peakCeilingDb;
    }

    /**
     * The factor for a mix with this sample peak and integrated loudness (LUFS, negative
     * infinity if unknown). A practically silent mix (peak at most 1e-4) stays as it is, and so
     * does a mix whose loudness cannot be measured.
     */
    public double gain(float peak, double loudness) {
        if (kind == Kind.NONE || !(peak > 1e-4f)) {
            return 1;
        }
        double ceiling = Math.pow(10, peakCeilingDb / 20);
        if (kind == Kind.PEAK) {
            return ceiling / peak;
        }
        if (Double.isInfinite(loudness) || Double.isNaN(loudness)) {
            return 1;
        }
        return Math.min(Math.pow(10, (target - loudness) / 20), ceiling / peak);
    }

    @Override
    public String toString() {
        switch (kind) {
            case PEAK:
                return "peak " + target + " dBFS";
            case LOUDNESS:
                return "loudness " + target + " LUFS (peaks <= " + peakCeilingDb + " dBFS)";
            default:
                return "none";
        }
    }
}
