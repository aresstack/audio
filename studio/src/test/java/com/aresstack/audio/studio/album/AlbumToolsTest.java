package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.Test;

/** What the AI help of the track list does with an album. */
public class AlbumToolsTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    public void titlesArePutInAsGivenAndChangedRemovedAndMoved() {
        AlbumTrack made = new AlbumTrack("t1", "Fertig", "", 90, "C major", "4/4", "", "", 120, "fertig");
        AlbumTrack planned = new AlbumTrack("t2", "Alt", "", 90, "C major", "4/4", "", "", 120, "");
        Album album = new Album("a", "A", new AlbumConcept("x", "", 2, 120, 240), List.of(made, planned));

        Album filled = AlbumTools.addTracks(album, json("{\"replace\":true,\"titles\":[{\"title\":\"Der Ruf\","
                + "\"mood\":\"geheimnisvoll\"},{\"title\":\"Abschied\",\"tempoBpm\":70,\"key\":\"D minor\"}]}"));
        assertEquals(List.of("Fertig", "Der Ruf", "Abschied"), filled.tracks().stream().map(AlbumTrack::title).toList());
        assertEquals(3, filled.concept().trackCount());
        assertTrue(filled.tracks().get(1).given());
        assertEquals("geheimnisvoll", filled.tracks().get(1).mood());
        assertEquals(70, filled.tracks().get(2).bpm());

        Album changed = AlbumTools.changeTrack(filled, json("{\"number\":2,\"title\":\"Der Ruf aus der Ferne\"}"));
        assertEquals("Der Ruf aus der Ferne", changed.tracks().get(1).title());
        try {
            AlbumTools.changeTrack(filled, json("{\"number\":1,\"title\":\"Neu\"}"));
            fail("a title with its piece stays");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("schon ein Stück"));
        }
        Album moved = AlbumTools.moveTrack(changed, json("{\"number\":3,\"to\":1}"));
        assertEquals("Abschied", moved.tracks().get(0).title());
        Album removed = AlbumTools.removeTrack(moved, json("{\"number\":3}"));
        assertEquals(2, removed.tracks().size());

        Album themed = AlbumTools.setTheme(removed, json("{\"name\":\"Aurora\",\"notes\":[{\"beat\":0,\"duration\":1,"
                + "\"pitch\":62}]}"));
        assertEquals("Aurora", themed.theme().name());
        assertTrue(AlbumTools.read(themed).contains("\"number\": 1"));
    }
}
