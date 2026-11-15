package com.aresstack.audio.gpu;

/** The graphics card cannot draw the visualizer here; the message says why, in German, for the status line. */
public final class GpuUnavailableException extends Exception {
    public GpuUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
