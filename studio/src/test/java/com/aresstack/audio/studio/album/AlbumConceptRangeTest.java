package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;
import org.junit.Test;

/** How long a piece of an album may be: a range, four to six minutes unless the user says otherwise. */
public final class AlbumConceptRangeTest {
    @Test
    public void theRangeIsKeptAndAnOldAlbumHasOneLengthForAll() {
        AlbumConcept standard = AlbumConcept.standard("", "", 12);
        assertEquals(240, standard.minSeconds());
        assertEquals(360, standard.maxSeconds());
        assertEquals(300, standard.secondsPerTrack());
        assertEquals("from and to swapped are put right", 240, new AlbumConcept("", "", 3, 360, 240).minSeconds());

        Album album = new Album("a1", "Winter", standard, java.util.List.of());
        Album again = AlbumFiles.read("a1", AlbumFiles.write(album));
        assertEquals(standard, again.concept());

        Album old = AlbumFiles.read("a2", JsonParser.parseString(
                "{\"title\":\"Alt\",\"concept\":{\"trackCount\":2,\"secondsPerTrack\":200},\"tracks\":[]}")
                .getAsJsonObject());
        assertEquals(200, old.concept().minSeconds());
        assertEquals(200, old.concept().maxSeconds());
    }

    @Test
    public void thePlanAsksForLengthsInTheRangeAndKeepsThemThere() {
        String prompt = ChatAlbumPlanner.prompt(new Album("a1", "Winter", AlbumConcept.standard("snow", "", 3),
                java.util.List.of()), java.util.List.of(), 3);
        assertTrue(prompt, prompt.contains("\"secondsMin\":240") && prompt.contains("\"secondsMax\":360")
                && prompt.contains("between 240 and 360"));
    }
}
