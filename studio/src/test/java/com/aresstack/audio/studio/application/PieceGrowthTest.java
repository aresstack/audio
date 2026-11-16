package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.studio.ai.ContinuationEnding;
import com.aresstack.audio.studio.ai.ContinuationPreset;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.TransitionMode;
import java.util.List;
import org.junit.Test;

/** Growing a piece to a whole length in steps ("Verlängern auf insgesamt …", the first composition of a long piece). */
public class PieceGrowthTest {
    @Test
    public void onlyTheLastStepEndsAndOnlyTheFirstChangesTheTransitionOrBringsInstruments() {
        ContinuationRequest asked = new ContinuationRequest(280, ContinuationPreset.DRAMATIC_BUILD, "mit Pauken",
                ContinuationEnding.CLOSED, TransitionMode.REVISE_TAIL, 2, List.of("flute"), 2);
        ContinuationRequest first = PieceGrowth.step(asked, 280, true, false);
        assertEquals(280, first.bars());
        assertEquals(ContinuationEnding.OPEN, first.ending());
        assertEquals(TransitionMode.REVISE_TAIL, first.transition());
        assertEquals(2, first.newParts());
        ContinuationRequest middle = PieceGrowth.step(asked, 280, false, false);
        assertEquals(ContinuationEnding.OPEN, middle.ending());
        assertEquals(TransitionMode.PROTECT_ORIGINAL, middle.transition());
        assertEquals(0, middle.newParts());
        assertEquals("mit Pauken", middle.instruction());
        assertEquals(List.of("flute"), middle.partIds());
        ContinuationRequest last = PieceGrowth.step(asked, 85, false, true);
        assertEquals(85, last.bars());
        assertEquals(ContinuationEnding.CLOSED, last.ending());
    }

    @Test
    public void halfAnHourInTheTempoOfTheEnd() {
        Composition piece = RegionReworkTest.flutePiece();
        int has = piece.timeMap().barsCovering(piece.endTick());
        assertEquals("not shorter than it is", has, PieceGrowth.barsLasting(piece, 1));
        long tick = piece.timeMap().tickAt(30 * 60);
        assertEquals(piece.timeMap().barsCovering(tick), PieceGrowth.barsLasting(piece, 30 * 60));
    }
}
