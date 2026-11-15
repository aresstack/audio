package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.studio.ai.FakeChatModel;
import java.util.List;
import org.junit.Test;

/** Titles the user gives (in the wish or in the list) and the main theme of an album. */
public class AlbumGivenTitlesTest {
    private static final String WISH = "## **1. Worum geht es?**\n\nKomponiere den Soundtrack zu „AURORA“.\n\n"
            + "## **4. So könnte die KI den Soundtrack aufbauen**\n\n"
            + "| Nr. | Szene                      | Musikalische Richtung                  |\n"
            + "| --- | -------------------------- | -------------------------------------- |\n"
            + "| 01  | Der Ruf aus der Ferne      | Geheimnisvolle Synthesizer, Hauptthema |\n"
            + "| 02  | Abschied von der Erde      | Emotionales Klavier, Streicher         |\n"
            + "| 03  | Heimkehr – Aurora          | Großes, triumphierendes Finale         |\n";

    @Test
    public void aTableInTheWishIsAListOfTitlesAndHeadingsAreNot() {
        List<TitleList.Entry> titles = TitleList.in(WISH);
        assertEquals(List.of(new TitleList.Entry("Der Ruf aus der Ferne", "Geheimnisvolle Synthesizer, Hauptthema"),
                new TitleList.Entry("Abschied von der Erde", "Emotionales Klavier, Streicher"),
                new TitleList.Entry("Heimkehr – Aurora", "Großes, triumphierendes Finale")), titles);
        assertEquals(List.of(new TitleList.Entry("Morgen", "ruhig"), new TitleList.Entry("Mittag", "")),
                TitleList.in("Titel:\n1. Morgen – ruhig\n2. Mittag\n\nmehr Text"));
        assertEquals(List.of(new TitleList.Entry("Eins", "leise"), new TitleList.Entry("Zwei", "laut")),
                TitleList.in("1\tEins\tleise\n2\tZwei\tlaut"));
        assertTrue(TitleList.in("## 1. Worum geht es?\nText\n## 2. Stil\nText").isEmpty());
        assertTrue(TitleList.in("Ein Album über den Winter").isEmpty());
    }

    @Test
    public void givenTitlesStayAndThePlanFillsInTheRestWithAMainTheme() throws Exception {
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> "{\"mainTheme\":{\"name\":\"Aurora\","
                + "\"description\":\"hopeful rising fifth\",\"key\":\"D minor\",\"notes\":[{\"beat\":0,\"duration\":1,"
                + "\"pitch\":62},{\"beat\":1,\"duration\":2,\"pitch\":69}]},\"tracks\":["
                + "{\"title\":\"Other Name\",\"mood\":\"mysterious\",\"tempoBpm\":70,\"key\":\"D minor\","
                + "\"theme\":\"hopeful, solo horn\"},"
                + "{\"title\":\"Farewell\",\"mood\":\"sad\",\"tempoBpm\":60,\"key\":\"F major\",\"theme\":\"\"},"
                + "{\"title\":\"Home\",\"tempoBpm\":120,\"key\":\"D major\",\"theme\":\"triumphant, full orchestra\"},"
                + "{\"title\":\"Encore\",\"tempoBpm\":100,\"key\":\"C major\"}]}");
        Album album = new Album("aurora", "Aurora", new AlbumConcept(WISH, "epic", 4, 180, 360), List.of());

        Album planned = new ChatAlbumPlanner(model, 4096).plan(album, null, line -> { });
        String prompt = model.prompts().get(0);
        assertTrue(prompt, prompt.contains("Keep each title exactly as given") && prompt.contains("Der Ruf aus der Ferne")
                && prompt.contains("leitmotif") && prompt.contains("mainTheme"));
        assertEquals(List.of("Der Ruf aus der Ferne", "Abschied von der Erde", "Heimkehr – Aurora", "Encore"),
                planned.tracks().stream().map(AlbumTrack::title).toList());
        AlbumTrack first = planned.tracks().get(0);
        assertEquals("the user's words are its mood", "Geheimnisvolle Synthesizer, Hauptthema", first.mood());
        assertEquals(70, first.bpm());
        assertTrue(first.given());
        assertFalse(planned.tracks().get(3).given());
        assertEquals("hopeful, solo horn", first.theme());
        assertEquals("Aurora", planned.theme().name());
        assertEquals(2, planned.theme().notes().size());

        // kept in the file, and each piece the theme appears in is told its notes and its variant
        Album read = AlbumFiles.read("aurora", AlbumFiles.write(planned));
        assertEquals(planned, read);
        String brief = read.brief(read.tracks().get(2));
        assertTrue(brief, brief.contains("{beat:0,duration:1,pitch:62}") && brief.contains("triumphant, full orchestra"));
        assertFalse("a piece without the theme is not told it", read.brief(read.tracks().get(1)).contains("pitch:62"));

        // planning again: the given titles stay, the theme too, and the plan is told the theme
        Album again = new ChatAlbumPlanner(model, 4096).plan(read, null, line -> { });
        assertEquals(planned.tracks().stream().map(AlbumTrack::title).toList(),
                again.tracks().stream().map(AlbumTrack::title).toList());
        assertEquals(planned.theme(), again.theme());
        assertTrue(model.prompts().get(1).contains("The album has this main theme"));
    }

    @Test
    public void anAlbumWithoutThemeTellsItsPiecesWhatItAlwaysDid() {
        AlbumTrack track = new AlbumTrack("t1", "A", "calm", 90, "C major", "4/4", "piano", "", 120, "");
        Album album = new Album("a", "A", AlbumConcept.standard("x", "", 1), List.of(track));
        assertFalse(album.brief(track).contains("main theme"));
    }
}
