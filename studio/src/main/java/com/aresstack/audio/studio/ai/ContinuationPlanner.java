package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Plans a continuation before any note is written (report 5.2, architecture review 2.4): one
 * plan for all parts. It is shared by every kind of change that adds bars ({@code APPEND} now,
 * {@code INSERT} and composing from scratch later): the plan only depends on the task.
 */
@FunctionalInterface
public interface ContinuationPlanner {
    /**
     * @param piece          the piece as it is, for the chords of the original
     * @param cacheDirectory where the answer of a model is kept
     * @return a plan that covers exactly the new bars of the task
     */
    ContinuationPlan plan(ContinuationTask task, Composition piece, Path cacheDirectory) throws IOException;
}
