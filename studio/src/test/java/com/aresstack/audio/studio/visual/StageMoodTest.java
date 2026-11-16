package com.aresstack.audio.studio.visual;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

/** The surprise of the stage: fire for rock, lasers for techno, never both, and only in the wildest moments. */
public final class StageMoodTest {
    private static final VisualSettings SETTINGS = VisualSettings.DEFAULTS;

    @Test
    public void onlyRockAndTechnoAreNamedByTheWordsAboutAPiece() {
        assertEquals(StageMood.Mood.FIRE, StageMood.fromWords("Ein rockiges Stück mit harten Gitarren, Hard Rock"));
        assertEquals(StageMood.Mood.LASER, StageMood.fromWords("Treibender Techno, 138 BPM"));
        assertNull(StageMood.fromWords("Ruhiger Jazz für die späte Stunde"));
        assertNull(StageMood.fromWords("Country-Ballade"));
        assertNull(StageMood.fromWords("Menuett für Streichquartett"));
        assertNull("a baroque piece is not rock", StageMood.fromWords("Barock, Cembalo"));
        assertNull(StageMood.fromWords(""));
    }

    @Test
    public void aPieceThatSaysNothingGetsNothingHoweverItSounds() {
        assertEquals(StageMood.Mood.OFF, heard(140, 0.9f, SETTINGS));
        assertEquals(StageMood.Mood.OFF, heard(96, 0.9f, SETTINGS));
        assertEquals(StageMood.Mood.OFF, heard(70, 0.2f, SETTINGS));
    }

    @Test
    public void aRecognizerIsFollowedOnlyWhenItIsSure() {
        GenreRecognizer sure = (sound, settings) -> new GenreRecognizer.Heard(GenreRecognizer.Genre.ROCK, 0.9, 100, null, "Rock");
        GenreRecognizer unsure = (sound, settings) -> new GenreRecognizer.Heard(GenreRecognizer.Genre.ROCK, 0.5, 100, null, "Rock");
        GenreRecognizer jazz = (sound, settings) -> new GenreRecognizer.Heard(GenreRecognizer.Genre.JAZZ, 1.0, 100, null, "Jazz");
        assertEquals(StageMood.Mood.FIRE, new StageMood(sure).at(beating(0, 100, 0.8f), SETTINGS));
        assertEquals(StageMood.Mood.OFF, new StageMood(unsure).at(beating(0, 100, 0.8f), SETTINGS));
        assertEquals(StageMood.Mood.OFF, new StageMood(jazz).at(beating(0, 100, 0.8f), SETTINGS));
        assertEquals(GenreRecognizer.Genre.UNKNOWN, new SimpleRecognizer().hear(beating(0, 140, 0.8f), SETTINGS).genre());
    }

    @Test
    public void aFixedSettingIsKept() {
        assertEquals(StageMood.Mood.LASER, heard(90, 0.2f, SETTINGS.withAtmosphere("laser")));
        assertEquals(StageMood.Mood.OFF, heard(140, 0.8f, SETTINGS.withAtmosphere("off")));
        assertEquals("auto", SETTINGS.withAtmosphere("nonsense").atmosphere());
        assertEquals("auto", SETTINGS.withAtmosphere("spots").atmosphere());
        assertEquals("fire", StageMood.next("auto"));
        assertEquals("auto", StageMood.next("off"));
    }

    @Test
    public void aRockPieceGetsNoFireWhileItPlaysAsLoudAsEver() {
        StageAtmosphere stage = new StageAtmosphere();
        float most = 0;
        for (int frame = 0; frame < 1800; frame++) { // a minute, heavy and even
            stage.listen(styled("Rock", frame, 110, 0.9f, 0.4f), SETTINGS);
            most = Math.max(most, stage.warmth());
        }
        assertEquals(StageMood.Mood.FIRE, stage.shown());
        assertEquals("no fire without a wild moment", 0f, most, 0f);
    }

    @Test
    public void fireComesWhenARockPieceBreaksLooseAndThenRests() {
        StageAtmosphere stage = new StageAtmosphere();
        for (int frame = 0; frame < 900; frame++) {
            stage.listen(styled("Rock", frame, 110, 0.5f, 0.25f), SETTINGS);
        }
        assertEquals(0f, stage.warmth(), 0f);
        int volleys = 0;
        boolean burning = false;
        for (int frame = 900; frame < 1200; frame++) { // ten seconds, twice as loud
            stage.listen(styled("Rock", frame, 110, 0.95f, 0.5f), SETTINGS);
            assertFalse("never lasers with the fire", stage.lasers());
            if (stage.warmth() > 0 && !burning) {
                volleys++;
            }
            burning = stage.warmth() > 0;
        }
        assertTrue("fire in the wild moment: " + volleys, volleys >= 1);
        assertTrue("rare, not on every beat: " + volleys, volleys <= 3);
    }

    @Test
    public void lasersBelongToTechnoAndFireStaysAway() {
        StageAtmosphere stage = new StageAtmosphere();
        for (int frame = 0; frame < 900; frame++) {
            stage.listen(styled("Techno", frame, 138, 0.5f, 0.25f), SETTINGS);
            assertFalse("no lasers while it plays as ever", stage.lasers());
        }
        boolean lasers = false;
        for (int frame = 900; frame < 1200; frame++) {
            stage.listen(styled("Techno", frame, 138, 0.95f, 0.5f), SETTINGS);
            lasers |= stage.lasers();
            assertEquals("never fire with the lasers", 0f, stage.warmth(), 0f);
        }
        assertTrue(lasers);
    }

    @Test
    public void otherMusicGetsNeitherHoweverWildItIs() {
        StageAtmosphere stage = new StageAtmosphere();
        for (int frame = 0; frame < 1200; frame++) {
            float loud = frame < 900 ? 0.25f : 0.5f;
            stage.listen(styled("Jazz-Trio", frame, 138, 0.95f, loud), SETTINGS);
            assertFalse(stage.lasers());
            assertEquals(0f, stage.warmth(), 0f);
        }
    }

    @Test
    public void setByHandTheFireComesOnEveryHeavyBeat() {
        StageAtmosphere stage = new StageAtmosphere();
        float most = 0;
        for (int frame = 0; frame < 90; frame++) {
            stage.listen(styled("", frame, 110, 0.9f, 0.4f), SETTINGS.withAtmosphere("fire"));
            most = Math.max(most, stage.warmth());
        }
        assertTrue(most > 0);
    }

    @Test
    public void fireGoesOutWhenTheMoodIsAnother() {
        StageAtmosphere stage = new StageAtmosphere();
        for (int frame = 0; frame < 40; frame++) {
            stage.listen(styled("", frame, 110, 0.9f, 0.4f), SETTINGS.withAtmosphere("fire"));
        }
        stage.listen(styled("", 17, 110, 0.9f, 0.4f), SETTINGS.withAtmosphere("fire")); // on a beat
        stage.listen(styled("", 41, 110, 0.9f, 0.4f), SETTINGS.withAtmosphere("laser"));
        assertTrue(stage.lasers());
        assertEquals(0f, stage.warmth(), 0f);
    }

    @Test
    public void theStageDrawsFireAndLasersInItsSpace() {
        for (String choice : new String[]{"fire", "laser"}) {
            StageLightEffect stage = new StageLightEffect();
            VisualSettings settings = SETTINGS.withAtmosphere(choice);
            BufferedImage image = new BufferedImage(480, 300, BufferedImage.TYPE_INT_RGB);
            for (int frame = 0; frame < 90; frame++) {
                Graphics2D g = image.createGraphics();
                stage.paint(g, 480, 300, beating(frame, 120, 0.9f), palette(), settings);
                g.dispose();
            }
            assertEquals(choice, stage.atmosphere().key());
        }
    }

    private static SoundPicture styled(String style, int frame, double bpm, float bass, float loud) {
        SoundPicture picture = beating(frame, bpm, bass);
        picture.style = style;
        picture.rmsLeft = loud;
        picture.rmsRight = loud;
        return picture;
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
