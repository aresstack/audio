package com.aresstack.audio.studio.visual;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * What the stage shows around the lights of its tracks, by the {@link StageMood} of the music:
 * jets of fire on the beats, lasers, candle light, a smoky club, lanterns or coloured spotlights.
 * Drawn with Java2D only, a few dozen shapes per picture; it keeps the little state the moving
 * things need (how high the fire stands, which colours the lights have). One instance per view.
 */
final class StageAtmosphere {
    /** Where the stage lies in the picture. */
    record Floor(float centre, float front, float back, float frontHalf, float backHalf, int width, int height) {
    }

    private static final Color[] LASERS = {new Color(0x35FF6B), new Color(0x2FE6FF), new Color(0xFF3DD4),
            new Color(0xFFE23D)};
    private static final Color[] SPOTS = {new Color(0xFF4FA3), new Color(0x44D9FF), new Color(0xFFD23F),
            new Color(0xA56BFF)};
    private static final Color WARM = new Color(0xFFC46B);
    private static final Color EMBER = new Color(0xFF5A1F);

    private final StageMood mood = new StageMood();
    private StageMood.Mood shown = StageMood.Mood.OFF;
    /** How much of the last change of atmosphere is done, 0 to 1: a new one comes in softly. */
    private float arrived = 1;
    private long seenBeats = -1;
    private double lastSeconds = Double.NaN;
    /** How high the fire stands, 0 to 1; a beat sets it off and it falls back by itself. */
    private float fire;
    /** Counts the beats that changed something: which jets fire, which colours the lights have. */
    private int turn;

    /** The atmosphere shown now. */
    StageMood.Mood shown() {
        return shown;
    }

    /** Reads the mood for this picture and moves what moves. Call once per picture, before painting. */
    void listen(SoundPicture sound, VisualSettings settings) {
        StageMood.Mood now = mood.at(sound, settings);
        if (now != shown) {
            shown = now;
            arrived = 0;
            fire = 0;
        }
        double step = Double.isNaN(lastSeconds) ? 0 : Math.max(0, Math.min(0.5, sound.seconds - lastSeconds));
        lastSeconds = sound.seconds;
        arrived = (float) Math.min(1, arrived + step / 1.5);
        fire *= (float) Math.exp(-step * 3.2);
        if (seenBeats >= 0 && sound.beats != seenBeats) {
            turn++;
            fire = 1;
        }
        seenBeats = sound.beats;
    }

    /** What stands behind the lights of the tracks: light from above, haze, the sky. */
    void paintBehind(Graphics2D g, Floor floor, SoundPicture sound, VisualSettings settings) {
        float strength = (float) settings.atmosphereStrength() * arrived;
        if (strength <= 0 || shown == StageMood.Mood.OFF) {
            return;
        }
        double t = sound.seconds;
        float loud = Math.min(1f, sound.loudness() * 3f);
        switch (shown) {
            case LASER -> lasers(g, floor, sound, strength, t);
            case CANDLE -> {
                haze(g, floor, WARM, strength * 0.10f, t, 3);
                for (int i = 0; i < 3; i++) {
                    float sway = (float) Math.sin(t * 0.25 + i * 2.1) * 0.12f;
                    float x = floor.centre + (i - 1) * floor.backHalf * 0.9f;
                    cone(g, x, 0, x + sway * floor.frontHalf, (floor.front + floor.back) / 2, floor.frontHalf * 0.28f,
                            WARM, strength * (0.10f + 0.12f * loud));
                }
            }
            case CLUB -> {
                wash(g, floor, new Color(0x2B4CFF), strength * 0.22f);
                haze(g, floor, new Color(0x8FA8FF), strength * 0.12f, t, 5);
                float sway = (float) Math.sin(t * 0.18) * 0.08f;
                cone(g, floor.centre - floor.backHalf * 0.5f, 0, floor.centre + sway * floor.frontHalf,
                        floor.back + (floor.front - floor.back) * 0.55f, floor.frontHalf * 0.34f,
                        new Color(0xFFB347), strength * (0.16f + 0.14f * sound.beat));
            }
            case LANTERN -> {
                // The evening sky behind the back wall, and the string of lanterns before it.
                g.setPaint(new GradientPaint(0, 0, alpha(new Color(0x5B2A6E), strength * 0.30f), 0, floor.back,
                        alpha(new Color(0xFF8A3D), strength * 0.34f)));
                g.fillRect(0, 0, floor.width, Math.round(floor.back));
                lanterns(g, floor, sound, strength, t);
            }
            case SPOTS -> {
                for (int i = 0; i < 4; i++) {
                    Color colour = SPOTS[(i + turn / 4) % SPOTS.length];
                    float from = floor.centre + (i - 1.5f) * floor.backHalf * 0.7f;
                    float to = floor.centre + (float) Math.sin(t * 0.7 + i * 1.7) * floor.frontHalf * 0.75f;
                    float depth = 0.5f + 0.35f * (float) Math.sin(t * 0.45 + i);
                    cone(g, from, 0, to, floor.front + (floor.back - floor.front) * depth, floor.frontHalf * 0.2f,
                            colour, strength * (0.14f + 0.22f * sound.beat));
                }
            }
            case FIRE -> wash(g, floor, EMBER, strength * (0.06f + 0.22f * fire));
            default -> {
            }
        }
    }

    /** What stands before the lights of the tracks: the fire, the candles at the edge. */
    void paintFront(Graphics2D g, Floor floor, SoundPicture sound, VisualSettings settings) {
        float strength = (float) settings.atmosphereStrength() * arrived;
        if (strength <= 0) {
            return;
        }
        double t = sound.seconds;
        if (shown == StageMood.Mood.FIRE) {
            // Four jets: two at the back wall, two at the front corners; the pairs take turns, a hard beat fires all.
            boolean all = sound.bass > 0.75f;
            float[] xs = {floor.centre - floor.backHalf * 0.8f, floor.centre + floor.backHalf * 0.8f,
                    floor.centre - floor.frontHalf * 0.92f, floor.centre + floor.frontHalf * 0.92f};
            float[] ys = {floor.back, floor.back, floor.front, floor.front};
            for (int i = 0; i < 4; i++) {
                boolean back = i < 2;
                if (!all && back != (turn % 2 == 0)) {
                    continue;
                }
                flame(g, xs[i], ys[i], floor.height * (back ? 0.28f : 0.42f) * fire, floor.frontHalf * (back ? 0.035f : 0.055f),
                        strength, t + i * 1.3);
            }
        } else if (shown == StageMood.Mood.CANDLE) {
            int candles = 9;
            for (int i = 0; i < candles; i++) {
                float x = floor.centre + (i - (candles - 1) / 2f) * floor.frontHalf * 2 * 0.9f / (candles - 1);
                float flicker = 0.75f + 0.25f * (float) Math.sin(t * (5 + i % 3) + i * 1.9);
                flame(g, x, floor.front, floor.height * 0.045f * flicker, floor.frontHalf * 0.008f, strength * 0.9f, t + i);
            }
        }
    }

    // ---- the shapes ----

    private void lasers(Graphics2D g, Floor floor, SoundPicture sound, float strength, double t) {
        haze(g, floor, LASERS[(turn / 8) % LASERS.length], strength * 0.06f, t, 3);
        float reach = (float) Math.hypot(floor.width, floor.height);
        for (int side = -1; side <= 1; side++) {
            float x = floor.centre + side * floor.backHalf * 0.95f;
            float y = floor.back * 0.35f;
            Color colour = LASERS[(turn / 8 + side + 1) % LASERS.length];
            int beams = 5;
            for (int i = 0; i < beams; i++) {
                // A fan that opens and closes and swings as a whole; every side at its own pace.
                double spread = 0.18 + 0.14 * Math.sin(t * 0.9 + side);
                double angle = Math.PI / 2 - side * 0.35 + Math.sin(t * (0.6 + 0.15 * side) + side * 2) * 0.5
                        + (i - (beams - 1) / 2.0) * spread;
                float ex = x + (float) Math.cos(angle) * reach;
                float ey = y + (float) Math.sin(angle) * reach;
                float bright = strength * (0.35f + 0.65f * sound.beat);
                g.setStroke(new BasicStroke(Math.max(3f, floor.width / 220f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(alpha(colour, bright * 0.22f));
                g.draw(new Line2D.Float(x, y, ex, ey));
                g.setStroke(new BasicStroke(1.2f));
                g.setColor(alpha(colour.brighter(), bright * 0.9f));
                g.draw(new Line2D.Float(x, y, ex, ey));
            }
        }
        g.setStroke(new BasicStroke(1f));
    }

    private void lanterns(Graphics2D g, Floor floor, SoundPicture sound, float strength, double t) {
        int count = 13;
        float left = floor.centre - floor.frontHalf * 0.98f;
        float right = floor.centre + floor.frontHalf * 0.98f;
        float top = floor.back * 0.25f;
        float sag = floor.back * 0.45f;
        Path2D.Float string = new Path2D.Float();
        for (int i = 0; i <= count; i++) {
            float share = i / (float) count;
            float x = left + (right - left) * share;
            float y = top + sag * 4 * share * (1 - share);
            if (i == 0) {
                string.moveTo(x, y);
            } else {
                string.lineTo(x, y);
            }
        }
        g.setColor(alpha(Color.BLACK, strength * 0.6f));
        g.draw(string);
        for (int i = 1; i < count; i++) {
            float share = i / (float) count;
            float x = left + (right - left) * share;
            float y = top + sag * 4 * share * (1 - share) + 4;
            float twinkle = 0.7f + 0.3f * (float) Math.sin(t * 1.3 + i * 2.4) + 0.3f * sound.beat;
            float radius = Math.max(5, floor.width / 70f);
            glow(g, x, y, radius * 2.4f, i % 3 == 0 ? new Color(0xFF8F5A) : WARM, strength * 0.75f * Math.min(1, twinkle));
        }
    }

    /** A flame standing on a point: glowing blobs from white at the foot to red at the tip, never still. */
    private void flame(Graphics2D g, float x, float foot, float height, float width, float strength, double t) {
        if (height < 2) {
            return;
        }
        int blobs = 7;
        for (int i = 0; i < blobs; i++) {
            float up = i / (float) (blobs - 1);
            float sway = (float) Math.sin(t * 9 + i * 1.7) * width * 0.9f * up;
            float radius = Math.max(2, (width * 2.2f + height * 0.12f) * (1 - up * 0.7f));
            Color colour = up < 0.25f ? new Color(0xFFF3C4) : up < 0.6f ? new Color(0xFFB02E) : EMBER;
            glow(g, x + sway, foot - height * up, radius, colour, strength * (0.85f - 0.5f * up));
        }
    }

    /** Light falling from a point above onto a place of the floor. */
    private void cone(Graphics2D g, float fromX, float fromY, float toX, float toY, float radius, Color colour, float strength) {
        Path2D.Float beam = new Path2D.Float();
        beam.moveTo(fromX, fromY);
        beam.lineTo(toX - radius, toY);
        beam.lineTo(toX + radius, toY);
        beam.closePath();
        g.setPaint(new GradientPaint(fromX, fromY, alpha(colour, strength), toX, toY, alpha(colour, strength * 0.35f)));
        g.fill(beam);
        g.setColor(alpha(colour, strength * 0.8f));
        g.fill(new Ellipse2D.Float(toX - radius, toY - radius * 0.3f, radius * 2, radius * 0.6f));
    }

    /** A soft light over the whole floor. */
    private void wash(Graphics2D g, Floor floor, Color colour, float strength) {
        float y = (floor.front + floor.back) / 2;
        glow(g, floor.centre, y, floor.frontHalf * 1.3f, colour, strength);
    }

    /** Slow clouds over the stage. */
    private void haze(Graphics2D g, Floor floor, Color colour, float strength, double t, int clouds) {
        for (int i = 0; i < clouds; i++) {
            float x = floor.centre + (float) Math.sin(t * 0.07 * (i + 1) + i * 2.3) * floor.frontHalf * 0.8f;
            float y = floor.back + (floor.front - floor.back) * (0.15f + 0.25f * i / Math.max(1, clouds - 1))
                    + (float) Math.sin(t * 0.11 + i) * floor.height * 0.03f;
            float wide = floor.frontHalf * (0.7f + 0.2f * i);
            g.setPaint(new RadialGradientPaint(x, y, wide, new float[]{0f, 1f},
                    new Color[]{alpha(colour, strength), alpha(colour, 0)}));
            g.fill(new Ellipse2D.Float(x - wide, y - wide * 0.3f, wide * 2, wide * 0.6f));
        }
    }

    private void glow(Graphics2D g, float x, float y, float radius, Color colour, float strength) {
        if (radius <= 0 || strength <= 0) {
            return;
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setPaint(new RadialGradientPaint(x, y, radius, new float[]{0f, 1f},
                new Color[]{alpha(colour, strength), alpha(colour, 0)}));
        g.fill(new Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2));
    }

    private static Color alpha(Color colour, float strength) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, strength)));
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), a);
    }
}
