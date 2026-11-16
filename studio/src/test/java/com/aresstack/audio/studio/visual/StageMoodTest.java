package com.aresstack.audio.studio.visual;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

/** The atmosphere of the stage: named by the words about the piece, else heard in its beat and low end. */
public final class StageMoodTest {
    private static final VisualSettings SETTINGS = VisualSettings.DEFAULTS;

    @Test
    public void theWordsAboutAPieceNameItsAtmosphere() {
        assertEquals(StageMood.Mood.FIRE, StageMood.fromWords("Ein rockiges Stück mit harten Gitarren, Hard Rock"));
        assertEquals(StageMood.Mood.LASER, StageMood.fromWords("Treibender Techno, 138 BPM"));
        assertEquals(StageMood.Mood.CLUB, StageMood.fromWords("Ruhiger Jazz für die späte Stunde"));
        assertEquals(StageMood.Mood.LANTERN, StageMood.fromWords("Country-Ballade"));
        assertEquals(StageMood.Mood.SPOTS, StageMood.fromWords("Klassische Popmusik der Achtziger"));
        assertEquals(StageMood.Mood.CANDLE, StageMood.fromWords("Menuett für Streichquartett"));
        assertEquals("a baroque piece is not rock", StageMood.Mood.CANDLE, StageMood.fromWords("Barock, Cembalo"));
        assertEquals("an instrument gives the style away", StageMood.Mood.LANTERN, StageMood.fromWords("Lied\nBanjo\nBass"));
        assertNull(StageMood.fromWords("Ein Stück über den Morgen im Viertel"));
        assertNull(StageMood.fromWords(""));
    }

    @Test
    public void namedStyleWinsOverWhatIsHeard() {
        StageMood mood = new StageMood();
        StageMood.Mood shown = null;
        for (int frame = 0; frame < 300; frame++) {
            SoundPicture picture = beating(frame, 140, 0.8f); // sounds like techno
            picture.style = "Jazz-Trio";
            shown = mood.at(picture, SETTINGS);
        }
        assertEquals(StageMood.Mood.CLUB, shown);
    }

    @Test
    public void fastBeatsCallForLasers() {
        assertEquals(StageMood.Mood.LASER, heard(140, 0.6f, SETTINGS));
    }

    @Test
    public void aHeavyLowEndWithASlowerBeatCallsForFire() {
        assertEquals(StageMood.Mood.FIRE, heard(96, 0.8f, SETTINGS));
    }

    @Test
    public void quietMusicGetsCandleLight() {
        assertEquals(StageMood.Mood.CANDLE, heard(70, 0.2f, SETTINGS));
    }

    @Test
    public void aFixedSettingIsKept() {
        VisualSettings fixed = SETTINGS.withAtmosphere("spots");
        assertEquals(StageMood.Mood.SPOTS, heard(140, 0.8f, fixed));
        assertEquals(StageMood.Mood.OFF, heard(140, 0.8f, SETTINGS.withAtmosphere("off")));
        assertEquals("auto", SETTINGS.withAtmosphere("nonsense").atmosphere());
        assertEquals("fire", StageMood.next("auto"));
        assertEquals("auto", StageMood.next("off"));
    }

    @Test
    public void everyAtmosphereDrawsAroundTheLights() {
        for (String choice : StageMood.CHOICES) {
            if (choice.equals("auto") || choice.equals("off")) {
                continue;
            }
            StageLightEffect stage = new StageLightEffect();
            VisualSettings settings = SETTINGS.withAtmosphere(choice);
            BufferedImage image = new BufferedImage(480, 300, BufferedImage.TYPE_INT_RGB);
            for (int frame = 0; frame < 90; frame++) {
                Graphics2D g = image.createGraphics();
                stage.paint(g, 480, 300, beating(frame, 120, 0.8f), palette(), settings);
                g.dispose();
            }
            assertEquals(choice, stage.atmosphere().key());
            int lit = 0;
            for (int y = 0; y < 300; y++) {
                for (int x = 0; x < 480; x++) {
                    int rgb = image.getRGB(x, y);
                    if (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255) > 90) {
                        lit++;
                    }
                }
            }
            assertTrue(choice + " lights the stage: " + lit, lit > 300);
        }
    }

    private static StageMood.Mood heard(double bpm, float bass, VisualSettings settings) {
        StageMood mood = new StageMood();
        StageMood.Mood shown = null;
        for (int frame = 0; frame < 600; frame++) { // twenty seconds
            shown = mood.at(beating(frame, bpm, bass), settings);
        }
        return shown;
    }

    /** A picture of music with a steady beat, at thirty pictures per second. */
    static SoundPicture beating(int frame, double bpm, float bass) {
        SoundPicture picture = new SoundPicture(256, 8);
        picture.seconds = frame / 30.0;
        picture.sounding = true;
        picture.bass = bass;
        picture.rmsLeft = bass * 0.4f;
        picture.rmsRight = bass * 0.4f;
        double beats = picture.seconds * bpm / 60;
        picture.beats = (long) beats;
        picture.beat = (float) Math.pow(0.86, (beats - Math.floor(beats)) * 60 / bpm * 30);
        return picture;
    }

    private static VisualPalette palette() {
        Color ground = new Color(0x0A0B0F);
        return new VisualPalette(ground, Color.WHITE, Color.GRAY, Color.PINK, Color.GREEN, Color.ORANGE, Color.RED,
                Color.CYAN, index -> Color.getHSBColor(index * 0.1f, 1f, 1f));
    }
}
