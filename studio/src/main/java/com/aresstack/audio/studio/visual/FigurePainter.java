package com.aresstack.audio.studio.visual;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The figures of the musicians: draws each upright with a light around its outline, in the
 * colour of its track, that grows with the level. The lights are made once per picture and
 * colour and kept; one instance belongs to one view.
 */
public final class FigurePainter {
    /** How far the light reaches beyond the picture it is made of, in the picture's pixels. */
    private static final int MARGIN = 28;
    private final Map<BufferedImage, Map<Integer, BufferedImage>> glows =
            new WeakHashMap<BufferedImage, Map<Integer, BufferedImage>>();

    /**
     * Draws a figure into its box.
     *
     * @param sounding how much its track sounds, 0 to 1: the light is brighter and wider with it
     * @param wider    how much wider the light is besides (the share of the room on the stage page)
     * @param bright   more light for a figure that is chosen
     * @param faint    a muted track: no light, the figure only faintly
     */
    public void paint(Graphics2D g, BufferedImage picture, Rectangle2D box, Color color, float sounding,
                      double wider, boolean bright, boolean faint) {
        Graphics2D figure = (Graphics2D) g.create();
        figure.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (!faint) {
            // The light around the outline: faint at rest, bright and wider while the track sounds.
            double scale = box.getHeight() / picture.getHeight();
            double grow = 1 + 0.22 * sounding + wider;
            double width = (picture.getWidth() + 2 * MARGIN) * scale * grow;
            double height = (picture.getHeight() + 2 * MARGIN) * scale * grow;
            Graphics2D light = (Graphics2D) figure.create();
            light.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    Math.min(1f, 0.3f + 0.7f * sounding + (bright ? 0.2f : 0f))));
            BufferedImage outline = glow(picture, bright ? color.brighter() : color);
            int left = (int) Math.round(box.getCenterX() - width / 2);
            int top = (int) Math.round(box.getCenterY() - height / 2);
            light.drawImage(outline, left, top, (int) Math.round(width), (int) Math.round(height), null);
            if (sounding > 0.05f) {
                light.drawImage(outline, left, top, (int) Math.round(width), (int) Math.round(height), null);
            }
            light.dispose();
        } else {
            figure.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
        }
        figure.drawImage(picture, (int) Math.round(box.getX()), (int) Math.round(box.getY()),
                (int) Math.round(box.getWidth()), (int) Math.round(box.getHeight()), null);
        figure.dispose();
    }

    /** The outline of a picture as a soft light in a colour, larger than the picture by {@link #MARGIN} all around. */
    private BufferedImage glow(BufferedImage picture, Color color) {
        Map<Integer, BufferedImage> byColour = glows.computeIfAbsent(picture,
                made -> new HashMap<Integer, BufferedImage>());
        return byColour.computeIfAbsent(color.getRGB(), rgb -> {
            int width = picture.getWidth() + 2 * MARGIN;
            int height = picture.getHeight() + 2 * MARGIN;
            BufferedImage shape = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = shape.createGraphics();
            g.drawImage(picture, MARGIN, MARGIN, null);
            g.setComposite(AlphaComposite.SrcIn); // the shape of the figure, all in the colour
            g.setColor(color);
            g.fillRect(0, 0, width, height);
            g.dispose();
            // Blurred three times with a small box: close to a soft bell, and done once per figure.
            float[] box = new float[81];
            java.util.Arrays.fill(box, 1f / 81);
            ConvolveOp blur = new ConvolveOp(new Kernel(9, 9, box), ConvolveOp.EDGE_ZERO_FILL, null);
            BufferedImage soft = shape;
            for (int pass = 0; pass < 3; pass++) {
                soft = blur.filter(soft, null);
            }
            return soft;
        });
    }
}
