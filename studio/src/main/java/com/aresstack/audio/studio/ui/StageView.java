package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The stage seen from the hall, a little from above: the floor runs back to a curtain at the
 * back wall and gets narrower there, curtains hang at both sides in front and move as in a
 * breath of air, the listener sits in front at the bottom, and every track is a disc on the
 * floor in the colour of its channel, smaller the further back it stands. A disc is moved
 * with the mouse; the wheel over a disc makes the track louder or quieter. The
 * larger a disc, the louder its track; the halo around it shows its share of the
 * room. A proposal of the AI is shown as dashed outlines until it is taken or dropped.
 */
final class StageView extends JComponent {
    /** Told what was done on the stage. */
    interface Listener {
        /**
         * @param settled {@code false} while a disc is still being dragged
         */
        void changed(SoundSourcePlacement placement, boolean settled);

        void selected(String trackId);
    }

    /** The stage in metres, as the mixer hears it: 12 wide, 9 deep, the listener 3 in front. */
    private static final double WIDTH_M = 12;
    private static final double DEPTH_M = 9;
    private static final double LISTENER_M = 3;

    private final Listener listener;
    private SpatialScene scene = SpatialScene.standard(new ArrayList<com.aresstack.audio.studio.application.MusicTrack>());
    private SpatialScene proposal;
    private List<String> order = new ArrayList<String>();
    private final Map<String, Float> levels = new HashMap<String, Float>();
    private String selected;
    private String dragged;
    private String hovered;
    private double grabX;
    private double grabDepth;

    StageView(Listener listener) {
        this.listener = listener;
        setOpaque(false);
        setPreferredSize(new Dimension(640, 560));
        setMinimumSize(new Dimension(320, 300));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                String hit = at(event.getPoint());
                if (hit != null) {
                    select(hit);
                    SoundSourcePlacement placement = scene.placement(hit);
                    Point2D place = point(placement.x(), placement.depth());
                    grabX = event.getX() - place.getX();
                    grabDepth = event.getY() - place.getY();
                    dragged = hit;
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragged != null) {
                    move(event, false);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (dragged != null) {
                    move(event, true);
                    dragged = null;
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                String hit = at(event.getPoint());
                if (hit == null ? hovered != null : !hit.equals(hovered)) {
                    hovered = hit;
                    setCursor(Cursor.getPredefinedCursor(hit == null ? Cursor.DEFAULT_CURSOR : Cursor.MOVE_CURSOR));
                    SoundSourcePlacement placement = hit == null ? null : scene.placement(hit);
                    setToolTipText(placement == null ? null : hit + " · " + words(placement));
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (hovered != null) {
                    hovered = null;
                    repaint();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                String hit = at(event.getPoint());
                SoundSourcePlacement placement = hit == null ? null : scene.placement(hit);
                if (placement == null) {
                    if (getParent() != null) {
                        getParent().dispatchEvent(javax.swing.SwingUtilities.convertMouseEvent(
                                StageView.this, event, getParent()));
                    }
                    return;
                }
                select(hit);
                SoundSourcePlacement louder = placement.withLevel(
                        Math.round(placement.levelDb()) - event.getWheelRotation());
                scene = scene.with(louder);
                repaint();
                StageView.this.listener.changed(louder, true);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Where a placement is and how loud, in a few words. */
    static String words(SoundSourcePlacement placement) {
        double pan = SpatialStemMixer.pan(placement);
        String side = Math.abs(pan) < 0.06 ? "Mitte" : Math.round(Math.abs(pan) * 100) + " % " + (pan < 0 ? "links" : "rechts");
        return side + " · " + String.format(java.util.Locale.GERMANY, "%.1f m", SpatialStemMixer.distance(placement))
                + " · " + decibels(placement.levelDb()) + (placement.muted() ? " · stumm" : "");
    }

    static String decibels(double db) {
        long whole = Math.round(db);
        return (whole > 0 ? "+" : whole < 0 ? "−" : "±") + Math.abs(whole) + " dB";
    }

    /** Shows a scene; the order of the tracks gives each disc the colour of its channel. */
    void setScene(SpatialScene shown, List<String> trackIds) {
        scene = shown;
        order = new ArrayList<String>(trackIds);
        if (selected != null && shown.placement(selected) == null) {
            selected = null;
        }
        repaint();
    }

    /** A proposal to be shown over the stage, or {@code null} for none. */
    void setProposal(SpatialScene proposed) {
        proposal = proposed;
        repaint();
    }

    /** How loud each track is right now, 0 to 1: its disc glows with it while the piece plays. */
    void setLevels(Map<String, Float> now) {
        if (now.isEmpty() && levels.isEmpty()) {
            return;
        }
        levels.clear();
        levels.putAll(now);
        repaint();
    }

    String selected() {
        return selected;
    }

    void select(String trackId) {
        if (trackId == null ? selected != null : !trackId.equals(selected)) {
            selected = trackId;
            repaint();
            listener.selected(trackId);
        }
    }

    private void move(MouseEvent event, boolean settled) {
        SoundSourcePlacement placement = scene.placement(dragged);
        if (placement == null) {
            return;
        }
        double[] place = place(event.getX() - grabX, event.getY() - grabDepth);
        double x = place[0];
        double depth = place[1];
        SoundSourcePlacement moved = placement.at(Math.round(x * 100) / 100.0, Math.round(depth * 100) / 100.0);
        scene = scene.with(moved);
        repaint();
        listener.changed(moved, settled);
    }

    /** How much narrower the back wall is on the screen than the front edge. */
    static final double BACK_NARROWING = 0.7;
    /** How flat a circle on the floor looks from the hall. */
    private static final float FLAT = 0.5f;

    /** The front edge of the floor on the screen: its half width, its middle, where it lies and where the back wall lies. */
    private double[] floor() {
        // Across: the floor and a curtain at each side. Down: the back curtain, the floor, the listener.
        double half = Math.max(60, Math.min((getWidth() - 40) / 2.5, (getHeight() - 30) / 2.2));
        double total = half * 2.2;
        double top = (getHeight() - total) / 2 + 4;
        double back = top + half * 0.62;
        double front = back + half * 0.9;
        return new double[]{half, getWidth() / 2.0, front, back};
    }

    /** How large things are at a depth, 1 at the front edge: the further back, the smaller. */
    private static double near(double depth) {
        return 1 / (1 + depth * (1 / BACK_NARROWING - 1));
    }

    /** Where a place of the stage is on the screen; in front of the edge (the listener) the depth is below 0. */
    private Point2D point(double x, double depth) {
        double[] floor = floor();
        double near = near(depth);
        double down = (1 - near) / (1 - BACK_NARROWING);
        return new Point2D.Double(floor[1] + x * floor[0] * near, floor[2] - (floor[2] - floor[3]) * down);
    }

    /** The place of the stage under a point of the screen: x and depth. */
    private double[] place(double px, double py) {
        double[] floor = floor();
        double down = (floor[2] - py) / (floor[2] - floor[3]);
        double near = Math.max(0.2, 1 - down * (1 - BACK_NARROWING));
        double depth = (1 / near - 1) / (1 / BACK_NARROWING - 1);
        return new double[]{(px - floor[1]) / (floor[0] * near), depth};
    }

    /** The outline of the floor: wide in front, narrow at the back wall. */
    private Path2D floorShape() {
        Path2D shape = new Path2D.Double();
        Point2D a = point(-1, 0);
        Point2D b = point(1, 0);
        Point2D c = point(1, 1);
        Point2D d = point(-1, 1);
        shape.moveTo(a.getX(), a.getY());
        shape.lineTo(b.getX(), b.getY());
        shape.lineTo(c.getX(), c.getY());
        shape.lineTo(d.getX(), d.getY());
        shape.closePath();
        return shape;
    }

    /** The radius of a disc on the screen: by its level, and smaller the further back it stands. */
    private float radius(SoundSourcePlacement placement) {
        return (float) (loudness(placement) * near(placement.depth()) / near(0.4));
    }

    private static float loudness(SoundSourcePlacement placement) {
        double share = (placement.levelDb() - SoundSourcePlacement.MIN_LEVEL)
                / (SoundSourcePlacement.MAX_LEVEL - SoundSourcePlacement.MIN_LEVEL);
        return (float) (11 + 15 * Math.pow(Math.max(0, share), 1.6));
    }

    /** The track whose disc is under a point; the one drawn last lies on top. */
    private String at(java.awt.Point point) {
        String hit = null;
        for (SoundSourcePlacement placement : drawn()) {
            Point2D place = point(placement.x(), placement.depth());
            double reach = radius(placement) + 4;
            double across = (point.x - place.getX()) / reach;
            double down = (point.y - place.getY()) / (reach * FLAT + 3);
            if (across * across + down * down <= 1) {
                hit = placement.trackId();
            }
        }
        return hit;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = DeckWidgets.smooth(graphics);
        RoundRectangle2D well = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 14f, 14f);
        g.setColor(AudioStudioTheme.WELL);
        g.fill(well);
        g.setColor(AudioStudioTheme.STROKE);
        g.draw(well);
        g.clip(well);

        double[] floor = floor();
        Path2D boards = floorShape();
        Point2D ear = point(0, -LISTENER_M / DEPTH_M);
        Point2D backLeft = point(-1, 1);
        Point2D backRight = point(1, 1);
        Point2D frontLeft = point(-1, 0);
        Point2D frontRight = point(1, 0);
        double breath = breath();

        // The curtain at the back wall, then the floor in front of it, lit from the front.
        double backTop = floor[3] - floor[0] * 0.58;
        paintCurtain(g, backLeft.getX(), backTop, backRight.getX() - backLeft.getX(), floor[3] - backTop,
                0, 0, 26, breath, 0.7f);
        g.setPaint(new java.awt.GradientPaint(0, (float) floor[3], new Color(0x1B1D25),
                0, (float) floor[2], new Color(0x2B2E3B)));
        g.fill(boards);
        // Rings of equal distance from the listener, and the lines beyond which a track is all on one side.
        Graphics2D rings = (Graphics2D) g.create();
        rings.clip(boards);
        rings.setStroke(new BasicStroke(1f));
        rings.setFont(AudioStudioTheme.lettering(9.5f));
        for (int metres = 4; metres <= 14; metres += 2) {
            Path2D ring = new Path2D.Double();
            for (int step = -90; step <= 90; step += 3) {
                Point2D at = metres(Math.sin(Math.toRadians(step)) * metres, Math.cos(Math.toRadians(step)) * metres);
                if (step == -90) {
                    ring.moveTo(at.getX(), at.getY());
                } else {
                    ring.lineTo(at.getX(), at.getY());
                }
            }
            rings.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_FAINT, 60));
            rings.draw(ring);
            Point2D word = metres(0, metres);
            rings.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_FAINT, 170));
            rings.drawString(metres + " m", (float) (word.getX() + 5), (float) (word.getY() + 12));
        }
        rings.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_FAINT, 50));
        rings.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 6f}, 0f));
        rings.draw(new Line2D.Double(ear, metres(0, 30)));
        for (int side = -1; side <= 1; side += 2) {
            rings.draw(new Line2D.Double(ear, metres(side * Math.sin(Math.toRadians(60)) * 30,
                    Math.cos(Math.toRadians(60)) * 30)));
        }
        rings.dispose();
        // The edges: the foot of the back curtain, the sides, the front open to the hall.
        g.setColor(new Color(0, 0, 0, 110));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(backLeft, backRight));
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(AudioStudioTheme.STROKE);
        g.draw(new Line2D.Double(backLeft, frontLeft));
        g.draw(new Line2D.Double(backRight, frontRight));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{2f, 5f}, 0f));
        g.draw(new Line2D.Double(frontLeft, frontRight));
        g.setFont(AudioStudioTheme.lettering(10f));
        g.setColor(AudioStudioTheme.TEXT_FAINT);
        FontMetrics metrics = g.getFontMetrics();
        String wall = "RÜCKWAND · " + scene.room().name().toUpperCase(java.util.Locale.ROOT);
        g.drawString(wall, (float) (floor[1] - metrics.stringWidth(wall) / 2.0), (float) floor[3] + 14);
        g.drawString("LINKS", (float) frontLeft.getX() + 6, (float) floor[2] + 14);
        g.drawString("RECHTS", (float) frontRight.getX() - 6 - metrics.stringWidth("RECHTS"), (float) floor[2] + 14);

        paintListener(g, ear);

        // What the AI proposes: where each disc would go.
        if (proposal != null) {
            for (SoundSourcePlacement proposed : proposal.placements()) {
                SoundSourcePlacement now = scene.placement(proposed.trackId());
                if (now == null || now.locked()) {
                    continue;
                }
                Color color = AudioStudioTheme.channel(Math.max(0, order.indexOf(proposed.trackId())));
                Point2D from = point(now.x(), now.depth());
                Point2D to = point(proposed.x(), proposed.depth());
                float radius = radius(proposed);
                g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, 0f));
                g.setColor(AudioStudioTheme.withAlpha(color, 150));
                g.draw(new Line2D.Double(from, to));
                g.setColor(AudioStudioTheme.withAlpha(color, 40));
                g.fill(new Ellipse2D.Double(to.getX() - radius, to.getY() - radius, radius * 2, radius * 2));
                g.setColor(AudioStudioTheme.withAlpha(color, 230));
                g.draw(new Ellipse2D.Double(to.getX() - radius, to.getY() - radius, radius * 2, radius * 2));
            }
        }

        // The tracks, those at the back first, so that the near ones stand in front of them.
        SoundSourcePlacement chosen = selected == null ? null : scene.placement(selected);
        for (SoundSourcePlacement placement : drawn()) {
            paintSource(g, placement, placement == chosen);
        }
        // The curtains at both sides in front, drawn back and moving a little in the air.
        double sideWidth = floor[0] * 0.24;
        double sideTop = backTop - floor[0] * 0.04;
        double sideHeight = floor[2] + floor[0] * 0.1 - sideTop;
        paintCurtain(g, frontLeft.getX() - sideWidth - 2, sideTop, sideWidth, sideHeight, -1, 0.42, 7, breath, 1f);
        paintCurtain(g, frontRight.getX() + 2, sideTop, sideWidth, sideHeight, 1, 0.42, 7, breath + 1.7, 1f);
        if (scene.placements().isEmpty()) {
            g.setFont(AudioStudioTheme.lettering(11f));
            g.setColor(AudioStudioTheme.TEXT_FAINT);
            String none = "KEINE SPUREN";
            g.drawString(none, (float) (floor[1] - g.getFontMetrics().stringWidth(none) / 2.0),
                    (float) ((floor[2] + floor[3]) / 2));
        }
        g.dispose();
    }

    private void paintSource(Graphics2D g, SoundSourcePlacement placement, boolean chosen) {
        int index = Math.max(0, order.indexOf(placement.trackId()));
        Color color = placement.muted() ? new Color(0x5A5C68) : AudioStudioTheme.channel(index);
        Point2D place = point(placement.x(), placement.depth());
        float radius = radius(placement);
        float x = (float) place.getX();
        float y = (float) place.getY();
        Float level = levels.get(placement.trackId());
        float sounding = level == null || placement.muted() ? 0 : Math.min(1, level * 1.6f);
        // The halo: the share of the room, and brighter while the track sounds.
        if (!placement.muted()) {
            float halo = radius + 10 + (float) placement.reverb() * 46;
            g.setPaint(new RadialGradientPaint(x, y, halo, new float[]{0f, 1f},
                    new Color[]{AudioStudioTheme.withAlpha(color, Math.round(46 + 110 * sounding)),
                            AudioStudioTheme.withAlpha(color, 0)}));
            Graphics2D flat = (Graphics2D) g.create();
            flat.translate(0, y);
            flat.scale(1, FLAT + 0.15);
            flat.translate(0, -y);
            flat.fill(new Ellipse2D.Float(x - halo, y - halo, halo * 2, halo * 2));
            flat.dispose();
        }
        // The width: an arc to both sides of the disc.
        if (!placement.muted() && placement.width() > 0.05) {
            float reach = radius + 5;
            double spread = 20 + 60 * placement.width();
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(AudioStudioTheme.withAlpha(color, 150));
            g.draw(new Arc2D.Float(x - reach, y - reach * FLAT, reach * 2, reach * 2 * FLAT, (float) (-spread / 2), (float) spread, Arc2D.OPEN));
            g.draw(new Arc2D.Float(x - reach, y - reach * FLAT, reach * 2, reach * 2 * FLAT, (float) (180 - spread / 2), (float) spread, Arc2D.OPEN));
        }
        float flat = radius * FLAT;
        // The disc lies on the floor: seen from the hall it is an ellipse with a rim towards the hall.
        if (!placement.muted()) {
            g.setColor(color.darker().darker());
            g.fill(new Ellipse2D.Float(x - radius, y - flat + 3, radius * 2, flat * 2));
        }
        Ellipse2D disc = new Ellipse2D.Float(x - radius, y - flat, radius * 2, flat * 2);
        if (placement.muted()) {
            g.setColor(new Color(0x23252E));
            g.fill(disc);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 4f}, 0f));
            g.setColor(color);
            g.draw(disc);
        } else {
            g.setPaint(new java.awt.GradientPaint(x, y - flat, AudioStudioTheme.withAlpha(color, 255),
                    x, y + flat, color.darker()));
            g.fill(disc);
        }
        boolean over = placement.trackId().equals(hovered);
        if (chosen || over) {
            g.setStroke(new BasicStroke(chosen ? 2.2f : 1.4f));
            g.setColor(chosen ? Color.WHITE : AudioStudioTheme.withAlpha(Color.WHITE, 150));
            g.draw(new Ellipse2D.Float(x - radius - 2.5f, y - flat - 2.5f, radius * 2 + 5, flat * 2 + 5));
        }
        // The number of the channel in the disc, the name under it.
        g.setFont(AudioStudioTheme.lettering(Math.max(8.5f, Math.min(12f, radius * 0.6f))));
        FontMetrics metrics = g.getFontMetrics();
        String number = String.valueOf(index + 1);
        g.setColor(placement.muted() ? AudioStudioTheme.TEXT_DIM : new Color(0x15161B));
        g.drawString(number, x - metrics.stringWidth(number) / 2f, y + metrics.getAscent() / 2f - 1.5f);
        g.setFont(getFont().deriveFont(chosen ? java.awt.Font.BOLD : java.awt.Font.PLAIN, 11.5f));
        metrics = g.getFontMetrics();
        String name = placement.trackId();
        float nameX = x - metrics.stringWidth(name) / 2f;
        float nameY = y + flat + 17;
        g.setColor(new Color(0, 0, 0, 120));
        g.fill(new RoundRectangle2D.Float(nameX - 5, nameY - 12, metrics.stringWidth(name) + 10, 16, 8, 8));
        g.setColor(placement.muted() ? AudioStudioTheme.TEXT_FAINT : chosen ? Color.WHITE : AudioStudioTheme.TEXT);
        g.drawString(name, nameX, nameY);
        if (placement.locked()) {
            paintLock(g, x + radius * 0.8f, y - flat * 0.9f);
        }
    }

    /** The tracks in the order they are drawn: those at the back first, the near ones in front of them. */
    private List<SoundSourcePlacement> drawn() {
        List<SoundSourcePlacement> drawn = new ArrayList<SoundSourcePlacement>(scene.placements());
        drawn.sort((a, b) -> Double.compare(b.depth(), a.depth()));
        return drawn;
    }

    /** A place given in metres from the listener: to the right, and towards the back wall. */
    private Point2D metres(double right, double ahead) {
        return point(right / (WIDTH_M / 2), (ahead - LISTENER_M) / DEPTH_M);
    }

    /** The breath of air that moves the curtains, in radians; it stands still while the stage is not shown. */
    private double breath() {
        return (System.nanoTime() - born) / 1e9 * 0.9;
    }

    private final long born = System.nanoTime();
    /** Moves the curtains; runs only while the stage is on the screen. */
    private final javax.swing.Timer air = new javax.swing.Timer(60, event -> {
        if (isShowing()) {
            repaint();
        }
    });

    @Override
    public void addNotify() {
        super.addNotify();
        air.start();
    }

    @Override
    public void removeNotify() {
        air.stop();
        super.removeNotify();
    }

    /** The colours of the curtains: dark red running into the pink of the desk. */
    private static final Color CURTAIN_DEEP = new Color(0x3A0A20);
    private static final Color CURTAIN = new Color(0x8E1B4A);
    private static final Color CURTAIN_LIGHT = new Color(0xC93C7A);

    /**
     * A curtain in folds. Each fold is lit on one flank and dark on the other; the folds shift a
     * little with the breath of air, and the hem swings with them.
     *
     * @param drawn  to which side it is drawn back at the bottom (-1 left, 1 right, 0 hangs straight)
     * @param waist  how much narrower it is at the bottom when drawn back, as a share of its width
     * @param folds  how many folds it has
     * @param bright how bright it is, 1 for a curtain in the light, less for one far back
     */
    private static void paintCurtain(Graphics2D g, double x, double y, double width, double height, int drawn,
                                     double waist, int folds, double breath, float bright) {
        Graphics2D c = (Graphics2D) g.create();
        int strips = Math.max(folds * 6, (int) (width / 3));
        double step = width / strips;
        for (int strip = 0; strip < strips; strip++) {
            double across = (strip + 0.5) / strips;
            // The fold under this strip, pushed a little to and fro by the air.
            double fold = across * folds * 2 * Math.PI + 0.5 * Math.sin(breath + across * 5.0);
            double light = 0.5 + 0.5 * Math.sin(fold);
            Color lit = mix(mix(CURTAIN_DEEP, CURTAIN, Math.min(1, light * 1.6)), CURTAIN_LIGHT,
                    Math.max(0, light * 1.9 - 1.15));
            Color top = scale(lit, bright * 0.55f);
            Color bottom = scale(lit, bright);
            // Drawn back, the cloth gathers towards its side at the bottom and swings there.
            double sway = Math.sin(breath * 0.8 + across * 3) * width * 0.03;
            double gathered = drawn == 0 ? across : drawn < 0 ? across * (1 - waist) : waist + across * (1 - waist);
            double left = x + (gathered - 0.5 / strips * (drawn == 0 ? 1 : 1 - waist)) * width + (drawn == 0 ? 0 : sway);
            double bottomWidth = step * (drawn == 0 ? 1 : 1 - waist) + 0.6;
            double hem = y + height + Math.sin(fold + breath * 0.6) * (drawn == 0 ? 2.5 : 5);
            Path2D cloth = new Path2D.Double();
            cloth.moveTo(x + strip * step, y);
            cloth.lineTo(x + strip * step + step + 0.6, y);
            cloth.lineTo(left + bottomWidth, hem);
            cloth.lineTo(left, hem);
            cloth.closePath();
            c.setPaint(new java.awt.GradientPaint(0, (float) y, top, 0, (float) (y + height), bottom));
            c.fill(cloth);
        }
        c.dispose();
    }

    private static Color mix(Color a, Color b, double share) {
        double s = Math.max(0, Math.min(1, share));
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * s),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * s),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * s));
    }

    private static Color scale(Color color, float by) {
        return new Color(Math.min(255, Math.round(color.getRed() * by)), Math.min(255, Math.round(color.getGreen() * by)),
                Math.min(255, Math.round(color.getBlue() * by)));
    }

    /** A small padlock: this track keeps its place when the AI proposes a stage. */
    private static void paintLock(Graphics2D g, float x, float y) {
        g.setColor(new Color(0x15161B));
        g.fill(new Ellipse2D.Float(x - 8, y - 8, 16, 16));
        g.setColor(AudioStudioTheme.AMBER);
        g.fill(new RoundRectangle2D.Float(x - 4, y - 1, 8, 6, 2, 2));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Arc2D.Float(x - 2.6f, y - 5, 5.2f, 7, 0, 180, Arc2D.OPEN));
    }

    /** The listener: a head seen from above, nose towards the stage. */
    private static void paintListener(Graphics2D g, Point2D ear) {
        float x = (float) ear.getX();
        float y = (float) ear.getY();
        g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 40));
        g.fill(new Ellipse2D.Float(x - 26, y - 26, 52, 52));
        g.setColor(AudioStudioTheme.ACCENT_SOFT);
        g.fill(new Ellipse2D.Float(x - 15, y - 4, 6, 9));
        g.fill(new Ellipse2D.Float(x + 9, y - 4, 6, 9));
        Path2D nose = new Path2D.Float();
        nose.moveTo(x - 4, y - 9);
        nose.lineTo(x, y - 16);
        nose.lineTo(x + 4, y - 9);
        nose.closePath();
        g.fill(nose);
        g.setColor(AudioStudioTheme.ACCENT);
        g.fill(new Ellipse2D.Float(x - 11, y - 11, 22, 22));
        g.setFont(AudioStudioTheme.lettering(10f));
        g.setColor(AudioStudioTheme.TEXT_DIM);
        String name = "HÖRER";
        g.drawString(name, x - g.getFontMetrics().stringWidth(name) / 2f, y + 26);
    }
}
