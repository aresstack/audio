package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** "Neues Album daraus": a new album whose concept says what the piece is like; the piece is not touched. */
public final class AlbumFromPieceTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void theNewAlbumStartsFromThePieceAndHasNoTitlesYet() throws Exception {
        AlbumFiles files = new AlbumFiles(temporary.newFolder("compositions").toPath());
        AlbumTrack title = new AlbumTrack("t4", "Pastel Bloom City", "Playful, cozy", 108, "C major", "4/4", "", "", 18,
                "pastel-bloom-city");

        Album made = AlbumFromPiece.create(files, title, "a cozy city tune", List.of("Flute", "Bass"));

        assertEquals("Album zu „Pastel Bloom City“", made.title());
        assertTrue(made.tracks().isEmpty());
        assertTrue(made.concept().description(), made.concept().description().contains("„Pastel Bloom City“"));
        assertTrue(made.concept().description(), made.concept().description().endsWith("a cozy city tune"));
        assertEquals("Playful, cozy · um 108 BPM, C major, 4/4 · Besetzung wie Flute, Bass", made.concept().style());
        AlbumConcept usual = AlbumConcept.standard("", "", 12);
        assertEquals(usual.trackCount(), made.concept().trackCount());
        assertEquals(usual.minSeconds(), made.concept().minSeconds());
        assertEquals(usual.maxSeconds(), made.concept().maxSeconds());
        assertEquals("it is kept as every album", made, files.album(made.id()).orElseThrow());
    }

    @Test
    public void aPieceWithoutWordsOrPlayersStillGivesAConcept() throws Exception {
        AlbumTrack title = new AlbumTrack("t1", "district-dawn", "", 100, "C major", "4/4", "", "", 300, "district-dawn");
        AlbumConcept concept = AlbumFromPiece.concept(title, "", List.of());
        assertEquals("um 100 BPM, C major, 4/4", concept.style());
        assertTrue(concept.description().endsWith("weitere Stücke in seiner Art."));
    }
}
