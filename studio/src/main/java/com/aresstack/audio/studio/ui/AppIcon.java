package com.aresstack.audio.studio.ui;

import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/**
 * The app icons the user can choose from. Each one lives in its own resource folder in several sizes,
 * so the window, the task bar and the Alt-Tab switcher each pick the size that fits.
 */
public enum AppIcon {
    AS("as", "AS"),
    KLAVIER("klavier", "Klavier"),
    REGLER("regler", "Regler"),
    DIRIGENT("dirigent", "Dirigent");

    /** The icon a fresh installation shows; changing the standard is this one line. */
    public static final AppIcon DEFAULT = DIRIGENT;

    /** The key under which {@code studio.properties} remembers the chosen icon. */
    public static final String SETTING = "appIcon";

    /** The sizes every icon folder holds, smallest first. */
    static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private final String key;
    private final String label;
    private List<Image> images;

    AppIcon(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** The icon saved under {@code key}, or the standard one when nothing or something unknown is saved. */
    public static AppIcon fromKey(String key) {
        if (key != null) {
            String wanted = key.trim().toLowerCase(Locale.ROOT);
            for (AppIcon icon : values()) {
                if (icon.key.equals(wanted)) {
                    return icon;
                }
            }
        }
        return DEFAULT;
    }

    /** All sizes of this icon, smallest first, loaded once. */
    public synchronized List<Image> images() {
        if (images == null) {
            List<Image> loaded = new ArrayList<>();
            for (int size : SIZES) {
                loaded.add(load(size));
            }
            images = Collections.unmodifiableList(loaded);
        }
        return images;
    }

    /** The icon at the given edge length, for previews such as the choice in the settings. */
    public ImageIcon preview(int size) {
        Image best = images().get(images().size() - 1);
        for (int index = 0; index < SIZES.length; index++) {
            if (SIZES[index] >= size) {
                best = images().get(index);
                break;
            }
        }
        return new ImageIcon(best.getWidth(null) == size ? best
                : best.getScaledInstance(size, size, Image.SCALE_SMOOTH));
    }

    /** Shows this icon on every open window and, where the system has one, on the task bar entry. */
    public void applyToAllWindows() {
        List<Image> all = images();
        for (Window window : Window.getWindows()) {
            window.setIconImages(all);
        }
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(all.get(all.size() - 1));
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Windows and Linux take the icon from the window itself
        }
    }

    private BufferedImage load(int size) {
        String path = "/com/aresstack/audio/studio/icons/" + key + "/" + size + ".png";
        try (InputStream input = AppIcon.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("App-Icon fehlt: " + path);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("App-Icon ist kein Bild: " + path);
            }
            return image;
        } catch (IOException failed) {
            throw new UncheckedIOException("App-Icon nicht lesbar: " + path, failed);
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
