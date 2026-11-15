package com.aresstack.audio.studio.visual;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * The stage seen from the hall: every track is a light at its place, glowing with its own level
 * and wandering as the track moves on the stage. The front edge is near and wide, the back wall far.
 */
final class StageLightEffect implements Effect {
    /** How much narrower the back wall is than the front edge, seen from the hall. */
    private static final float BACK_NARROWING = 0.55f;
    /** The radius of the glow of a track at full level, as a share of the stage width. */
    private static final float GLOW = 0.16f;

    @Override
    public String key() {
        return "stage";
    }

    @Override
    public String label() {
        return "Bühne";
    }

    @Override
    public String tip() {
        return "Bühnenlichter: jede Spur leuchtet an ihrem Platz mit ihrem Pegel und wandert mit ihren Bewegungen";
    }

    @Override
    public void paint(Graphics2D g, int width, int height, SoundPicture sound, VisualPalette palette,
                      VisualSettings settings) {
        Effect.fade(g, width, height, palette.background(), settings.trail() * 0.6);
        float margin = Math.max(12, width * 0.05f);
        float front = height - margin;
        float back = margin + height * 0.12f;
        float frontHalf = (width - 2 * margin) / 2f;
        float backHalf = frontHalf * BACK_NARROWING;
        float cx = width / 2f;
        Path2D.Float floor = new Path2D.Float();
        floor.moveTo(cx - frontHalf, front);
        floor.lineTo(cx + frontHalf, front);
        floor.lineTo(cx + backHalf, back);
        floor.lineTo(cx - backHalf, back);
        floor.closePath();
        g.setColor(palette.faint());
        g.draw(floor);
        List<TrackLight> lights = sound.lights;
        if (lights.isEmpty()) {
            Labels.centred(g, width, height, sound.sounding
                    ? "Bühnenlichter gibt es bei der Wiedergabe der Bühne." : "Es spielt gerade nichts.", palette);
            return;
        }
        // Far ones first, so that near lights stand in front.
        TrackLight[] ordered = lights.toArray(new TrackLight[0]);
        java.util.Arrays.sort(ordered, (a, b) -> Double.compare(b.depth(), a.depth()));
        for (TrackLight light : ordered) {
            float depth = (float) light.depth();
            float half = frontHalf + (backHalf - frontHalf) * depth;
            float x = cx + (float) light.x() * half;
            float y = front + (back - front) * depth - (float) light.height() * (front - back) * 0.5f;
            float scale = 1 - depth * (1 - BACK_NARROWING);
            float level = Math.min(1f, light.level());
            float radius = Math.max(4, frontHalf * 2 * GLOW * scale * (0.25f + level));
            Color color = palette.track().apply(light.index());
            Color glow = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (60 + 195 * level));
            Color edge = new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
            g.setPaint(new RadialGradientPaint(x, y, radius, new float[]{0f, 1f}, new Color[]{glow, edge}));
            g.fill(new Ellipse2D.Float(x - radius, y - radius, 2 * radius, 2 * radius));
            g.setColor(palette.ink());
            float core = Math.max(3, radius * 0.15f);
            g.fill(new Ellipse2D.Float(x - core / 2, y - core / 2, core, core));
            g.setColor(palette.ink());
            g.drawString(light.trackId(), x - g.getFontMetrics().stringWidth(light.trackId()) / 2f, y + radius * 0.5f + 12);
        }
    }
}
