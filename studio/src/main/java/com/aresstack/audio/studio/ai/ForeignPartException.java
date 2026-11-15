package com.aresstack.audio.studio.ai;

import java.io.IOException;

/**
 * A model answered for another part or section than it was asked for. The answer is refused as a
 * whole, not repaired: the AI never addresses objects other than its target (architecture review
 * E5, first safeguard).
 */
public final class ForeignPartException extends IOException {
    public ForeignPartException(String message) {
        super(message);
    }
}
