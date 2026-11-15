package com.aresstack.audio.studio.visual;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/**
 * Flowing shapes of light: the waveform drawn as curves round the centre, turned into several
 * mirrored copies that slowly rotate and breathe with the loudness, laid over their own trail.
 */
final class AlchemyEffect implements Effect {
    /** How many mirrored copies of the curve stand round the centre. */
    private static final int SYMMETRY = 6;
    /** Points of one curve. */
    private static final int POINTS = 240;
    private double turn;

    @Override
    public String key() {
        return "alchemy";
    }

    @Override
    public String label() {
        return "Alchemie";
    }

    @Override
    public String tip() {
        return "Fließende Lichtformen: die Wellenform als gespiegelte Kurven, die sich drehen und atmen";
    }

    @Override
    public void paint(Graphics2D g, int width, int height, SoundPicture sound, VisualPalette palette,
                      VisualSettings settings) {
        Effect.fade(g, width, height, palette.background(), settings.trail());
        turn += 0.004 + sound.middle * 0.02 + sound.beat * 0.03;
        double cx = width / 2.0;
        double cy = height / 2.0;
        double base = Math.min(width, height) * (0.18 + 0.25 * sound.loudness());
        int n = sound.left.length;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.setStroke(new BasicStroke(1.6f + sound.beat * 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int copy = 0; copy < SYMMETRY; copy++) {
            double offset = turn + copy * 2 * Math.PI / SYMMETRY;
            boolean mirrored = copy % 2 == 1;
            Path2D.Float curve = new Path2D.Float();
            for (int p = 0; p < POINTS; p++) {
                int i = (int) ((long) p * (n - 1) / (POINTS - 1));
                double along = (double) p / (POINTS - 1);
                double angle = offset + (mirrored ? -1 : 1) * along * Math.PI / SYMMETRY * 2;
                double r = base * (0.4 + along) + sound.left[i] * base * 0.9;
                double x = cx + Math.cos(angle) * r;
                double y = cy + Math.sin(angle) * (r + sound.right[i] * base * 0.5);
                if (p == 0) {
                    curve.moveTo(x, y);
                } else {
                    curve.lineTo(x, y);
                }
            }
            float hue = (float) ((sound.seconds * 0.03 + copy * 0.08 + sound.treble * 0.3) % 1);
            g.setColor(Color.getHSBColor(hue, 0.6f, 1f));
            g.draw(curve);
        }
        g.setComposite(AlphaComposite.SrcOver);
    }
}
