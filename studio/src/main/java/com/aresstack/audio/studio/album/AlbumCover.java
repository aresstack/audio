package com.aresstack.audio.studio.album;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The cover of an album: a picture kept in the folder of the album as {@code cover.png}, and as
 * {@code cover.svg} if the AI drew it. An export lays it beside the titles as {@code cover.png},
 * where players such as VLC look for it. An album without a cover gets a placeholder made here,
 * which never fails: an export must never fail because of its cover.
 */
public final class AlbumCover {
    public static final String PNG = "cover.png";
    public static final String SVG = "cover.svg";
    /** The width and height of a cover in pixels. */
    public static final int SIZE = 1000;
    /** One see-through pixel as PNG: the cover of last resort. */
    private static final byte[] ONE_PIXEL = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    private AlbumCover() {
    }

    /** Where the cover of an album lies. */
    public static Path file(Path albumFolder) {
        return albumFolder.resolve(PNG);
    }

    /** Whether the album has a cover of its own. */
    public static boolean exists(Path albumFolder) {
        return albumFolder != null && Files.isRegularFile(file(albumFolder));
    }

    /** The cover of the album; {@code null} if it has none or it cannot be read. */
    public static BufferedImage read(Path albumFolder) {
        if (!exists(albumFolder)) {
            return null;
        }
        try {
            return ImageIO.read(file(albumFolder).toFile());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The cover of the album as the bytes of its PNG file; {@code null} if it has none. */
    public static byte[] bytes(Path albumFolder) throws IOException {
        return exists(albumFolder) ? Files.readAllBytes(file(albumFolder)) : null;
    }

    /**
     * Keeps a cover with its album.
     *
     * @param svg the drawing it was made from, or {@code null} for a picture from elsewhere: an
     *            older drawing is then removed, because it is no longer what the cover shows
     */
    public static void save(Path albumFolder, String svg, BufferedImage image) throws IOException {
        Files.createDirectories(albumFolder);
        Path fresh = albumFolder.resolve(PNG + ".part");
        try {
            Files.write(fresh, png(image));
            Files.move(fresh, file(albumFolder), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(fresh);
        }
        if (svg == null) {
            Files.deleteIfExists(albumFolder.resolve(SVG));
        } else {
            Files.write(albumFolder.resolve(SVG), svg.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A picture as the bytes of a PNG file. */
    public static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("Das Bild ließ sich nicht als PNG schreiben.");
        }
        return bytes.toByteArray();
    }

    /**
     * The cover of an album that has none, as a PNG file: its title on a quiet dark ground. Never
     * fails: if the title cannot be drawn the picture is empty, and if no picture can be made at
     * all it is a single see-through pixel.
     */
    public static byte[] placeholder(String title) {
        try {
            return png(placeholderImage(title));
        } catch (IOException | RuntimeException | Error undrawn) {
            try {
                return png(new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB));
            } catch (IOException | RuntimeException | Error none) {
                return ONE_PIXEL.clone();
            }
        }
    }

    /** The placeholder as a picture, see {@link #placeholder}. */
    public static BufferedImage placeholderImage(String title) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Every album its own colour, the same each time: taken from its title.
            String name = title == null || title.isBlank() ? "Album" : title.strip();
            float hue = Math.floorMod(name.hashCode(), 360) / 360f;
            g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, 0.55f, 0.34f), SIZE, SIZE,
                    Color.getHSBColor((hue + 0.12f) % 1f, 0.65f, 0.12f)));
            g.fillRect(0, 0, SIZE, SIZE);
            // A record, hinted at by its rings.
            g.setColor(new Color(255, 255, 255, 26));
            for (int ring = 0; ring < 5; ring++) {
                int radius = 250 + ring * 60;
                g.setStroke(new java.awt.BasicStroke(ring == 0 ? 6 : 2));
                g.drawOval(SIZE / 2 - radius, SIZE / 2 - radius, 2 * radius, 2 * radius);
            }
            g.setColor(new Color(0xF4F1F8));
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 92);
            List<String> lines = wrap(name, g.getFontMetrics(font), SIZE - 180);
            while (lines.size() > 4 && font.getSize() > 44) {
                font = font.deriveFont(font.getSize() - 12f);
                lines = wrap(name, g.getFontMetrics(font), SIZE - 180);
            }
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            int y = (SIZE - lines.size() * metrics.getHeight()) / 2 + metrics.getAscent();
            for (String line : lines) {
                g.drawString(line, (SIZE - metrics.stringWidth(line)) / 2, y);
                y += metrics.getHeight();
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static List<String> wrap(String text, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && metrics.stringWidth(line + " " + word) > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(line.length() == 0 ? "" : " ").append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
