package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.golden.Golden;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** F0 safety net: what the lanes show of every shipped piece: tempo, length, and the notes of each lane. */
public final class ArrangementViewCharacterizationTest {
    @Test
    public void theLanesShowTheSameOfEveryPiece() throws Exception {
        StringBuilder text = new StringBuilder();
        for (String fixture : Golden.FIXTURES) {
            ArrangementView.Arrangement arrangement = ArrangementView.Arrangement.read(Golden.fixture(fixture));
            text.append(fixture).append(": tempo ").append(arrangement.tempo).append(", ticks ")
                    .append(arrangement.ticks).append(", bars ").append(arrangement.bars()).append('\n');
            for (Map.Entry<String, ? extends List<?>> lane : arrangement.lanes.entrySet()) {
                text.append("  ").append(lane.getKey()).append(' ').append(lane.getValue().size()).append('\n');
            }
        }
        Golden.assertMatches("lanes.txt", text.toString());
    }

    @Test
    public void thereAreNoLanesWithoutAFile() {
        Assert.assertNull(ArrangementView.Arrangement.read(null));
        Assert.assertNull(ArrangementView.Arrangement.read(Golden.fixture("missing")));
    }
}
