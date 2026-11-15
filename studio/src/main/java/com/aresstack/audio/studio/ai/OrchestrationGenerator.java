package com.aresstack.audio.studio.ai;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the notes of one part for one frame of a re-orchestration (architecture review 2.4:
 * generators per part and section), after its assignment in the plan and from the material of the
 * passage as it is. Repairs are the normalizer's business, the patch is the application's.
 */
@FunctionalInterface
public interface OrchestrationGenerator {
    /**
     * @param material   the notes of the chosen parts in this frame as they are now, in words
     * @param connection the last notes the part played before this frame, in words, or empty
     * @throws ForeignPartException if the answer is for another part or frame
     */
    GeneratedNotes generate(OrchestrationTask task, OrchestrationPlan plan, ContinuationVoice voice, VoiceFrame frame,
                            OrchestrationPlan.Assignment assignment, String material, String connection,
                            Path cacheDirectory) throws IOException;
}
