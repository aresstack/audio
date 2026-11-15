package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;

import com.aresstack.audio.studio.score.Score;
import java.util.List;
import org.junit.Test;

/** F2: key signature, meter and accidentals bar by bar in the score. */
public final class ScoreKeyTest {
    @Test
    public void accidentalsHoldToTheEndOfTheBar() {
        Score score = Score.of(List.of("tempo\t96", "key\tD\tminor", "track\tlead\t0\t0\tmelody",
                "note\tlead\t0\t480\t73\t90",      // C#5: sharp
                "note\tlead\t480\t480\t73\t90",    // C#5 again: the sharp still holds
                "note\tlead\t960\t480\t72\t90",    // C5: natural
                "note\tlead\t1440\t480\t70\t90",   // Bb4: in the signature
                "note\tlead\t1920\t480\t73\t90",   // next bar, C#5: sharp again
                "note\tlead\t2400\t480\t71\t90",   // B4: natural against the flat
                "note\tlead\t2880\t960\t61\t90")); // C#4: another octave, its own sharp
        Score.Staff lead = score.staves().get(0);
        assertEquals(-1, score.keySignature());
        assertEquals(Score.Accidental.SHARP, lead.bar(0).get(0).accidental(0));
        assertEquals(Score.Accidental.NONE, lead.bar(0).get(1).accidental(0));
        assertEquals(Score.Accidental.NATURAL, lead.bar(0).get(2).accidental(0));
        assertEquals(Score.Accidental.NONE, lead.bar(0).get(3).accidental(0));
        assertEquals("Bb4", lead.bar(0).get(3).spelling(0).name());
        assertEquals(Score.Accidental.SHARP, lead.bar(1).get(0).accidental(0));
        assertEquals(Score.Accidental.NATURAL, lead.bar(1).get(1).accidental(0));
        assertEquals(Score.Accidental.SHARP, lead.bar(1).get(2).accidental(0));
    }

    @Test
    public void aNoteTiedOverTheBarLineGetsNoSignAgain() {
        Score score = Score.of(List.of("tempo\t96", "key\tD\tminor", "track\tlead\t0\t0\tmelody",
                "note\tlead\t960\t1920\t73\t90"));
        Score.Staff lead = score.staves().get(0);
        Score.Event first = lead.bar(0).stream().filter(e -> !e.rest()).findFirst().orElseThrow();
        Score.Event held = lead.bar(1).get(0);
        assertEquals(Score.Accidental.SHARP, first.accidental(0));
        assertEquals(Score.Accidental.NONE, held.accidental(0));
    }

    @Test
    public void barsFollowTheMeter() {
        Score score = Score.of(List.of("tempo\t120", "meter\t3\t4", "bars\t4", "track\tlead\t0\t0\tmelody",
                "note\tlead\t0\t1440\t60\t90"));
        assertEquals(12, score.barLength());
        assertEquals(4, score.bars());
        assertEquals(1.5, score.secondsPerBar(), 1e-9);
        assertEquals(12, score.staves().get(0).bar(0).get(0).length());
        assertEquals(Score.Accidental.NONE, score.staves().get(0).bar(0).get(0).accidental(0));
    }

    @Test
    public void aPieceFromBeforeIsInCMajor() {
        Score score = Score.of(List.of("tempo\t108", "track\tlead\t0\t0\tmelody", "note\tlead\t0\t480\t70\t90"));
        assertEquals(0, score.keySignature());
        assertEquals(16, score.barLength());
        assertEquals("Bb4", score.staves().get(0).bar(0).get(0).spelling(0).name());
        assertEquals(Score.Accidental.FLAT, score.staves().get(0).bar(0).get(0).accidental(0));
    }
}
