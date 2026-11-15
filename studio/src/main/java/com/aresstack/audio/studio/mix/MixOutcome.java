package com.aresstack.audio.studio.mix;

import com.aresstack.audio.core.mix.LevelMode;
import com.aresstack.audio.core.mix.MasterBus;
import com.aresstack.audio.core.mix.OfflineMix;

/**
 * What a mix to a file found out about its level. In the compatibility mode this is the factor
 * the peak normalization applied: kept with the stems (manifest), it lets a live preview play the
 * piece exactly as loud as the file ({@link #previewMaster()}).
 */
public final class MixOutcome {
    private final LevelMode mode;
    private final float legacyFactor;
    private final OfflineMix.Result fixed;

    private MixOutcome(LevelMode mode, float legacyFactor, OfflineMix.Result fixed) {
        this.mode = mode;
        this.legacyFactor = legacyFactor;
        this.fixed = fixed;
    }

    static MixOutcome legacy(float factor) {
        return new MixOutcome(LevelMode.LEGACY_PEAK, factor, null);
    }

    static MixOutcome fixed(OfflineMix.Result result) {
        return new MixOutcome(LevelMode.FIXED, Float.NaN, result);
    }

    public LevelMode mode() {
        return mode;
    }

    /** The factor of the peak normalization including the master level; NaN in {@link LevelMode#FIXED}. */
    public float legacyFactor() {
        return legacyFactor;
    }

    /** What the fixed mix measured and applied; {@code null} in the compatibility mode. */
    public OfflineMix.Result fixed() {
        return fixed;
    }

    /**
     * The master bus a live preview of the same piece uses, for the compatibility mode: the
     * factor of this mix as a fixed gain. {@code null} in {@link LevelMode#FIXED}, where the
     * preview takes {@link MixLevel#live()}.
     */
    public MasterBus.Settings previewMaster() {
        return mode == LevelMode.LEGACY_PEAK ? MasterBus.Settings.legacy(legacyFactor) : null;
    }
}
