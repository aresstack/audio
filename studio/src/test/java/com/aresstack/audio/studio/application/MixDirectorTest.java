package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.aresstack.audio.studio.stage.SpatialScene;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** What the AI is told before it mixes, and what is made of a proposal that comes with a stage. */
public final class MixDirectorTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void tellsTheAiWhatEachTrackPlaysAndWhatIsFixed() throws Exception {
        Path song = folder.newFolder().toPath();
        Path arrangement = song.resolve("arrangement.tsv");
        Files.write(arrangement, Arrays.asList("tempo\t96",
                "track\tflute\t73\t0\tmelody", "note\tflute\t0\t480\t72\t90", "note\tflute\t3840\t480\t84\t70",
                "track\tbass\t32\t1\tbass", "note\tbass\t0\t1920\t36\t100"), StandardCharsets.UTF_8);
        SpatialScene scene = SpatialScene.standard(StageMixService.tracks(arrangement));
        scene = scene.with(scene.placement("bass").at(0.25, 0.5).withLocked(true));

        String prompt = MixDirector.prompt("A quiet morning", StageMixService.tracks(arrangement), arrangement, scene);

        Assert.assertTrue(prompt.contains("A quiet morning"));
        Assert.assertTrue(prompt.contains("96 BPM"));
        Assert.assertTrue(prompt.contains("flute (melody"));
        Assert.assertTrue("how many notes, how high, how hard, and in which bars", prompt.contains(
                "2 notes, pitches 72-84, mean velocity 80, notes per bar [1, 0, 1]"));
        Assert.assertTrue(prompt.contains("FIXED by the user at x=0.25"));
        Assert.assertFalse(prompt.substring(prompt.indexOf("- flute"), prompt.indexOf("- bass")).contains("FIXED"));
        Assert.assertTrue(prompt.contains("'dynamics'"));
        Assert.assertTrue(prompt.contains("'stage'"));
    }

    @Test
    public void theMixIsSeenOnTheStageWhileItArrives() throws Exception {
        Path song = folder.newFolder().toPath();
        Path arrangement = song.resolve("arrangement.tsv");
        Files.write(arrangement, Arrays.asList("tempo\t96",
                "track\tflute\t73\t0\tmelody", "note\tflute\t0\t480\t72\t90",
                "track\tbass\t32\t1\tbass", "note\tbass\t0\t1920\t36\t100"), StandardCharsets.UTF_8);
        java.util.List<MusicTrack> tracks = StageMixService.tracks(arrangement);
        SpatialScene scene = SpatialScene.standard(tracks);
        String answer = "{\"room\":\"cathedral\",\"stage\":[{\"track\":\"flute\",\"x\":-0.5,\"depth\":0.2,\"level\":0,"
                + "\"width\":0.3,\"reverb\":0.4},{\"track\":\"bass\",\"x\":0.75,\"depth\":0.6,\"level\":-4,\"width\":0.2,"
                + "\"reverb\":0.1}]}";

        Assert.assertNull(MixDirector.seenMix("", scene, tracks));
        String firstOnly = answer.substring(0, answer.indexOf("\"depth\":0.6"));
        SpatialScene seen = MixDirector.seenMix(firstOnly, scene, tracks);
        Assert.assertEquals("the flute has its place", -0.5, seen.placement("flute").x(), 1e-9);
        Assert.assertEquals("the bass, still being written, stands where it stood",
                scene.placement("bass").x(), seen.placement("bass").x(), 1e-9);
        Assert.assertEquals("Kathedrale", seen.room().name());
        SpatialScene whole = MixDirector.seenMix(answer, scene, tracks);
        Assert.assertEquals(0.75, whole.placement("bass").x(), 1e-9);
        for (int cut = 0; cut <= answer.length(); cut++) {
            MixDirector.seenMix(answer.substring(0, cut), scene, tracks); // never throws
        }
    }

    @Test
    public void anInstrumentProposalBringsItsStageAndOneWithoutGetsTheUsualSeating() throws Exception {
        KipitzChatClient offline = new KipitzChatClient(
                "http://127.0.0.1:1/v1/chat/completions", "", "local", "", 8080)
                .legacyModel("local"); // the kept answers below were written for this model
        Path staged = folder.newFolder().toPath();
        Files.write(staged.resolve("instrument-proposal.json"), ("{\"tracks\":["
                + "{\"id\":\"clarinet\",\"role\":\"melody\",\"instrument\":\"Warm clarinet\",\"program\":71},"
                + "{\"id\":\"harp\",\"role\":\"harmony\",\"instrument\":\"Concert harp\",\"program\":46}],"
                + "\"room\":\"chamber\",\"stage\":["
                + "{\"track\":\"clarinet\",\"x\":0.2,\"depth\":0.1,\"level\":0,\"width\":0.3,\"reverb\":0.4},"
                + "{\"track\":\"ghost\",\"x\":9}]}").getBytes(StandardCharsets.UTF_8));

        InstrumentProposalService.Staged proposal = new InstrumentProposalService(offline, 4096)
                .proposeStaged("Forest at dusk", staged, message -> { });

        Assert.assertEquals(2, proposal.tracks().tracks().size());
        Assert.assertEquals("chamber", proposal.scene().room().kind());
        Assert.assertEquals(0.2, proposal.scene().placement("clarinet").x(), 1e-9);
        Assert.assertNotNull("a track the AI forgot to place gets the usual seat", proposal.scene().placement("harp"));
        Assert.assertNull("a place for a track that does not exist is dropped", proposal.scene().placement("ghost"));

        Path plain = folder.newFolder().toPath();
        Files.write(plain.resolve("instrument-proposal.json"), ("{\"tracks\":["
                + "{\"id\":\"clarinet\",\"role\":\"melody\",\"instrument\":\"Warm clarinet\",\"program\":71}]}")
                .getBytes(StandardCharsets.UTF_8));
        InstrumentProposalService.Staged old = new InstrumentProposalService(offline, 4096)
                .proposeStaged("Forest at dusk", plain, message -> { });
        Assert.assertEquals("hall", old.scene().room().kind());
        Assert.assertEquals(0, old.scene().placement("clarinet").x(), 0);
    }
}
