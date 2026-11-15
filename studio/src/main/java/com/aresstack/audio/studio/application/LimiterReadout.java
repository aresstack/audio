package com.aresstack.audio.studio.application;

import java.util.Locale;

/**
 * What the stage says about the limiter of live playback ("export old, live new"): how far it
 * turns the sound down right now, in dB. The strongest reduction stays readable for a moment,
 * so a short peak is not gone before it was seen. Nothing is said while the limiter rests.
 *
 * <p>Used from the event thread alone; the gain comes from the audio thread as one number.
 */
public final class LimiterReadout {
    /** Below this the limiter is not worth a word: a tenth of a dB. */
    static final double SILENT_DB = 0.1;
    /** How long the strongest reduction stays in the line. */
    static final long HOLD_NANOS = 1_500_000_000L;

    private double heldDb;
    private long heldAt;

    /**
     * @param gain  the gain of the limiter now, 1 while it rests
     * @param nanos the time of this reading
     * @return e.g. {@code "Limiter −2,3 dB"}, or an empty text while the limiter rests
     */
    public String read(float gain, long nanos) {
        double db = gain > 0 && gain < 1 ? -20 * Math.log10(gain) : 0;
        if (db >= heldDb || nanos - heldAt > HOLD_NANOS) {
            heldDb = db;
            heldAt = nanos;
        }
        return heldDb < SILENT_DB ? "" : String.format(Locale.GERMANY, "Limiter −%.1f dB", heldDb);
    }

    /** Playback stopped or another piece plays: nothing is held. */
    public void clear() {
        heldDb = 0;
    }
}
