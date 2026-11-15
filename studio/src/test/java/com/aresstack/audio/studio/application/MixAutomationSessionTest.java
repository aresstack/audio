package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.MixValidator;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.project.ProjectFolder;
import com.aresstack.audio.studio.project.StemManifest;
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
 * Work package D3 in the folder of a piece: lanes of place and share of the room live in
 * {@code mix.json} beside the level curve. Changing them touches neither the composition nor a
 * stem (A12, extended to every kind of automation), and a new stage file from the old pipeline,
 * which knows level curves only, does not take them away.
 */
public final class MixAutomationSessionTest {
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

    /** The mix of the piece with the melody moving from left to right and into the room. */
    private static MixScene moving(MixScene mix) {
        MixChannel melody = mix.channelOf(MixChannel.Owner.part("melody"));
        List<AutomationLane> lanes = new ArrayList<>(mix.automation());
        lanes.add(new AutomationLane("melody-x", melody.id(), AutomationLane.X,
                List.of(new AutomationLane.Point(0, -0.8), new AutomationLane.Point(8 * BAR, 0.8))));
        lanes.add(new AutomationLane("melody-depth", melody.id(), AutomationLane.DEPTH,
                List.of(new AutomationLane.Point(2 * BAR, 0.1), new AutomationLane.Point(6 * BAR, 0.7))));
        lanes.add(new AutomationLane("melody-room", melody.id(), AutomationLane.REVERB,
                List.of(new AutomationLane.Point(4 * BAR, 0.2), new AutomationLane.Point(4 * BAR, 0.6))));
        return new MixScene(mix.room(), mix.masterDb(), mix.levelMode(), mix.channels(), lanes);
    }

    @Test
    public void movingASourceChangesNeitherTheCompositionNorAStem() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        byte[] composition = Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME));
        List<StemManifest.Entry> stems = StemManifest.load(piece);
        List<byte[]> sounds = new ArrayList<>();
        for (String stem : STEMS) {
            sounds.add(Files.readAllBytes(piece.resolve(stem)));
        }

        MixScene moved = moving(session.mix());
        assertEquals(List.of(), MixValidator.validate(moved));
        MixJson.save(piece, moved);
        ProjectSession reopened = ProjectSession.open(piece).orElseThrow();
        reopened.save();

        assertEquals(moved, reopened.mix());
        assertEquals(moved, MixJson.load(piece));
        assertFalse(reopened.synchronize());
        assertArrayEquals(composition, Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME)));
        assertEquals(stems, StemManifest.load(piece));
        assertEquals("no stem has to be rendered again", stems,
                StemManifest.current(StemManifest.load(piece), reopened.composition()));
        for (int i = 0; i < STEMS.size(); i++) {
            assertArrayEquals(STEMS.get(i), sounds.get(i), Files.readAllBytes(piece.resolve(STEMS.get(i))));
        }
    }

    @Test
    public void aNewStageFileTakesTheLevelCurveButKeepsPlaceAndRoomShare() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        MixScene moved = moving(session.mix());
        MixJson.save(piece, moved);
        session = ProjectSession.open(piece).orElseThrow();
        String melody = moved.channelOf(MixChannel.Owner.part("melody")).id();
        AutomationLane before = moved.lane(melody, AutomationLane.LEVEL);
        assertNotNull("the old stage has a level curve for the melody", before);

        String scene = new String(Files.readAllBytes(piece.resolve(ProjectFolder.SCENE)), StandardCharsets.UTF_8)
                .replace("{\"bar\":4.5,\"db\":0.0}", "{\"bar\":6.0,\"db\":2.0}");
        Files.write(piece.resolve(ProjectFolder.SCENE), scene.getBytes(StandardCharsets.UTF_8));
        assertTrue(session.synchronize());

        MixScene mix = session.mix();
        AutomationLane level = mix.lane(melody, AutomationLane.LEVEL);
        assertNotEquals(before.points(), level.points());
        assertEquals(new AutomationLane.Point(6 * BAR, 2), level.points().get(1));
        assertEquals(moved.lane(melody, AutomationLane.X), mix.lane(melody, AutomationLane.X));
        assertEquals(moved.lane(melody, AutomationLane.DEPTH), mix.lane(melody, AutomationLane.DEPTH));
        assertEquals(moved.lane(melody, AutomationLane.REVERB), mix.lane(melody, AutomationLane.REVERB));
        assertEquals(List.of(), MixValidator.validate(mix));
    }
}
