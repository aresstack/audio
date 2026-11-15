package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Meta;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * C3, report 8.3: the context of a continuation is told in tiers. A short piece is given in full;
 * a longer one only with its beginning (main theme, loop target) and the end where the
 * continuation joins; a long one with shorter windows and coarser part summaries. The choice and
 * the text are pure functions of piece and request.
 */
public final class ContinuationContextTest {
    private static final long BAR = 1920;

    /** A piece of {@code bars} bars: a melody with one note per bar, its pitch telling the bar. */
    static Composition piece(int bars) {
        List<Note> notes = new ArrayList<>();
        for (int bar = 0; bar < bars; bar++) {
            notes.add(new Note("n" + bar, bar * BAR, 960, 48 + bar % 40, 80, "v1"));
        }
        Part melody = new Part("melody", "Flöte", "melody", "Flute", 73, null, null,
                List.of(new Voice("v1", "Flöte", null, null)), null);
        Track track = new Track("t1", "Flöte", Track.Kind.MIDI, "melody",
                List.of(new MidiClip("c1", 0, bars * BAR, null, notes)));
        Section whole = new Section("a", "A", null, 0, bars * BAR, null, List.of("C", "G", "Am", "F"), null);
        return new Composition(new Meta("Test", null, null, null, null, null), TimeMap.of(100), List.of(whole),
                List.of(melody), List.of(track), List.of());
    }

    @Test
    public void aShortPieceIsGivenInFull() {
        Composition piece = piece(8);
        ContinuationTask task = ContinuationTask.of(piece, ContinuationRequest.of(16, ContinuationPreset.STANDALONE,
                ContinuationEnding.CLOSED), 1);
        assertEquals(ContextTiers.Tier.FULL, task.tiers().tier());
        assertEquals(1, task.tiers().windows().size());
        for (int bar = 0; bar < 8; bar++) {
            assertTrue(task.context().text().contains("  " + (bar + 1) + " 0 2 "));
        }
        assertTrue(task.context().text().contains("notes per bar: 1 1 1 1 1 1 1 1"));
        assertEquals(8, task.firstNewBar());
        assertEquals(8 * BAR, task.newStart());
        assertEquals(24 * BAR, task.newEnd());
    }

    @Test
    public void aLongerPieceIsGivenWithItsThemeAndItsEnd() {
        Composition piece = piece(40);
        ContinuationTask task = ContinuationTask.of(piece, ContinuationRequest.of(8, ContinuationPreset.VARIATION_REPRISE,
                ContinuationEnding.LOOP), 1);
        assertEquals(ContextTiers.Tier.WINDOWED, task.tiers().tier());
        String text = task.context().text();
        assertTrue(text.contains("# BEGINNING OF THE PIECE: MAIN THEME AND LOOP TARGET\n# NOTES bars 1-8"));
        assertTrue(text.contains("# END OF THE ORIGINAL, WHERE THE CONTINUATION JOINS\n# NOTES bars 33-40"));
        assertTrue(text.contains("\n  1 0 2 "));
        assertTrue(text.contains("\n  40 0 2 "));
        assertFalse(text.contains("\n  20 0 2 ")); // the middle is only summarised
        assertTrue(text.contains("notes per bar:"));
    }

    @Test
    public void aLongPieceGetsShortWindowsAndCoarseSummaries() {
        Composition piece = piece(100);
        ContinuationRequest request = ContinuationRequest.of(16, ContinuationPreset.STANDALONE, ContinuationEnding.OPEN)
                .revisingTail(6);
        ContinuationTask task = ContinuationTask.of(piece, request, 1);
        assertEquals(ContextTiers.Tier.LONG, task.tiers().tier());
        String text = task.context().text();
        assertTrue(text.contains("# NOTES bars 1-4"));
        // the end window reaches two bars behind the revised ones
        assertTrue(text.contains("# NOTES bars 93-100"));
        assertTrue(text.contains("notes per 4 bars: 4 4 4"));
        assertFalse(text.contains("\n  50 0 2 "));
        // and stays far smaller than the whole piece in full would be
        String full = ContextBuilder.build(piece, 0, 100 * BAR, 0, null).text();
        assertTrue(text.length() < full.length() / 2);
    }

    @Test
    public void theSamePieceAndRequestGiveTheSameBytes() {
        ContinuationRequest request = ContinuationRequest.of(16, ContinuationPreset.DRAMATIC_BUILD,
                ContinuationEnding.CLOSED);
        ContinuationTask first = ContinuationTask.of(piece(40), request, 1);
        ContinuationTask again = ContinuationTask.of(piece(40), request, 1);
        assertEquals(first.context().text(), again.context().text());
        assertEquals(first.context().digest(), again.context().digest());
        assertEquals(ChatContinuationPlanner.prompt(first), ChatContinuationPlanner.prompt(again));
    }

    @Test(expected = IllegalArgumentException.class)
    public void theTransitionCannotReviseTheWholeOriginal() {
        ContinuationTask.of(piece(2), ContinuationRequest.of(8, ContinuationPreset.STANDALONE,
                ContinuationEnding.OPEN).revisingTail(2), 1);
    }
}
