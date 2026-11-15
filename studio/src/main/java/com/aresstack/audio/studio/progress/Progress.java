package com.aresstack.audio.studio.progress;

/**
 * Hears how a longer piece of work is getting on: composing, rendering, mixing. It belongs to
 * none of them, so that rendering and mixing do not depend on the AI composer.
 */
@FunctionalInterface
public interface Progress {
    void update(String message);
}
