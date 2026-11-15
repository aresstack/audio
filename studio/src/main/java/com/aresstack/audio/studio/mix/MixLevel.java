package com.aresstack.audio.studio.mix;

import com.aresstack.audio.core.dsp.LookaheadLimiter;
import com.aresstack.audio.core.mix.ExportNormalization;
import com.aresstack.audio.core.mix.LevelMode;
import com.aresstack.audio.core.mix.MasterBus;

/**
 * How loud the finished mix of a piece is made (architecture review E8, decision O3):
 *
 * <ul>
 *   <li>{@link LevelMode#LEGACY_PEAK}: as Audio Studio has always done it; every piece that
 *       exists keeps its sound until the user switches it on purpose.</li>
 *   <li>{@link LevelMode#FIXED}: the master level, then the look-ahead limiter; only a WAV
 *       export may normalize at the very end, to a peak or a loudness.</li>
 * </ul>
 */
public final class MixLevel {
    /**
     * The fixed make-up gain of {@link LevelMode#FIXED} in the studio: +12 dB, the most the
     * peak normalization of {@code LEGACY_PEAK} ever raised a mix. The stems of the studio are
     * rendered with headroom (the three reference pieces peak at −11 to −15 dBFS on the stage),
     * and the old normalization raised all three by exactly these 12 dB. With the same fixed gain
     * a piece that was raised that much keeps its level; a louder piece, which the old way
     * turned down as a whole, is now held by the limiter instead.
     */
    public static final double MAKEUP_DB = 12;

    private final LevelMode mode;
    private final double makeupDb;
    private final double masterDb;
    private final LookaheadLimiter.Settings limiter;
    private final ExportNormalization normalization;

    private MixLevel(LevelMode mode, double makeupDb, double masterDb, LookaheadLimiter.Settings limiter,
                     ExportNormalization normalization) {
        this.mode = mode;
        this.makeupDb = makeupDb;
        this.masterDb = Math.min(0, masterDb);
        this.limiter = limiter;
        this.normalization = normalization;
    }

    /** The compatibility mode: normalized to the peak as before, then the master level. */
    public static MixLevel legacy(double masterDb) {
        return new MixLevel(LevelMode.LEGACY_PEAK, 0, masterDb, null, ExportNormalization.NONE);
    }

    /** The fixed level structure with the studio's make-up gain and the usual limiter (−1 dBFS, 80 ms), not normalized. */
    public static MixLevel fixed(double masterDb) {
        return fixed(masterDb, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.NONE);
    }

    /** The fixed level structure with the studio's make-up gain; the normalization applies to the written file only. */
    public static MixLevel fixed(double masterDb, LookaheadLimiter.Settings limiter, ExportNormalization normalization) {
        return fixed(MAKEUP_DB, masterDb, limiter, normalization);
    }

    /** The fixed level structure with another make-up gain (0 to +24 dB). */
    public static MixLevel fixed(double makeupDb, double masterDb, LookaheadLimiter.Settings limiter,
                                 ExportNormalization normalization) {
        if (limiter == null || normalization == null) {
            throw new IllegalArgumentException("A fixed level needs limiter settings and a normalization.");
        }
        if (!(makeupDb >= 0 && makeupDb <= 24)) {
            throw new IllegalArgumentException("A make-up gain lies between 0 and +24 dB: " + makeupDb);
        }
        return new MixLevel(LevelMode.FIXED, makeupDb, masterDb, limiter, normalization);
    }

    public LevelMode mode() {
        return mode;
    }

    /** The fixed make-up gain in dB; 0 in the compatibility mode. */
    public double makeupDb() {
        return makeupDb;
    }

    /** How far below full level the mix is, 0 or less. */
    public double masterDb() {
        return masterDb;
    }

    /** The limiter of {@link LevelMode#FIXED}; {@code null} in the compatibility mode. */
    public LookaheadLimiter.Settings limiter() {
        return limiter;
    }

    /** What the export does at the very end; always none in the compatibility mode. */
    public ExportNormalization normalization() {
        return normalization;
    }

    /** The same level without a normalization, as live playback and preview hear it. */
    public MixLevel live() {
        return mode == LevelMode.FIXED ? new MixLevel(mode, makeupDb, masterDb, limiter, ExportNormalization.NONE) : this;
    }

    /** The master bus of {@link LevelMode#FIXED}. */
    MasterBus.Settings master() {
        return MasterBus.Settings.fixed(makeupDb, masterDb, limiter);
    }

    @Override
    public String toString() {
        return mode == LevelMode.FIXED
                ? "fixed +" + makeupDb + " dB make-up, master " + masterDb + " dB, limiter " + limiter.ceilingDb() + " dBFS, normalization " + normalization
                : "legacy peak " + masterDb + " dB";
    }
}
