package com.aresstack.audio.studio.ai;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the notes of one part for one frame of a region (work package C2; architecture review
 * 2.4, generators per part and section). It gives the notes as the model wrote them; repairs are
 * the normalizer's business, and the patch is built by the application.
 */
@FunctionalInterface
public interface RegionGenerator {
    /**
     * @param connection what the part plays just before this frame, in bars of the piece: the end
     *                   of the previous frame as just written, or for the first frame the notes
     *                   before the region
     * @throws ForeignPartException if the answer is for another part or frame
     */
    GeneratedNotes generate(RegionTask task, VoiceFrame frame, boolean last, String connection, Path cacheDirectory)
            throws IOException;
}
