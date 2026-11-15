package com.aresstack.audio.studio.hrtf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The real download from the MIT Media Lab; only in CI, when asked with {@code -Dhrtf.live=true}. */
public final class HrtfDownloadIntegrationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theMitKemarSetIsDownloadedCheckedAndReadOffline() throws Exception {
        Assume.assumeTrue("Run with -Dhrtf.live=true", Boolean.getBoolean("hrtf.live"));
        HrtfLibrary library = new HrtfLibrary(folder.getRoot().toPath());
        library.download(HrtfDataset.MIT_KEMAR, null, System.out::println);
        assertTrue(library.isInstalled(HrtfDataset.MIT_KEMAR));
        Path source = library.folder(HrtfDataset.MIT_KEMAR).resolve(HrtfLibrary.SOURCE_FILE);
        assertTrue(Files.readString(source).contains("Gardner"));
        assertEquals(44100, library.load(HrtfDataset.MIT_KEMAR).sampleRate());
    }
}
