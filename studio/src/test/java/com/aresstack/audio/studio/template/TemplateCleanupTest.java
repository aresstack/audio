package com.aresstack.audio.studio.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** What is plainly wrong in a template is set right; what only looks odd is shown to the AI, not changed. */
public final class TemplateCleanupTest {
    @Test
    public void plainMistakesAreSetRight() {
        ScorePart part = new ScorePart("Flauto", 73, false, 1, List.of(
                new ScoreNote(0, 480, 72, 80),
                new ScoreNote(0, 240, 72, 80), // the same note again, shorter
                new ScoreNote(240, 0, 74, 80), // no length
                new ScoreNote(240, 960, 76, 0), // no loudness; still sounds when 76 begins again
                new ScoreNote(720, 480, 76, 90)));
        ScorePart silent = new ScorePart("Leer", 0, false, 1, List.of(new ScoreNote(0, 0, 60, 80)));
        TemplateScore cleaned = TemplateCleanup.clean(new TemplateScore("", 0, 0, 0, null, List.of(part, silent),
                List.of(), "MIDI"));

        assertEquals(1, cleaned.parts().size());
        assertEquals(List.of(
                new ScoreNote(0, 480, 72, 80),
                new ScoreNote(240, 480, 76, TemplateCleanup.DEFAULT_VELOCITY),
                new ScoreNote(720, 480, 76, 90)), cleaned.parts().get(0).notes());
        assertEquals(cleaned.remarks().toString(), 5, cleaned.remarks().size());
    }

    @Test
    public void aSingleNoteOctavesAwayIsFlaggedButKept() {
        ScorePart part = new ScorePart("Violino", 40, false, 1, List.of(
                new ScoreNote(0, 240, 76, 80),
                new ScoreNote(240, 240, 93, 80), // a wrong octave, most likely
                new ScoreNote(480, 240, 77, 80),
                new ScoreNote(720, 240, 79, 80)));
        TemplateScore score = new TemplateScore("", 0, 0, 0, null, List.of(part), List.of(), "MIDI");

        List<TemplateCleanup.Flag> flags = TemplateCleanup.flags(score, 10);
        assertEquals(1, flags.size());
        assertEquals(0, flags.get(0).part());
        assertEquals(1, flags.get(0).note());
        assertTrue(flags.get(0).why().contains("16 semitones"));
        assertEquals(4, TemplateCleanup.clean(score).parts().get(0).notes().size());
    }
}
