package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
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
        Composition after = ProjectSession.open(piece).orElseThrow().composition();
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

    private static List<Note> notes(Composition composition, String trackId) {
        return ((MidiClip) composition.track(trackId).clips().get(0)).notes();
    }
}
