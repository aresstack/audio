package com.aresstack.audio.studio.visual;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;

/** Lettering shared by the effects. */
final class Labels {
    private Labels() {
    }

    /** Says in the middle of the picture that nothing plays. */
    static void quiet(Graphics2D g, int width, int height, VisualPalette palette) {
        centred(g, width, height, "Es spielt gerade nichts.", palette);
    }

    static void centred(Graphics2D g, int width, int height, String text, VisualPalette palette) {
        Font was = g.getFont();
        g.setFont(was.deriveFont(Font.PLAIN, Math.max(12f, Math.min(22f, height / 18f))));
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(palette.faint());
        g.drawString(text, (width - metrics.stringWidth(text)) / 2f, height / 2f);
        g.setFont(was);
    }
}
