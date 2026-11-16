package com.aresstack.audio.studio.progress;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NotesDoneTest {
    @Test
    public void theShareIsTheBarsWrittenOfTheBarsToWriteOverAllVoices() {
        NotesDone done = new NotesDone();
        assertEquals("nothing is expected yet", 0, done.fraction(), 0);
        done.expect("violin/0", 8);
        done.expect("violin/1", 8);
        done.expect("cello/0", 8);
        done.expect("cello/1", 8);
        assertEquals(0, done.fraction(), 0);

        done.reached("violin/0", 4);
        assertEquals("4 of 32 bars", 0.125, done.fraction(), 1e-9);
        done.whole("violin/0");
        done.reached("cello/0", 2);
        assertEquals("10 of 32 bars", 10 / 32.0, done.fraction(), 1e-9);
    }

    @Test
    public void aJobNeverRunsBackNorPastItsSize() {
        NotesDone done = new NotesDone();
        done.expect("a", 8);
        done.reached("a", 6);
        done.reached("a", 3); // the answer was asked again and starts anew
        assertEquals(0.75, done.fraction(), 1e-9);
        done.reached("a", 20);
        assertEquals(1, done.fraction(), 0);
        done.reached("unknown", 5);
        done.whole("unknown");
        assertEquals("a job nobody expected counts for nothing", 1, done.fraction(), 0);
    }

    @Test
    public void itIsFullOnlyWhenEveryJobIsWhole() {
        NotesDone done = new NotesDone();
        done.expect("a", 8);
        done.expect("b", 16);
        done.whole("a");
        assertEquals(8 / 24.0, done.fraction(), 1e-9);
        done.whole("b"); // also a voice that rests there
        assertEquals(1, done.fraction(), 0);
    }
}
