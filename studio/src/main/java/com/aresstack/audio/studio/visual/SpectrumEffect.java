package com.aresstack.audio.studio.visual;

import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

/** The classic analyser: one bar per band, low at the left, with a cap that falls slowly from the last peak. */
final class SpectrumEffect implements Effect {
    /** The share of a bar's slot that is the bar; the rest is the gap. */
    private static final float BAR_SHARE = 0.78f;
    /** How far a cap falls per picture, as a share of the height. */
    private static final float CAP_FALL = 0.012f;
    private float[] caps = new float[0];

    @Override
    public String key() {
        return "spectrum";
    }

    @Override
    public String label() {
        return "Spektrum";
    }

    @Override
    public String tip() {
        return "Frequenzspektrum: ein Balken je Band, tief links, hoch rechts";
    }

    @Override
    public void paint(Graphics2D g, int width, int height, SoundPicture sound, VisualPalette palette,
                      VisualSettings settings) {
        Effect.clear(g, width, height, palette.background());
        float[] bands = sound.bands;
        if (caps.length != bands.length) {
            caps = new float[bands.length];
        }
        float margin = Math.max(8, width * 0.02f);
        float usable = width - 2 * margin;
        float slot = usable / bands.length;
        float floorY = height - margin;
        float top = margin;
        GradientPaint heat = new GradientPaint(0, floorY, palette.low(), 0, top, palette.high());
        for (int b = 0; b < bands.length; b++) {
            float value = bands[b];
            float x = margin + b * slot + slot * (1 - BAR_SHARE) / 2;
            float barHeight = value * (floorY - top);
            Rectangle2D bar = new Rectangle2D.Float(x, floorY - barHeight, slot * BAR_SHARE, barHeight);
            g.setPaint(heat);
            g.fill(bar);
            caps[b] = Math.max(value, caps[b] - CAP_FALL);
            float capY = floorY - caps[b] * (floorY - top);
            g.setColor(palette.ink());
            g.fill(new Rectangle2D.Float(x, capY - 3, slot * BAR_SHARE, 2.5f));
        }
        g.setColor(palette.faint());
        g.draw(new java.awt.geom.Line2D.Float(margin, floorY + 1, width - margin, floorY + 1));
        if (!sound.sounding) {
            Labels.quiet(g, width, height, palette);
        }
    }
}
