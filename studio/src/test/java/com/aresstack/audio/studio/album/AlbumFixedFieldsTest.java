package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.application.CompositionSpec;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Settings the user set ("Erweitert"), which planning keeps, and the count taken from the track list. */
public class AlbumFixedFieldsTest {
    private static final String ANSWER = "{\"tracks\":[{\"title\":\"Planned\",\"mood\":\"calm\",\"tempoBpm\":72,"
            + "\"key\":\"D minor\",\"meter\":\"3/4\",\"targetSeconds\":240},{\"title\":\"Second\",\"tempoBpm\":130,"
            + "\"key\":\"E major\",\"meter\":\"4/4\",\"targetSeconds\":200},{\"title\":\"Third\",\"tempoBpm\":90,"
            + "\"key\":\"G major\",\"meter\":\"4/4\",\"targetSeconds\":200}]}";

    @Test
    public void whatTheUserSetStaysAndIsToldToThePlan() throws Exception {
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> ANSWER);
        AlbumTrack mine = AlbumTrack.given("t1", "Mine", "").withBpm(140).fix(AlbumTrack.BPM)
                .withMeter("6/8").fix(AlbumTrack.METER);
        assertEquals(Set.of(AlbumTrack.BPM, AlbumTrack.METER), mine.fixed());
        Album album = new Album("a", "A", AlbumConcept.standard("ein Album", "", 1), List.of(mine));

        Album planned = new ChatAlbumPlanner(model, 4096).plan(album, null, line -> { });
        String prompt = model.prompts().get(0);
        assertTrue(prompt, prompt.contains("set by the user"));
        AlbumTrack track = planned.tracks().get(0);
        assertEquals("Mine", track.title());
        assertEquals(140, track.bpm());
        assertEquals("6/8", track.meter());
        assertEquals("the automatic key comes from the plan", Key.parse("D minor").name(), track.key());
        assertEquals(240, track.targetSeconds());
        assertEquals(mine.fixed(), track.fixed());

        // kept in the file
        assertEquals(planned, AlbumFiles.read("a", AlbumFiles.write(planned)));
        // set back to automatic
        assertTrue(track.withFixed(Set.of()).fixed().isEmpty());
    }

    @Test
    public void fromTheListTheListSaysHowManyTitles() throws Exception {
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> ANSWER);
        AlbumConcept concept = AlbumConcept.standard("ein Album", "", 12).withFromList(true);
        assertTrue(concept.fromList());
        Album album = new Album("a", "A", concept, List.of(AlbumTrack.given("t1", "Eins", ""),
                AlbumTrack.given("t2", "Zwei", "")));

        Album planned = new ChatAlbumPlanner(model, 4096).plan(album, null, line -> { });
        assertEquals(List.of("Eins", "Zwei"), planned.tracks().stream().map(AlbumTrack::title).toList());
        assertEquals(2, planned.concept().trackCount());
        assertTrue(planned.concept().fromList());
        assertEquals(planned, AlbumFiles.read("a", AlbumFiles.write(planned)));
        assertFalse(AlbumConcept.standard("x", "", 3).fromList());
    }

    @Test
    public void aPieceIsPlannedAsAnAlbumOfOneTitle() throws Exception {
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> ANSWER);
        CompositionSpec shown = CompositionSpec.of(32, 108, Key.C_MAJOR, 4, 4);

        assertSame("all set: nothing to ask", shown, PieceSpecPlanner.plan(model, 4096, "x", shown,
                Set.copyOf(PieceSpecPlanner.SETTINGS), null, line -> { }));
        assertTrue(model.prompts().isEmpty());

        CompositionSpec planned = PieceSpecPlanner.plan(model, 4096, "ein ruhiger Walzer", shown,
                Set.of(AlbumTrack.LENGTH), null, line -> { });
        assertEquals("the bars are the user's", 32, planned.bars());
        assertEquals(72, planned.bpm());
        assertEquals(Key.parse("D minor"), planned.key());
        assertEquals(3, planned.numerator());
        assertTrue(model.prompts().get(0).contains("ein ruhiger Walzer"));
        assertFalse("one title has no main theme", model.prompts().get(0).contains("mainTheme"));
    }

    @Test
    public void aPieceWithoutALengthInTheAnswerIsNotHalfAnHourLong() throws Exception {
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> "{\"tracks\":[{\"title\":\"Techno\","
                + "\"tempoBpm\":123,\"key\":\"A minor\",\"meter\":\"4/4\"}]}");
        CompositionSpec planned = PieceSpecPlanner.plan(model, 4096, "Melodic Techno, 122-124 BPM",
                CompositionSpec.of(32, 108, Key.C_MAJOR, 4, 4), Set.of(), null, line -> { });
        assertEquals(123, planned.bpm());
        assertTrue("was 925 bars (half an hour): " + planned.bars(),
                planned.seconds() <= AlbumConcept.DEFAULT_MAX_SECONDS);
    }
}
