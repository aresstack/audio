package com.aresstack.audio.studio.visual;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The stage as a scene in space, seen from the hall through a camera that sways a little: the
 * boards of the floor, a curtain at the back wall, one drawn back at each side and a border above,
 * all of cloth that waves in the air, and on the floor the musicians, each at the place of its
 * track: its figure with a light around its outline that grows with its level, or a light where
 * it has no figure. Around them stands the atmosphere the music asks for ({@link StageAtmosphere}).
 * Every picture is drawn anew from the scene; nothing is left over from the last one.
 */
final class StageLightEffect implements Effect {
    /** How many lights stand on the stage when the tracks are not known: groups of the spectrum, low left, high right. */
    static final int SPECTRUM_LIGHTS = 7;
    /** Where the spectrum lights stand: the front of the arc, how far its middle reaches back, how wide it is, how high it lifts. */
    private static final double ARC_FRONT = 0.25;
    private static final double ARC_BACK = 0.4;
    private static final double ARC_WIDTH = 0.85;
    private static final double ARC_LIFT = 0.3;

    /** The stage in metres: half its width, its depth, the height of its curtains. */
    static final double HALF = 6;
    static final double DEPTH = 8;
    static final double HIGH = 6.2;

    /** The cloth in its folds, in the open and where the light catches it: all from the colour of the settings. */
    private Color curtainDeep = new Color(0x2A0716);
    private Color curtain = new Color(0x8E1B4A);
    private Color curtainLight = new Color(0xD9508A);
    private String curtainSet = VisualSettings.DEFAULT_CURTAIN;
    private double feather;
    private double opacity = 1;

    /**
     * Lights from the spectrum, for a sound whose tracks are not known (a file from elsewhere):
     * every light is a group of bands, low on the left, high on the right, the middle ones further
     * back, glowing with the loudest band of their group.
     */
    static List<TrackLight> fromSpectrum(SoundPicture sound) {
        float[] bands = sound.bands;
        int count = Math.min(SPECTRUM_LIGHTS, Math.max(1, bands.length));
        java.util.List<TrackLight> lights = new java.util.ArrayList<TrackLight>(count);
        for (int i = 0; i < count; i++) {
            int from = i * bands.length / count;
            int to = Math.max(from + 1, (i + 1) * bands.length / count);
            float level = 0f;
            for (int b = from; b < to && b < bands.length; b++) {
                level = Math.max(level, bands[b]);
            }
            double x = count == 1 ? 0 : -1 + 2.0 * i / (count - 1);
            double depth = ARC_FRONT + ARC_BACK * (1 - Math.abs(x)); // an arc: the middle stands back
            lights.add(new TrackLight("", i, x * ARC_WIDTH, depth, level * ARC_LIFT, level));
        }
        return lights;
    }

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
        return "Die Bühne im Raum: wehende Vorhänge, die Musiker an ihren Plätzen, jede Spur leuchtet mit ihrem Pegel";
    }

    /** The camera in the hall: where it stands, how it looks down, and how a place in space falls into the picture. */
    static final class Camera {
        private final double x;
        private final double y;
        private final double z;
        private final double sin;
        private final double cos;
        private final double focal;
        private final double centreX;
        private final double centreY;

        Camera(int width, int height, double seconds) {
            // It sways slowly from side to side: what is near moves more than what is far, and the stage gets its depth.
            x = Math.sin(seconds * 0.17) * 1.1;
            y = 3.4;
            z = -11;
            double pitch = Math.atan2(y - 2.9, 1 - z); // looks at the middle of the opening of the stage
            sin = Math.sin(pitch);
            cos = Math.cos(pitch);
            // The whole stage with its side curtains fits across and in height.
            focal = Math.min(width * 0.94 * -z / (2 * (HALF + 1.9)), height * 0.98 * -z / (HIGH + 1.6));
            centreX = width / 2.0;
            centreY = height * 0.54;
        }

        /** Where a place in space falls into the picture: x, y, and its distance from the camera along its view. */
        double[] at(double px, double py, double pz) {
            double dx = px - x;
            double dy = py - y;
            double dz = pz - z;
            double ahead = Math.max(0.5, dz * cos - dy * sin);
            double up = dy * cos + dz * sin;
            return new double[]{centreX + focal * dx / ahead, centreY - focal * up / ahead, ahead};
        }

        /** How many pixels a metre is at a distance. */
        double scale(double ahead) {
            return focal / ahead;
        }
    }

    private final StageAtmosphere atmosphere = new StageAtmosphere();
    private final FigurePainter figures = new FigurePainter();
    /** How many tracks stood on the stage in the last picture, for tests. */
    int lightsShown;
    /** How hard the last beat still pushes the curtains, 0 to 1: it falls with the beat. */
    private float kick;
    /** How fast the waves of the cloth run at full loudness, against their pace in silence. */
    private static final double FLOW = 2.2;
    private static final double IDLE_FLOW = 0.55;
    /** How far a band of the spectrum bulges the cloth at its place, in metres at the hem. */
    private static final double BULGE = 0.9;
    private float[] bands;
    private double flow;
    private double lastSeconds = Double.NaN;
    private float flash;

    /** The atmosphere shown now, for tests. */
    StageMood.Mood atmosphere() {
        return atmosphere.shown();
    }

    @Override
    public void paint(Graphics2D g, int width, int height, SoundPicture sound, VisualPalette palette,
                      VisualSettings settings) {
        Effect.clear(g, width, height, palette.background());
        double t = sound.seconds;
        Camera camera = new Camera(width, height, t);
        if (sound.sounding) {
            atmosphere.listen(sound, settings);
        }
        float warm = sound.sounding ? atmosphere.warmth() : 0;

        kick = sound.sounding ? sound.beat * (0.45f + 0.55f * Math.min(1f, sound.bass)) : 0f;
        // As in the tunnel: the cloth is shaped by the spectrum, its waves run with the loudness,
        // and a beat sends a push and a flash through it. In silence it moves on slowly.
        bands = sound.sounding ? sound.bands : null;
        double passed = Double.isNaN(lastSeconds) ? 0 : Math.max(0, Math.min(0.2, sound.seconds - lastSeconds));
        lastSeconds = sound.seconds;
        float loud = sound.sounding ? sound.loudness() : 0f;
        flow += passed * (IDLE_FLOW + FLOW * loud * 2 + (sound.sounding ? sound.beat : 0f) * FLOW);
        flash = sound.sounding ? sound.beat : 0f;
        t = flow;
        if (!settings.curtain().equals(curtainSet)) {
            curtainSet = settings.curtain();
            curtain = Color.decode(curtainSet);
            float[] hsb = Color.RGBtoHSB(curtain.getRed(), curtain.getGreen(), curtain.getBlue(), null);
            curtainDeep = Color.getHSBColor(hsb[0], Math.min(1f, hsb[1] * 1.05f), hsb[2] * 0.3f);
            curtainLight = Color.getHSBColor(hsb[0], hsb[1] * 0.78f, Math.min(1f, hsb[2] * 1.55f));
        }
        feather = settings.figureFeather();
        opacity = settings.figureOpacity();
        paintFloor(g, camera, warm);
        // The curtain at the back wall.
        paintCloth(g, camera, t, -HALF - 0.6, HIGH, DEPTH, 2 * HALF + 1.2, 0, HIGH, 22, 0, 0.0, 0.62f + 0.3f * warm, 88, 12);
        if (sound.sounding) {
            atmosphere.paintBehind(g, camera, sound, settings);
        }
        paintTracks(g, camera, sound, palette, width, height);
        if (sound.sounding) {
            atmosphere.paintFront(g, camera, sound, settings);
        }
        // The curtains drawn back at both sides in front, and the border above them.
        paintCloth(g, camera, t, -HALF - 1.7, HIGH, -0.2, 2.6, 0, HIGH, 7, -1, 1.1, 1f, 30, 14);
        paintCloth(g, camera, t, HALF - 0.9, HIGH, -0.2, 2.6, 0, HIGH, 7, 1, 2.6, 1f, 30, 14);
        paintCloth(g, camera, t, -HALF - 1.7, HIGH + 0.5, -0.3, 2 * HALF + 3.4, 0, 1.3, 30, 0, 4.0, 0.9f, 96, 4);
    }

    /** The boards of the floor, darker towards the back wall, and the light of the fire on them. */
    private void paintFloor(Graphics2D g, Camera camera, float warm) {
        double[] a = camera.at(-HALF - 0.6, 0, -0.6);
        double[] b = camera.at(HALF + 0.6, 0, -0.6);
        double[] c = camera.at(HALF + 0.6, 0, DEPTH);
        double[] d = camera.at(-HALF - 0.6, 0, DEPTH);
        Path2D boards = new Path2D.Double();
        boards.moveTo(a[0], a[1]);
        boards.lineTo(b[0], b[1]);
        boards.lineTo(c[0], c[1]);
        boards.lineTo(d[0], d[1]);
        boards.closePath();
        Color near = mix(new Color(0x3A3440), new Color(0x7A4A2A), warm * 0.7);
        Color far = mix(new Color(0x16151B), new Color(0x3A2016), warm * 0.7);
        g.setPaint(new GradientPaint(0, (float) d[1], far, 0, (float) a[1], near));
        g.fill(boards);
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(0, 0, 0, 70));
        for (int board = -6; board <= 6; board++) {
            double[] from = camera.at(board, 0, -0.6);
            double[] to = camera.at(board, 0, DEPTH);
            g.draw(new Line2D.Double(from[0], from[1], to[0], to[1]));
        }
        // The edge of the stage towards the hall.
        g.setColor(new Color(0x0C0B10));
        double[] e = camera.at(-HALF - 0.6, -0.7, -0.6);
        double[] f = camera.at(HALF + 0.6, -0.7, -0.6);
        Path2D edge = new Path2D.Double();
        edge.moveTo(a[0], a[1]);
        edge.lineTo(b[0], b[1]);
        edge.lineTo(f[0], f[1]);
        edge.lineTo(e[0], e[1]);
        edge.closePath();
        g.fill(edge);
    }

    /**
     * A curtain of cloth hanging in the plane across the stage at a depth: folds from top to
     * bottom, and waves that run through it in the air, larger towards the free hem. Every patch
     * is lit by how it faces the light, so the folds and the waves show as they move.
     *
     * @param left   where it begins, across
     * @param top    how high it hangs
     * @param depth  where it hangs, from the front edge
     * @param wide   how wide it is
     * @param bottom how high its hem hangs
     * @param tall   how long it is
     * @param folds  how many folds it has
     * @param drawn  to which side it is drawn back (-1 left, 1 right, 0 hangs free)
     * @param phase  shifts its waves against those of the other curtains
     * @param bright how bright it is
     */
    private void paintCloth(Graphics2D g, Camera camera, double t, double left, double top, double depth, double wide,
                            double bottom, double tall, int folds, int drawn, double phase, float bright, int columns, int rows) {
        Object smooth = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF); // no seams between patches
        double[][][] grid = new double[rows + 1][columns + 1][];
        for (int row = 0; row <= rows; row++) {
            double v = row / (double) rows;
            for (int column = 0; column <= columns; column++) {
                double u = column / (double) columns;
                // Drawn back, the cloth is gathered to its side at the height of the cord and falls open below it.
                double waist = drawn == 0 ? 0 : 0.62 * Math.exp(-Math.pow((v - 0.6) / 0.28, 2));
                double across = drawn == 0 ? u : drawn < 0 ? u * (1 - waist) : waist + u * (1 - waist);
                double fold = Math.sin(u * folds * 2 * Math.PI + phase) * 0.16 * (1 - 0.5 * waist);
                // The air: a slow swell through the whole cloth and a quicker ripple, both larger towards the hem.
                double free = drawn == 0 ? v : v * (1 - waist);
                double wave = free * (0.26 * Math.sin(t * 1.25 + u * 4.2 + phase)
                        + 0.12 * Math.sin(t * 2.6 - u * 9.5 + v * 3.1 + phase * 2))
                        + free * free * 0.3 * Math.sin(t * 0.55 + phase) * Math.max(0, Math.sin(t * 0.21 + u * 2.4 + phase));
                // The beat: every beat pushes the cloth towards the hall, most at the hem, and the push
                // runs down through it as a ripple while the beat fades.
                wave -= free * kick * (0.42 + 0.16 * Math.sin(u * 5 + phase))
                        + kick * 0.14 * v * Math.sin(v * 7 - (1 - kick) * 9 + phase);
                if (bands != null && bands.length > 0) {
                    // Mirrored across the cloth, low in the middle and high at the edges, so both sides answer alike.
                    double band = Math.abs(u * 2 - 1) * (bands.length - 1);
                    int low = (int) band;
                    int high = Math.min(bands.length - 1, low + 1);
                    double level = bands[low] + (bands[high] - bands[low]) * (band - low);
                    wave -= BULGE * level * (0.25 + 0.75 * free) * (drawn == 0 ? 1 : 0.6);
                }
                double sway = free * (0.12 * Math.sin(t * 0.9 + phase + v * 2) + 0.1 * kick * Math.sin(phase * 3));
                grid[row][column] = new double[]{left + across * wide + sway, top - v * tall, depth + fold + wave};
            }
        }
        Path2D patch = new Path2D.Double();
        for (int row = 0; row < rows; row++) {
            double v = (row + 0.5) / rows;
            for (int column = 0; column < columns; column++) {
                double[] p = grid[row][column];
                double[] q = grid[row][column + 1];
                double[] r = grid[row + 1][column + 1];
                double[] s = grid[row + 1][column];
                // How the patch faces the light that comes from the hall, a little from the left and from above.
                double ax = q[0] - p[0];
                double ay = q[1] - p[1];
                double az = q[2] - p[2];
                double bx = s[0] - p[0];
                double by = s[1] - p[1];
                double bz = s[2] - p[2];
                double nx = ay * bz - az * by;
                double ny = az * bx - ax * bz;
                double nz = ax * by - ay * bx;
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double facing = length == 0 ? 0 : Math.abs((nx * -0.42 + ny * 0.25 + nz * -0.87) / length);
                double light = Math.pow(facing, 2.2);
                Color cloth = mix(mix(curtainDeep, curtain, Math.min(1, light * 1.5)), curtainLight,
                        Math.max(0, light * 2.4 - 1.5));
                cloth = scale(cloth, bright * (float) (0.55 + 0.45 * v) * (1f + 0.3f * flash));
                double[] a = camera.at(p[0], p[1], p[2]);
                double[] b = camera.at(q[0], q[1], q[2]);
                double[] c = camera.at(r[0], r[1], r[2]);
                double[] d = camera.at(s[0], s[1], s[2]);
                patch.reset();
                patch.moveTo(a[0], a[1]);
                patch.lineTo(b[0] + 0.7, b[1]);
                patch.lineTo(c[0] + 0.7, c[1] + 0.7);
                patch.lineTo(d[0], d[1] + 0.7);
                patch.closePath();
                g.setColor(cloth);
                g.fill(patch);
            }
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, smooth == null ? RenderingHints.VALUE_ANTIALIAS_ON : smooth);
    }

    /** The musicians at the places of their tracks, those at the back first. */
    private void paintTracks(Graphics2D g, Camera camera, SoundPicture sound, VisualPalette palette, int width, int height) {
        List<TrackLight> lights = sound.lights;
        lightsShown = 0;
        if (lights.isEmpty()) {
            if (!sound.sounding) {
                Labels.quiet(g, width, height, palette);
                return;
            }
            if (sound.lightsComing) {
                // The tracks are being read: lights at made-up places at the front would jump to
                // their real places a moment later, so the stage stays empty until they are there.
                return;
            }
            lights = fromSpectrum(sound); // the tracks are not known: the sound itself stands on the stage
        }
        TrackLight[] ordered = lights.toArray(new TrackLight[0]);
        java.util.Arrays.sort(ordered, (a, b) -> Double.compare(b.depth(), a.depth()));
        lightsShown = ordered.length;
        for (TrackLight light : ordered) {
            double x = light.x() * (HALF - 0.8);
            double z = 0.6 + light.depth() * (DEPTH - 1.6);
            float level = Math.min(1f, light.level());
            Color color = palette.track().apply(light.index());
            double[] foot = camera.at(x, 0, z);
            double metre = camera.scale(foot[2]);
            // The light the track throws on the boards around its place.
            pool(g, camera, x, z, 0.7 + 1.1 * level, color, 0.16f + 0.4f * level);
            BufferedImage picture = light.trackId().isEmpty() ? null : sound.figures.apply(light.trackId(), color);
            if (picture != null) {
                double tall = sound.figureHeight * metre * (1 + 0.05 * level);
                double wide = tall * picture.getWidth() / picture.getHeight();
                Rectangle2D box = new Rectangle2D.Double(foot[0] - wide / 2, foot[1] - tall, wide, tall);
                figures.paint(g, picture, box, color, Math.min(1f, level * 1.6f), 0, false, false, (float) feather, (float) opacity);
            } else {
                // No figure: a light at the height of a musician, lifted by the height of the track.
                double[] at = camera.at(x, 1.3 + light.height() * 2.5, z);
                float radius = (float) Math.max(4, metre * (0.35 + 1.25 * level));
                Color glow = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (70 + 185 * level));
                Color edge = new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
                g.setPaint(new RadialGradientPaint((float) at[0], (float) at[1], radius, new float[]{0f, 1f},
                        new Color[]{glow, edge}));
                g.fill(new Ellipse2D.Double(at[0] - radius, at[1] - radius, 2 * radius, 2 * radius));
                float core = (float) Math.max(3, metre * 0.14);
                g.setColor(palette.ink());
                g.fill(new Ellipse2D.Double(at[0] - core / 2, at[1] - core / 2, core, core));
            }
            if (!light.trackId().isEmpty()) {
                g.setColor(new Color(palette.ink().getRed(), palette.ink().getGreen(), palette.ink().getBlue(), 190));
                g.drawString(light.trackId(), (float) (foot[0] - g.getFontMetrics().stringWidth(light.trackId()) / 2.0),
                        (float) (foot[1] + 14));
            }
        }
    }

    /** A round light lying on the boards. */
    static void pool(Graphics2D g, Camera camera, double x, double z, double radius, Color color, float strength) {
        for (int ring = 0; ring < 3; ring++) {
            double r = radius * (1 - ring * 0.3);
            Path2D round = new Path2D.Double();
            for (int step = 0; step < 24; step++) {
                double angle = step * Math.PI / 12;
                double[] at = camera.at(x + Math.cos(angle) * r, 0.01, z + Math.sin(angle) * r);
                if (step == 0) {
                    round.moveTo(at[0], at[1]);
                } else {
                    round.lineTo(at[0], at[1]);
                }
            }
            round.closePath();
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
                    Math.max(0, Math.min(255, Math.round(255 * strength * 0.45f)))));
            g.fill(round);
        }
    }

    static Color mix(Color a, Color b, double share) {
        double s = Math.max(0, Math.min(1, share));
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * s),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * s),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * s));
    }

    private static Color scale(Color color, float by) {
        return new Color(Math.min(255, Math.round(color.getRed() * by)), Math.min(255, Math.round(color.getGreen() * by)),
                Math.min(255, Math.round(color.getBlue() * by)));
    }
}
