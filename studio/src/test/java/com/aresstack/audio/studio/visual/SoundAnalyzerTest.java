package com.aresstack.audio.studio.visual;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Properties;
import org.junit.Test;

public final class SoundAnalyzerTest {
    private static final int RATE = 44100;

    /** A feed that plays one sine at full scale (or nothing). */
    private static SoundFeed sine(double hz, double amplitude) {
        return new SoundFeed() {
            @Override
            public boolean window(float[] left, float[] right, int count) {
                for (int i = 0; i < count; i++) {
                    float v = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / RATE));
                    left[i] = v;
                    right[i] = v;
                }
                return true;
            }

            @Override
            public int sampleRate() {
                return RATE;
            }
        };
    }

    @Test
    public void aSineLightsTheBandOfItsPitch() {
        VisualSettings settings = VisualSettings.DEFAULTS;
        SoundAnalyzer analyzer = new SoundAnalyzer(settings, RATE);
        double hz = 1000;
        SoundPicture picture = analyzer.analyze(sine(hz, 1));
        int loudest = 0;
        for (int b = 1; b < picture.rawBands.length; b++) {
            if (picture.rawBands[b] > picture.rawBands[loudest]) {
                loudest = b;
            }
        }
        double ratio = Math.pow(settings.highestHz() / settings.lowestHz(), 1.0 / settings.bands());
        double low = settings.lowestHz() * Math.pow(ratio, loudest);
        double high = low * ratio;
        assertTrue("1 kHz lies in band " + low + ".." + high, hz >= low / ratio && hz <= high * ratio);
        assertEquals("a full-scale sine is near 0 dB", 1f, picture.rawBands[loudest], 0.05f);
        assertEquals(1f, picture.peakLeft, 0.01f);
        assertEquals(Math.sqrt(0.5), picture.rmsLeft, 0.01);
        assertTrue(picture.sounding);
        assertTrue("the middle is lit", picture.middle > picture.treble);
    }

    @Test
    public void sidesInOppositePhaseDoNotCancel() {
        SoundAnalyzer analyzer = new SoundAnalyzer(VisualSettings.DEFAULTS, RATE);
        SoundPicture picture = analyzer.picture();
        picture.sounding = true;
        for (int i = 0; i < picture.left.length; i++) {
            float v = (float) Math.sin(2 * Math.PI * 1000 * i / RATE);
            picture.left[i] = v;
            picture.right[i] = -v;
        }
        analyzer.analyzeWindow(0);
        float loudest = 0;
        for (float band : picture.rawBands) {
            loudest = Math.max(loudest, band);
        }
        assertEquals(1f, loudest, 0.05f);
    }

    @Test
    public void silenceGivesNothing() {
        SoundAnalyzer analyzer = new SoundAnalyzer(VisualSettings.DEFAULTS, RATE);
        SoundPicture picture = analyzer.analyze(SoundFeed.SILENT);
        assertFalse(picture.sounding);
        assertEquals(0f, picture.peakLeft, 0f);
        for (float band : picture.bands) {
            assertEquals(0f, band, 0f);
        }
        assertEquals(List.of(), picture.lights);
    }

    @Test
    public void bandsRiseFastAndFallSlowly() {
        VisualSettings settings = VisualSettings.DEFAULTS;
        SoundAnalyzer analyzer = new SoundAnalyzer(settings, RATE);
        SoundPicture picture = analyzer.analyze(sine(1000, 1));
        int loudest = 0;
        for (int b = 1; b < picture.bands.length; b++) {
            if (picture.bands[b] > picture.bands[loudest]) {
                loudest = b;
            }
        }
        float risen = picture.bands[loudest];
        assertEquals(picture.rawBands[loudest] * settings.attack(), risen, 1e-4);
        analyzer.analyze(SoundFeed.SILENT);
        assertEquals(risen * (1 - settings.release()), picture.bands[loudest], 1e-4);
    }

    @Test
    public void aBassBurstAfterQuietIsABeat() {
        SoundAnalyzer analyzer = new SoundAnalyzer(VisualSettings.DEFAULTS, RATE);
        SoundPicture picture = analyzer.picture();
        // quiet bass for a while, then a loud one: one beat
        for (int i = 0; i < 20; i++) {
            fill(picture, 60, 0.001);
            analyzer.analyzeWindow(i * 0.05);
        }
        assertEquals(0, picture.beats);
        fill(picture, 60, 1);
        analyzer.analyzeWindow(1.0);
        assertEquals(1, picture.beats);
        assertEquals(1f, picture.beat, 0f);
        // right after it, even louder: too soon for the next one
        fill(picture, 60, 1);
        analyzer.analyzeWindow(1.0 + VisualSettings.DEFAULTS.beatGap() / 2);
        assertEquals(1, picture.beats);
    }

    private static void fill(SoundPicture picture, double hz, double amplitude) {
        picture.sounding = true;
        for (int i = 0; i < picture.left.length; i++) {
            float v = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / RATE));
            picture.left[i] = v;
            picture.right[i] = v;
        }
    }

    @Test
    public void settingsGoThroughTheFileAndStayInRange() {
        Properties saved = new Properties();
        VisualSettings changed = VisualSettings.DEFAULTS.showing("tunnel");
        changed.into(saved);
        assertEquals(changed, VisualSettings.from(saved));
        saved.setProperty("visualizer.windowFrames", "3000");
        saved.setProperty("visualizer.bands", "nonsense");
        saved.setProperty("visualizer.framesPerSecond", "100000");
        VisualSettings read = VisualSettings.from(saved);
        assertEquals("a power of two", 4096, read.windowFrames());
        assertEquals(VisualSettings.DEFAULTS.bands(), read.bands());
        assertEquals(120, read.framesPerSecond());
        assertEquals("tunnel", read.effect());
    }
}
