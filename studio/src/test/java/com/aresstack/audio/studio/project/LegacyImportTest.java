package com.aresstack.audio.studio.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.golden.Golden;
import com.aresstack.audio.studio.score.Score;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package F1, import of old pieces (architecture review 5.2) and the exports: every old
 * piece loads, and arrangement.tsv and arrangement.mid come out as before.
 */
public final class LegacyImportTest {
    private static final Path LEGACY = Paths.get("src", "test", "resources", "legacy");

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** Every arrangement the repository has: the golden fixtures, the composer's output, the examples, the old folders. */
    private static List<Path> arrangements() throws Exception {
        List<Path> files = new ArrayList<>();
        for (String fixture : Golden.FIXTURES) {
            files.add(Golden.fixture(fixture));
        }
        files.add(Golden.root().resolve("compose").resolve("offline-fixture.tsv"));
        files.add(Paths.get("src", "main", "resources", "district-dawn.tsv"));
        try (Stream<Path> examples = Files.list(Paths.get("..", "music", "examples"))) {
            examples.filter(file -> file.toString().endsWith(".tsv")).sorted().forEach(files::add);
        }
        try (Stream<Path> folders = Files.list(LEGACY)) {
            folders.sorted().forEach(folder -> files.add(folder.resolve(ProjectFolder.ARRANGEMENT)));
        }
        return files;
    }

    /** A folder with only this arrangement in it, as the oldest pieces are. */
    private Path pieceOf(Path arrangement) throws Exception {
        Path folder = temporary.newFolder().toPath();
        Files.copy(arrangement, folder.resolve(ProjectFolder.ARRANGEMENT));
        return folder;
    }

    private static LegacyImport.Result imported(Path folder) throws Exception {
        return LegacyImport.read(folder).orElseThrow();
    }

    @Test
    public void everyOldPieceLoadsIntoASoundModel() throws Exception {
        for (Path arrangement : arrangements()) {
            Path folder = arrangement.getParent().startsWith(LEGACY) ? arrangement.getParent() : pieceOf(arrangement);
            LegacyImport.Result result = imported(folder);
            Composition composition = result.composition();
            assertEquals(arrangement + ": " + CompositionValidator.validate(composition), List.of(),
                    CompositionValidator.validate(composition));
            assertFalse(arrangement.toString(), composition.parts().isEmpty());
            assertEquals("one track per part", composition.parts().size(), composition.tracks().size());
            assertEquals("one section over the whole piece", 1, composition.sections().size());
            assertEquals(composition.endTick(), composition.sections().get(0).endTick());
            assertTrue(composition.timeMap().constant());
            assertEquals(LevelMode.LEGACY_PEAK, result.mix().levelMode());
            assertEquals("a channel for every part", composition.parts().size(), result.mix().channels().size());
        }
    }

    @Test
    public void theTsvRoundTripIsLineIdentical() throws Exception {
        int identical = 0;
        for (Path arrangement : arrangements()) {
            if (arrangement.getParent().endsWith("damaged")) {
                continue; // not a piece the old studio could play: what the import left out is reported instead
            }
            ArrangementFile original = ArrangementFile.read(arrangement);
            Composition composition = imported(pieceOf(arrangement)).composition();
            List<String> exported = TsvExport.lines(composition);
            assertEquals(arrangement.toString(), TsvExport.canonical(original), exported);
            // A file as the studio writes it comes back byte for byte.
            List<String> raw = Files.readAllLines(arrangement, StandardCharsets.UTF_8);
            if (raw.equals(TsvExport.canonical(original))) {
                assertEquals(arrangement.toString(), raw, exported);
                identical++;
            }
            // And the exported file gives a model that exports the same lines again.
            Path again = temporary.newFolder().toPath();
            Files.write(again.resolve(ProjectFolder.ARRANGEMENT), exported, StandardCharsets.UTF_8);
            assertEquals(arrangement.toString(), exported, TsvExport.lines(imported(again).composition()));
        }
        assertTrue("files the studio wrote come back byte for byte: " + identical, identical >= 4);
    }

    @Test
    public void theMidiExportWritesTheBytesOfTheReferenceRenderer() throws Exception {
        for (String fixture : Golden.FIXTURES) {
            String expected = null;
            for (String line : Files.readAllLines(Golden.root().resolve("render").resolve(fixture + ".sha256"))) {
                if (line.startsWith("arrangement.mid\t")) {
                    expected = line.substring(line.indexOf('\t') + 1);
                }
            }
            Composition composition = imported(pieceOf(Golden.fixture(fixture))).composition();
            assertEquals(fixture, expected, Golden.sha256(MidiExport.bytes(composition)));
        }
    }

    @Test
    public void theFilesOfTheModelReadBackAsTheyWereWritten() throws Exception {
        for (Path arrangement : arrangements()) {
            Path folder = arrangement.getParent().startsWith(LEGACY) ? arrangement.getParent() : pieceOf(arrangement);
            LegacyImport.Result result = imported(folder);
            assertEquals(result.composition(), CompositionJson.read(
                    com.google.gson.JsonParser.parseString(JsonText.write(CompositionJson.write(result.composition())))
                            .getAsJsonObject()));
            assertEquals(result.mix(), MixJson.read(com.google.gson.JsonParser.parseString(
                    JsonText.write(MixJson.write(result.mix()))).getAsJsonObject()));
        }
    }

    @Test
    public void theSameOldPieceAlwaysGivesTheSameModel() throws Exception {
        Path folder = LEGACY.resolve("studio-piece");
        assertEquals(imported(folder), imported(folder));
    }

    @Test
    public void aPieceOfTheStudioBringsAllItsFilesIntoTheModel() throws Exception {
        Path folder = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(LEGACY.resolve("studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        for (String stem : List.of("melody-sampled.wav", "harmony-sampled.wav", "bass.wav", "drums.wav", "melody.wav")) {
            Files.write(folder.resolve(stem), new byte[]{1, 2, 3});
        }
        LegacyImport.Result result = imported(folder);
        Composition composition = result.composition();

        assertEquals("Morgen im Viertel", composition.meta().title());
        assertEquals("warm, hopeful", composition.meta().mood());
        assertEquals("Ein Viertel erwacht, warm und hoffnungsvoll", composition.meta().brief());
        assertEquals("Question and answer between melody and strings.", composition.meta().remarks());
        Section whole = composition.sections().get(0);
        assertEquals(List.of("C", "G", "Am", "F", "C", "G", "F", "G"), whole.harmony());
        assertEquals(8 * 1920, whole.lengthTicks());
        assertEquals(108, composition.timeMap().tempoAt(0), 0);

        Part harmony = composition.part("harmony");
        assertEquals("String ensemble", harmony.instrument());
        assertEquals("generaluser::48", harmony.libraryId());
        assertEquals(48, harmony.program());
        assertEquals("harmony", harmony.role());
        assertNull("the studio's own channels are derived", harmony.legacyChannel());
        assertEquals("harmony", composition.track("harmony").defaultPartId());

        MixScene mix = result.mix();
        assertEquals("chamber", mix.room().kind());
        assertEquals(-3, mix.masterDb(), 0);
        MixChannel melody = mix.channelOf(MixChannel.Owner.part("melody"));
        assertTrue(melody.locked());
        assertTrue(mix.channelOf(MixChannel.Owner.part("bass")).muted());
        assertEquals("the drums get the usual seat", -3, mix.channelOf(MixChannel.Owner.part("drums")).levelDb(), 0);
        assertEquals(1, mix.automation().size());
        AutomationLane lane = mix.automation().get(0);
        assertEquals(melody.id(), lane.channelId());
        assertEquals(List.of(new AutomationLane.Point(0, -6), new AutomationLane.Point(4 * 1920 + 960, 0)), lane.points());
        assertTrue(result.warnings().toString(), result.warnings().stream().anyMatch(w -> w.contains("retired")));

        List<String> stems = result.stems().stream().map(e -> e.partId() + ":" + e.file() + ":" + e.origin()).toList();
        assertEquals(List.of("melody:melody-sampled.wav:stems.tsv", "melody:melody.wav:reference",
                "harmony:harmony-sampled.wav:stems.tsv", "bass:bass.wav:stems.tsv", "drums:drums.wav:stems.tsv"), stems);
        assertTrue(result.stems().stream().allMatch(e -> StemManifest.ADOPTED.equals(e.state())));
        assertEquals(result.stems(), StemManifest.current(result.stems(), composition));
    }

    @Test
    public void channelsAndRolesOfHandWrittenPiecesAreKept() throws Exception {
        Composition edge = imported(pieceOf(Golden.fixture("edge-cases"))).composition();
        assertNull(edge.part("lead").legacyChannel());
        assertEquals("shares channel 0 with lead", Integer.valueOf(0), edge.part("double").legacyChannel());
        assertEquals(Integer.valueOf(2), edge.part("low").legacyChannel());
        assertEquals(96, edge.timeMap().tempoAt(0), 0);
        Composition demo = imported(pieceOf(Golden.fixture("demo-arrangement"))).composition();
        assertNull("the demo names no roles", demo.part("drums").role());
        assertNull("drums by their id, as the old format has it", demo.part("drums").legacyChannel());
    }

    @Test
    public void theVoicesOfTheScoreAreKeptAsTheVoiceOfEachNote() throws Exception {
        Path folder = LEGACY.resolve("two-voices");
        Composition composition = imported(folder).composition();
        Part piano = composition.part("piano");
        assertEquals(2, piano.voices().size());
        MidiClip clip = (MidiClip) composition.track("piano").clips().get(0);
        List<String> lower = clip.notes().stream().filter(n -> n.voiceId().equals(piano.voices().get(1).id()))
                .map(n -> n.tick() + ":" + n.pitch()).toList();
        assertEquals("the held note is the lower voice", List.of("0:60"), lower);
        Score score = Score.read(folder.resolve(ProjectFolder.ARRANGEMENT));
        assertEquals("the score writes it the same way", 2, score.staves().get(0).voices());
        // Pieces whose staves have one voice get one voice.
        for (String fixture : Golden.FIXTURES) {
            for (Part part : imported(pieceOf(Golden.fixture(fixture))).composition().parts()) {
                assertEquals(fixture + " " + part.id(), 1, part.voices().size());
            }
        }
    }

    @Test
    public void whatCannotBeTakenOverIsLeftOutAndReported() throws Exception {
        LegacyImport.Result result = imported(LEGACY.resolve("damaged"));
        Composition composition = result.composition();
        assertEquals(List.of("lead", "odd"), composition.parts().stream().map(Part::id).toList());
        assertEquals(108, composition.timeMap().tempoAt(0), 0);
        assertEquals(0, composition.part("odd").program());
        assertEquals(1, composition.notesOf("lead").size());
        assertEquals(1, composition.notesOf("odd").size());
        String all = String.join("\n", result.warnings());
        for (String expected : List.of("kein lesbares Tempo", "doppelt", "ungültiger Kennung", "unbekannte Rolle",
                "kein General-MIDI-Programm", "unbekannten Spur", "ungültige Note", "unknown kind of line",
                "tracks.json nicht lesbar", "spatial-scene.json nicht lesbar")) {
            assertTrue(expected + " in\n" + all, all.contains(expected));
        }
    }

    @Test
    public void aFolderWithoutArrangementIsNoPieceYet() throws Exception {
        assertTrue(LegacyImport.read(temporary.newFolder().toPath()).isEmpty());
    }

    @Test(expected = TsvExport.NotExpressible.class)
    public void aTempoChangeCannotBeWrittenAsTsv() throws Exception {
        Composition composition = imported(pieceOf(Golden.fixture("edge-cases"))).composition();
        TimeMap changing = new TimeMap(List.of(new TimeMap.Tempo(0, 96), new TimeMap.Tempo(1920, 120)),
                composition.timeMap().meters(), composition.timeMap().keys());
        TsvExport.lines(new Composition(composition.meta(), changing, composition.sections(), composition.parts(),
                composition.tracks(), composition.assets()));
    }
}
