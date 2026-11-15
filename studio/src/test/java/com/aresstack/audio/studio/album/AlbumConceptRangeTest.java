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
        assertEquals(140, old.concept().minSeconds());
        assertEquals(260, old.concept().maxSeconds());
    }

    @Test
    public void thePlanAsksForLengthsInTheRangeAndKeepsThemThere() {
        String prompt = ChatAlbumPlanner.prompt(new Album("a1", "Winter", AlbumConcept.standard("snow", "", 3),
                java.util.List.of()), java.util.List.of(), 3);
        assertTrue(prompt, prompt.contains("\"secondsMin\":240") && prompt.contains("\"secondsMax\":360")
                && prompt.contains("between 240 and 360"));
    }

    /** "Original nachbilden": the real movements with their lengths, not the album's range, and kept in the file. */
    @Test
    public void anAlbumThatRebuildsAWorkAsksForTheRealMovementsAndKeepsTheirLengths() throws Exception {
        AlbumConcept concept = AlbumConcept.standard("Vivaldi, Die vier Jahreszeiten", "", 12).withOriginal(true);
        Album album = new Album("a1", "Die vier Jahreszeiten", concept, java.util.List.of());
        assertEquals(concept, AlbumFiles.read("a1", AlbumFiles.write(album)).concept());
        String prompt = ChatAlbumPlanner.prompt(album, java.util.List.of(), 12);
        assertTrue(prompt, prompt.contains("rebuilds an existing work") && prompt.contains("real title of the movement")
                && !prompt.contains("between 240 and 360"));

        java.util.List<String> asked = new java.util.ArrayList<String>();
        com.aresstack.audio.studio.ai.ChatModel model = (instruction, question, tokens, cache) -> {
            asked.add(question);
            if (question.startsWith("List the movements")) {
                return JsonParser.parseString("{\"movements\":[{\"title\":\"Frühling – I. Allegro\"},"
                        + "{\"title\":\"Frühling – II. Largo\"}]}").getAsJsonObject();
            }
            return question.contains("movement 1 of 2")
                    ? JsonParser.parseString("{\"title\":\"Frühling – I. Allegro\",\"key\":\"E major\","
                    + "\"tempoBpm\":100,\"meter\":\"4/4\",\"targetSeconds\":200}").getAsJsonObject()
                    : JsonParser.parseString("{\"key\":\"C# minor\",\"tempoBpm\":50,\"meter\":\"3/4\","
                    + "\"targetSeconds\":150}").getAsJsonObject();
        };
        Album planned = new ChatAlbumPlanner(model, 1024).plan(album.withConcept(concept), null, line -> { });
        assertEquals("the movements first, then one request per movement", 3, asked.size());
        assertEquals("as many tracks as the work has movements, not as the album asked", 2, planned.tracks().size());
        assertEquals(2, planned.concept().trackCount());
        assertEquals("Frühling – I. Allegro", planned.tracks().get(0).title());
        assertEquals("Frühling – II. Largo", planned.tracks().get(1).title());
        assertEquals("3/4", planned.tracks().get(1).meter());
        assertEquals("the original's length, below the album's four minutes", 200,
                planned.tracks().get(0).targetSeconds());
        assertTrue(planned.brief(planned.tracks().get(0)).contains("REBUILD THE ORIGINAL"));
    }
}
