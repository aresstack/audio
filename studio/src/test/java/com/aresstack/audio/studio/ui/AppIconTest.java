package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.Test;

public class AppIconTest {

    @Test
    public void everyIconHasEverySizeWithTransparentCorners() {
        for (AppIcon icon : AppIcon.values()) {
            List<Image> images = icon.images();
            assertEquals(icon.name(), AppIcon.SIZES.length, images.size());
            for (int index = 0; index < AppIcon.SIZES.length; index++) {
                BufferedImage image = (BufferedImage) images.get(index);
                int size = AppIcon.SIZES[index];
                assertEquals(icon.name() + " width " + size, size, image.getWidth());
                assertEquals(icon.name() + " height " + size, size, image.getHeight());
                assertTrue(icon.name() + " " + size + " has alpha", image.getColorModel().hasAlpha());
                int corner = image.getRGB(0, 0) >>> 24;
                int middle = image.getRGB(size / 2, size / 2) >>> 24;
                assertTrue(icon.name() + " " + size + " corner is see-through", corner < 64);
                assertEquals(icon.name() + " " + size + " middle is opaque", 255, middle);
            }
        }
    }

    @Test
    public void dirigentIsTheStandard() {
        assertSame(AppIcon.DIRIGENT, AppIcon.DEFAULT);
    }

    @Test
    public void savedKeysAreReadBackAndUnknownOnesFallBackToTheStandard() {
        for (AppIcon icon : AppIcon.values()) {
            assertSame(icon, AppIcon.fromKey(icon.key()));
        }
        assertSame(AppIcon.REGLER, AppIcon.fromKey(" Regler "));
        assertSame(AppIcon.DEFAULT, AppIcon.fromKey(null));
        assertSame(AppIcon.DEFAULT, AppIcon.fromKey(""));
        assertSame(AppIcon.DEFAULT, AppIcon.fromKey("gibt-es-nicht"));
    }

    @Test
    public void previewHasTheAskedSize() {
        assertEquals(48, AppIcon.KLAVIER.preview(48).getIconWidth());
        assertEquals(30, AppIcon.DIRIGENT.preview(30).getIconWidth());
    }
}
