package com.aresstack.audio.studio.progress;

import java.util.List;

/**
 * A listener that also wants to see the piece while it is being composed: what the model has
 * written so far, as the lines of an arrangement that is not finished and is kept nowhere.
 * Called from the worker threads, a few times a second at most.
 */
public interface SketchProgress extends TrackProgress {
    /**
     * @param arrangement the lines of an arrangement with the notes written so far; tracks
     *                    without a note yet are left out
     */
    void sketch(List<String> arrangement);

    /**
     * How much of the answers of the model has arrived so far, over all tracks that are being
     * composed: nothing while the service lets the requests wait, more with every piece of text.
     */
    default void arriving(long characters) {
    }

    /**
     * What the AI does before the first note: choosing the instruments, planning form and
     * harmonies. Told when the step begins and again while its answer arrives.
     *
     * @param what       the step, as the studio says it
     * @param characters how much of the answer has arrived so far
     */
    default void preparing(String what, long characters) {
    }

    /** The plan is there: the chords of the piece, bar by bar. The notes come next. */
    default void planned(List<String> chords) {
    }

    /**
     * The form of the piece as far as the plan has said it: told again with every section and
     * chord that arrives, and once more when the plan is whole.
     */
    default void form(List<FormSection> sections) {
    }
}
