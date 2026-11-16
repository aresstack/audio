package com.aresstack.audio.studio.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.DoubleConsumer;
import javax.swing.JComponent;

/**
 * The place in the piece under the transport keys: a thin line that stays in the background. It
 * fills with the share that has played; a click or a drag on it moves the playback there. Only
 * under the pointer it shows a knob and its colour.
 */
final class SeekLine extends JComponent {
    private static final Color TRACK = new Color(255, 255, 255, 26);
    private static final Color FILLED = new Color(255, 255, 255, 70);
    private static final Color FILLED_HOT = new Color(AudioStudioTheme.ACCENT.getRed(),
            AudioStudioTheme.ACCENT.getGreen(), AudioStudioTheme.ACCENT.getBlue(), 200);
    /** The share played, from 0 to 1; below 0 when nothing can be played. */
    private double share = -1;
    /** The share under the pointer while it is dragged; below 0 otherwise. */
    private double dragged = -1;
    private boolean hot;
    /** How long the piece is, in seconds; not positive when nothing can be played. */
    private double length;
    /** How far one notch of the mouse wheel moves the playback, in seconds. */
    static final int WHEEL_SECONDS = 5;
    private double wheelSeconds = WHEEL_SECONDS;

    /** Sets how far one notch of the mouse wheel moves the playback, in seconds. */
    void setWheelSeconds(double seconds) {
        wheelSeconds = Math.max(0.1, seconds);
    }
    private final DoubleConsumer seek;

    /** @param seek moves the playback to a share of the piece, from 0 to 1 */
    SeekLine(DoubleConsumer seek) {
        this.seek = seek;
        setOpaque(false);
        setPreferredSize(new Dimension(120, 12));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Position im Stück: klicken, ziehen oder mit dem Mausrad verstellen");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hot = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hot = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                mouseDragged(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (share < 0) {
                    return;
                }
                dragged = at(event.getX());
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (dragged >= 0) {
                    double to = dragged;
                    dragged = -1;
                    share = to;
                    seek.accept(to);
                    repaint();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(event -> {
            if (share < 0 || length <= 0) {
                return;
            }
            // Wheel up goes on in the piece, wheel down goes back, one step of the setting per notch.
            double to = Math.max(0, Math.min(1, share - event.getPreciseWheelRotation() * wheelSeconds / length));
            share = to;
            seek.accept(to);
            repaint();
        });
    }

    private static final int INSET = 6;

    private double at(int x) {
        int width = Math.max(1, getWidth() - 2 * INSET);
        return Math.max(0, Math.min(1, (x - INSET) / (double) width));
    }

    /** Where the playback is: the second of so many; nothing to play when the length is not positive. */
    void setTime(double second, double length) {
        this.length = length;
        double next = length <= 0 ? -1 : Math.max(0, Math.min(1, second / length));
        if (Math.abs(next - share) > 0.0005) {
            share = next;
            if (dragged < 0) {
                repaint();
            }
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth() - 2 * INSET;
        int y = getHeight() / 2;
        int thick = hot && share >= 0 ? 3 : 2;
        g.setColor(TRACK);
        g.fillRoundRect(INSET, y - thick / 2, width, thick, thick, thick);
        double shown = dragged >= 0 ? dragged : share;
        if (shown >= 0) {
            int reached = (int) Math.round(width * shown);
            g.setColor(hot ? FILLED_HOT : FILLED);
            g.fillRoundRect(INSET, y - thick / 2, reached, thick, thick, thick);
            if (hot || dragged >= 0) {
                g.fillOval(INSET + reached - 4, y - 4, 8, 8);
            }
        }
        g.dispose();
    }
}
