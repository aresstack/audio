package com.aresstack.audio.studio.visual;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.util.Random;

/**
 * A cloud of glowing particles: the low end blows them out of the centre, a beat sends a burst,
 * each takes the colour of the band it was born from and fades as it flies.
 */
final class ParticleEffect implements Effect {
    /** How fast a particle is born, as a share of the picture's diagonal per picture at full low end. */
    private static final double SPEED = 0.025;
    /** How much of its speed a particle keeps per picture. */
    private static final double DRAG = 0.985;
    /** How much of its life a particle loses per picture. */
    private static final double AGING = 0.012;
    /** How many particles a picture may give birth to at full loudness, as a share of all. */
    private static final double BIRTH_SHARE = 0.06;
    private final Random random = new Random(7);
    private double[] x = new double[0];
    private double[] y = new double[0];
    private double[] vx = new double[0];
    private double[] vy = new double[0];
    private double[] life = new double[0];
    private float[] hue = new float[0];
    private int next;

    @Override
    public String key() {
        return "particles";
    }

    @Override
    public String label() {
        return "Partikel";
    }

    @Override
    public String tip() {
        return "Partikelwolke: die Tiefen blasen Funken aus der Mitte, ein Schlag schickt einen Schwall";
    }

    @Override
    public void paint(Graphics2D g, int width, int height, SoundPicture sound, VisualPalette palette,
                      VisualSettings settings) {
        int count = settings.particles();
        if (x.length != count) {
            x = new double[count];
            y = new double[count];
            vx = new double[count];
            vy = new double[count];
            life = new double[count];
            hue = new float[count];
            next = 0;
        }
        Effect.fade(g, width, height, palette.background(), settings.trail());
        if (count == 0) {
            return;
        }
        double diagonal = Math.hypot(width, height);
        double cx = width / 2.0;
        double cy = height / 2.0;
        int born = (int) (count * BIRTH_SHARE * (sound.loudness() * 2 + sound.beat * 2));
        float[] bands = sound.bands;
        for (int i = 0; i < born; i++) {
            int band = random.nextInt(bands.length);
            double angle = random.nextDouble() * 2 * Math.PI;
            double speed = diagonal * SPEED * (0.2 + bands[band] + sound.bass + sound.beat) * (0.5 + random.nextDouble());
            int k = next;
            next = (next + 1) % count;
            x[k] = cx;
            y[k] = cy;
            vx[k] = Math.cos(angle) * speed;
            vy[k] = Math.sin(angle) * speed;
            life[k] = 1;
            hue[k] = (float) band / bands.length;
        }
        for (int k = 0; k < count; k++) {
            if (life[k] <= 0) {
                continue;
            }
            x[k] += vx[k];
            y[k] += vy[k];
            vx[k] *= DRAG;
            vy[k] *= DRAG;
            life[k] -= AGING;
            if (life[k] <= 0 || x[k] < 0 || y[k] < 0 || x[k] > width || y[k] > height) {
                life[k] = 0;
                continue;
            }
            float alive = (float) life[k];
            Color color = Color.getHSBColor(hue[k] * 0.8f + (float) (sound.seconds * 0.02 % 1), 0.7f, 1f);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (255 * alive)));
            double size = 1.5 + 4 * alive;
            g.fill(new Ellipse2D.Double(x[k] - size / 2, y[k] - size / 2, size, size));
        }
    }
}
