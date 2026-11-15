package com.aresstack.audio.studio.ai;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the notes of one part for one frame (architecture review 2.4: generators per part and
 * section). It gives the notes as the model wrote them; repairs are the normalizer's business,
 * and the patch is built by the application, never by the generator.
 */
@FunctionalInterface
public interface PartGenerator {
    /**
     * @param connection the last notes the new voice played before this frame, in words, or empty
     * @throws ForeignPartException if the answer is for another part or frame
     */
    GeneratedNotes generate(VoiceTask task, VoicePlan plan, VoiceFrame frame, String connection, Path cacheDirectory)
            throws IOException;
}
