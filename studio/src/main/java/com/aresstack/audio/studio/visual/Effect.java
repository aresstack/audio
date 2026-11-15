package com.aresstack.audio.studio.visual;

import java.awt.Graphics2D;

/**
 * One way of drawing the sound. An effect may keep state from picture to picture (falling peaks,
 * particles); one instance belongs to one view. It draws onto a picture that keeps what was drawn
 * before: an effect that wants a clean picture fills it first, one that wants trails fades it.
 */
public interface Effect {
    /** The key under which the choice is kept in the settings. */
    String key();

    /** The short name on its key in the head of the visualizer. */
    String label();

    /** What it shows, as the tooltip of its key. */
    String tip();

    /** Draws one picture of {@code width} × {@code height} pixels. */
    void paint(Graphics2D g, int width, int height, SoundPicture sound, VisualPalette palette, VisualSettings settings);

    /** Fades what was drawn before, leaving {@code keep} of it (the trail of the moving effects). */
    static void fade(Graphics2D g, int width, int height, java.awt.Color ground, double keep) {
        int alpha = (int) Math.round(255 * (1 - Math.max(0, Math.min(1, keep))));
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.setColor(new java.awt.Color(ground.getRed(), ground.getGreen(), ground.getBlue(), Math.max(1, alpha)));
        g.fillRect(0, 0, width, height);
    }

    /** Fills the picture with its ground. */
    static void clear(Graphics2D g, int width, int height, java.awt.Color ground) {
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(ground);
        g.fillRect(0, 0, width, height);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
    }
}
