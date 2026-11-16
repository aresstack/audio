package com.aresstack.audio.studio.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * The place of the cover on the page of an album: the picture, whole and in its proportions, on a
 * dark ground; a word instead while the album has none or the AI is drawing one.
 */
final class CoverBox extends JComponent {
    /** The width and height of the place, in pixels. */
    static final int SIDE = 148;
    private BufferedImage picture;
    private String word = "";

    CoverBox() {
        setPreferredSize(new Dimension(SIDE, SIDE));
        setMinimumSize(new Dimension(SIDE, SIDE));
        setOpaque(false);
    }

    /** Shows a cover; {@code null} if the album has none. */
    void show(BufferedImage cover) {
        picture = cover;
        repaint();
    }

    /** A word over the place, e.g. while the AI draws; empty for none. */
    void say(String text) {
        word = text == null ? "" : text;
        repaint();
    }

    /** The picture shown, or {@code null}. */
    BufferedImage shown() {
        return picture;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            int width = getWidth();
            int height = getHeight();
            RoundRectangle2D frame = new RoundRectangle2D.Double(0.5, 0.5, width - 1, height - 1, 10, 10);
            g.setColor(AudioStudioTheme.WELL);
            g.fill(frame);
            if (picture != null) {
                double scale = Math.min((width - 2.0) / picture.getWidth(), (height - 2.0) / picture.getHeight());
                int w = Math.max(1, (int) Math.round(picture.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(picture.getHeight() * scale));
                java.awt.Shape before = g.getClip();
                g.clip(frame);
                g.drawImage(picture, (width - w) / 2, (height - h) / 2, w, h, null);
                g.setClip(before);
            }
            String shown = !word.isEmpty() ? word : picture == null ? "kein Cover" : "";
            if (!shown.isEmpty()) {
                g.setFont(getFont().deriveFont(12f));
                FontMetrics metrics = g.getFontMetrics();
                int textWidth = metrics.stringWidth(shown);
                int y = height / 2 + metrics.getAscent() / 2 - 1;
                if (picture != null) {
                    // On a picture the word needs a ground of its own to be read.
                    g.setColor(new Color(0, 0, 0, 170));
                    g.fillRoundRect((width - textWidth) / 2 - 8, y - metrics.getAscent() - 4, textWidth + 16,
                            metrics.getHeight() + 8, 8, 8);
                }
                g.setColor(picture != null ? AudioStudioTheme.TEXT : AudioStudioTheme.TEXT_FAINT);
                g.drawString(shown, (width - textWidth) / 2, y);
            }
            g.setColor(AudioStudioTheme.STROKE);
            g.draw(frame);
        } finally {
            g.dispose();
        }
    }
}
