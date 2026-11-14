package com.aresstack.audio.instruments.download;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercise all official direct URLs only when explicitly activated in CI. */
public final class InstrumentDownloadIntegrationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void installsAllDirectSoundFontsAndSevenesZipVariants() throws Exception {
        Assume.assumeTrue("Run with -Dsoundfont.live=true", Boolean.getBoolean("soundfont.live"));
        Path root = folder.getRoot().toPath();
        InstrumentRegistry registry = new InstrumentRegistry(root.resolve("instruments.properties"));
        InstrumentDownloadService installer = new InstrumentDownloadService(
                root.resolve("libraries"), registry, "", 8080);
        Map<String, InstrumentLibrary> libraries = new HashMap<String, InstrumentLibrary>();
        for (InstrumentLibrary library : InstrumentCatalog.standard()) {
            libraries.put(library.id(), library);
        }
        for (InstrumentDownload direct : InstrumentDownloadCatalog.all()) {
            final String libraryId = direct.libraryId();
            Assert.assertFalse(registry.isInstalled(libraryId));
            installer.install(libraries.get(libraryId), direct, new InstrumentDownloadService.Progress() {
                @Override
                public void update(String message) {
                    System.out.println(message);
                }
            });
            Assert.assertTrue("Not registered after download: " + libraryId,
                    registry.isInstalled(libraryId));
        }
        Assert.assertEquals(4, registry.installedInstruments().size());
    }
}
