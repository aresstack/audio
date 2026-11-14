package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Moving something on the stage only mixes the rendered tracks again: no AI, no synthesizer. */
public final class StageMixServiceTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aStageChangeIsMixedFromTheRenderedTracksAlone() throws Exception {
        // All there is of the piece: its written arrangement and the sound of its two tracks.
        // No MIDI file, no sound bank, no answer of the AI and no way to ask it.
        Path song = piece("tempo\t120", "track\tmelody\t73\t0\tmelody", "track\tbass\t32\t1\tbass");
        StageMixService service = new StageMixService();
        SpatialSceneStore store = new SpatialSceneStore();

        Assert.assertNull("a piece from before the stage", store.load(song));
        Path mix = service.mix(song.resolve("arrangement.tsv"), song, message -> { });
        SpatialStemMixer.Signal usual = SpatialStemMixer.read(mix);
        Assert.assertEquals("the usual seating puts the melody in the middle", rms(usual.left), rms(usual.right),
                rms(usual.left) * 0.15);

        // The melody is moved far to the left and the bass is muted: only mixing runs again.
        SpatialScene moved = service.scene(song.resolve("arrangement.tsv"), song);
        moved = moved.with(moved.placement("melody").at(-1, 0)).with(moved.placement("bass").withMuted(true));
        store.save(song, moved);
        long before = Files.getLastModifiedTime(song.resolve("melody.wav")).toMillis();
        SpatialStemMixer.Signal left = SpatialStemMixer.read(service.mix(song.resolve("arrangement.tsv"), song,
                message -> { }));

        Assert.assertTrue(rms(left.left) > 4 * rms(left.right));
        Assert.assertEquals("the tracks themselves are not touched", before,
                Files.getLastModifiedTime(song.resolve("melody.wav")).toMillis());
        try (java.util.stream.Stream<Path> files = Files.list(song)) {
            Assert.assertEquals("nothing but the mix was written", 5, files.count());
        }
    }

    @Test
    public void usesTheFileTheLastRenderingNamedForEachTrack() throws Exception {
        Path song = piece("tempo\t108", "track\tmelody\t73\t0\tmelody");
        // An older sampled file lies around, but the last rendering used the Java sound.
        SpatialStemMixer.write(tone(1, 0.9f), song.resolve("melody-sampled.wav"), 16);
        Assert.assertEquals("melody-sampled.wav", StageMixService.stem(song, "melody").getFileName().toString());
        StageMixService.keepStems(song, Arrays.asList("melody"), new Path[]{song.resolve("melody.wav")});
        Assert.assertEquals("melody.wav", StageMixService.stem(song, "melody").getFileName().toString());
    }

    @Test
    public void aTrackThatIsNotRenderedYetIsNamedAndAMutedOneIsNotNeeded() throws Exception {
        Path song = piece("tempo\t108", "track\tmelody\t73\t0\tmelody", "track\tchoir\t52\t1\ttexture");
        StageMixService service = new StageMixService();
        try {
            service.mix(song.resolve("arrangement.tsv"), song, message -> { });
            Assert.fail("the choir has no sound yet");
        } catch (java.io.IOException missing) {
            Assert.assertTrue(missing.getMessage().contains("choir"));
        }
        SpatialScene scene = service.scene(song.resolve("arrangement.tsv"), song);
        new SpatialSceneStore().save(song, scene.with(scene.placement("choir").withMuted(true)));
        Assert.assertTrue(Files.size(service.mix(song.resolve("arrangement.tsv"), song, message -> { })) > 1000);
    }

    @Test
    public void readsRolesAndTempoOfArrangementsOldAndNew() throws Exception {
        Path song = piece("tempo\t96", "track\tmelody\t10\t0", "track\tkit\t0\t9", "track\tpad\t89\t2\ttexture");
        Assert.assertEquals(96, StageMixService.tempo(song.resolve("arrangement.tsv")));
        java.util.List<MusicTrack> tracks = StageMixService.tracks(song.resolve("arrangement.tsv"));
        Assert.assertEquals(MusicTrack.MELODY, tracks.get(0).role());
        Assert.assertEquals("an arrangement without roles: channel 10 is the drums", MusicTrack.DRUMS,
                tracks.get(1).role());
        Assert.assertEquals(MusicTrack.TEXTURE, tracks.get(2).role());
    }

    @Test
    public void aRenamedTrackKeepsItsNotesAndItsSound() throws Exception {
        Path song = piece("tempo\t108", "track\tmelody\t73\t0\tmelody", "note\tmelody\t0\t480\t72\t90",
                "track\tbass\t32\t1\tbass", "note\tbass\t0\t480\t40\t90");
        StageMixService.keepStems(song, Arrays.asList("melody", "bass"),
                new Path[]{song.resolve("melody.wav"), song.resolve("bass.wav")});

        StageMixService.rename(song, "melody", "flute");

        java.util.List<String> lines = Files.readAllLines(song.resolve("arrangement.tsv"), StandardCharsets.UTF_8);
        Assert.assertEquals("track\tflute\t73\t0\tmelody", lines.get(1));
        Assert.assertEquals("note\tflute\t0\t480\t72\t90", lines.get(2));
        Assert.assertEquals("the other track is untouched", "note\tbass\t0\t480\t40\t90", lines.get(4));
        Assert.assertTrue(Files.isRegularFile(song.resolve("flute.wav")));
        Assert.assertFalse(Files.exists(song.resolve("melody.wav")));
        Assert.assertEquals("flute.wav", StageMixService.stem(song, "flute").getFileName().toString());
        Assert.assertTrue(Files.size(new StageMixService().mix(song.resolve("arrangement.tsv"), song,
                message -> { })) > 1000);
    }

    /** A piece of one second: its arrangement and a tone as the sound of the melody and of the bass. */
    private Path piece(String... lines) throws Exception {
        Path song = folder.newFolder().toPath();
        Files.write(song.resolve("arrangement.tsv"), Arrays.asList(lines), StandardCharsets.UTF_8);
        SpatialStemMixer.write(tone(1, 0.4f), song.resolve("melody.wav"), 16);
        SpatialStemMixer.write(tone(1, 0.4f), song.resolve("bass.wav"), 16);
        return song;
    }

    private static SpatialStemMixer.Mix tone(int seconds, float height) throws Exception {
        float[] samples = new float[SpatialStemMixer.RATE * seconds];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (height * Math.sin(2 * Math.PI * 330 * i / SpatialStemMixer.RATE));
        }
        return new SpatialStemMixer().render(
                Arrays.asList(new SpatialStemMixer.Signal(samples, samples, SpatialStemMixer.RATE)),
                Arrays.asList(new SoundSourcePlacement("a", 0, 0, 0, 1, 0, false, false, null)),
                com.aresstack.audio.studio.stage.AcousticRoom.STUDIO.withAmount(0), 2);
    }

    private static double rms(float[] samples) {
        double sum = 0;
        for (float sample : samples) {
            sum += sample * (double) sample;
        }
        return Math.sqrt(sum / samples.length);
    }
}
