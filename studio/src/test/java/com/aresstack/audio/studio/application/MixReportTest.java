package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.core.mix.ExportNormalization;
import org.junit.Test;

/**
 * What the export says about its normalization (acceptance finding on the level controls): a
 * silent mix or one whose loudness cannot be measured is not normalized, and the message must
 * not claim that a target was reached; a loudness target the peaks do not allow is named as such.
 */
public final class MixReportTest {
    @Test
    public void aSilentMixIsNotSaidToHaveReachedTheTarget() {
        for (ExportNormalization wanted : new ExportNormalization[]{ExportNormalization.peak(-1),
                ExportNormalization.loudness(-16)}) {
            float peak = 0f;
            double loudness = Double.NEGATIVE_INFINITY;
            String said = MixReport.normalization(wanted, peak, loudness, wanted.gain(peak, loudness));
            assertEquals("Nicht normalisiert: Die Mischung ist still.", said);
        }
        // Practically silent is silent too: the normalization leaves it alone, so nothing was reached.
        ExportNormalization peakTarget = ExportNormalization.peak(-1);
        assertEquals("Nicht normalisiert: Die Mischung ist still.",
                MixReport.normalization(peakTarget, 5e-5f, -90, peakTarget.gain(5e-5f, -90)));
    }

    @Test
    public void aLoudnessThatCannotBeMeasuredIsNotSaidToBeReachedNorBlamedOnThePeaks() {
        ExportNormalization wanted = ExportNormalization.loudness(-16);
        for (double loudness : new double[]{Double.NEGATIVE_INFINITY, Double.NaN}) {
            float peak = 0.5f; // audible, but too short or too quiet for a loudness
            String said = MixReport.normalization(wanted, peak, loudness, wanted.gain(peak, loudness));
            assertTrue(said, said.startsWith("Nicht normalisiert: Die Lautheit ließ sich nicht messen"));
            assertFalse(said, said.contains("gebracht"));
            assertFalse(said, said.contains("Spitzen"));
        }
    }

    @Test
    public void aLoudnessTargetThePeaksDoNotAllowIsSaidAsItCameOut() {
        ExportNormalization wanted = ExportNormalization.loudness(-16, -1);
        float peak = 0.5f;       // −6 dBFS: only 5 dB of room up to −1 dBFS
        double loudness = -30;   // the target would need 14 dB
        double gain = wanted.gain(peak, loudness);
        assertEquals(5.0, 20 * Math.log10(gain), 0.05);
        assertEquals("Lautheit -25,0 LUFS statt -16,0 LUFS (+5,0 dB): mehr lassen die Spitzen bei -1,0 dBFS nicht zu",
                MixReport.normalization(wanted, peak, loudness, gain));
    }

    @Test
    public void aTargetThatWasReachedIsSaidSo() {
        ExportNormalization loud = ExportNormalization.loudness(-16, -1);
        assertEquals("Lautheit auf -16,0 LUFS gebracht (+4,0 dB)",
                MixReport.normalization(loud, 0.25f, -20, loud.gain(0.25f, -20)));
        ExportNormalization peak = ExportNormalization.peak(-1);
        assertEquals("Spitze auf -1,0 dBFS gebracht (+5,0 dB)",
                MixReport.normalization(peak, 0.5f, -30, peak.gain(0.5f, -30)));
        assertNull(MixReport.normalization(ExportNormalization.NONE, 0.5f, -30, 1));
    }
}
