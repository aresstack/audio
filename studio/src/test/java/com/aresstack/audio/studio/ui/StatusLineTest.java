package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StatusLineTest {
    @Test
    public void theBarStandsForTheShareThatIsDone() {
        StatusLine line = new StatusLine("Bereit");
        assertTrue("no bar while nothing is at work", line.progress() < 0);
        line.progress(0.25);
        assertEquals(0.25, line.progress(), 0);
        line.progress(0.6);
        assertEquals(0.6, line.progress(), 0);
        line.endProgress();
        assertTrue("work that ends before it is full leaves no bar", line.progress() < 0);
    }

    @Test
    public void aFullBarGoesAwayAtOnceIfTheSettingsSaySo() {
        StatusLine line = new StatusLine("Bereit");
        line.setLingerSeconds(0);
        line.progress(0.9);
        line.progress(1);
        assertTrue(line.progress() < 0);
    }

    @Test
    public void aFullBarStaysForItsTimeAndWorkThatEndsLeavesItThere() {
        StatusLine line = new StatusLine("Bereit");
        line.setLingerSeconds(3);
        line.progress(0.9);
        line.progress(1);
        assertEquals(1, line.progress(), 0);
        line.endProgress();
        assertEquals("it goes away after its time, not with the end of the work", 1, line.progress(), 0);
        line.clearProgress();
        assertTrue(line.progress() < 0);
    }

    @Test
    public void workThatIsDoneBeforeAnythingWasShownShowsNoBar() {
        StatusLine line = new StatusLine("Bereit");
        line.progress(1);
        assertTrue(line.progress() < 0);
    }
}
