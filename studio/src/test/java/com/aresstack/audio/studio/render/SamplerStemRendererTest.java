package com.aresstack.audio.studio.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SamplerStemRendererTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void anSfzBankChangesWithItsSamples() throws Exception {
        Path root = folder.newFolder("piano").toPath();
        Path sfz = root.resolve("piano.sfz");
        Files.writeString(sfz, "<region> sample=samples/c4.wav");
        Path sample = root.resolve("samples").resolve("c4.wav");
        Files.createDirectories(sample.getParent());
        Files.write(sample, new byte[100]);
        String before = SamplerStemRenderer.fingerprint(sfz);
        assertEquals(before, SamplerStemRenderer.fingerprint(sfz));

        Files.write(sample, new byte[120]);
        Files.setLastModifiedTime(sample, FileTime.fromMillis(Files.getLastModifiedTime(sample).toMillis() + 5000));
        assertNotEquals(before, SamplerStemRenderer.fingerprint(sfz));
    }

    @Test
    public void anSf2BankIsOneFile() throws Exception {
        Path root = folder.newFolder("gm").toPath();
        Path sf2 = root.resolve("gm.sf2");
        Files.write(sf2, new byte[64]);
        String before = SamplerStemRenderer.fingerprint(sf2);
        Files.write(root.resolve("readme.txt"), new byte[10]);
        assertEquals(before, SamplerStemRenderer.fingerprint(sf2));
    }
}
