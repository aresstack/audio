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
    private final LevelMode mode;
    private final double masterDb;
    private final LookaheadLimiter.Settings limiter;
    private final ExportNormalization normalization;

    private MixLevel(LevelMode mode, double masterDb, LookaheadLimiter.Settings limiter,
                     ExportNormalization normalization) {
        this.mode = mode;
        this.masterDb = Math.min(0, masterDb);
        this.limiter = limiter;
        this.normalization = normalization;
    }

    /** The compatibility mode: normalized to the peak as before, then the master level. */
    public static MixLevel legacy(double masterDb) {
        return new MixLevel(LevelMode.LEGACY_PEAK, masterDb, null, ExportNormalization.NONE);
    }

    /** The fixed level structure with the usual limiter (−1 dBFS, 80 ms), not normalized. */
    public static MixLevel fixed(double masterDb) {
        return fixed(masterDb, LookaheadLimiter.Settings.DEFAULT, ExportNormalization.NONE);
    }

    /** The fixed level structure; the normalization applies to the written file only. */
    public static MixLevel fixed(double masterDb, LookaheadLimiter.Settings limiter, ExportNormalization normalization) {
        if (limiter == null || normalization == null) {
            throw new IllegalArgumentException("A fixed level needs limiter settings and a normalization.");
        }
        return new MixLevel(LevelMode.FIXED, masterDb, limiter, normalization);
    }

    public LevelMode mode() {
        return mode;
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
        return mode == LevelMode.FIXED ? new MixLevel(mode, masterDb, limiter, ExportNormalization.NONE) : this;
    }

    /** The master bus of {@link LevelMode#FIXED}. */
    MasterBus.Settings master() {
        return MasterBus.Settings.fixed(masterDb, limiter);
    }

    @Override
    public String toString() {
        return mode == LevelMode.FIXED
                ? "fixed " + masterDb + " dB, limiter " + limiter.ceilingDb() + " dBFS, normalization " + normalization
                : "legacy peak " + masterDb + " dB";
    }
}
