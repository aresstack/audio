package com.aresstack.audio.studio.visual;

import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

/** Large level meters: peak and RMS of both sides on a dB scale, and the low, middle and high share beside them. */
final class MeterEffect implements Effect {
    /** The marks of the dB scale. */
    private static final int[] MARKS_DB = {0, -6, -12, -18, -24, -36, -48, -60};
    /** How far a held peak falls per picture, in dB. */
    private static final float HOLD_FALL_DB = 0.6f;
    private float holdLeftDb = Float.NEGATIVE_INFINITY;
    private float holdRightDb = Float.NEGATIVE_INFINITY;

    @Override
    public String key() {
        return "meter";
    }

    @Override
    public String label() {
        return "Pegel";
    }

    @Override
    public String tip() {
        return "Pegel: Spitze und Mittel (RMS) links und rechts in dB, daneben Tiefen, Mitten und Höhen";
    }

    @Override
    public void paint(Graphics2D g, int width, int height, SoundPicture sound, VisualPalette palette,
                      VisualSettings settings) {
        Effect.clear(g, width, height, palette.background());
        double floor = settings.floorDb();
        float margin = Math.max(10, width * 0.03f);
        float top = margin;
        float bottom = height - margin;
        float column = (width - 2 * margin) / 7f;
        float peakL = db(sound.peakLeft);
        float peakR = db(sound.peakRight);
        holdLeftDb = Math.max(peakL, holdLeftDb - HOLD_FALL_DB);
        holdRightDb = Math.max(peakR, holdRightDb - HOLD_FALL_DB);
        bar(g, margin, top, column * 1.4f, bottom, share(peakL, floor), share(db(sound.rmsLeft), floor),
                share(holdLeftDb, floor), palette);
        bar(g, margin + column * 1.8f, top, column * 1.4f, bottom, share(peakR, floor), share(db(sound.rmsRight), floor),
                share(holdRightDb, floor), palette);
        g.setColor(palette.faint());
        for (int mark : MARKS_DB) {
            if (mark < floor) {
                continue;
            }
            float y = bottom - (float) share(mark, floor) * (bottom - top);
            g.drawString(mark + " dB", margin + column * 3.35f, y + 4);
        }
        float x = margin + column * 4.4f;
        float[] parts = {sound.bass, sound.middle, sound.treble};
        java.awt.Color[] colors = {palette.low(), palette.middle(), palette.cool()};
        String[] names = {"Tief", "Mitte", "Hoch"};
        for (int i = 0; i < 3; i++) {
            float h = parts[i] * (bottom - top);
            g.setColor(colors[i]);
            g.fill(new Rectangle2D.Float(x + i * column * 0.9f, bottom - h, column * 0.7f, h));
            g.setColor(palette.faint());
            g.drawString(names[i], x + i * column * 0.9f, bottom + margin * 0.8f);
        }
        if (sound.beat > 0) {
            g.setColor(new java.awt.Color(palette.accent().getRed(), palette.accent().getGreen(),
                    palette.accent().getBlue(), (int) (255 * sound.beat)));
            float r = Math.min(column, margin * 2);
            g.fill(new java.awt.geom.Ellipse2D.Float(width - margin - r, top, r, r));
        }
    }

    private static void bar(Graphics2D g, float x, float top, float w, float bottom, double peak, double rms,
                            double hold, VisualPalette palette) {
        float span = bottom - top;
        g.setColor(palette.faint());
        g.draw(new Rectangle2D.Float(x, top, w, span));
        g.setPaint(new java.awt.GradientPaint(0, bottom, palette.low(), 0, top, palette.high()));
        g.fill(new Rectangle2D.Float(x + 1, bottom - (float) peak * span, w - 2, (float) peak * span));
        g.setColor(palette.ink());
        g.fill(new Rectangle2D.Float(x + 1, bottom - (float) rms * span - 1, w - 2, 2));
        g.setColor(palette.high());
        g.fill(new Rectangle2D.Float(x + 1, bottom - (float) hold * span - 2, w - 2, 3));
    }

    private static float db(float linear) {
        return (float) (20 * Math.log10(Math.max(linear, 1e-9f)));
    }

    private static double share(double db, double floor) {
        return Math.max(0, Math.min(1, (db - floor) / -floor));
    }
}
