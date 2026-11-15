package com.aresstack.audio.studio.ai;

import java.io.IOException;
import java.nio.file.Path;

/** Decides where and how a new voice plays before its notes are written (architecture review 2.4). */
@FunctionalInterface
public interface VoicePlanner {
    /**
     * @param cacheDirectory where the answer of a model is kept
     * @throws ForeignPartException if the plan is for another part
     */
    VoicePlan plan(VoiceTask task, Path cacheDirectory) throws IOException;
}
