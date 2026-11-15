package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

/** What the AI writes for an album is seen while it arrives: each title, each proposal. */
public final class AlbumStreamingTest {
    @Test
    public void theTitlesOfAPlanAreSeenOneByOneWhileItIsWritten() {
        AlbumConcept concept = AlbumConcept.standard("snow", "", 3);
        String text = "{\"tracks\":[{\"title\":\"Eins\",\"tempoBpm\":90,\"targetSeconds\":250},"
                + "{\"title\":\"Zwei\",\"mood\":\"still\"},{\"title\":\"Dr";
        List<AlbumTrack> seen = ChatAlbumPlanner.seenTracks(text, List.of(), 3, concept);
        assertEquals(2, seen.size());
        assertEquals("Eins", seen.get(0).title());
        assertEquals(250, seen.get(0).targetSeconds());
        assertEquals("Zwei", seen.get(1).title());
        assertEquals(0, ChatAlbumPlanner.seenTracks("{\"tra", List.of(), 3, concept).size());
    }

    @Test
    public void proposalsAreSeenAsTheyArriveAndNoneIsHalf() {
        List<String> seen = ChatAlbumSuggester.clean(com.aresstack.audio.studio.ai.PartialJson.read(
                "{\"suggestions\":[\"Barock, Streicher\",\"Orgel\",\"Bläser im Sch"), List.of("orgel"));
        assertEquals(List.of("Barock, Streicher"), seen);
    }
}
