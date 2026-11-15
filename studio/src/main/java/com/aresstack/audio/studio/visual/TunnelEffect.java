package com.aresstack.audio.studio.visual;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/**
 * A tunnel of light rings flying towards the viewer: each ring is shaped by the spectrum, the
 * speed follows the loudness, a beat sends a bright ring and the colours wander with the high end.
 */
final class TunnelEffect implements Effect {
    /** How far the rings travel per picture at full loudness, as a share of the distance between two rings. */
    private static final double SPEED = 0.35;
    /** The least travel per picture, so that the tunnel moves on in silence. */
    private static final double IDLE_SPEED = 0.02;
    /** How much a band bulges a ring, as a share of its radius. */
    private static final double BULGE = 0.35;
    /** Points around a ring. */
    private static final int POINTS = 96;
    private double travel;
    private double hue;

    @Override
    public String key() {
        return "tunnel";
    }

    @Override
    public String label() {
        return "Tunnel";
    }

    @Override
    public String tip() {
        return "Lichttunnel: Ringe nach dem Spektrum geformt, Tempo nach der Lautstärke, Blitz auf dem Schlag";
    }

    @Override
    public void paint(Graphics2D g, int width, int height, SoundPicture sound, VisualPalette palette,
                      VisualSettings settings) {
        Effect.fade(g, width, height, palette.background(), settings.trail());
        travel += IDLE_SPEED + SPEED * sound.loudness() * 2 + sound.beat * SPEED;
        hue += 0.002 + sound.treble * 0.01;
        int rings = settings.tunnelRings();
        double cx = width / 2.0 + Math.sin(sound.seconds * 0.4) * width * 0.06;
        double cy = height / 2.0 + Math.cos(sound.seconds * 0.3) * height * 0.06;
        double reach = Math.hypot(width, height) / 2;
        float[] bands = sound.bands;
        for (int r = rings - 1; r >= 0; r--) {
            double z = r + 1 - (travel % 1);  // 0 near the viewer, rings far away
            double radius = reach / Math.pow(z, 1.25);
            if (radius < 2) {
                continue;
            }
            double nearness = 1.0 / z;
            Path2D.Float ring = new Path2D.Float();
            double twist = sound.seconds * 0.3 + r * 0.15;
            for (int p = 0; p <= POINTS; p++) {
                double angle = 2 * Math.PI * p / POINTS + twist;
                // Mirrored round the circle, so that the shape closes without a seam.
                int band = (int) (Math.abs(((double) p / POINTS) * 2 - 1) * (bands.length - 1));
                double rr = radius * (1 + BULGE * bands[band] * nearness);
                double x = cx + Math.cos(angle) * rr;
                double y = cy + Math.sin(angle) * rr;
                if (p == 0) {
                    ring.moveTo(x, y);
                } else {
                    ring.lineTo(x, y);
                }
            }
            float brightness = (float) Math.min(1, 0.25 + nearness * (0.6 + sound.loudness()));
            int index = (int) Math.floor(travel) + r;
            boolean flash = sound.beat > 0.5f && r == 0;
            Color color = Color.getHSBColor((float) ((hue + index * 0.04) % 1), flash ? 0.2f : 0.75f, brightness);
            g.setColor(color);
            g.setStroke(new BasicStroke((float) Math.max(1, 6 * nearness + sound.beat * 3)));
            g.draw(ring);
        }
    }
}
