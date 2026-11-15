package com.aresstack.audio.studio.golden;

import com.aresstack.audio.studio.application.MusicTrack;
import com.aresstack.audio.studio.application.StageMixService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * F0 safety net: how the places that read {@code arrangement.tsv} understand it today, for
 * well-formed pieces and for the mistakes the reference renderer refuses. These are pinned
 * before the readers are merged into one, so that the merge cannot change them unnoticed.
 */
public final class ArrangementReadingCharacterizationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theStageReadsTracksRolesAndProgramsInTheirWrittenOrder() throws Exception {
        StringBuilder text = new StringBuilder();
        for (String fixture : Golden.FIXTURES) {
            Path file = Golden.fixture(fixture);
            text.append(fixture).append(": tempo ").append(StageMixService.tempo(file)).append('\n');
            for (MusicTrack track : StageMixService.tracks(file)) {
                text.append("  ").append(track.id()).append(' ').append(track.role()).append(' ')
                        .append(track.program()).append('\n');
            }
        }
        Assert.assertEquals("demo-arrangement: tempo 108\n"
                + "  melody melody 10\n  harmony harmony 48\n  bass bass 32\n  drums drums 0\n"
                + "district-dawn: tempo 108\n"
                + "  melody melody 10\n  harmony harmony 48\n  bass bass 32\n  drums drums 0\n"
                + "edge-cases: tempo 96\n"
                + "  lead melody 73\n  pad harmony 89\n  double melody 71\n  low bass 33\n  kit drums 0\n",
                text.toString());
    }

    @Test
    public void aTrackWithoutRoleGetsItFromItsChannelOrNameAndAnOddProgramIsMadeValid() throws Exception {
        Path file = write("tempo\t500",
                "track\tbass\t33\t2",          // the name is a role
                "track\tbeat\t0\t9",           // channel 9 means drums
                "track\tstrings\t48\t1",       // anything else is harmony
                "track\tloud\t200\t3\tmelody", // programs are kept within 0..127
                "track\todd\tx\t4\tnoise",     // an unknown role and a program that is no number
                "note\tbass\t0\t480\t36\t90");
        List<MusicTrack> tracks = StageMixService.tracks(file);
        Assert.assertEquals(5, tracks.size());
        Assert.assertEquals("bass", tracks.get(0).role());
        Assert.assertEquals("drums", tracks.get(1).role());
        Assert.assertEquals("harmony", tracks.get(2).role());
        Assert.assertEquals("melody", tracks.get(3).role());
        Assert.assertEquals(127, tracks.get(3).program());
        Assert.assertEquals("harmony", tracks.get(4).role());
        Assert.assertEquals(0, tracks.get(4).program());
        Assert.assertEquals("tempo is kept within 20..400", 400, StageMixService.tempo(file));
        Assert.assertEquals(108, StageMixService.tempo(write("track\ta\t0\t0", "note\ta\t0\t1\t60\t90")));
        Assert.assertEquals(108, StageMixService.tempo(write("tempo\tfast", "track\ta\t0\t0")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anArrangementWithoutTracksHasNoStage() throws Exception {
        StageMixService.tracks(write("tempo\t108"));
    }

    @Test
    public void renamingATrackRewritesOnlyItsOwnLines() throws Exception {
        Path directory = folder.newFolder("piece").toPath();
        List<String> before = Arrays.asList("# keep me", "tempo\t108", "track\tlead\t73\t0\tmelody",
                "track\tleader\t1\t1", "note\tlead\t0\t480\t72\t90\t", "note\tleader\t0\t480\t60\t90",
                "", "something\tlead");
        Files.write(directory.resolve("arrangement.tsv"), before, StandardCharsets.UTF_8);
        Files.write(directory.resolve("lead.wav"), new byte[]{1});
        Files.write(directory.resolve(StageMixService.STEMS_FILE),
                Arrays.asList("lead\tlead.wav", "leader\tleader.wav"), StandardCharsets.UTF_8);

        StageMixService.rename(directory, "lead", "flute");

        Assert.assertEquals(Arrays.asList("# keep me", "tempo\t108", "track\tflute\t73\t0\tmelody",
                "track\tleader\t1\t1", "note\tflute\t0\t480\t72\t90\t", "note\tleader\t0\t480\t60\t90",
                "", "something\tlead"), Files.readAllLines(directory.resolve("arrangement.tsv"), StandardCharsets.UTF_8));
        Assert.assertTrue(Files.isRegularFile(directory.resolve("flute.wav")));
        Assert.assertEquals(Arrays.asList("flute\tflute.wav", "leader\tleader.wav"),
                Files.readAllLines(directory.resolve(StageMixService.STEMS_FILE), StandardCharsets.UTF_8));
    }

    @Test
    public void theReferenceRendererRefusesWhatItCannotPlay() throws Exception {
        String track = "track\ta\t0\t0";
        String note = "note\ta\t0\t480\t60\t90";
        refuses("Invalid or duplicate track ID: a", "tempo\t108", track, track, note);
        refuses("Invalid or duplicate track ID: Lead", "tempo\t108", "track\tLead\t0\t0", note);
        refuses("Unknown part: b", "tempo\t108", track, "note\tb\t0\t480\t60\t90");
        refuses("Unknown part: a", "tempo\t108", note, track);
        refuses("Invalid note: note\ta\t15000\t480\t60\t90", "tempo\t108", track, "note\ta\t15000\t480\t60\t90");
        refuses("Invalid note: note\ta\t0\t480\t60\t0", "tempo\t108", track, "note\ta\t0\t480\t60\t0");
        refuses("Invalid input line: chord\ta\tC", "tempo\t108", track, "chord\ta\tC", note);
        refuses("Invalid input line: track\ta\t0", "tempo\t108", "track\ta\t0", note);
        refuses("Invalid input line: note\ta\t0\t480\t60", "tempo\t108", track, "note\ta\t0\t480\t60");
        refuses("Invalid input line: note\ta\t0\t480\t60\t90\t1", "tempo\t108", track, "note\ta\t0\t480\t60\t90\t1");
        refuses("Require tempo 60..180 and at least one track.", "tempo\t200", track, note);
        refuses("Require tempo 60..180 and at least one track.", track, note);
        refuses("Require tempo 60..180 and at least one track.", "tempo\t108");
        refuses("Invalid or empty track: a", "tempo\t108", track);
        refuses("Invalid or empty track: a", "tempo\t108", "track\ta\t0\t9", "note\ta\t0\t480\t36\t90");
        refuses("Invalid or empty track: k", "tempo\t108", "track\tk\t0\t9\tdrums", track, note);
    }

    private void refuses(String message, String... lines) throws Exception {
        Path output = folder.newFolder().toPath();
        try {
            Golden.renderReference(write(lines), output);
            Assert.fail("expected the renderer to refuse: " + message);
        } catch (IllegalArgumentException refused) {
            Assert.assertEquals(message, refused.getMessage());
        }
    }

    private Path write(String... lines) throws Exception {
        Path file = folder.newFile().toPath();
        Files.write(file, Arrays.asList(lines), StandardCharsets.UTF_8);
        return file;
    }
}
