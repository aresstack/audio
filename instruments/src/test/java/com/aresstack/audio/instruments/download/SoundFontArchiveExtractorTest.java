package com.aresstack.audio.instruments.download;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercise archive handling locally without relying on external download servers. */
public final class SoundFontArchiveExtractorTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void extractsAndVerifiesSevenZipSoundFont() throws Exception {
        Path root = folder.getRoot().toPath();
        Path fixture = root.resolve("fixture.sf2");
        Files.write(fixture, syntheticSoundFont());
        Path archive = root.resolve("test.7z");
        try (SevenZOutputFile zip = new SevenZOutputFile(archive.toFile())) {
            SevenZArchiveEntry entry = zip.createArchiveEntry(fixture.toFile(), "guitar.sf2");
            zip.putArchiveEntry(entry);
            zip.write(Files.readAllBytes(fixture));
            zip.closeArchiveEntry();
        }

        List<Path> result = new SoundFontArchiveExtractor().extractSevenZip(archive,
                root.resolve("unpacked"));
        Assert.assertEquals(1, result.size());
        InstrumentDownloadService.verifySoundFont(result.get(0));
        Assert.assertEquals(4096L, Files.size(result.get(0)));
    }

    @Test(expected = java.io.IOException.class)
    public void blocksArchivePathTraversal() throws Exception {
        Path root = folder.getRoot().toPath();
        Path fixture = root.resolve("fixture.sf2");
        Files.write(fixture, syntheticSoundFont());
        Path archive = root.resolve("traversal.7z");
        try (SevenZOutputFile zip = new SevenZOutputFile(archive.toFile())) {
            SevenZArchiveEntry entry = zip.createArchiveEntry(fixture.toFile(), "../outside.sf2");
            zip.putArchiveEntry(entry);
            zip.write(Files.readAllBytes(fixture));
            zip.closeArchiveEntry();
        }
        new SoundFontArchiveExtractor().extractSevenZip(archive, root.resolve("unpacked"));
    }

    @Test
    public void listsFourVerifiedDirectDownloads() {
        Assert.assertEquals(4, InstrumentDownloadCatalog.all().size());
        Assert.assertNull(InstrumentDownloadCatalog.forLibrary("vsco2"));
        Assert.assertNull(InstrumentDownloadCatalog.forLibrary("vpo"));
        Assert.assertNotNull(InstrumentDownloadCatalog.forLibrary("generaluser"));
        Assert.assertNotNull(InstrumentDownloadCatalog.forLibrary("musescore-general"));
    }

    private static byte[] syntheticSoundFont() {
        byte[] data = new byte[4096];
        data[0] = 'R';
        data[1] = 'I';
        data[2] = 'F';
        data[3] = 'F';
        data[8] = 's';
        data[9] = 'f';
        data[10] = 'b';
        data[11] = 'k';
        return data;
    }
}
