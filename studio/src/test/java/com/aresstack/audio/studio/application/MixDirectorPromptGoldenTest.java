package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.golden.Golden;
import java.nio.file.Path;
import org.junit.Test;

/** F0 safety net: what the mixing engineer is told about every shipped piece, read from its arrangement. */
public final class MixDirectorPromptGoldenTest {
    @Test
    public void theMixingEngineerHearsTheSameAboutEveryPiece() throws Exception {
        for (String fixture : Golden.FIXTURES) {
            Path file = Golden.fixture(fixture);
            Golden.assertMatches("mix-prompt/" + fixture + ".txt",
                    MixDirector.prompt("Golden piece", StageMixService.tracks(file), file, null) + "\n");
        }
    }
}
