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
        paint(g, picture, box, color, sounding, wider, bright, faint, 0f, 1f);
    }

    /**
     * @param feather how softly the figure fades out at its edges, 0 (hard) to 1: the light behind it
     *                shines through there. The feet stay solid, so that the figure stands.
     * @param opacity how solid the whole figure is, 0 to 1: below 1 it stands there like a hologram
     */
    public void paint(Graphics2D g, BufferedImage picture, Rectangle2D box, Color color, float sounding,
                      double wider, boolean bright, boolean faint, float feather, float opacity) {
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
            figure.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, opacity))));
        } else {
            figure.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f * Math.max(0f, Math.min(1f, opacity))));
        }
        figure.drawImage(feather > 0.02f ? feathered(picture, feather) : picture, (int) Math.round(box.getX()), (int) Math.round(box.getY()),
                (int) Math.round(box.getWidth()), (int) Math.round(box.getHeight()), null);
        figure.dispose();
    }

    private final Map<BufferedImage, Map<Integer, BufferedImage>> soft =
            new WeakHashMap<BufferedImage, Map<Integer, BufferedImage>>();

    /**
     * The picture fading out towards its outline: how solid a point is follows how much of the
     * figure lies around it, so the middle stays solid and the rim turns to glass. The lowest part,
     * where the feet are, stays as it is.
     */
    private BufferedImage feathered(BufferedImage picture, float feather) {
        int step = Math.round(Math.max(0f, Math.min(1f, feather)) * 20);
        Map<Integer, BufferedImage> bySoftness = soft.computeIfAbsent(picture, made -> new HashMap<Integer, BufferedImage>());
        return bySoftness.computeIfAbsent(step, key -> {
            int width = picture.getWidth();
            int height = picture.getHeight();
            int[] pixels = picture.getRGB(0, 0, width, height, null, 0, width);
            // How wide the rim is: up to a tenth of the width of the picture.
            int reach = Math.max(1, Math.round(width * 0.1f * step / 20f));
            float[] around = new float[width * height];
            float[] line = new float[width * height];
            for (int i = 0; i < pixels.length; i++) {
                around[i] = (pixels[i] >>> 24) / 255f;
            }
            for (int pass = 0; pass < 2; pass++) { // twice a box: a soft slope
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float sum = 0;
                        for (int d = -reach; d <= reach; d++) {
                            int at = x + d;
                            sum += at < 0 || at >= width ? 0 : around[y * width + at];
                        }
                        line[y * width + x] = sum / (2 * reach + 1);
                    }
                }
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float sum = 0;
                        for (int d = -reach; d <= reach; d++) {
                            int at = y + d;
                            // Below the picture counts as figure: the bottom edge is the floor, not a rim.
                            sum += at < 0 ? 0 : at >= height ? around[(height - 1) * width + x] : line[at * width + x];
                        }
                        around[y * width + x] = sum / (2 * reach + 1);
                    }
                }
            }
            int lowest = 0;
            for (int i = pixels.length - 1; i >= 0 && lowest == 0; i--) {
                if ((pixels[i] >>> 24) > 16) {
                    lowest = i / width;
                }
            }
            float feet = lowest - height * 0.12f; // from here down the figure stays solid
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                float keep = Math.max(0f, Math.min(1f, (y - feet) / (height * 0.06f)));
                for (int x = 0; x < width; x++) {
                    int pixel = pixels[y * width + x];
                    float inside = Math.max(0f, Math.min(1f, (around[y * width + x] - 0.35f) / 0.6f));
                    float solid = inside * inside * (3 - 2 * inside);
                    float alpha = (pixel >>> 24) * (keep + (1 - keep) * (0.18f + 0.82f * solid));
                    out.setRGB(x, y, (Math.round(alpha) << 24) | (pixel & 0xFFFFFF));
                }
            }
            return out;
        });
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
