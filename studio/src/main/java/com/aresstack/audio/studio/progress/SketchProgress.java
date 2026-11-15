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
}
