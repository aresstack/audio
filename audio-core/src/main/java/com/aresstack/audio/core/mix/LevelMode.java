package com.aresstack.audio.core.mix;

/**
 * How the loudness of the whole mix is set (architecture review E8, decision O3).
 */
public enum LevelMode {
    /**
     * As Audio Studio has always done it, for pieces that exist: offline, the finished mix is
     * normalized to its peak ({@link LegacyPeakNormalization}). A live preview cannot know the
     * peak in advance; it uses the factor the last offline mix found
     * ({@link MasterBus.Settings#legacy}).
     */
    LEGACY_PEAK,
    /**
     * A fixed level structure: the master level, then the {@link com.aresstack.audio.core.dsp.LookaheadLimiter}.
     * Live playback, preview and export run the same chain; only the export may normalize at the
     * very end ({@link ExportNormalization}). New pieces start with this mode.
     */
    FIXED
}
