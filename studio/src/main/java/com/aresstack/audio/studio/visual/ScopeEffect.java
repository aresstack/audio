package com.aresstack.audio.studio.visual;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/**
 * The oscilloscope: the waveform of both sides one above the other, and beside it the goniometer
 * (left against right), which shows how wide the stereo picture is.
 */
final class ScopeEffect implements Effect {
    /** The share of the width the waveforms take; the goniometer has the rest. */
    private static final float WAVE_SHARE = 0.68f;

    @Override
    public String key() {
        return "scope";
    }

    @Override
    public String label() {
        return "Oszi";
    }

    @Override
    public String tip() {
        return "Oszilloskop: die Wellenform links und rechts, daneben das Stereobild (Goniometer)";
    }

    @Override
    public void paint(Graphics2D g, int width, int height, SoundPicture sound, VisualPalette palette,
                      VisualSettings settings) {
        Effect.clear(g, width, height, palette.background());
        float margin = Math.max(8, width * 0.02f);
        float waveWidth = (width - 3 * margin) * WAVE_SHARE;
        float rowHeight = (height - 3 * margin) / 2f;
        g.setStroke(new BasicStroke(1.4f));
        wave(g, sound.left, margin, margin, waveWidth, rowHeight, palette.low(), palette);
        wave(g, sound.right, margin, 2 * margin + rowHeight, waveWidth, rowHeight, palette.cool(), palette);
        float size = Math.min(width - 3 * margin - waveWidth, height - 2 * margin);
        float cx = 2 * margin + waveWidth + (width - 3 * margin - waveWidth) / 2f;
        float cy = height / 2f;
        g.setColor(palette.faint());
        g.draw(new java.awt.geom.Line2D.Float(cx - size / 2, cy, cx + size / 2, cy));
        g.draw(new java.awt.geom.Line2D.Float(cx, cy - size / 2, cx, cy + size / 2));
        g.setColor(palette.accent());
        float half = size / 2f;
        int n = sound.left.length;
        int step = Math.max(1, n / 1024);
        for (int i = 0; i < n; i += step) {
            // Mid upwards, side across: mono is a vertical line, wide stereo fills the circle.
            float mid = (sound.left[i] + sound.right[i]) * 0.5f;
            float side = (sound.left[i] - sound.right[i]) * 0.5f;
            float x = cx + side * half;
            float y = cy - mid * half;
            g.fillRect((int) x, (int) y, 1, 1);
        }
        if (!sound.sounding) {
            Labels.quiet(g, width, height, palette);
        }
    }

    private static void wave(Graphics2D g, float[] samples, float x, float y, float w, float h,
                             java.awt.Color color, VisualPalette palette) {
        float middle = y + h / 2;
        g.setColor(palette.faint());
        g.draw(new java.awt.geom.Line2D.Float(x, middle, x + w, middle));
        Path2D.Float line = new Path2D.Float();
        int n = samples.length;
        int points = Math.max(2, Math.min(n, (int) w));
        for (int p = 0; p < points; p++) {
            int i = (int) ((long) p * (n - 1) / (points - 1));
            float px = x + w * p / (points - 1);
            float py = middle - samples[i] * h / 2;
            if (p == 0) {
                line.moveTo(px, py);
            } else {
                line.lineTo(px, py);
            }
        }
        g.setColor(color);
        g.draw(line);
    }
}
