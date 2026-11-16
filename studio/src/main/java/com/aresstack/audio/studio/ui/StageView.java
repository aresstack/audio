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
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The stage from above: the back wall at the top, the listener in front at the
 * bottom, and every track as a disc in the colour of its channel. A disc is moved
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
    /** The figure of a track as a picture, or {@code null}; none at all while no figures are wanted. */
    private java.util.function.Function<String, java.awt.image.BufferedImage> figures = track -> null;
    private com.aresstack.audio.studio.figure.FigureSettings figureLook =
            com.aresstack.audio.studio.figure.FigureSettings.DEFAULT;
    private double bob = com.aresstack.audio.studio.figure.FigureSettings.DEFAULT_BOB;

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

    /**
     * The figures of the musicians: each stands on the disc of its track, as tall as the settings
     * say, and grows a little with the level of its track while it sounds.
     */
    void setFigures(java.util.function.Function<String, java.awt.image.BufferedImage> pictures,
                    com.aresstack.audio.studio.figure.FigureSettings look, double bobbing) {
        figures = pictures == null ? track -> null : pictures;
        figureLook = look;
        bob = bobbing;
        repaint();
    }

    /** Draws the figures anew, e.g. when one arrived. */
    void figuresChanged() {
        repaint();
    }

    /** Where the figure of a placement stands on the screen, or {@code null} if it has none. */
    private Rectangle2D figureBox(SoundSourcePlacement placement) {
        if (!figureLook.shown()) {
            return null;
        }
        java.awt.image.BufferedImage picture = figures.apply(placement.trackId());
        if (picture == null) {
            return null;
        }
        Float level = levels.get(placement.trackId());
        float sounding = level == null || placement.muted() ? 0 : Math.min(1, level * 1.6f);
        double metre = floor().getWidth() / WIDTH_M;
        double height = figureLook.height() * metre * (1 + bob * sounding);
        double width = height * picture.getWidth() / picture.getHeight();
        Point2D place = point(placement.x(), placement.depth());
        // The feet in the middle of the disc: the figure stands on its place.
        return new Rectangle2D.Double(place.getX() - width / 2, place.getY() - height, width, height);
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
        Rectangle2D floor = floor();
        double x = (event.getX() - grabX - floor.getCenterX()) / (floor.getWidth() / 2);
        double depth = (floor.getMaxY() - (event.getY() - grabDepth)) / floor.getHeight();
        SoundSourcePlacement moved = placement.at(Math.round(x * 100) / 100.0, Math.round(depth * 100) / 100.0);
        scene = scene.with(moved);
        repaint();
        listener.changed(moved, settled);
    }

    /** The floor of the stage on the screen: as large as fits, with room for the listener below it. */
    private Rectangle2D floor() {
        double availableWidth = getWidth() - 48;
        double availableHeight = getHeight() - 40;
        double scale = Math.max(8, Math.min(availableWidth / WIDTH_M, availableHeight / (DEPTH_M + LISTENER_M + 0.6)));
        double width = WIDTH_M * scale;
        double height = DEPTH_M * scale;
        double total = (DEPTH_M + LISTENER_M + 0.6) * scale;
        return new Rectangle2D.Double((getWidth() - width) / 2, (getHeight() - total) / 2 + 6, width, height);
    }

    private Point2D point(double x, double depth) {
        Rectangle2D floor = floor();
        return new Point2D.Double(floor.getCenterX() + x * floor.getWidth() / 2,
                floor.getMaxY() - depth * floor.getHeight());
    }

    private float radius(SoundSourcePlacement placement) {
        double share = (placement.levelDb() - SoundSourcePlacement.MIN_LEVEL)
                / (SoundSourcePlacement.MAX_LEVEL - SoundSourcePlacement.MIN_LEVEL);
        return (float) (11 + 15 * Math.pow(Math.max(0, share), 1.6));
    }

    /** The track whose disc is under a point; the one drawn last lies on top. */
    private String at(java.awt.Point point) {
        String hit = null;
        for (SoundSourcePlacement placement : scene.placements()) {
            Point2D place = point(placement.x(), placement.depth());
            Rectangle2D figure = figureBox(placement);
            if (place.distance(point) <= radius(placement) + 4 || figure != null && figure.contains(point)) {
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

        Rectangle2D floor = floor();
        double metre = floor.getWidth() / WIDTH_M;
        Point2D ear = new Point2D.Double(floor.getCenterX(), floor.getMaxY() + LISTENER_M * metre);

        // The floor, lit from the front.
        g.setPaint(new java.awt.GradientPaint(0, (float) floor.getMinY(), new Color(0x1B1D25),
                0, (float) floor.getMaxY(), new Color(0x262935)));
        g.fill(floor);
        // Rings of equal distance from the listener, and the lines beyond which a track is all on one side.
        Graphics2D rings = (Graphics2D) g.create();
        rings.clip(floor);
        rings.setStroke(new BasicStroke(1f));
        rings.setFont(AudioStudioTheme.lettering(9.5f));
        for (int metres = 4; metres <= 14; metres += 2) {
            double radius = metres * metre;
            rings.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_FAINT, 60));
            rings.draw(new Ellipse2D.Double(ear.getX() - radius, ear.getY() - radius, radius * 2, radius * 2));
            rings.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_FAINT, 170));
            rings.drawString(metres + " m", (float) (ear.getX() + 5), (float) (ear.getY() - radius + 12));
        }
        rings.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_FAINT, 50));
        rings.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 6f}, 0f));
        rings.draw(new Line2D.Double(ear.getX(), ear.getY(), ear.getX(), floor.getMinY()));
        double reach = 30 * metre;
        for (int side = -1; side <= 1; side += 2) {
            rings.draw(new Line2D.Double(ear.getX(), ear.getY(),
                    ear.getX() + side * Math.sin(Math.toRadians(60)) * reach,
                    ear.getY() - Math.cos(Math.toRadians(60)) * reach));
        }
        rings.dispose();
        // The walls: the back wall thick, the sides thin, the front open to the hall.
        g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.TEXT_DIM, 200));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(floor.getMinX(), floor.getMinY(), floor.getMaxX(), floor.getMinY()));
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(AudioStudioTheme.STROKE);
        g.draw(new Line2D.Double(floor.getMinX(), floor.getMinY(), floor.getMinX(), floor.getMaxY()));
        g.draw(new Line2D.Double(floor.getMaxX(), floor.getMinY(), floor.getMaxX(), floor.getMaxY()));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{2f, 5f}, 0f));
        g.draw(new Line2D.Double(floor.getMinX(), floor.getMaxY(), floor.getMaxX(), floor.getMaxY()));
        g.setFont(AudioStudioTheme.lettering(10f));
        g.setColor(AudioStudioTheme.TEXT_FAINT);
        FontMetrics metrics = g.getFontMetrics();
        String wall = "RÜCKWAND · " + scene.room().name().toUpperCase(java.util.Locale.ROOT);
        g.drawString(wall, (float) (floor.getCenterX() - metrics.stringWidth(wall) / 2.0), (float) floor.getMinY() - 6);
        g.drawString("LINKS", (float) floor.getMinX() + 6, (float) floor.getMaxY() + 14);
        g.drawString("RECHTS", (float) floor.getMaxX() - 6 - metrics.stringWidth("RECHTS"), (float) floor.getMaxY() + 14);

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

        // The tracks; the chosen one last, so that it lies on top.
        List<SoundSourcePlacement> drawn = new ArrayList<SoundSourcePlacement>(scene.placements());
        SoundSourcePlacement chosen = selected == null ? null : scene.placement(selected);
        if (chosen != null) {
            drawn.remove(chosen);
            drawn.add(chosen);
        }
        for (SoundSourcePlacement placement : drawn) {
            paintSource(g, placement, placement == chosen);
        }
        paintFigures(g, drawn, chosen);
        if (scene.placements().isEmpty()) {
            g.setFont(AudioStudioTheme.lettering(11f));
            g.setColor(AudioStudioTheme.TEXT_FAINT);
            String none = "KEINE SPUREN";
            g.drawString(none, (float) (floor.getCenterX() - g.getFontMetrics().stringWidth(none) / 2.0),
                    (float) floor.getCenterY());
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
            g.fill(new Ellipse2D.Float(x - halo, y - halo, halo * 2, halo * 2));
        }
        // The width: an arc to both sides of the disc.
        if (!placement.muted() && placement.width() > 0.05) {
            float reach = radius + 5;
            double spread = 20 + 60 * placement.width();
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(AudioStudioTheme.withAlpha(color, 150));
            g.draw(new Arc2D.Float(x - reach, y - reach, reach * 2, reach * 2, (float) (-spread / 2), (float) spread, Arc2D.OPEN));
            g.draw(new Arc2D.Float(x - reach, y - reach, reach * 2, reach * 2, (float) (180 - spread / 2), (float) spread, Arc2D.OPEN));
        }
        Ellipse2D disc = new Ellipse2D.Float(x - radius, y - radius, radius * 2, radius * 2);
        if (placement.muted()) {
            g.setColor(new Color(0x23252E));
            g.fill(disc);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 4f}, 0f));
            g.setColor(color);
            g.draw(disc);
        } else {
            g.setPaint(new java.awt.GradientPaint(x, y - radius, AudioStudioTheme.withAlpha(color, 255),
                    x, y + radius, color.darker()));
            g.fill(disc);
        }
        boolean over = placement.trackId().equals(hovered);
        if (chosen || over) {
            g.setStroke(new BasicStroke(chosen ? 2.2f : 1.4f));
            g.setColor(chosen ? Color.WHITE : AudioStudioTheme.withAlpha(Color.WHITE, 150));
            g.draw(new Ellipse2D.Float(x - radius - 2.5f, y - radius - 2.5f, radius * 2 + 5, radius * 2 + 5));
        }
        // The number of the channel in the disc, the name under it.
        g.setFont(AudioStudioTheme.lettering(Math.max(9.5f, Math.min(13f, radius * 0.8f))));
        FontMetrics metrics = g.getFontMetrics();
        String number = String.valueOf(index + 1);
        g.setColor(placement.muted() ? AudioStudioTheme.TEXT_DIM : new Color(0x15161B));
        g.drawString(number, x - metrics.stringWidth(number) / 2f, y + metrics.getAscent() / 2f - 1.5f);
        g.setFont(getFont().deriveFont(chosen ? java.awt.Font.BOLD : java.awt.Font.PLAIN, 11.5f));
        metrics = g.getFontMetrics();
        String name = placement.trackId();
        float nameX = x - metrics.stringWidth(name) / 2f;
        float nameY = y + radius + 15;
        g.setColor(new Color(0, 0, 0, 120));
        g.fill(new RoundRectangle2D.Float(nameX - 5, nameY - 12, metrics.stringWidth(name) + 10, 16, 8, 8));
        g.setColor(placement.muted() ? AudioStudioTheme.TEXT_FAINT : chosen ? Color.WHITE : AudioStudioTheme.TEXT);
        g.drawString(name, nameX, nameY);
        if (placement.locked()) {
            paintLock(g, x + radius * 0.75f, y - radius * 0.75f);
        }
    }

    /**
     * The figures over the discs, the ones further back first, so that a figure in front stands
     * before them. A muted track's figure is faint; the chosen one has a light at its feet.
     */
    private void paintFigures(Graphics2D g, List<SoundSourcePlacement> drawn, SoundSourcePlacement chosen) {
        List<SoundSourcePlacement> back = new ArrayList<SoundSourcePlacement>(drawn);
        back.sort((a, b) -> Double.compare(b.depth(), a.depth()));
        for (SoundSourcePlacement placement : back) {
            Rectangle2D box = figureBox(placement);
            if (box == null) {
                continue;
            }
            java.awt.image.BufferedImage picture = figures.apply(placement.trackId());
            Graphics2D figure = (Graphics2D) g.create();
            figure.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (placement.muted()) {
                figure.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
            }
            if (placement == chosen || placement.trackId().equals(hovered)) {
                float glow = (float) box.getWidth() * 0.6f;
                float feetX = (float) box.getCenterX();
                float feetY = (float) box.getMaxY();
                figure.setPaint(new RadialGradientPaint(feetX, feetY, glow, new float[]{0f, 1f},
                        new Color[]{AudioStudioTheme.withAlpha(Color.WHITE, placement == chosen ? 90 : 50),
                                AudioStudioTheme.withAlpha(Color.WHITE, 0)}));
                figure.fill(new Ellipse2D.Float(feetX - glow, feetY - glow * 0.4f, glow * 2, glow * 0.8f));
            }
            figure.drawImage(picture, (int) Math.round(box.getX()), (int) Math.round(box.getY()),
                    (int) Math.round(box.getWidth()), (int) Math.round(box.getHeight()), null);
            figure.dispose();
        }
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
