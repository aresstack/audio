package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.studio.golden.Golden;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.project.ProjectFile;
import com.aresstack.audio.studio.project.ProjectFolder;
import com.aresstack.audio.studio.project.StemManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package F1, the folder of a piece (architecture review 5.1, E6, 7.2): an old piece is
 * imported when it is first opened, its old files stay unchanged in legacy/, nothing is
 * rendered again, and the way back restores the old state.
 */
public final class ProjectSessionTest {
    private static final List<String> STEMS = List.of("melody-sampled.wav", "harmony-sampled.wav", "bass.wav",
            "drums.wav", "melody.wav", "arrangement-instruments.wav", "arrangement.mid");

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path piece;
    private Map<String, byte[]> original;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        piece = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, piece.resolve(file.getFileName()));
            }
        }
        Files.createDirectories(piece.resolve("earlier"));
        Files.write(piece.resolve("earlier").resolve("melody.json"), "{\"notes\":[]}".getBytes(StandardCharsets.UTF_8));
        Files.write(piece.resolve("melody-4f2a.json"), "{\"notes\":[]}".getBytes(StandardCharsets.UTF_8));
        for (String stem : STEMS) {
            Files.write(piece.resolve(stem), stem.getBytes(StandardCharsets.UTF_8));
            Files.setLastModifiedTime(piece.resolve(stem), FileTime.fromMillis(1_700_000_000_000L));
        }
        original = contents(piece);
    }

    private static Map<String, byte[]> contents(Path folder) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(folder.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return files;
    }

    private static void assertSameFiles(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String name : expected.keySet()) {
            assertArrayEquals(name, expected.get(name), actual.get(name));
        }
    }

    private Map<String, byte[]> legacyOriginals() {
        Map<String, byte[]> kept = new TreeMap<>();
        for (String name : ProjectFolder.LEGACY_FILES) {
            if (original.containsKey(name)) {
                kept.put(name, original.get(name));
            }
        }
        return kept;
    }

    @Test
    public void openingAnOldPieceImportsItWithoutChangingOrRenderingAnything() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        assertEquals(List.of("melody", "harmony", "bass", "drums"),
                session.composition().parts().stream().map(p -> p.id()).toList());

        Map<String, byte[]> now = contents(piece);
        for (Map.Entry<String, byte[]> file : original.entrySet()) {
            assertArrayEquals("unchanged: " + file.getKey(), file.getValue(), now.get(file.getKey()));
        }
        for (String stem : STEMS) {
            assertEquals("not rendered again: " + stem, 1_700_000_000_000L,
                    Files.getLastModifiedTime(piece.resolve(stem)).toMillis());
        }
        assertSameFiles(legacyOriginals(), contents(piece.resolve(ProjectFolder.LEGACY)));
        assertTrue(Files.isRegularFile(piece.resolve(CompositionJson.FILE_NAME)));
        assertTrue(Files.isRegularFile(piece.resolve(MixJson.FILE_NAME)));
        assertEquals(ProjectFolder.LEGACY, ProjectFile.load(piece).importedFrom());
        List<StemManifest.Entry> stems = StemManifest.load(piece);
        assertEquals(5, stems.size());
        assertTrue(stems.stream().allMatch(entry -> StemManifest.ADOPTED.equals(entry.state())));
    }

    @Test
    public void savingKeepsLegacyAndTheArrangementAsTheyWere() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        session.save();
        assertSameFiles(legacyOriginals(), contents(piece.resolve(ProjectFolder.LEGACY)));
        assertArrayEquals(original.get(ProjectFolder.ARRANGEMENT),
                Files.readAllBytes(piece.resolve(ProjectFolder.ARRANGEMENT)));
        ProjectSession again = ProjectSession.open(piece).orElseThrow();
        assertEquals(session.composition(), again.composition());
        assertEquals(session.mix(), again.mix());
    }

    @Test
    public void savingWritesTheArrangementFromTheModelWhenItWasLost() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Files.delete(piece.resolve(ProjectFolder.ARRANGEMENT));
        session.save();
        assertArrayEquals(original.get(ProjectFolder.ARRANGEMENT),
                Files.readAllBytes(piece.resolve(ProjectFolder.ARRANGEMENT)));
        Path midi = temporary.newFile("export.mid").toPath();
        session.exportMidi(midi);
        assertTrue(Files.size(midi) > 100);
    }

    @Test
    public void theWayBackRestoresTheOldStateAndAnotherImportReadsLegacy() throws Exception {
        Composition first = ProjectSession.open(piece).orElseThrow().composition();
        // The old pipeline renames a track and the stage is saved again, as the studio does it.
        StageMixService.rename(piece, "bass", "low");
        Files.write(piece.resolve(ProjectFolder.SCENE), "{\"version\":1}".getBytes(StandardCharsets.UTF_8));
        assertTrue(ProjectSession.revertToLegacy(piece));

        Map<String, byte[]> restored = contents(piece);
        assertTrue("legacy/ stays", restored.keySet().stream().anyMatch(name -> name.startsWith("legacy/")));
        restored.keySet().removeIf(name -> name.startsWith("legacy/") || name.startsWith("render/"));
        Map<String, byte[]> expected = new TreeMap<>(original);
        // Renaming moved the rendered files; they are not source files, and the way back leaves them alone.
        for (String moved : List.of("bass.wav")) {
            expected.remove(moved);
            restored.remove("low.wav");
        }
        assertSameFiles(expected, restored);

        Composition again = ProjectSession.open(piece).orElseThrow().composition();
        assertEquals("the same old piece gives the same model", first, again);
    }

    @Test
    public void whatTheOldPipelineWritesIsTakenIntoTheModelKeepingIds() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition before = session.composition();
        // The composer writes a new arrangement in which one note of the melody changed.
        List<String> lines = new ArrayList<>(Files.readAllLines(piece.resolve(ProjectFolder.ARRANGEMENT)));
        int changed = lines.indexOf(lines.stream().filter(l -> l.startsWith("note\tmelody\t")).findFirst().orElseThrow());
        String[] fields = lines.get(changed).split("\t");
        fields[5] = Integer.toString(Integer.parseInt(fields[5]) + 1);
        lines.set(changed, String.join("\t", fields));
        Files.write(piece.resolve(ProjectFolder.ARRANGEMENT), lines, StandardCharsets.UTF_8);

        assertTrue(session.synchronize());
        Composition after = session.composition();
        List<Note> old = notes(before, "melody");
        List<Note> now = notes(after, "melody");
        assertEquals(old.size(), now.size());
        int differentIds = 0;
        for (int i = 0; i < old.size(); i++) {
            if (!old.get(i).id().equals(now.get(i).id())) {
                differentIds++;
            }
        }
        assertEquals("only the changed note is a new note", 1, differentIds);
        assertEquals(notes(before, "harmony"), notes(after, "harmony"));
        assertEquals(before.track("melody").clips().get(0).id(), after.track("melody").clips().get(0).id());
        assertEquals(before.part("melody").voices(), after.part("melody").voices());
        assertEquals(after, CompositionJson.load(piece));
        assertFalse("the stem of the melody no longer fits",
                StemManifest.load(piece).stream().anyMatch(entry -> entry.partId().equals("melody")));
        assertTrue(StemManifest.load(piece).stream().anyMatch(entry -> entry.partId().equals("harmony")));
        assertFalse("nothing more to take over", session.synchronize());
        assertSameFiles(legacyOriginals(), contents(piece.resolve(ProjectFolder.LEGACY)));
    }

    @Test
    public void aRenamedTrackKeepsTheRestOfThePiece() throws Exception {
        Composition before = ProjectSession.open(piece).orElseThrow().composition();
        StageMixService.rename(piece, "bass", "low");
        ProjectSession reopened = ProjectSession.open(piece).orElseThrow();
        assertEquals("opening takes nothing over", before, reopened.composition());
        assertTrue(reopened.externalChanges());
        assertTrue(reopened.synchronize());
        assertFalse(reopened.externalChanges());
        Composition after = reopened.composition();
        assertEquals(List.of("melody", "harmony", "low", "drums"), after.parts().stream().map(p -> p.id()).toList());
        assertEquals(notes(before, "drums"), notes(after, "drums"));
        assertNotEquals(null, after.part("low"));
    }

    @Test
    public void piecesWithMissingFilesOpenToo() throws Exception {
        for (String fixture : Golden.FIXTURES) {
            Path folder = temporary.newFolder(fixture).toPath();
            Files.copy(Golden.fixture(fixture), folder.resolve(ProjectFolder.ARRANGEMENT));
            ProjectSession session = ProjectSession.open(folder).orElseThrow();
            assertFalse(session.composition().parts().isEmpty());
            assertArrayEquals(Files.readAllBytes(Golden.fixture(fixture)),
                    Files.readAllBytes(folder.resolve(ProjectFolder.LEGACY).resolve(ProjectFolder.ARRANGEMENT)));
        }
        Path empty = temporary.newFolder("not-composed-yet").toPath();
        assertTrue(ProjectSession.open(empty).isEmpty());
        assertFalse("no legacy/ for a piece that has nothing yet", Files.exists(empty.resolve(ProjectFolder.LEGACY)));
    }

    @Test
    public void aFolderOfANewerStudioIsRefused() throws Exception {
        ProjectSession.open(piece).orElseThrow();
        Files.write(piece.resolve(ProjectFile.FILE_NAME), "{\"formatVersion\":99}".getBytes(StandardCharsets.UTF_8));
        try {
            ProjectSession.open(piece);
            fail("a newer format must not be misread");
        } catch (IOException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("neueren Audio Studio"));
        }
    }

    @Test
    public void whatOnlyTheModelKnowsSurvivesReopeningAndChangesOfTheOldFiles() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition withGenre = session.composition().withMeta(session.composition().meta().withGenre("Stadtmusik"));
        CompositionJson.save(piece, withGenre);
        com.aresstack.audio.composition.mix.MixScene fixed = new com.aresstack.audio.composition.mix.MixScene(
                session.mix().room(), session.mix().masterDb(), com.aresstack.audio.composition.mix.LevelMode.FIXED,
                session.mix().channels(), session.mix().automation());
        MixJson.save(piece, fixed);

        ProjectSession reopened = ProjectSession.open(piece).orElseThrow();
        assertEquals(withGenre, reopened.composition());
        assertEquals(fixed, reopened.mix());
        assertFalse(reopened.synchronize());

        // The composer changes one note: the model takes it, and keeps the rest of what it knows.
        List<String> lines = new ArrayList<>(Files.readAllLines(piece.resolve(ProjectFolder.ARRANGEMENT)));
        int at = lines.indexOf(lines.stream().filter(l -> l.startsWith("note\tbass\t")).findFirst().orElseThrow());
        lines.set(at, lines.get(at).replaceFirst("\t(\\d+)$", "\t1"));
        Files.write(piece.resolve(ProjectFolder.ARRANGEMENT), lines, StandardCharsets.UTF_8);
        assertTrue(reopened.synchronize());
        assertEquals("Stadtmusik", reopened.composition().meta().genre());
        assertEquals(fixed, reopened.mix());
        assertEquals(withGenre.part("melody"), reopened.composition().part("melody"));
        assertNotEquals(withGenre.track("bass"), reopened.composition().track("bass"));
    }

    @Test
    public void savingRewritesAnArrangementWithLinesTheRendererRefuses() throws Exception {
        Path folder = temporary.newFolder("odd-line").toPath();
        Files.write(folder.resolve(ProjectFolder.ARRANGEMENT), List.of("tempo\t100", "track\tpiano\t0\t0\tharmony",
                "volume\tpiano\t3", "note\tpiano\t0\t480\t60\t80"), StandardCharsets.UTF_8);
        ProjectSession.open(folder).orElseThrow().save();
        assertEquals(List.of("tempo\t100", "track\tpiano\t0\t0\tharmony", "note\tpiano\t0\t480\t60\t80"),
                Files.readAllLines(folder.resolve(ProjectFolder.ARRANGEMENT)));
        assertEquals("the original stays in legacy/", 4,
                Files.readAllLines(folder.resolve(ProjectFolder.LEGACY).resolve(ProjectFolder.ARRANGEMENT)).size());
    }

    @Test
    public void goingBackToAnEarlierArrangementGivesNoIdTwice() throws Exception {
        Path folder = temporary.newFolder("three-notes").toPath();
        List<String> original = List.of("tempo\t100", "track\tpiano\t0\t0\tharmony", "note\tpiano\t0\t480\t60\t80",
                "note\tpiano\t480\t480\t62\t80", "note\tpiano\t960\t480\t64\t80");
        Files.write(folder.resolve(ProjectFolder.ARRANGEMENT), original, StandardCharsets.UTF_8);
        ProjectSession session = ProjectSession.open(folder).orElseThrow();
        List<String> changed = new ArrayList<>(original);
        changed.set(3, "note\tpiano\t480\t480\t65\t80");
        changed.set(4, "note\tpiano\t960\t480\t67\t80");
        Files.write(folder.resolve(ProjectFolder.ARRANGEMENT), changed, StandardCharsets.UTF_8);
        assertTrue(session.synchronize());
        Files.write(folder.resolve(ProjectFolder.ARRANGEMENT), original, StandardCharsets.UTF_8);
        assertTrue(session.synchronize());
        assertEquals(List.of(), com.aresstack.audio.composition.CompositionValidator.validate(session.composition()));
    }

    @Test
    public void aChangeOfTempoMeansTheTakenOverStemsNoLongerFit() throws Exception {
        Composition composition = ProjectSession.open(piece).orElseThrow().composition();
        Composition slower = new Composition(composition.meta(), com.aresstack.audio.composition.TimeMap.of(60),
                composition.sections(), composition.parts(), composition.tracks(), composition.assets());
        assertNotEquals(StemManifest.digest(composition, "melody"), StemManifest.digest(slower, "melody"));
        assertEquals(List.of(), StemManifest.current(StemManifest.load(piece), slower));
    }

    /** PO acceptance (1): a model changed and saved while arrangement.tsv is older comes back unchanged. */
    @Test
    public void aSavedModelIsNotOverwrittenByAnOlderArrangement() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition edited = withVelocity(session.composition(), "melody", 0, 33);
        CompositionJson.save(piece, edited); // as an editor of the model saves; arrangement.tsv is now behind
        Files.setLastModifiedTime(piece.resolve(ProjectFolder.ARRANGEMENT), FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        ProjectSession reopened = ProjectSession.open(piece).orElseThrow();
        assertEquals(edited, reopened.composition());
        assertEquals(edited, CompositionJson.load(piece));
        assertFalse("the view is old, not new", reopened.externalChanges());
        assertFalse(reopened.synchronize());
        assertEquals(edited, ProjectSession.open(piece).orElseThrow().composition());

        // Saving writes the view from the model; the model stays as it is.
        reopened.save();
        assertEquals(edited, ProjectSession.open(piece).orElseThrow().composition());
        assertTrue(Files.readAllLines(piece.resolve(ProjectFolder.ARRANGEMENT)).stream()
                .anyMatch(line -> line.startsWith("note\tmelody\t") && line.endsWith("\t33")));
    }

    /** PO acceptance (2): an arrangement the old composer really wrote anew is taken over on purpose. */
    @Test
    public void aNewlyWrittenArrangementIsTakenOverOnlyOnPurpose() throws Exception {
        Composition before = ProjectSession.open(piece).orElseThrow().composition();
        List<String> lines = new ArrayList<>(Files.readAllLines(piece.resolve(ProjectFolder.ARRANGEMENT)));
        lines.add("note\tdrums\t15240\t120\t42\t60");
        Files.write(piece.resolve(ProjectFolder.ARRANGEMENT), lines, StandardCharsets.UTF_8);

        ProjectSession reopened = ProjectSession.open(piece).orElseThrow();
        assertEquals("not by opening", before, reopened.composition());
        assertEquals(before, CompositionJson.load(piece));
        assertTrue(reopened.externalChanges());
        assertTrue(reopened.synchronize());
        assertEquals(notes(before, "drums").size() + 1, notes(reopened.composition(), "drums").size());
        assertEquals(reopened.composition(), CompositionJson.load(piece));
        assertEquals(reopened.composition(), ProjectSession.open(piece).orElseThrow().composition());
    }

    /** PO acceptance (3): what arrangement.tsv cannot say is saved and opened without loss, and a new file cannot replace it. */
    @Test
    public void whatTheArrangementCannotSayIsNeverLost() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition c = session.composition();
        TimeMap time = new TimeMap(List.of(new TimeMap.Tempo(0, 108), new TimeMap.Tempo(4 * 1920, 90)),
                List.of(new TimeMap.Meter(0, 4, 4), new TimeMap.Meter(4 * 1920, 3, 4)),
                List.of(new TimeMap.KeyChange(0, Key.C_MAJOR), new TimeMap.KeyChange(4 * 1920, new Key(2, Key.Mode.MINOR))));
        long end = 4 * 1920 + 4 * 1440;
        List<Section> sections = List.of(new Section("intro", "Intro", "intro", 0, 4 * 1920, 0.3, List.of("C"), "leise"),
                new Section("main", "Hauptteil", "verse", 4 * 1920, end - 4 * 1920, 0.8, List.of("Dm"), null));
        Track melody = c.track("melody");
        MidiClip first = (MidiClip) melody.clips().get(0);
        MidiClip flute = new MidiClip("flutclip", 4 * 1920, 1440, "harmony",
                List.of(new Note("flutnote", 0, 480, 74, 90, c.part("harmony").firstVoice().id())));
        MidiClip shortened = new MidiClip(first.id(), 0, 4 * 1920, null,
                first.notes().stream().filter(n -> n.endTick() <= 4 * 1920).toList());
        List<Track> tracks = new ArrayList<>(c.tracks());
        tracks.set(tracks.indexOf(melody), melody.withClips(List.of(shortened, flute)));
        Composition beyond = new Composition(c.meta().withGenre("Filmmusik"), time, sections, c.parts(), tracks, c.assets());
        assertEquals(List.of(), com.aresstack.audio.composition.CompositionValidator.validate(beyond));
        CompositionJson.save(piece, beyond);

        ProjectSession reopened = ProjectSession.open(piece).orElseThrow();
        assertEquals(beyond, reopened.composition());
        assertFalse(reopened.withinTsv());
        reopened.save(); // the TSV cannot say it, so it is left as it is, and the model stays
        assertEquals(beyond, ProjectSession.open(piece).orElseThrow().composition());

        // Even a newly written arrangement cannot replace such a model.
        List<String> lines = new ArrayList<>(Files.readAllLines(piece.resolve(ProjectFolder.ARRANGEMENT)));
        lines.add("note\tdrums\t15240\t120\t42\t60");
        Files.write(piece.resolve(ProjectFolder.ARRANGEMENT), lines, StandardCharsets.UTF_8);
        ProjectSession again = ProjectSession.open(piece).orElseThrow();
        try {
            again.synchronize();
            fail("a model beyond the TSV must not be replaced from it");
        } catch (IOException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("nicht übernommen"));
        }
        assertEquals(beyond, again.composition());
        assertEquals(beyond, CompositionJson.load(piece));
    }

    @Test
    public void aTakeOverThatCannotBeWrittenIsTakenOverByTheNextCall() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition before = session.composition();
        List<String> lines = new ArrayList<>(Files.readAllLines(piece.resolve(ProjectFolder.ARRANGEMENT)));
        lines.add("note\tdrums\t15240\t120\t42\t60");
        Files.write(piece.resolve(ProjectFolder.ARRANGEMENT), lines, StandardCharsets.UTF_8);
        // render/ cannot be written: a file stands where the folder belongs.
        Path render = piece.resolve(ProjectFolder.RENDER);
        try (Stream<Path> walk = Files.walk(render)) {
            for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
        Files.write(render, new byte[]{0});
        try {
            session.synchronize();
            fail("writing must fail");
        } catch (IOException expected) {
            // as intended
        }
        assertEquals("the session keeps what is on disk", before, session.composition());
        assertEquals(before, CompositionJson.load(piece));
        assertTrue(session.externalChanges());

        Files.delete(render);
        assertTrue("the next call takes the change over", session.synchronize());
        assertEquals(notes(before, "drums").size() + 1, notes(session.composition(), "drums").size());
        assertEquals(session.composition(), CompositionJson.load(piece));
        assertFalse(session.externalChanges());
    }

    @Test
    public void aNewOrRenamedPartKeepsTheMixOfTheOthers() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        com.aresstack.audio.composition.mix.MixScene mix = session.mix();
        List<com.aresstack.audio.composition.mix.MixChannel> louder = new ArrayList<>();
        for (com.aresstack.audio.composition.mix.MixChannel c : mix.channels()) {
            louder.add(new com.aresstack.audio.composition.mix.MixChannel(c.id(), c.owner(), c.x(), c.depth(),
                    c.owner().id().equals("harmony") ? 4 : c.levelDb(), c.width(), c.reverb(), c.muted(), c.locked()));
        }
        com.aresstack.audio.composition.mix.MixScene edited = new com.aresstack.audio.composition.mix.MixScene(
                new com.aresstack.audio.composition.mix.Room("cathedral", 4.5, 0.3), -6,
                com.aresstack.audio.composition.mix.LevelMode.FIXED, louder, mix.automation());
        MixJson.save(piece, edited);
        session = ProjectSession.open(piece).orElseThrow();

        // The composer adds a part, as the old pipeline writes it.
        List<String> lines = new ArrayList<>(Files.readAllLines(piece.resolve(ProjectFolder.ARRANGEMENT)));
        lines.add("track\tbells\t14\t3\ttexture");
        lines.add("note\tbells\t0\t480\t84\t60");
        Files.write(piece.resolve(ProjectFolder.ARRANGEMENT), lines, StandardCharsets.UTF_8);
        assertTrue(session.synchronize());
        com.aresstack.audio.composition.mix.MixScene after = session.mix();
        assertEquals(edited.room(), after.room());
        assertEquals(-6, after.masterDb(), 0);
        assertEquals(com.aresstack.audio.composition.mix.LevelMode.FIXED, after.levelMode());
        assertEquals(edited.channelOf(com.aresstack.audio.composition.mix.MixChannel.Owner.part("harmony")),
                after.channelOf(com.aresstack.audio.composition.mix.MixChannel.Owner.part("harmony")));
        assertEquals("the level curve of the melody stays", edited.automation(), after.automation());
        assertTrue(after.channelOf(com.aresstack.audio.composition.mix.MixChannel.Owner.part("bells")) != null);
        assertEquals(5, after.channels().size());

        // A renamed track: its channel and curve go with the old id, the others stay.
        StageMixService.rename(piece, "melody", "lead");
        assertTrue(session.synchronize());
        assertEquals(edited.channelOf(com.aresstack.audio.composition.mix.MixChannel.Owner.part("harmony")),
                session.mix().channelOf(com.aresstack.audio.composition.mix.MixChannel.Owner.part("harmony")));
        assertEquals(com.aresstack.audio.composition.mix.LevelMode.FIXED, session.mix().levelMode());
    }

    @Test
    public void aNewSoundBankInTracksJsonIsTakenOver() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Path tracks = piece.resolve(ProjectFolder.TRACKS);
        String json = new String(Files.readAllBytes(tracks), StandardCharsets.UTF_8)
                .replace("\"Music box\",\"program\":10,\"libraryId\":\"builtin\"",
                        "\"Celesta\",\"program\":10,\"libraryId\":\"generaluser::8\"");
        Files.write(tracks, json.getBytes(StandardCharsets.UTF_8));
        assertTrue(session.synchronize());
        assertEquals("generaluser::8", session.composition().part("melody").libraryId());
        assertEquals("Celesta", session.composition().part("melody").instrument());
        assertEquals(session.composition(), ProjectSession.open(piece).orElseThrow().composition());
    }

    private static Composition withVelocity(Composition composition, String trackId, int index, int velocity) {
        Track track = composition.track(trackId);
        MidiClip clip = (MidiClip) track.clips().get(0);
        List<Note> notes = new ArrayList<>(clip.notes());
        Note old = notes.get(index);
        notes.set(index, new Note(old.id(), old.tick(), old.length(), old.pitch(), velocity, old.voiceId()));
        List<Track> tracks = new ArrayList<>(composition.tracks());
        tracks.set(tracks.indexOf(track), track.withClips(List.of(clip.withNotes(notes))));
        return composition.withTracks(tracks);
    }

    private static List<Note> notes(Composition composition, String trackId) {
        return ((MidiClip) composition.track(trackId).clips().get(0)).notes();
    }
}
