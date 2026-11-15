package com.aresstack.audio.studio.visual;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class EffectsTest {
    private static final Color GROUND = new Color(0x0A0B0F);
    private static final VisualPalette PALETTE = new VisualPalette(GROUND, Color.WHITE, Color.GRAY, Color.PINK,
            Color.GREEN, Color.ORANGE, Color.RED, Color.CYAN, index -> Color.getHSBColor(index * 0.1f, 1f, 1f));

    @Test
    public void everyEffectHasItsOwnKeyAndDrawsSound() {
        Set<String> keys = new HashSet<>();
        for (Effect effect : Effects.all()) {
            assertTrue("one key per effect: " + effect.key(), keys.add(effect.key()));
            BufferedImage image = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D ground = image.createGraphics();
            Effect.clear(ground, 320, 200, GROUND);
            ground.dispose();
            SoundPicture picture = loud();
            for (int frame = 0; frame < 5; frame++) {
                picture.seconds = frame / 30.0;
                Graphics2D g = image.createGraphics();
                effect.paint(g, image.getWidth(), image.getHeight(), picture, PALETTE, VisualSettings.DEFAULTS);
                g.dispose();
            }
            assertTrue(effect.key() + " draws something", litPixels(image) > 50);
        }
        assertTrue(!keys.contains("score"));
    }

    @Test
    public void effectsDrawInSilenceToo() {
        for (Effect effect : Effects.all()) {
            BufferedImage image = new BufferedImage(160, 100, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            effect.paint(g, 160, 100, new SoundPicture(256, 8), PALETTE, VisualSettings.DEFAULTS);
            g.dispose();
        }
    }

    @Test
    public void aLoudTrackShinesBrighterOnTheStage() {
        Effect stage = Effects.find(Effects.all(), "stage");
        assertEquals("stage", stage.key());
        SoundPicture picture = loud();
        picture.lights = List.of(new TrackLight("leise", 0, -0.6, 0.3, 0, 0.05f),
                new TrackLight("laut", 1, 0.6, 0.3, 0, 1f));
        BufferedImage image = new BufferedImage(400, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        Effect.clear(g, 400, 240, GROUND); // as the visualizer starts every picture: on its ground
        stage.paint(g, 400, 240, picture, PALETTE, VisualSettings.DEFAULTS);
        g.dispose();
        int quiet = litPixels(image.getSubimage(0, 0, 200, 240));
        int loud = litPixels(image.getSubimage(200, 0, 200, 240));
        assertTrue("loud " + loud + " > quiet " + quiet, loud > quiet);
    }

    @Test
    public void anUnknownKeyFindsTheFirstEffect() {
        List<Effect> all = Effects.all();
        assertEquals(all.get(0).key(), Effects.find(all, "gibt-es-nicht").key());
        assertNotEquals(all.get(0).key(), Effects.find(all, "tunnel").key());
    }

    private static SoundPicture loud() {
        SoundPicture picture = new SoundPicture(1024, 32);
        for (int i = 0; i < 1024; i++) {
            picture.left[i] = (float) Math.sin(i * 0.05);
            picture.right[i] = (float) Math.cos(i * 0.07);
        }
        for (int b = 0; b < 32; b++) {
            picture.bands[b] = 0.3f + 0.6f * (b % 5) / 4f;
            picture.rawBands[b] = picture.bands[b];
        }
        picture.peakLeft = 0.9f;
        picture.peakRight = 0.8f;
        picture.rmsLeft = 0.6f;
        picture.rmsRight = 0.5f;
        picture.bass = 0.8f;
        picture.middle = 0.5f;
        picture.treble = 0.4f;
        picture.beat = 1f;
        picture.sounding = true;
        return picture;
    }

    private static int litPixels(BufferedImage image) {
        int lit = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != (GROUND.getRGB() & 0xFFFFFF)) {
                    lit++;
                }
            }
        }
        return lit;
    }
}
