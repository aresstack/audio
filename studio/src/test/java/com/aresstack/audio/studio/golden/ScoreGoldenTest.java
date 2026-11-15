package com.aresstack.audio.studio.golden;

import com.aresstack.audio.studio.score.Score;
import org.junit.Test;

/** F0 safety net: the conductor's score of every shipped piece, bar by bar and voice by voice. */
public final class ScoreGoldenTest {
    @Test
    public void theScoreOfEveryPieceStaysAsItIs() throws Exception {
        for (String fixture : Golden.FIXTURES) {
            Golden.assertMatches("score/" + fixture + ".txt", Golden.describe(Score.read(Golden.fixture(fixture))));
        }
    }
}
