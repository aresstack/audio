package com.aresstack.audio.studio.golden;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * F0 safety net: TSV to MIDI and Java-synthesis WAVs. The reference renderer of the music
 * module must write the very same bytes for every shipped piece, before and after any change
 * to how the arrangement is read.
 */
public final class ReferenceRenderGoldenTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theReferenceRendererWritesTheSameBytesForEveryPiece() throws Exception {
        for (String fixture : Golden.FIXTURES) {
            Path output = folder.newFolder(fixture).toPath();
            Golden.renderReference(Golden.fixture(fixture).toAbsolutePath(), output);
            String[] names;
            try (Stream<Path> files = Files.list(output)) {
                names = files.map(file -> file.getFileName().toString()).toArray(String[]::new);
            }
            Golden.assertMatches("render/" + fixture + ".sha256", Golden.hashes(output, names));
        }
    }
}
