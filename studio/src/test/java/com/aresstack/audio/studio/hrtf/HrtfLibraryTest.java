package com.aresstack.audio.studio.hrtf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.studio.mix.StageSound;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The optional download of measured responses (decision of the product owner on PR #33): only
 * an archive with the checksum of its source is installed, source and citation lie next to the
 * data, a downloaded set is used offline, and without one the stage keeps the head model.
 */
public final class HrtfLibraryTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final List<String> NOTHING = new ArrayList<String>();

    /** A small set in the layout of the compact MIT KEMAR archive, right side only. */
    private Path archive(String... extra) throws IOException {
        Path zip = folder.newFile("compact.zip").toPath();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int elevation = -40; elevation <= 90; elevation += 10) {
                for (int azimuth = 0; azimuth <= 180; azimuth += 30) {
                    out.putNextEntry(new ZipEntry("compact/elev" + elevation + "/H" + elevation + "e"
                            + String.format("%03d", azimuth) + "a.dat"));
                    out.write(response(azimuth));
                    out.closeEntry();
                }
            }
            out.putNextEntry(new ZipEntry("compact/readme.txt"));
            out.write("not data".getBytes(StandardCharsets.US_ASCII));
            out.closeEntry();
            for (String name : extra) {
                out.putNextEntry(new ZipEntry(name));
                out.write(response(0));
                out.closeEntry();
            }
        }
        return zip;
    }

    /** 128 stereo frames, big-endian: a click in each ear, the far ear later and softer. */
    private static byte[] response(int azimuth) {
        ByteBuffer buffer = ByteBuffer.allocate(128 * 4);
        int lag = 2 + azimuth / 30;
        for (int i = 0; i < 128; i++) {
            buffer.putShort((short) (i == 2 + lag ? 8000 : 0)); // left, the far ear
            buffer.putShort((short) (i == 2 ? 16000 : 0));      // right
        }
        return buffer.array();
    }

    private static HrtfDataset dataset(String sha256) {
        return new HrtfDataset("test-set", "Test-Satz", URI.create("https://example.invalid/compact.zip"),
                URI.create("https://example.invalid/"), "test", 1_000_000, sha256, "Free to use.", "A. Author, 1994.");
    }

    @Test
    public void anArchiveWithTheChecksumOfItsSourceIsInstalledWithSourceAndCitation() throws Exception {
        Path zip = archive();
        HrtfDataset set = dataset(HrtfLibrary.sha256(zip));
        HrtfLibrary library = new HrtfLibrary(folder.getRoot().toPath().resolve("hrtf"));
        assertFalse(library.isInstalled(set));
        List<String> said = new ArrayList<String>();
        library.install(set, zip, said::add);
        assertTrue(library.isInstalled(set));
        String source = Files.readString(library.folder(set).resolve(HrtfLibrary.SOURCE_FILE));
        assertTrue(source, source.contains("https://example.invalid/compact.zip"));
        assertTrue(source, source.contains(set.sha256()));
        assertTrue(source, source.contains("Free to use."));
        assertTrue(source, source.contains("A. Author, 1994."));
        assertTrue(said.get(0), said.get(0).contains("A. Author, 1994."));
        assertFalse("only the responses are unpacked", Files.exists(library.folder(set).resolve("readme.txt")));
        assertEquals(44100, library.load(set).sampleRate());
    }

    @Test
    public void anArchiveThatDiffersFromItsSourceIsRefusedAndNothingIsInstalled() throws Exception {
        Path zip = archive();
        HrtfDataset set = dataset("0".repeat(64));
        HrtfLibrary library = new HrtfLibrary(folder.getRoot().toPath().resolve("hrtf"));
        try {
            library.install(set, zip, NOTHING::add);
            fail("installed despite the wrong checksum");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Prüfsumme"));
        }
        assertFalse(library.isInstalled(set));
        assertFalse(Files.exists(library.folder(set)));
    }

    @Test
    public void namesThatLeadOutOfTheFolderStayInsideIt() throws Exception {
        Path zip = archive("../../H0e000a.dat", "/etc/H10e030a.dat");
        HrtfDataset set = dataset(HrtfLibrary.sha256(zip));
        Path root = folder.getRoot().toPath().resolve("deep").resolve("hrtf");
        new HrtfLibrary(root).install(set, zip, NOTHING::add);
        try (var files = Files.list(folder.getRoot().toPath())) {
            assertEquals(List.of("compact.zip", "deep"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    public void withoutTheSetTheStageKeepsTheHeadModelAndTouchesNoNetwork() throws Exception {
        HrtfLibrary library = new HrtfLibrary(folder.getRoot().toPath().resolve("hrtf"));
        StageSound sound = StageSound.binaural();
        assertSame(sound, library.onStage(sound, HrtfDataset.MIT_KEMAR));
    }

    @Test
    public void aDownloadedSetIsUsedOfflineAndNotDownloadedAgain() throws Exception {
        Path zip = archive();
        HrtfDataset set = dataset(HrtfLibrary.sha256(zip));
        HrtfLibrary library = new HrtfLibrary(folder.getRoot().toPath().resolve("hrtf"));
        library.install(set, zip, NOTHING::add);
        // The source cannot be reached: an installed set must not try.
        List<String> said = new ArrayList<String>();
        library.download(set, target -> {
            throw new AssertionError("no connection for an installed set");
        }, said::add);
        assertTrue(said.get(0), said.get(0).startsWith("Bereits geladen"));
        StageSound sound = StageSound.binaural();
        StageSound measured = library.onStage(sound, set);
        assertNotSame(sound.head(), measured.head());
        assertEquals(44100, measured.head().sampleRate());
    }

    @Test
    public void theKemarSetNamesItsSourceTermsAndAuthors() {
        HrtfDataset kemar = HrtfDataset.MIT_KEMAR;
        assertEquals("sound.media.mit.edu", kemar.uri().getHost());
        assertTrue(kemar.terms().contains("no restrictions on use, provided the authors are cited"));
        assertTrue(kemar.citation().contains("Bill Gardner and Keith Martin"));
        assertTrue(kemar.citation().contains("#280"));
    }
}
