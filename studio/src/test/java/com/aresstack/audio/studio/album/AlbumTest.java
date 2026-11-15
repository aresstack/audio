package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.application.CompositionSpec;
import com.aresstack.audio.studio.application.SongLibrary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Albums, first part: an album is kept as a file beside the pieces, its titles turn a length into
 * bars, and a chat model plans the whole list of titles at once.
 */
public final class AlbumTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static AlbumTrack track(String id, int bpm, String meter, int seconds) {
        return new AlbumTrack(id, "Titel " + id, "ruhig", bpm, "D major", meter, "Klavier, Streicher", "", seconds, "");
    }

    @Test
    public void theLengthOfATitleBecomesBarsOfItsTempoAndMeter() {
        // 300 s × 120 BPM ÷ (60 × 4 quarters) = 150 bars.
        CompositionSpec spec = track("t1", 120, "4/4", 300).spec();
        assertEquals(150, spec.bars());
        assertEquals(120, spec.bpm());
        assertEquals(Key.parse("D major"), spec.key());
        assertEquals(300, spec.seconds(), 1e-9);

        // A waltz: 3 quarters per bar. 300 × 90 ÷ 180 = 150 bars.
        assertEquals(150, track("t2", 90, "3/4", 300).spec().bars());
        // 6/8 has three quarters per bar as well; 72 BPM: 300 × 72 ÷ 180 = 120 bars.
        CompositionSpec compound = track("t3", 72, "6/8", 300).spec();
        assertEquals(120, compound.bars());
        assertEquals("6/8", compound.meter());

        // Whole bars only: the planned length differs from the wish by less than a bar, and the list can say so.
        AlbumTrack odd = track("t4", 100, "4/4", 301);
        assertEquals(125, odd.spec().bars());
        assertEquals(300, odd.plannedSeconds(), 1e-9);
        assertTrue(odd.asLongAsAsked(odd.plannedSeconds()));
        assertTrue(odd.asLongAsAsked(271));
        assertFalse("more than ten percent too short", odd.asLongAsAsked(240));

        // Never more bars than a piece can have, never less than one.
        assertEquals(CompositionSpec.MAX_BARS, track("t5", 400, "2/4", 3600).spec().bars());
        assertEquals(1, new AlbumTrack("t6", "x", "", 20, "C", "4/4", "", "", 10, "").spec().bars());
    }

    @Test
    public void whatAPlanSaysBadlyIsBroughtIntoRange() {
        AlbumTrack track = new AlbumTrack("t1", "  ", null, 9000, "no key at all", "seven", null, null, 5, null);
        assertEquals("Ohne Titel", track.title());
        assertEquals(CompositionSpec.MAX_BPM, track.bpm());
        assertEquals("C major", track.key());
        assertEquals("4/4", track.meter());
        assertEquals(10, track.targetSeconds());
        assertFalse(track.hasPiece());
        assertEquals("d-Moll is read and written as the files write keys", "D minor",
                new AlbumTrack("t2", "x", "", 100, "d-Moll", "3/4", "", "", 60, "").key());
    }

    @Test
    public void anAlbumIsKeptBesideThePiecesAndIsNotAPiece() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        Path piece = songs.create("Snowfall at Midnight");
        AlbumFiles files = new AlbumFiles(library);
        assertEquals(List.of(), files.albums());

        Album album = files.create("Christmas Memories", new AlbumConcept("Weihnachtsalbum, instrumental",
                "klassisch, orchestral", 12, 300));
        assertEquals("christmas-memories", album.id());
        assertEquals(3600, album.concept().seconds());
        album = album.withTracks(List.of(track("t1", 120, "4/4", 300),
                track("t2", 60, "3/4", 300).withPiece(piece.getFileName().toString())));
        files.save(album);

        assertTrue(Files.isRegularFile(library.resolve(".albums/christmas-memories/album.json")));
        assertEquals("read again, it is the same album", album, files.album("christmas-memories").orElseThrow());
        assertEquals(List.of(album), new AlbumFiles(library).albums());
        assertEquals("the folder of the albums is not a piece of the library", 1, songs.songs().size());
        assertEquals(600, album.targetSeconds());
        assertEquals("t3", album.freshTrackId());

        // A second album of the same name gets a folder of its own; a broken one does not hide the others.
        Album second = files.create("Christmas Memories", album.concept());
        assertEquals("christmas-memories-2", second.id());
        Files.write(files.folder(second.id()).resolve(AlbumFiles.FILE_NAME), "{ not json".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(album), files.albums());

        // Forgetting an album leaves its pieces.
        files.delete(album.id());
        assertFalse(files.album(album.id()).isPresent());
        assertTrue(Files.isDirectory(piece));
        assertEquals("umlauts and signs make a plain folder name", "frohliche-weihnacht-grusse",
                AlbumFiles.slug("Fröhliche Weihnacht & Grüße!"));
    }

    @Test
    public void theModelPlansTheWholeListAtOnceAndKeepsTitlesThatHaveAPiece() throws Exception {
        Path cache = temporary.newFolder("album").toPath();
        List<String> said = new ArrayList<String>();
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> {
            assertEquals(ChatAlbumPlanner.INSTRUCTION, instruction);
            int wanted = Integer.parseInt(prompt.replaceAll("(?s).*\"tracksWanted\":(\\d+).*", "$1"));
            StringBuilder answer = new StringBuilder("{\"tracks\":[");
            String[][] ideas = {
                    {"Christmas Overture", "festive opening", "112", "D major", "4/4"},
                    {"Winter Wonderland Waltz", "lively waltz", "150", "G major", "3/4"},
                    {"Silent Winter Night", "gentle and calm", "60", "F major", "6/8"}};
            for (int i = 0; i < wanted; i++) {
                String[] idea = ideas[i % ideas.length];
                answer.append(i == 0 ? "" : ",").append("{\"title\":\"").append(idea[0]).append("\",\"mood\":\"")
                        .append(idea[1]).append("\",\"tempoBpm\":").append(idea[2]).append(",\"key\":\"").append(idea[3])
                        .append("\",\"meter\":\"").append(idea[4]).append("\",\"instrumentation\":[\"strings\",\"bells\"],"
                                + "\"form\":\"intro, theme, variation, reprise, outro\",\"targetSeconds\":")
                        .append(i == 2 ? 20 : 300).append('}');
            }
            return answer.append("]}").toString();
        });
        ChatAlbumPlanner planner = new ChatAlbumPlanner(model, 4096);
        Album empty = new Album("christmas", "Christmas Memories",
                new AlbumConcept("A Christmas album, instrumental", "classical, orchestral", 3, 300), List.of());

        Album planned = planner.plan(empty, cache, said::add);
        assertEquals(3, planned.tracks().size());
        assertEquals(1, model.prompts().size());
        assertTrue("the model is told the wish, the style and the length",
                model.prompts().get(0).contains("A Christmas album, instrumental")
                        && model.prompts().get(0).contains("classical, orchestral")
                        && model.prompts().get(0).contains("\"secondsPerTrack\":300"));
        AlbumTrack waltz = planned.tracks().get(1);
        assertEquals("Winter Wonderland Waltz", waltz.title());
        assertEquals("3/4", waltz.meter());
        assertEquals("strings, bells", waltz.instrumentation());
        assertEquals("300 s × 150 BPM ÷ (60 × 3)", 250, waltz.spec().bars());
        assertEquals("a length far from what the user asked for is not taken", 300, planned.tracks().get(2).targetSeconds());
        assertEquals(List.of("t1", "t2", "t3"), planned.tracks().stream().map(AlbumTrack::id).toList());
        assertTrue(Files.isRegularFile(cache.resolve(ChatAlbumPlanner.ANSWER_FILE)));

        // The second title has its piece: planning again keeps it at its place and plans the two others anew.
        Album begun = planned.with(waltz.withPiece("winter-wonderland-waltz").withTitle("My Waltz"));
        Album again = planner.plan(begun, cache, said::add);
        assertEquals(2, model.prompts().size());
        assertTrue(model.prompts().get(1).contains("\"tracksWanted\":2") && model.prompts().get(1).contains("My Waltz"));
        assertEquals(3, again.tracks().size());
        assertEquals("My Waltz", again.tracks().get(1).title());
        assertEquals("winter-wonderland-waltz", again.tracks().get(1).piece());
        assertEquals("Christmas Overture", again.tracks().get(0).title());

        // What each piece is told about its album: its own place and a line for each other title.
        String brief = again.brief(again.tracks().get(0));
        assertTrue(brief, brief.contains("track 1 of 3") && brief.contains("Christmas Memories")
                && brief.contains("festive opening") && brief.contains("\"My Waltz\"") && brief.contains("150 BPM"));
        assertFalse("its own title is not among the others", brief.contains("- 1. \"Christmas Overture\""));
    }

    @Test
    public void aModelThatSaysTooLittleStillGivesAWholeList() throws Exception {
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> "{\"tracks\":[{\"title\":\"Only One\"}]}");
        Album planned = new ChatAlbumPlanner(model, 1024).plan(new Album("a", "A",
                new AlbumConcept("x", "", 3, 120), List.of()), null, line -> { });
        assertEquals(List.of("Only One", "Titel 2", "Titel 3"), planned.tracks().stream().map(AlbumTrack::title).toList());
        assertEquals(120, planned.tracks().get(2).targetSeconds());
        assertFalse(planned.tracks().get(2).form().isEmpty());
    }

    /** Proposals: the model is asked as someone who knows music; its answer is cleaned, and a broken one gives none. */
    @Test
    public void proposalsForAFieldComeCleanedFromTheModel() throws Exception {
        List<String> prompts = new ArrayList<String>();
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> {
            assertEquals(ChatAlbumSuggester.INSTRUCTION, instruction);
            prompts.add(prompt);
            return "{\"suggestions\":[\"Barock,  Cembalo und Streicher\",\"barock, cembalo und streicher\",\"\",7,"
                    + "\"Orgelmusik\",\"Wiener Klassik\"]}";
        });
        ChatAlbumSuggester suggester = new ChatAlbumSuggester(model, 4096);
        assertEquals(List.of("Barock, Cembalo und Streicher", "Wiener Klassik"), suggester.suggest(
                AlbumSuggester.Field.STYLE, "Weihnachtslieder", "", "klassisch", List.of("Orgelmusik")));
        assertTrue(prompts.get(0).contains("has written \"klassisch\"") && prompts.get(0).contains("Weihnachtslieder")
                && prompts.get(0).contains("do not repeat these: \"Orgelmusik\""));
        // Without a style typed, rough directions are asked for.
        suggester.suggest(AlbumSuggester.Field.STYLE, "Weihnachtslieder", "", "", List.of());
        assertTrue(prompts.get(1).contains("different musical directions"));
        assertEquals(List.of(), new ChatAlbumSuggester(new FakeChatModel((i, p) -> "{\"oops\":1}"), 512)
                .suggest(AlbumSuggester.Field.TITLE, "A", "", "", List.of()));

        // Kept with the album: new ones in front, old ones stay.
        Path folder = temporary.newFolder().toPath();
        AlbumSuggestions kept = AlbumSuggestions.EMPTY.with(AlbumSuggester.Field.STYLE, List.of("Orgelmusik"))
                .with(AlbumSuggester.Field.STYLE, List.of("Chor", "orgelmusik"));
        kept.save(folder);
        assertEquals(List.of("Chor", "Orgelmusik"), AlbumSuggestions.load(folder).of(AlbumSuggester.Field.STYLE));
        assertEquals(List.of(), AlbumSuggestions.load(folder).of(AlbumSuggester.Field.TITLE));
    }

    /** PO finding on PR #39: an id is one safe folder name; nothing else is opened or deleted. */
    @Test
    public void onlyTheFolderOfOneAlbumIsEverDeleted() throws Exception {
        Path library = temporary.newFolder("lib-delete").toPath();
        AlbumFiles files = new AlbumFiles(library);
        Album one = files.create("One", new AlbumConcept("x", "", 2, 60));
        Album two = files.create("Two", new AlbumConcept("y", "", 2, 60));
        Files.createDirectories(library.resolve("a-piece"));
        for (String bad : new String[]{".", "..", "", "one/..", "one/../two", "../a-piece", "one/", "/tmp", "One",
                "one\\..", ".albums", null}) {
            try {
                files.delete(bad);
                Assert.fail("deleted with the id " + bad);
            } catch (IllegalArgumentException refused) {
                // as it must be
            }
            try {
                files.folder(bad);
                Assert.fail("a folder for the id " + bad);
            } catch (IllegalArgumentException refused) {
                // as it must be
            }
            assertTrue(files.album(bad == null ? "" : bad).isEmpty());
            assertEquals("after delete(" + bad + ")", 2, files.albums().size());
        }
        assertTrue(Files.isDirectory(library.resolve("a-piece")));
        files.delete(one.id());
        assertEquals(List.of(two), files.albums());
        files.delete("never-there");
        assertEquals(List.of(two), files.albums());
    }

    /** PO finding on PR #39: a piece folder from album.json is one plain name in the library, or it is not followed. */
    @Test
    public void aPieceFolderFromTheFileNeverLeadsOutOfTheLibrary() throws Exception {
        for (String bad : new String[]{"../other", "..", ".", "a/b", "a\\b", "/abs", "C:\\x", "C:x", ".albums",
                ".hidden", "x/..", " padded", "dot."}) {
            assertFalse(bad, AlbumTrack.isFolderName(bad));
            try {
                new AlbumTrack("t1", "T", "", 100, "C major", "4/4", "", "", 60, bad);
                // " padded" is trimmed to a plain name, which is fine.
                assertEquals(bad, " padded", bad);
            } catch (IllegalArgumentException refused) {
                // as it must be
            }
        }
        assertTrue(AlbumTrack.isFolderName("Winter Waltz 2"));
        assertTrue(AlbumTrack.isFolderName("stille-nacht"));

        Path library = temporary.newFolder("lib-piece").toPath();
        AlbumFiles files = new AlbumFiles(library);
        Album album = files.create("Tampered", new AlbumConcept("x", "", 1, 60));
        files.save(album.withTracks(List.of(new AlbumTrack("t1", "T", "", 100, "C major", "4/4", "", "", 60, "good"))));
        Path file = files.folder(album.id()).resolve(AlbumFiles.FILE_NAME);
        String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"piece\": \"good\""));
        Files.write(file, json.replace("\"piece\": \"good\"", "\"piece\": \"../outside\"")
                .getBytes(StandardCharsets.UTF_8));
        Album read = files.album(album.id()).orElseThrow();
        assertEquals("the entry is not followed", "", read.tracks().get(0).piece());
        assertFalse(read.tracks().get(0).hasPiece());
    }
}
