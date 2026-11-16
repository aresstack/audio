package com.aresstack.audio.studio.visual;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * The surprise of the stage ({@link StageMood}): for a rock piece, jets of fire that shoot up
 * from the front of the stage on its hardest beats; for a techno piece, lasers through the haze
 * while it runs at its wildest. Left to itself it shows in the wildest moments only, measured
 * against the piece itself: louder than the piece has been on average. The fire is made of
 * particles that live in the space of the stage: they shoot up, slow, spread, cool from white
 * over yellow and orange to red and end as smoke; sparks fly out of it and fall. One instance per view.
 */
final class StageAtmosphere {
    /** Where the jets stand along the front of the stage, in metres from its middle. */
    private static final double[] JETS = {-5.0, -2.5, 0, 2.5, 5.0};
    private static final double JET_DEPTH = 0.25;
    private static final int PARTICLES = 2600;
    /** How long a jet burns after the beat that set it off, in seconds. */
    private static final double BURN = 0.8;
    private static final Color[] LASERS = {new Color(0x35FF6B), new Color(0x2FE6FF), new Color(0xFF3DD4),
            new Color(0xFFE23D)};

    private final StageMood mood = new StageMood();
    private StageMood.Mood shown = StageMood.Mood.OFF;
    private final Random random = new Random(7);
    private long seenBeats = -1;
    private double lastSeconds = Double.NaN;
    private int turn;

    // The loudness of the last second against that of the piece so far: what "wild" is measured by.
    private double loudNow = -1;
    private double loudUsual = -1;
    private double wildFor;
    private double lasersLeft;
    private double fireRests;

    // The fire: every particle with its place and speed in metres, its age and how long it lives.
    private final double[] px = new double[PARTICLES];
    private final double[] py = new double[PARTICLES];
    private final double[] pz = new double[PARTICLES];
    private final double[] vx = new double[PARTICLES];
    private final double[] vy = new double[PARTICLES];
    private final double[] vz = new double[PARTICLES];
    private final float[] age = new float[PARTICLES];
    private final float[] life = new float[PARTICLES];
    private final float[] size = new float[PARTICLES];
    private final boolean[] spark = new boolean[PARTICLES];
    private int next;
    private final double[] burning = new double[JETS.length];
    private final BufferedImage[] flames = {blob(new Color(0xFFF6D8)), blob(new Color(0xFFD257)), blob(new Color(0xFF8A1E)),
            blob(new Color(0xE23A10)), blob(new Color(0x3A302E))};

    /** The atmosphere shown now. */
    StageMood.Mood shown() {
        return shown;
    }

    /** How much the fire lights the stage right now, 0 to 1. */
    float warmth() {
        double most = 0;
        for (double jet : burning) {
            most = Math.max(most, jet / BURN);
        }
        return (float) Math.min(1, most);
    }

    /** Whether the lasers run now. */
    boolean lasers() {
        return shown == StageMood.Mood.LASER && lasersLeft > 0;
    }

    /** Reads the mood for this picture and moves what moves. Call once per picture, before painting. */
    void listen(SoundPicture sound, VisualSettings settings) {
        shown = mood.at(sound, settings);
        double step = Double.isNaN(lastSeconds) ? 0 : Math.max(0, Math.min(0.2, sound.seconds - lastSeconds));
        lastSeconds = sound.seconds;
        double loud = sound.loudness();
        loudNow = loudNow < 0 ? loud : loudNow + (loud - loudNow) * Math.min(1, step / 1.0);
        loudUsual = loudUsual < 0 ? loud : loudUsual + (loud - loudUsual) * Math.min(1, step / 25.0);
        boolean byHand = StageMood.byHand(settings);
        // Wild: clearly louder than the piece is as a rule. Set by hand, the surprise needs no such moment.
        boolean wild = byHand || loudNow > loudUsual * settings.wildRatio() && loudNow > 0.05;
        wildFor = wild ? wildFor + step : 0;
        boolean beat = seenBeats >= 0 && sound.beats != seenBeats;
        seenBeats = sound.beats;
        if (beat) {
            turn++;
        }
        fireRests = Math.max(0, fireRests - step);
        if (shown == StageMood.Mood.FIRE && beat && wild && sound.bass >= settings.fireBass() && fireRests <= 0) {
            // Left to itself the fire is rare: after a volley it rests, however wild the piece stays.
            fireRests = byHand ? 0 : settings.moodSeconds();
            // The hardest beats fire all jets, the others the outer and the inner ones in turn.
            boolean all = sound.bass >= Math.min(0.95, settings.fireBass() + 0.15);
            for (int jet = 0; jet < JETS.length; jet++) {
                if (all || jet % 2 == turn % 2) {
                    burning[jet] = BURN;
                }
            }
        }
        if (shown == StageMood.Mood.LASER) {
            if (byHand || wildFor >= settings.moodSeconds() / 2) {
                lasersLeft = Math.max(lasersLeft, settings.moodSeconds());
            }
        } else {
            lasersLeft = 0;
        }
        lasersLeft = Math.max(0, lasersLeft - step);
        burn(step);
    }

    /** Lets the jets that burn throw new fire, and moves all the fire there is. */
    private void burn(double step) {
        if (step <= 0) {
            return;
        }
        for (int jet = 0; jet < JETS.length; jet++) {
            if (burning[jet] <= 0) {
                continue;
            }
            double force = burning[jet] / BURN; // strongest right after the beat
            int born = (int) Math.round(step * 620 * (0.4 + 0.6 * force));
            for (int i = 0; i < born; i++) {
                int at = next++ % PARTICLES;
                spark[at] = random.nextInt(14) == 0;
                px[at] = JETS[jet] + (random.nextDouble() - 0.5) * 0.25;
                py[at] = 0.05;
                pz[at] = JET_DEPTH + (random.nextDouble() - 0.5) * 0.2;
                double up = (9.5 + random.nextDouble() * 5.5) * (0.55 + 0.45 * force);
                vx[at] = (random.nextDouble() - 0.5) * (spark[at] ? 5 : 0.9);
                vy[at] = spark[at] ? up * 1.15 : up;
                vz[at] = (random.nextDouble() - 0.5) * 0.6;
                age[at] = 0;
                life[at] = spark[at] ? 1.1f + random.nextFloat() * 0.6f : 0.42f + random.nextFloat() * 0.5f;
                size[at] = 0.16f + random.nextFloat() * 0.16f;
            }
            burning[jet] -= step;
        }
        for (int i = 0; i < PARTICLES; i++) {
            if (age[i] >= life[i]) {
                continue;
            }
            age[i] += (float) step;
            if (spark[i]) {
                vy[i] -= 9.8 * step; // a spark falls
            } else {
                // Hot gas: it shoots up, the air brakes it, and it wavers as it rises.
                vy[i] *= Math.exp(-step * 2.4);
                vy[i] += 1.5 * step;
                vx[i] += Math.sin(age[i] * 23 + i) * 1.8 * step;
            }
            px[i] += vx[i] * step;
            py[i] += vy[i] * step;
            pz[i] += vz[i] * step;
            if (py[i] < 0) {
                age[i] = life[i];
            }
        }
    }

    /** What stands behind the musicians: the lasers and their haze. */
    void paintBehind(Graphics2D g, StageLightEffect.Camera camera, SoundPicture sound, VisualSettings settings) {
        if (!lasers()) {
            return;
        }
        float strength = (float) (settings.atmosphereStrength() * Math.min(1, lasersLeft / 1.0));
        double t = sound.seconds;
        Color haze = LASERS[(turn / 8) % LASERS.length];
        for (int cloud = 0; cloud < 3; cloud++) {
            double[] at = camera.at(Math.sin(t * 0.09 * (cloud + 1) + cloud * 2.3) * 4, 2.2 + cloud * 0.8, 4 + cloud);
            float wide = (float) (camera.scale(at[2]) * 4.5);
            g.setPaint(new RadialGradientPaint((float) at[0], (float) at[1], wide, new float[]{0f, 1f},
                    new Color[]{alpha(haze, strength * 0.10f), alpha(haze, 0)}));
            g.fill(new Ellipse2D.Double(at[0] - wide, at[1] - wide, wide * 2, wide * 2));
        }
        // Three heads under the border at the back, each throwing a fan down into the hall that
        // opens, closes and swings as a whole, every head at its own pace.
        float reach = (float) Math.hypot(g.getClipBounds() == null ? 4000 : g.getClipBounds().width,
                g.getClipBounds() == null ? 4000 : g.getClipBounds().height) * 1.2f;
        for (int side = -1; side <= 1; side++) {
            double[] from = camera.at(side * 4.6, StageLightEffect.HIGH - 0.3, StageLightEffect.DEPTH - 0.6);
            float x = (float) from[0];
            float y = (float) from[1];
            Color colour = LASERS[(turn / 8 + side + 1) % LASERS.length];
            int beams = 5;
            for (int i = 0; i < beams; i++) {
                double spread = 0.18 + 0.14 * Math.sin(t * 0.9 + side);
                double angle = Math.PI / 2 - side * 0.35 + Math.sin(t * (0.6 + 0.15 * side) + side * 2) * 0.5
                        + (i - (beams - 1) / 2.0) * spread;
                float ex = x + (float) Math.cos(angle) * reach;
                float ey = y + (float) Math.sin(angle) * reach;
                float bright = strength * (0.35f + 0.65f * sound.beat);
                g.setStroke(new BasicStroke((float) Math.max(3, camera.scale(from[2]) * 0.3), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g.setColor(alpha(colour, bright * 0.22f));
                g.draw(new Line2D.Float(x, y, ex, ey));
                g.setStroke(new BasicStroke(1.2f));
                g.setColor(alpha(colour.brighter(), bright * 0.9f));
                g.draw(new Line2D.Float(x, y, ex, ey));
            }
        }
        g.setStroke(new BasicStroke(1f));
    }

    /** What stands before the musicians: the fire at the front of the stage. */
    void paintFront(Graphics2D g, StageLightEffect.Camera camera, SoundPicture sound, VisualSettings settings) {
        float strength = (float) settings.atmosphereStrength();
        if (strength <= 0) {
            return;
        }
        Graphics2D fire = (Graphics2D) g.create();
        // The glow at the mouth of every jet that burns, on the boards and in the air.
        for (int jet = 0; jet < JETS.length; jet++) {
            if (burning[jet] <= 0) {
                continue;
            }
            float force = (float) (burning[jet] / BURN);
            double[] at = camera.at(JETS[jet], 0.6, JET_DEPTH);
            float wide = (float) (camera.scale(at[2]) * (1.2 + 1.3 * force));
            fire.setPaint(new RadialGradientPaint((float) at[0], (float) at[1], wide, new float[]{0f, 0.4f, 1f},
                    new Color[]{alpha(new Color(0xFFE9A8), strength * 0.4f * force),
                            alpha(new Color(0xFF7A1A), strength * 0.25f * force), alpha(new Color(0xFF7A1A), 0)}));
            fire.fill(new Ellipse2D.Double(at[0] - wide, at[1] - wide, wide * 2, wide * 2));
        }
        for (int i = 0; i < PARTICLES; i++) {
            if (age[i] >= life[i]) {
                continue;
            }
            float old = age[i] / life[i];
            double[] at = camera.at(px[i], py[i], pz[i]);
            double metre = camera.scale(at[2]);
            if (spark[i]) {
                fire.setComposite(AlphaComposite.SrcOver);
                fire.setColor(alpha(new Color(0xFFD98A), strength * (1 - old)));
                fire.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double[] tail = camera.at(px[i] - vx[i] * 0.03, py[i] - vy[i] * 0.03, pz[i]);
                fire.draw(new Line2D.Double(at[0], at[1], tail[0], tail[1]));
                continue;
            }
            // It cools as it ages: white, yellow, orange, red, and smoke at the end; and it spreads.
            int stage = old < 0.12f ? 0 : old < 0.38f ? 1 : old < 0.66f ? 2 : old < 0.86f ? 3 : 4;
            double wide = metre * size[i] * (1 + 1.5 * old);
            float seen = strength * (stage == 4 ? 0.35f * (1 - old) / 0.14f : 0.6f - 0.3f * old);
            fire.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, seen))));
            fire.drawImage(flames[stage], (int) Math.round(at[0] - wide), (int) Math.round(at[1] - wide),
                    (int) Math.round(wide * 2), (int) Math.round(wide * 2), null);
        }
        fire.dispose();
    }

    /** A soft round patch of one colour, drawn large or small as one particle of fire. */
    private static BufferedImage blob(Color colour) {
        int width = 48;
        BufferedImage image = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new RadialGradientPaint(width / 2f, width / 2f, width / 2f, new float[]{0f, 0.5f, 1f},
                new Color[]{alpha(colour, 1f), alpha(colour, 0.55f), alpha(colour, 0f)}));
        g.fillRect(0, 0, width, width);
        g.dispose();
        return image;
    }

    private static Color alpha(Color colour, float strength) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, strength)));
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), a);
    }
}
