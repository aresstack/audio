package com.aresstack.audio.studio.application;

import com.aresstack.audio.core.mix.ExportNormalization;
import com.aresstack.audio.core.mix.LevelMode;
import com.aresstack.audio.core.mix.OfflineMix;
import com.aresstack.audio.studio.mix.MixOutcome;
import java.util.Locale;

/** Says in the user's words what a mix found out about its level: what the limiter did, what the normalization reached. */
public final class MixReport {
    /** Below this the limiter's work is not worth a number: rounding of a gain that is 1. */
    private static final double UNTOUCHED_DB = 0.05;

    /** At most this sample peak is silence: {@link ExportNormalization} does not touch such a mix. */
    private static final float SILENT_PEAK = 1e-4f;

    private MixReport() {
    }

    /**
     * How far the limiter turned the mix down at its loudest place, as a positive number of dB;
     * 0 if it left the mix alone, and NaN for a mix in the compatibility mode, which has no limiter.
     */
    public static double limiterReductionDb(MixOutcome outcome) {
        if (outcome == null || outcome.mode() != LevelMode.FIXED || outcome.fixed() == null) {
            return Double.NaN;
        }
        float gain = outcome.fixed().lowestLimiterGain();
        if (!(gain > 0) || gain >= 1) {
            return 0;
        }
        double db = -20 * Math.log10(gain);
        return db < UNTOUCHED_DB ? 0 : db;
    }

    /** What the limiter did, in a few words; {@code null} for a mix in the compatibility mode. */
    public static String limiter(MixOutcome outcome) {
        double db = limiterReductionDb(outcome);
        if (Double.isNaN(db)) {
            return null;
        }
        return db == 0 ? "Limiter greift nicht ein"
                : String.format(Locale.GERMANY, "Limiter senkt die Spitzen um bis zu %.1f dB", db);
    }

    /** The same in the few characters a status line has, as a meter would show it; {@code null} without a limiter. */
    public static String limiterShort(MixOutcome outcome) {
        double db = limiterReductionDb(outcome);
        if (Double.isNaN(db)) {
            return null;
        }
        return db == 0 ? "Limiter ±0 dB" : String.format(Locale.GERMANY, "Limiter −%.1f dB", db);
    }

    /**
     * What the normalization of an exported file did; {@code null} if none was asked for. A mix
     * that is silent or whose loudness cannot be measured is left as it is, and that is what is
     * said: never that a target was reached. A loudness target the peaks do not allow is said as
     * it came out.
     */
    public static String normalization(MixOutcome outcome, ExportNormalization wanted) {
        if (outcome == null || outcome.fixed() == null) {
            return null;
        }
        OfflineMix.Result result = outcome.fixed();
        return normalization(wanted, result.peakBeforeNormalization(), result.loudnessBeforeNormalization(),
                result.normalizationGain());
    }

    /**
     * The same from what the mix measured before the normalization (sample peak, integrated
     * loudness in LUFS) and the factor it then applied.
     */
    public static String normalization(ExportNormalization wanted, float peak, double loudness, double gain) {
        if (wanted == null || wanted.kind() == ExportNormalization.Kind.NONE) {
            return null;
        }
        // The same bound below which ExportNormalization leaves a mix alone.
        if (!(peak > SILENT_PEAK)) {
            return "Nicht normalisiert: Die Mischung ist still.";
        }
        double gainDb = 20 * Math.log10(gain);
        if (!(gain > 0) || Double.isNaN(gainDb) || Double.isInfinite(gainDb)) {
            return "Nicht normalisiert: Der Pegel der Mischung ließ sich nicht messen.";
        }
        if (wanted.kind() == ExportNormalization.Kind.PEAK) {
            return String.format(Locale.GERMANY, "Spitze auf %.1f dBFS gebracht (%+.1f dB)", wanted.target(), gainDb);
        }
        if (Double.isNaN(loudness) || Double.isInfinite(loudness)) {
            return "Nicht normalisiert: Die Lautheit ließ sich nicht messen (die Mischung ist zu kurz oder zu leise).";
        }
        double reached = loudness + gainDb;
        if (reached < wanted.target() - 0.1) {
            return String.format(Locale.GERMANY,
                    "Lautheit %.1f LUFS statt %.1f LUFS (%+.1f dB): mehr lassen die Spitzen bei %.1f dBFS nicht zu",
                    reached, wanted.target(), gainDb, wanted.peakCeilingDb());
        }
        return String.format(Locale.GERMANY, "Lautheit auf %.1f LUFS gebracht (%+.1f dB)", reached, gainDb);
    }
}
