package com.aresstack.audio.studio.render;

import java.io.IOException;

/** Distinguish a sampler that cannot be launched from a sampler that fails during rendering. */
public final class SamplerUnavailableException extends IOException {
    private final String executable;

    public SamplerUnavailableException(String executable, IOException cause) {
        super("Cannot start sampler '" + executable + "'. Set its executable path in Settings.", cause);
        this.executable = executable;
    }

    public String executable() {
        return executable;
    }
}
