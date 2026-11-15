package com.aresstack.audio.studio.ai;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the notes of one part for one frame of a continuation: a planned section, or the revised
 * tail of the original (architecture review 2.4: generators per part and section). It gives the
 * notes as the model wrote them; repairs are the normalizer's business, and the patch is built by
 * the application.
 */
@FunctionalInterface
public interface SectionGenerator {
    /** The frame id of the revised last bars of the original. */
    String TRANSITION = "transition";

    /**
     * @param connection the last notes the part played before this frame, in words, or empty
     * @throws ForeignPartException if the answer is for another part or frame
     */
    GeneratedNotes generate(ContinuationTask task, ContinuationPlan plan, ContinuationVoice voice, VoiceFrame frame,
                            String connection, Path cacheDirectory) throws IOException;
}
