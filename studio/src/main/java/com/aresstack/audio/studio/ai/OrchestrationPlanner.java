package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Plans a re-orchestration before any note is written (architecture review 2.4, E4): one plan of
 * who plays what in each frame, for all parts.
 */
@FunctionalInterface
public interface OrchestrationPlanner {
    /** @return a plan with one entry per frame of the task */
    OrchestrationPlan plan(OrchestrationTask task, Composition piece, Path cacheDirectory) throws IOException;
}
