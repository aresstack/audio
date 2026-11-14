package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.score.Score;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** An arrangement written out as notes: lengths, rests, chords, ties, clefs and places on the staff. */
public final class ScoreTest {
    @Test
    public void writesNotesChordsAndRestsWithTheirLengths() {
        Score score = Score.of(Arrays.asList("tempo\t96", "track\tflute\t73\t0\tmelody",
                "note\tflute\t0\t480\t72\t90",        // a quarter
                "note\tflute\t480\t240\t74\t90",      // an eighth
                "note\tflute\t960\t960\t60\t90",      // a chord of a half note
                "note\tflute\t960\t960\t64\t90",
                "note\tflute\t960\t960\t67\t90"));

        Assert.assertEquals(96, score.tempo());
        Assert.assertEquals(1, score.bars());
        Score.Staff staff = score.staves().get(0);
        Assert.assertEquals(Score.Clef.TREBLE, staff.clef());
        List<Score.Event> bar = staff.bar(0);
        Assert.assertEquals("quarter, eighth, eighth rest, half chord", 4, bar.size());
        Assert.assertEquals(4, bar.get(0).length());
        Assert.assertArrayEquals(new int[]{72}, bar.get(0).pitches());
        Assert.assertEquals(2, bar.get(1).length());
        Assert.assertTrue(bar.get(2).rest());
        Assert.assertEquals(6, bar.get(2).start());
        Assert.assertEquals(2, bar.get(2).length());
        Assert.assertArrayEquals(new int[]{60, 64, 67}, bar.get(3).pitches());
        Assert.assertEquals(8, bar.get(3).length());
        Assert.assertEquals(8, bar.get(3).start());
    }

    @Test
    public void aNoteLongerThanItsBarIsTiedOverTheBarLine() {
        Score score = Score.of(Arrays.asList("tempo\t108", "track\tcello\t42\t0\tbass",
                "note\tcello\t1440\t1920\t43\t80"));  // begins on the last beat and lasts a whole bar

        Score.Staff staff = score.staves().get(0);
        Assert.assertEquals("a low track is written in the bass clef", Score.Clef.BASS, staff.clef());
        Assert.assertEquals(2, score.bars());
        List<Score.Event> first = staff.bar(0);
        Assert.assertTrue(first.get(0).rest());
        Assert.assertEquals("a dotted half rest is written as a half and a quarter", 8, first.get(0).length());
        Assert.assertEquals(4, first.get(1).length());
        Score.Event begun = first.get(2);
        Assert.assertEquals(4, begun.length());
        Assert.assertTrue(begun.tied());
        List<Score.Event> second = staff.bar(1);
        Assert.assertEquals(12, second.get(0).length());
        Assert.assertTrue(second.get(0).dotted());
        Assert.assertFalse(second.get(0).tied());
        Assert.assertTrue(second.get(1).rest());
        // Every bar is full: its lengths add up to sixteen sixteenths.
        for (int bar = 0; bar < 2; bar++) {
            int sum = 0;
            for (Score.Event event : staff.bar(bar)) {
                Assert.assertEquals(sum, event.start());
                sum += event.length();
            }
            Assert.assertEquals(Score.BAR, sum);
        }
    }

    @Test
    public void anEmptyBarHoldsOneWholeRestAndOneVoiceEndsAChordWhenTheNextBegins() {
        Score score = Score.of(Arrays.asList("tempo\t108", "track\tpiano\t0\t0\tharmony",
                "note\tpiano\t0\t1920\t60\t80",       // would ring through the bar ...
                "note\tpiano\t480\t480\t64\t80",      // ... but the next note begins after a quarter
                "note\tpiano\t3840\t120\t61\t80"));   // a sixteenth in the third bar

        Score.Staff staff = score.staves().get(0);
        Assert.assertEquals(3, score.bars());
        Assert.assertEquals(4, staff.bar(0).get(0).length());
        Assert.assertFalse(staff.bar(0).get(0).tied());
        Assert.assertEquals(1, staff.bar(1).size());
        Assert.assertTrue(staff.bar(1).get(0).rest());
        Assert.assertEquals(16, staff.bar(1).get(0).length());
        Assert.assertEquals(1, staff.bar(2).get(0).length());
    }

    @Test
    public void placesPitchesOnTheStaffAndDrumsOnTheirLines() {
        Assert.assertEquals("E4 is the lowest line of the treble clef", 0, Score.position(64, Score.Clef.TREBLE));
        Assert.assertEquals("middle C is one ledger line below", -2, Score.position(60, Score.Clef.TREBLE));
        Assert.assertEquals("F5 is the top line", 8, Score.position(77, Score.Clef.TREBLE));
        Assert.assertEquals("G2 is the lowest line of the bass clef", 0, Score.position(43, Score.Clef.BASS));
        Assert.assertEquals("middle C is one ledger line above", 10, Score.position(60, Score.Clef.BASS));
        Assert.assertTrue(Score.sharp(61));
        Assert.assertFalse(Score.sharp(60));
        Assert.assertEquals("C sharp sits where C sits", Score.position(60, Score.Clef.TREBLE),
                Score.position(61, Score.Clef.TREBLE));

        Score kit = Score.of(Arrays.asList("tempo\t108", "track\tkit\t0\t9\tdrums", "note\tkit\t0\t240\t36\t90",
                "note\tkit\t0\t240\t42\t90"));
        Assert.assertEquals(Score.Clef.PERCUSSION, kit.staves().get(0).clef());
        Assert.assertTrue("the hi-hat is a cross above the staff", Score.cross(42));
        Assert.assertFalse(Score.cross(36));
        Assert.assertTrue(Score.position(36, Score.Clef.PERCUSSION) < Score.position(38, Score.Clef.PERCUSSION));
    }
}
