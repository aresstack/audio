package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.application.RegionReworkTest;
import java.util.List;
import org.junit.Test;

/**
 * Work package C2: the context of a region is a pure function of piece and selection, shows the
 * bars before and after it, leaves out exactly the notes to be written anew, and does not change
 * the context of a new voice (C1).
 */
public final class RegionContextTest {
    private static final long BAR = 1920;
    private final Composition piece = RegionReworkTest.flutePiece();

    @Test
    public void theSameSelectionGivesTheSameBytes() {
        RegionRequest request = RegionRequest.replace(Selection.bars(piece, List.of("flute"), 5, 8), "");
        MusicalContext first = RegionComposer.context(piece, request);
        MusicalContext again = RegionComposer.context(RegionReworkTest.flutePiece(), request);
        assertEquals(first.text(), again.text());
        assertEquals(first.digest(), again.digest());
        assertNotEquals(first.digest(), RegionComposer.context(piece,
                RegionRequest.replace(Selection.bars(piece, List.of("strings"), 5, 8), "")).digest());
        // the window: two bars before and after the region
        assertEquals(2 * BAR, first.fromTick());
        assertEquals(10 * BAR, first.toTick());
    }

    @Test
    public void onlyTheSelectedNotesAreLeftOut() {
        MusicalContext context = RegionComposer.context(piece,
                RegionRequest.replace(Selection.bars(piece, List.of("flute"), 5, 8), ""));
        String text = context.text();
        String flute = text.substring(text.indexOf("\npart flute\n"), text.indexOf("\npart strings\n"));
        assertTrue(flute.contains("REGION bars 5-8: 16 notes to be written anew, not shown"));
        assertFalse(flute.contains("\n  5 "));
        assertFalse(flute.contains("\n  8 "));
        assertTrue(flute.contains("\n  4 0 1 E5/76 90"));
        assertTrue(flute.contains("\n  held-into-region 4 2 4 G5/79 90"));
        assertTrue(flute.contains("\n  9 0 1 "));
        String strings = text.substring(text.indexOf("\npart strings\n"));
        assertTrue(strings.contains("\n  5 0 4 C4/60 90"));
        assertFalse(strings.contains("REGION"));
    }

    @Test
    public void theContextOfANewVoiceIsAsBefore() {
        // without a region the builder writes what C1 wrote: no region line, no held-into-region
        String text = ContextBuilder.build(piece, 4 * BAR, 8 * BAR, 2, null).text();
        assertFalse(text.contains("REGION"));
        assertFalse(text.contains("held-into-region"));
        assertTrue(text.contains("\n  5 0 1 "));
    }

    @Test
    public void framesFollowTheSectionsWithinTheRegion() {
        List<VoiceFrame> frames = VoiceFrame.within(piece, 6 * BAR, 10 * BAR);
        assertEquals(2, frames.size());
        assertEquals("chorus", frames.get(0).sectionId());
        assertEquals(6, frames.get(0).firstBar());
        assertEquals(2, frames.get(0).bars());
        assertEquals(List.of("F", "G"), frames.get(0).harmony());
        assertEquals("outro", frames.get(1).sectionId());
        assertEquals(List.of("C", "Am"), frames.get(1).harmony());
        // past the last section: one frame for the whole stretch
        List<VoiceFrame> beyond = VoiceFrame.within(piece, 10 * BAR, 14 * BAR);
        assertEquals(1, beyond.size());
        assertEquals(VoiceFrame.WHOLE, beyond.get(0).sectionId());
    }
}
