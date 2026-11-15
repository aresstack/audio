package com.aresstack.audio.studio.ai;

import java.io.IOException;

/**
 * A model's answer that holds no notes to read: no list of notes, or more than one answer may
 * hold. The answer is not the model's last word: it is asked again, and a section that still gets
 * no readable answer stays silent instead of failing the whole piece.
 */
public final class UnreadableAnswerException extends IOException {
    public UnreadableAnswerException(String message) {
        super(message);
    }
}
