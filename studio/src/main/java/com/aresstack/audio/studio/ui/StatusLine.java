package com.aresstack.audio.studio.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JLabel;
import javax.swing.Timer;

/**
 * The status line at the foot of the window. While the AI writes notes or the tracks are rendered
 * a bar lies under its words, see-through so that they stay readable: it grows from the left edge
 * to the right one with the share of the work that is done. Once it is full it goes away, at once
 * or after the time the settings give it.
 */
final class StatusLine extends JLabel {
    /** How long the full bar stays by default, in seconds. */
    static final int LINGER_SECONDS = 3;
    /** The longest it may stay, in seconds. */
    static final int MAX_LINGER_SECONDS = 60;
    private static final Color BAR = new Color(AudioStudioTheme.ACCENT.getRed(), AudioStudioTheme.ACCENT.getGreen(),
            AudioStudioTheme.ACCENT.getBlue(), 64);
    private static final Color EDGE = new Color(AudioStudioTheme.ACCENT.getRed(), AudioStudioTheme.ACCENT.getGreen(),
            AudioStudioTheme.ACCENT.getBlue(), 150);
    /** The share the work has reached; below 0 there is no bar. */
    private double share = -1;
    /** The share the bar shows: it glides after the one reached. */
    private double drawn;
    private int lingerMillis = LINGER_SECONDS * 1000;
    private final Timer hide = new Timer(lingerMillis, event -> clearProgress());
    private final Timer glide = new Timer(30, event -> glide());

    StatusLine(String text) {
        super(text);
        hide.setRepeats(false);
    }

    /** How long the full bar stays before it goes away, in seconds; 0 lets it go at once. */
    void setLingerSeconds(int seconds) {
        lingerMillis = Math.max(0, Math.min(MAX_LINGER_SECONDS, seconds)) * 1000;
    }

    /** The work is this far, from 0 to 1. At 1 the bar is full and goes away after its time. */
    void progress(double reached) {
        if (Double.isNaN(reached)) {
            return;
        }
        double next = Math.max(0, Math.min(1, reached));
        if (next >= 1) {
            if (share < 0 || lingerMillis == 0) {
                clearProgress(); // nothing was shown, or the full bar is not wanted: none appears
                return;
            }
            if (share < 1) {
                hide.setInitialDelay(lingerMillis);
                hide.restart();
            }
        } else {
            hide.stop();
            if (next < drawn) {
                drawn = next; // a new piece of work begins at the left edge
            }
        }
        share = next;
        glide.start();
        repaint();
    }

    /** The work is over: a bar that is not full goes away now, a full one after its time. */
    void endProgress() {
        if (share < 1) {
            clearProgress();
        }
    }

    /** No bar. */
    void clearProgress() {
        hide.stop();
        glide.stop();
        share = -1;
        drawn = 0;
        repaint();
    }

    /** The share the bar stands for, from 0 to 1; below 0 if there is none. */
    double progress() {
        return share;
    }

    private void glide() {
        if (share < 0 || Math.abs(share - drawn) < 0.002) {
            drawn = Math.max(0, share);
            glide.stop();
        } else {
            drawn += (share - drawn) * 0.25;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (share >= 0) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = (int) Math.round(getWidth() * Math.max(0, Math.min(1, drawn)));
            if (width > 0) {
                g.setColor(BAR);
                g.fillRoundRect(0, 1, width, getHeight() - 2, 6, 6);
                g.setColor(EDGE);
                g.fillRect(Math.max(0, width - 2), 1, 2, getHeight() - 2);
            }
            g.dispose();
        }
        super.paintComponent(graphics);
    }
}
