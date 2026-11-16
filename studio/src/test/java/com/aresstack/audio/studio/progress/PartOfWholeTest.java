package com.aresstack.audio.studio.progress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** A step of a long piece tells how far the notes are as a share of the whole piece. */
public class PartOfWholeTest {
    @Test
    public void theShareOfAStepIsItsPartOfTheWhole() {
        List<Double> told = new ArrayList<Double>();
        List<String> tracks = new ArrayList<String>();
        SketchProgress whole = new SketchProgress() {
            @Override
            public void sketch(List<String> arrangement) {
            }

            @Override
            public void update(String message) {
            }

            @Override
            public void track(String trackId, Stage stage, State state, String detail) {
                tracks.add(trackId + " " + state + " " + detail);
            }

            @Override
            public void done(double share) {
                told.add(share);
            }
        };
        SketchProgress second = (SketchProgress) PartOfWhole.of(whole, 0.25, 0.5);
        second.done(0);
        second.done(0.5);
        second.done(1);
        second.track("bass", TrackProgress.Stage.COMPOSE, TrackProgress.State.WORKING, "Abschnitt 1/4");
        assertEquals(List.of(0.25, 0.375, 0.5), told);
        assertEquals(List.of("bass WORKING Abschnitt 1/4"), tracks);
        Progress plain = message -> { };
        assertSame("a progress without a sketch is passed on as it is", plain, PartOfWhole.of(plain, 0, 1));
    }
}
