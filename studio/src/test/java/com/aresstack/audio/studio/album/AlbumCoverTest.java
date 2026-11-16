package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.studio.ai.FakeChatModel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The cover of an album: kept with it, drawn by the AI as SVG, and a placeholder that never fails. */
public class AlbumCoverTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final String GOOD = "{\"svg\": \"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1000 1000'>"
            + "<rect x='0' y='0' width='1000' height='1000' fill='#18324A'/>"
            + "<circle cx='500' cy='420' r='180' fill='#F4D35E'/>"
            + "<text x='500' y='820' font-family='sans-serif' font-size='80' text-anchor='middle' fill='#FFFFFF'>"
            + "Liebliche Weihnacht</text></svg>\"}";
    /** A drawing without a ground: a cover must fill its square. */
    private static final String NO_GROUND = "{\"svg\": \"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1000 1000'>"
            + "<circle cx='500' cy='500' r='200' fill='#F4D35E'/></svg>\"}";

    private static Album album() {
        return new Album("weihnacht", "Liebliche Weihnacht", new AlbumConcept("Ruhige Weihnachtsmusik", "Orchester", 2, 10),
                List.of(new AlbumTrack("t1", "Sternenstill", "still", 80, "C major", "4/4", "", "", 60, ""),
                        new AlbumTrack("t2", "Flockentanz", "", 120, "G major", "3/4", "", "", 60, "")));
    }

    @Test
    public void thePlaceholderIsAPictureOfTheRightSizeForAnyTitle() throws Exception {
        for (String title : new String[]{"Liebliche Weihnacht", "", null,
                "Ein sehr langer Albumtitel der über viele Zeilen gehen muss weil er einfach nicht aufhören will zu wachsen"}) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(AlbumCover.placeholder(title)));
            assertNotNull(title, image);
            assertEquals(AlbumCover.SIZE, image.getWidth());
            assertEquals(AlbumCover.SIZE, image.getHeight());
        }
    }

    @Test
    public void aCoverIsKeptWithItsAlbumAndAPictureOfTheUsersOwnReplacesTheDrawing() throws Exception {
        Path folder = temporary.newFolder("album").toPath();
        assertFalse(AlbumCover.exists(folder));
        assertNull(AlbumCover.read(folder));
        assertNull(AlbumCover.bytes(folder));

        AlbumCover.save(folder, "<svg/>", new BufferedImage(30, 30, BufferedImage.TYPE_INT_ARGB));
        assertTrue(AlbumCover.exists(folder));
        assertEquals(30, AlbumCover.read(folder).getWidth());
        assertEquals("<svg/>", Files.readString(folder.resolve(AlbumCover.SVG), StandardCharsets.UTF_8));

        AlbumCover.save(folder, null, new BufferedImage(60, 30, BufferedImage.TYPE_INT_RGB));
        assertEquals(60, AlbumCover.read(folder).getWidth());
        assertFalse("the drawing is no longer what the cover shows", Files.exists(folder.resolve(AlbumCover.SVG)));
        assertFalse(Files.exists(folder.resolve(AlbumCover.PNG + ".part")));
    }

    @Test
    public void theAiDrawsTheCoverFromWhatTheAlbumSaysAndIsAskedAgainForOneThatDoesNotFillTheSquare() throws Exception {
        final int[] asked = {0};
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> asked[0]++ == 0 ? NO_GROUND : GOOD);
        Path answers = temporary.newFolder("answers").toPath();

        CoverArtist.Drawn drawn = new CoverArtist(model).draw(album(), answers);

        assertEquals(2, model.prompts().size());
        String first = model.prompts().get(0);
        assertTrue(first, first.contains("\"Liebliche Weihnacht\"") && first.contains("Ruhige Weihnachtsmusik")
                && first.contains("Orchester") && first.contains("- Sternenstill (still)") && first.contains("- Flockentanz"));
        assertTrue("the AI is told what was wrong", model.prompts().get(1).contains("does not fill the whole square"));
        assertEquals(AlbumCover.SIZE, drawn.image().getWidth());
        assertEquals(AlbumCover.SIZE, drawn.image().getHeight());
        assertEquals("the ground of the drawing", 0xFF18324A, drawn.image().getRGB(5, 5));
        assertEquals("its motif", 0xFFF4D35E, drawn.image().getRGB(500, 420));
        assertTrue(drawn.svg().startsWith("<svg"));
    }

    @Test
    public void anAiThatNeverDrawsAUsableCoverFailsWithAWordAboutWhy() throws Exception {
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> "{\"svg\": \"no drawing\"}");
        try {
            new CoverArtist(model).draw(album(), temporary.newFolder("answers").toPath());
            org.junit.Assert.fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("kein brauchbares Cover"));
        }
        assertEquals(CoverArtist.ATTEMPTS, model.prompts().size());
    }
}
