package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class SketchFrontTest {
    private static final long BAR = 1920;

    private static String note(String track, int bar, int pitch) {
        return "note\t" + track + "\t" + bar * BAR + "\t480\t" + pitch + "\t80";
    }

    @Test
    public void theFirstPictureIsWhatIsThereAndTheNewNotesAreFollowed() {
        SketchFront front = new SketchFront();
        front.begin();
        // a piece of 280 bars is lengthened: the first picture is the piece as it is
        List<String> piece = List.of("tempo\t108", "track\tviolin\t40\t0", note("violin", 0, 60), note("violin", 279, 62));
        assertEquals("nothing of the piece as it is is new", -1, front.next(piece, BAR));

        List<String> more = List.of("tempo\t108", "track\tviolin\t40\t0", note("violin", 0, 60), note("violin", 279, 62),
                note("violin", 280, 64), note("violin", 281, 65));
        assertEquals("the view goes where the new notes begin", 280, front.next(more, BAR));
        assertEquals("and stays while nothing new comes", -1, front.next(more, BAR));
    }

    @Test
    public void theViewNeverRunsBackBetweenVoicesThatWriteAtDifferentPlaces() {
        SketchFront front = new SketchFront();
        front.begin();
        assertEquals(-1, front.next(List.of(note("violin", 0, 60)), BAR));
        assertEquals(8, front.next(List.of(note("violin", 0, 60), note("violin", 8, 62)), BAR));
        assertEquals("a slower voice does not pull the view back", -1,
                front.next(List.of(note("violin", 0, 60), note("violin", 8, 62), note("cello", 3, 48)), BAR));
        assertEquals(9, front.next(List.of(note("violin", 0, 60), note("violin", 8, 62), note("cello", 3, 48),
                note("violin", 9, 64), note("cello", 12, 50)), BAR));
    }

    @Test
    public void aNewPieceOfWorkBeginsAnew() {
        SketchFront front = new SketchFront();
        front.begin();
        front.next(List.of(note("violin", 0, 60)), BAR);
        assertEquals(40, front.next(List.of(note("violin", 0, 60), note("violin", 40, 62)), BAR));
        front.begin();
        assertEquals(-1, front.next(List.of(note("violin", 0, 60), note("violin", 40, 62)), BAR));
        assertEquals("an insertion early in the piece is followed again", 4,
                front.next(List.of(note("violin", 0, 60), note("violin", 40, 62), note("violin", 4, 61)), BAR));
    }
}
