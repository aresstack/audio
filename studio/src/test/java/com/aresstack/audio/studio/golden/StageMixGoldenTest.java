package com.aresstack.audio.studio.golden;

import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.application.StageMixService;
import com.aresstack.audio.studio.application.StudioMusicRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * F0 safety net: the whole way from the written arrangement to the mix on the stage, as the
 * studio takes it without sound banks: reference rendering in a child process, the note of
 * which file holds each track, and the mix of all tracks on the usual stage. The hash of the
 * mix must stay the same.
 */
public final class StageMixGoldenTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theMixOnTheStageStaysTheSameForEveryPiece() throws Exception {
        for (String fixture : Golden.FIXTURES) {
            Path home = folder.newFolder(fixture).toPath();
            Path arrangement = home.resolve("arrangement.tsv");
            Files.copy(Golden.fixture(fixture), arrangement);
            Path directory = home.resolve("render");
            Path mix = new StudioMusicRenderer(new InstrumentRegistry(home.resolve("none.properties"))).render(
                    arrangement, directory, new HashMap<String, InstrumentLibrary>(), "missing-fluidsynth",
                    "missing-sfizz", message -> { });
            String stems = new String(Files.readAllBytes(directory.resolve(StageMixService.STEMS_FILE)),
                    StandardCharsets.UTF_8);
            Golden.assertMatches("mix/" + fixture + ".txt", "stems.tsv:\n" + stems
                    + "mix\t" + mix.getFileName() + "\t" + Golden.sha256(mix) + "\n");
        }
    }
}
