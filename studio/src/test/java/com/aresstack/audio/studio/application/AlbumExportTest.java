package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.core.dsp.LoudnessMeter;
import com.aresstack.audio.studio.album.Album;
import com.aresstack.audio.studio.album.AlbumConcept;
import com.aresstack.audio.studio.album.AlbumExporter;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Albums, export: every title as a WAV file at the same loudness, a cue sheet over these files,
 * the cover beside them, and if wanted the whole album as one file with pauses; a title that is missing does not stop the export, and no piece is
 * changed by it.
 */
public final class AlbumExportTest {
    private static final int RATE = SpatialStemMixer.RATE;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** A piece of one track: a tone of this height, so that the pieces differ in loudness. */
    private static void piece(Path folder, double frequency, float height, int seconds) throws Exception {
        Files.createDirectories(folder);
        Files.write(folder.resolve("arrangement.tsv"), Arrays.asList("tempo\t120", "track\tmelody\t73\t0\tmelody"),
                StandardCharsets.UTF_8);
        float[] samples = new float[RATE * seconds];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (height * Math.sin(2 * Math.PI * frequency * i / RATE));
        }
        SpatialStemMixer.write(new SpatialStemMixer().render(
                Arrays.asList(new SpatialStemMixer.Signal(samples, samples, RATE)),
                Arrays.asList(new SoundSourcePlacement("a", 0, 0, 0, 1, 0, false, false, null)),
                AcousticRoom.STUDIO.withAmount(0), 2), folder.resolve("melody.wav"), 16);
    }

    private static AlbumTrack track(String id, String title, String piece) {
        return new AlbumTrack(id, title, "", 120, "C major", "4/4", "", "", 10, piece);
    }

    private static double lufs(Path wav) throws Exception {
        SpatialStemMixer.Signal signal = SpatialStemMixer.read(wav);
        LoudnessMeter meter = new LoudnessMeter();
        meter.prepare(Math.round(signal.rate), 4096);
        com.aresstack.audio.core.AudioBlock block = new com.aresstack.audio.core.AudioBlock(4096);
        for (int from = 0; from < signal.left.length; from += 4096) {
            int count = Math.min(4096, signal.left.length - from);
            System.arraycopy(signal.left, from, block.left, 0, count);
            System.arraycopy(signal.right, from, block.right, 0, count);
            block.setFrames(count);
            meter.process(block);
        }
        return meter.integrated();
    }

    @Test
    public void everyTitleComesOutAtTheSameLoudnessAndTheWholeAlbumInOneFile() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        piece(library.resolve("loud"), 440, 0.6f, 4);
        piece(library.resolve("quiet"), 330, 0.05f, 6);
        byte[] untouched = Files.readAllBytes(library.resolve("quiet").resolve("melody.wav"));
        Album album = new Album("christmas", "Christmas: Memories?", new AlbumConcept("A Christmas album", "", 3, 10),
                List.of(track("t1", "Overture", "loud"), track("t2", "Not there yet", ""),
                        track("t3", "Silent / Night", "quiet")));
        Path out = temporary.newFolder("export").toPath();

        AlbumExporter.Result result = new AlbumExporter(library, new StageMixdown())
                .export(album, out, new AlbumExporter.Options(-18, 16, true, 2), line -> { });

        assertEquals(2, result.titles().size());
        assertEquals(List.of("01 - Overture.wav", "03 - Silent Night.wav"),
                result.titles().stream().map(title -> title.file().getFileName().toString()).toList());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).startsWith("02 Not there yet"));
        // The source tones differ by more than 20 dB; exported, both are at the target.
        for (AlbumExporter.Exported title : result.titles()) {
            assertEquals(title.file().getFileName().toString(), -18, lufs(title.file()), 0.2);
        }
        double first = result.titles().get(0).seconds();
        double second = result.titles().get(1).seconds();
        assertTrue(first + " and " + second, first >= 4 && first < 10 && second >= first + 2 - 1e-6 && second < 12);

        // One file of the whole album: first title, two seconds of silence, second title.
        assertEquals("Christmas Memories.wav", result.whole().getFileName().toString());
        SpatialStemMixer.Signal whole = SpatialStemMixer.read(result.whole());
        assertEquals(first + 2 + second, whole.left.length / (double) RATE, 1e-3);
        int pauseFrom = (int) Math.round(first * RATE);
        for (int i = pauseFrom + 10; i < pauseFrom + 2 * RATE - 10; i += 97) {
            assertEquals(0f, whole.left[i], 0f);
        }
        SpatialStemMixer.Signal last = SpatialStemMixer.read(result.titles().get(1).file());
        int secondFrom = pauseFrom + 2 * RATE;
        assertArrayEquals("the second title begins where the cue sheet says",
                Arrays.copyOfRange(last.left, 0, RATE), Arrays.copyOfRange(whole.left, secondFrom, secondFrom + RATE), 0f);
        assertEquals(first + 2, result.titles().get(1).startSeconds(), 1e-9);
        // The cue sheet names the files of the titles, each from its start: a player plays the album from it.
        assertEquals("Christmas Memories.cue", result.cueSheet().getFileName().toString());
        assertEquals("\uFEFFTITLE \"Christmas: Memories?\"\n"
                + "FILE \"01 - Overture.wav\" WAVE\n"
                + "  TRACK 01 AUDIO\n"
                + "    TITLE \"Overture\"\n"
                + "    INDEX 01 00:00:00\n"
                + "FILE \"03 - Silent Night.wav\" WAVE\n"
                + "  TRACK 02 AUDIO\n"
                + "    TITLE \"Silent / Night\"\n"
                + "    INDEX 01 00:00:00\n",
                new String(Files.readAllBytes(result.cueSheet()), StandardCharsets.UTF_8));
        // No cover of its own: a placeholder lies beside the titles, a picture a player can read.
        assertEquals("cover.png", result.cover().getFileName().toString());
        assertTrue(result.coverNote(), result.coverNote().contains("Platzhalter"));
        java.awt.image.BufferedImage placeholder = javax.imageio.ImageIO.read(result.cover().toFile());
        assertEquals(1000, placeholder.getWidth());
        assertEquals(1000, placeholder.getHeight());
        String list = new String(Files.readAllBytes(result.list()), StandardCharsets.UTF_8);
        assertTrue(list, list.contains("01 - Overture") && list.contains("2 Titel") && list.contains("-18.0 LUFS")
                && list.contains("Fehlt: 02 Not there yet"));

        // Exporting changed no piece and left nothing unfinished behind.
        assertArrayEquals(untouched, Files.readAllBytes(library.resolve("quiet").resolve("melody.wav")));
        assertFalse(Files.exists(library.resolve("quiet").resolve(StageMixService.MIX_FILE)));
        try (Stream<Path> files = Files.list(out)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".part")));
        }

        // As the studio exports: single files only, and the same cue sheet over them.
        assertFalse(AlbumExporter.Options.STANDARD.continuous());
        Path singles = temporary.newFolder("single").toPath();
        AlbumExporter.Result single = new AlbumExporter(library, new StageMixdown()).export(album,
                singles, new AlbumExporter.Options(-18, 24, false, 2), line -> { });
        assertEquals(null, single.whole());
        assertFalse(Files.exists(singles.resolve("Christmas Memories.wav")));
        assertArrayEquals(Files.readAllBytes(result.cueSheet()), Files.readAllBytes(single.cueSheet()));
        assertEquals(-18, lufs(single.titles().get(0).file()), 0.2);
    }

    @Test
    public void theCoverOfTheAlbumLiesBesideTheTitlesAndAFailingCoverNeverStopsTheExport() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        piece(library.resolve("loud"), 440, 0.6f, 4);
        Album album = new Album("a", "Liebliche Weihnacht", new AlbumConcept("x", "", 1, 10),
                List.of(track("t1", "Sternenstill", "loud")));
        final byte[] own = com.aresstack.audio.studio.album.AlbumCover.png(
                new java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_RGB));

        AlbumExporter.Result with = new AlbumExporter(library, new StageMixdown(), of -> own).export(album,
                temporary.newFolder("with").toPath(), AlbumExporter.Options.STANDARD, line -> { });
        assertArrayEquals("the album's own cover, as it is", own, Files.readAllBytes(with.cover()));
        assertEquals(null, with.coverNote());

        AlbumExporter.Result failing = new AlbumExporter(library, new StageMixdown(), of -> {
            throw new java.io.IOException("Die KI ist nicht erreichbar.");
        }).export(album, temporary.newFolder("failing").toPath(), AlbumExporter.Options.STANDARD, line -> { });
        assertEquals("the titles are exported all the same", 1, failing.titles().size());
        assertTrue(Files.isRegularFile(failing.titles().get(0).file()));
        assertTrue(failing.coverNote(), failing.coverNote().contains("Die KI ist nicht erreichbar")
                && failing.coverNote().contains("Platzhalter"));
        assertEquals(1000, javax.imageio.ImageIO.read(failing.cover().toFile()).getWidth());
        String cue = new String(Files.readAllBytes(failing.cueSheet()), StandardCharsets.UTF_8);
        assertTrue(cue, cue.contains("FILE \"01 - Sternenstill.wav\" WAVE"));
    }

    @Test
    public void anAlbumWithoutAnyPieceCannotBeExported() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        Album album = new Album("a", "A", new AlbumConcept("x", "", 1, 10), List.of(track("t1", "Only", "")));
        try {
            new AlbumExporter(library, new StageMixdown()).export(album, temporary.newFolder("out").toPath(),
                    AlbumExporter.Options.STANDARD, line -> { });
            org.junit.Assert.fail();
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Kein Titel"));
        }
    }
}
