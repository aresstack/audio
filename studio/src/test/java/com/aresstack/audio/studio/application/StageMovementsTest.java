package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.MixValidator;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.MixJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package D3 for the stage page: the curves of place and room share of a track are drawn in
 * bars and kept as lanes in ticks in {@code mix.json}, with the parameters x, depth and reverb.
 * Drawing one changes neither the notes nor a rendered sound, and it can be undone.
 */
public final class StageMovementsTest {
    private static final List<String> STEMS = List.of("melody-sampled.wav", "harmony-sampled.wav", "bass.wav",
            "drums.wav", "melody.wav", "arrangement-instruments.wav", "arrangement.mid");
    private static final long BAR = 1920;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path piece;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        piece = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, piece.resolve(file.getFileName()));
            }
        }
        for (String stem : STEMS) {
            Files.write(piece.resolve(stem), stem.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void aDrawnMovementIsKeptAsALaneInTheMixDocumentAndNothingElseChanges() throws Exception {
        List<String> log = new ArrayList<>();
        PieceModel model = new PieceModel(log::add);
        model.open(piece);
        byte[] composition = Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME));
        List<byte[]> sounds = new ArrayList<>();
        for (String stem : STEMS) {
            sounds.add(Files.readAllBytes(piece.resolve(stem)));
        }
        StageMovements still = model.movements();
        assertTrue(still.editable("melody"));
        assertFalse("a track the piece does not have", still.editable("choir"));
        assertFalse(still.moves("melody"));

        // The melody wanders from the left to the right over eight bars, and goes back at bar 3.
        StageMovements drawn = still
                .with("melody", AutomationLane.X, List.of(new StageMovements.Point(8, 0.8),
                        new StageMovements.Point(0, -0.8)))
                .with("melody", AutomationLane.DEPTH, List.of(new StageMovements.Point(2.5, 0.7)))
                .with("melody", AutomationLane.REVERB, List.of(new StageMovements.Point(4, 0.2),
                        new StageMovements.Point(4, 3)));
        assertTrue(model.move(drawn, "melody", AutomationLane.X));
        assertTrue(model.move(drawn, "melody", AutomationLane.DEPTH));
        assertTrue(model.move(drawn, "melody", AutomationLane.REVERB));

        MixScene mix = MixJson.load(piece);
        String channel = mix.channelOf(MixChannel.Owner.part("melody")).id();
        assertEquals("in order, in ticks", List.of(new AutomationLane.Point(0, -0.8),
                new AutomationLane.Point(8 * BAR, 0.8)), mix.lane(channel, "x").points());
        assertEquals(List.of(new AutomationLane.Point(2 * BAR + BAR / 2, 0.7)), mix.lane(channel, "depth").points());
        assertEquals("two points at one bar are a jump; a value stays within its range",
                List.of(new AutomationLane.Point(4 * BAR, 0.2), new AutomationLane.Point(4 * BAR, 1)),
                mix.lane(channel, "reverb").points());
        assertNotNull("the level curve of the old stage stays", mix.lane(channel, AutomationLane.LEVEL));
        assertEquals(List.of(), MixValidator.validate(mix));
        assertArrayEquals(composition, Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME)));
        for (int i = 0; i < STEMS.size(); i++) {
            assertArrayEquals(STEMS.get(i), sounds.get(i), Files.readAllBytes(piece.resolve(STEMS.get(i))));
        }

        // Opened again, the page shows what was drawn.
        PieceModel again = new PieceModel(log::add);
        again.open(piece);
        StageMovements read = again.movements();
        assertEquals(List.of(new StageMovements.Point(0, -0.8), new StageMovements.Point(8, 0.8)),
                read.of("melody", AutomationLane.X));
        assertEquals(List.of(new StageMovements.Point(2.5, 0.7)), read.of("melody", AutomationLane.DEPTH));
        assertTrue(read.moves("melody"));
        assertFalse(read.moves("bass"));

        // The curve taken away: the lane goes, the others stay; and the step can be undone.
        ProjectSession session = again.session().orElseThrow();
        String lane = session.mix().lane(channel, "x").id();
        assertTrue(again.move(read.with("melody", AutomationLane.X, List.of()), "melody", AutomationLane.X));
        assertNull(MixJson.load(piece).lane(channel, "x"));
        assertNotNull(MixJson.load(piece).lane(channel, "depth"));
        assertTrue(session.canUndo());
        session.undo();
        assertEquals(lane, MixJson.load(piece).lane(channel, "x").id());
        assertEquals(read.of("melody", AutomationLane.X), again.movements().of("melody", AutomationLane.X));
        assertFalse("the same curve again changes nothing", session.automate("melody", "x",
                read.ticks("melody", AutomationLane.X)));
        assertArrayEquals(composition, Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME)));
        assertEquals(List.of(), log.stream().filter(line -> line.startsWith("WARN: Der Verlauf")).toList());
    }

    @Test
    public void aMovementOfATrackWithoutAChannelIsRefusedWithAWarning() {
        List<String> log = new ArrayList<>();
        PieceModel model = new PieceModel(log::add);
        assertSame(StageMovements.NONE, model.movements());
        assertFalse(StageMovements.NONE.editable("melody"));
        assertSame("nothing can be drawn without a mix document", StageMovements.NONE,
                StageMovements.NONE.with("melody", AutomationLane.X, List.of(new StageMovements.Point(1, 0))));
        assertFalse(model.move(StageMovements.NONE, "melody", AutomationLane.X));
        model.open(piece);
        assertFalse(model.move(model.movements(), "choir", AutomationLane.X));
        assertEquals(2, log.stream().filter(line -> line.startsWith("WARN: Der Verlauf")).count());
    }

    @Test
    public void theLevelCurveHasOneSourceAndIsNotWrittenFromHere() throws Exception {
        // The level curve comes from the stage (dynamics in spatial-scene.json) into the level lane of
        // the model. Written here as well, the next change of the stage would overwrite it.
        List<String> log = new ArrayList<>();
        PieceModel model = new PieceModel(log::add);
        model.open(piece);
        byte[] before = Files.readAllBytes(piece.resolve(MixJson.FILE_NAME));
        assertFalse(StageMovements.PARAMETERS.contains(AutomationLane.LEVEL));
        StageMovements drawn = model.movements().with("melody", AutomationLane.LEVEL,
                List.of(new StageMovements.Point(1, -6)));
        assertEquals(List.of(), drawn.of("melody", AutomationLane.LEVEL));
        assertFalse(model.move(drawn, "melody", AutomationLane.LEVEL));
        assertArrayEquals(before, Files.readAllBytes(piece.resolve(MixJson.FILE_NAME)));
        assertEquals(1, log.stream().filter(line -> line.startsWith("WARN: Der Verlauf")).count());
    }

    @Test
    public void barsFollowTheMetersOfThePiece() {
        // Two bars of 4/4, then 3/4: bar 2.5 lies in the middle of the first bar of three quarters.
        TimeMap time = new TimeMap(List.of(new TimeMap.Tempo(0, 120)),
                List.of(new TimeMap.Meter(0, 4, 4), new TimeMap.Meter(2 * BAR, 3, 4)),
                List.of(new TimeMap.KeyChange(0, Key.C_MAJOR)));
        assertEquals(BAR + BAR / 4, StageMovements.tick(time, 1.25));
        assertEquals(2 * BAR + 720, StageMovements.tick(time, 2.5));
        assertEquals(2.5, StageMovements.bar(time, 2 * BAR + 720), 1e-12);
        assertEquals(3, StageMovements.bar(time, 2 * BAR + 1440), 1e-12);
        MixScene mix = new MixScene(new com.aresstack.audio.composition.mix.Room("hall", 2, 0.4), 0,
                com.aresstack.audio.composition.mix.LevelMode.LEGACY_PEAK,
                List.of(new MixChannel("c1", MixChannel.Owner.part("melody"), 0, 0.2, 0, 1, 0.3, false, false)),
                List.of(new AutomationLane("l1", "c1", AutomationLane.X,
                        List.of(new AutomationLane.Point(0, -1), new AutomationLane.Point(2 * BAR + 720, 1)))));
        StageMovements movements = StageMovements.of(mix, time);
        assertEquals(List.of(new StageMovements.Point(0, -1), new StageMovements.Point(2.5, 1)),
                movements.of("melody", AutomationLane.X));
        assertEquals(mix.lane("c1", AutomationLane.X).points(), movements.ticks("melody", AutomationLane.X));
    }
}
