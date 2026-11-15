package com.aresstack.audio.studio.stage;

import com.aresstack.audio.studio.application.MusicTrack;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The stage as data: the usual seating, what stays when tracks change, and how it is kept and read. */
public final class SpatialSceneTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aPieceWithoutAStageGetsTheUsualSeating() {
        SpatialScene scene = SpatialScene.standard(MusicTrack.defaults());

        Assert.assertEquals(4, scene.placements().size());
        Assert.assertEquals("hall", scene.room().kind());
        Assert.assertEquals("the melody stands in front, in the middle", 0, scene.placement("melody").x(), 0);
        Assert.assertTrue(scene.placement("melody").depth() < scene.placement("harmony").depth());
        Assert.assertTrue(Math.abs(scene.placement("bass").x()) < 0.3);
        Assert.assertTrue(Math.abs(scene.placement("drums").x()) < 0.3);
        Assert.assertTrue("the lead is the loudest",
                scene.placement("melody").levelDb() > scene.placement("harmony").levelDb());
        // Two tracks of one role do not sit on top of each other.
        SpatialScene two = SpatialScene.standard(Arrays.asList(
                new MusicTrack("flute", MusicTrack.MELODY, "Flute", 73, "builtin"),
                new MusicTrack("oboe", MusicTrack.MELODY, "Oboe", 68, "builtin")));
        Assert.assertNotEquals(two.placement("flute").x(), two.placement("oboe").x(), 0.1);
    }

    @Test
    public void changingTheTracksKeepsWhatIsPlacedAndSeatsWhatIsNew() {
        SpatialScene scene = SpatialScene.standard(MusicTrack.defaults())
                .with(new SoundSourcePlacement("melody", -0.7, 0.9, -5, 0.2, 0.8, false, true, null));
        List<MusicTrack> tracks = new ArrayList<MusicTrack>(MusicTrack.defaults().subList(0, 2));
        tracks.add(new MusicTrack("choir", MusicTrack.TEXTURE, "Choir", 52, "builtin"));

        SpatialScene fitted = scene.fitting(tracks);

        Assert.assertEquals(3, fitted.placements().size());
        Assert.assertEquals(-0.7, fitted.placement("melody").x(), 0);
        Assert.assertNotNull(fitted.placement("choir"));
        Assert.assertNull("a track that is gone leaves the stage", fitted.placement("drums"));
    }

    @Test
    public void aProposalIsTakenButForWhatIsProtected() {
        SpatialScene scene = SpatialScene.standard(MusicTrack.defaults());
        scene = scene.with(scene.placement("melody").at(-0.9, 0.1).withLocked(true))
                .with(scene.placement("bass").withMuted(true));
        SpatialScene proposal = new SpatialScene(AcousticRoom.CATHEDRAL, Arrays.asList(
                new SoundSourcePlacement("melody", 0.5, 0.5), new SoundSourcePlacement("bass", 0.4, 0.4),
                new SoundSourcePlacement("stranger", 0, 0)));

        SpatialScene taken = scene.taking(proposal);

        Assert.assertEquals("protected: stays where the user put it", -0.9, taken.placement("melody").x(), 0);
        Assert.assertEquals(0.4, taken.placement("bass").x(), 0);
        Assert.assertTrue("muting is the user's, not the proposal's", taken.placement("bass").muted());
        Assert.assertEquals("cathedral", taken.room().kind());
        Assert.assertNull(taken.placement("stranger"));
        Assert.assertEquals("a track the proposal forgot stays", scene.placement("drums").x(),
                taken.placement("drums").x(), 0);
    }

    @Test
    public void theStageIsKeptWithThePieceAndReadBackAsItWas() throws Exception {
        Path song = folder.newFolder("song").toPath();
        SpatialSceneStore store = new SpatialSceneStore();
        Assert.assertNull("a piece from before the stage has no file", store.load(song));

        SpatialScene scene = new SpatialScene(AcousticRoom.CHAMBER.withReverbSeconds(1.4).withAmount(0.25),
                Arrays.asList(new SoundSourcePlacement("melody", -0.25, 0.1, -3, 0.5, 0.6, true, true,
                        Arrays.asList(new SoundSourcePlacement.LevelPoint(4, 2),
                                new SoundSourcePlacement.LevelPoint(0, -6)))));
        store.save(song, scene);
        SpatialScene read = store.load(song);

        Assert.assertTrue(Files.isRegularFile(song.resolve("spatial-scene.json")));
        JsonObject written = new JsonParser().parse(
                new String(Files.readAllBytes(song.resolve("spatial-scene.json")), "UTF-8")).getAsJsonObject();
        Assert.assertEquals(1, written.get("version").getAsInt());
        Assert.assertEquals("chamber", read.room().kind());
        Assert.assertEquals(1.4, read.room().reverbSeconds(), 1e-9);
        Assert.assertEquals(0.25, read.room().amount(), 1e-9);
        SoundSourcePlacement melody = read.placement("melody");
        Assert.assertEquals(-0.25, melody.x(), 1e-9);
        Assert.assertEquals(0.1, melody.depth(), 1e-9);
        Assert.assertEquals(-3, melody.levelDb(), 1e-9);
        Assert.assertEquals(0.5, melody.width(), 1e-9);
        Assert.assertEquals(0.6, melody.reverb(), 1e-9);
        Assert.assertTrue(melody.muted() && melody.locked());
        Assert.assertEquals("the points are kept in the order of the bars", 0, melody.dynamics().get(0).bar(), 0);
        Assert.assertEquals(-2, melody.dynamicsAt(2), 1e-9);
        Assert.assertEquals(2, melody.dynamicsAt(99), 1e-9);
    }

    @Test
    public void whatTheAiWritesIsBroughtIntoRange() {
        JsonObject wild = new JsonParser().parse("{\"room\":\"spaceship\",\"stage\":["
                + "{\"track\":\"melody\",\"x\":-7,\"depth\":3,\"level\":40,\"width\":-2,\"reverb\":9,"
                + "\"dynamics\":[{\"bar\":-3,\"db\":90},{\"bar\":\"soon\"},\"loud\"]},"
                + "{\"x\":0.5},{\"track\":\"bass\",\"x\":\"left\",\"muted\":\"perhaps\"},42]}").getAsJsonObject();

        SpatialScene read = SpatialSceneStore.read(wild);

        Assert.assertEquals("an unknown room is the concert hall", "hall", read.room().kind());
        Assert.assertEquals(2, read.placements().size());
        SoundSourcePlacement melody = read.placement("melody");
        Assert.assertEquals(-1, melody.x(), 0);
        Assert.assertEquals(1, melody.depth(), 0);
        Assert.assertEquals(SoundSourcePlacement.MAX_LEVEL, melody.levelDb(), 0);
        Assert.assertEquals(0, melody.width(), 0);
        Assert.assertEquals(1, melody.reverb(), 0);
        Assert.assertEquals(2, melody.dynamics().size());
        Assert.assertEquals(6, melody.dynamics().get(0).db(), 0);
        Assert.assertEquals("a word where a number belongs: the usual value", 0, read.placement("bass").x(), 0);
        Assert.assertFalse(read.placement("bass").muted());
    }

    @Test
    public void heightIsKeptAndOnlyWrittenWhenAStageHasIt() {
        SoundSourcePlacement low = new SoundSourcePlacement("a", 0.2, 0.4);
        SoundSourcePlacement high = low.named("b").atHeight(0.75);
        SpatialScene scene = new SpatialScene(AcousticRoom.HALL, java.util.List.of(low, high));
        com.google.gson.JsonObject json = SpatialSceneStore.write(scene);
        SpatialScene read = SpatialSceneStore.read(json);
        Assert.assertEquals(0, read.placement("a").height(), 0);
        Assert.assertEquals(0.75, read.placement("b").height(), 1e-9);
        Assert.assertEquals(0.75, read.placement("b").at(0.1, 0.1).height(), 1e-9);
        // Only the high source writes a height.
        Assert.assertEquals(1, json.toString().split("\"height\"", -1).length - 1);
        Assert.assertEquals(1, high.atHeight(3).height(), 0);
    }
}
