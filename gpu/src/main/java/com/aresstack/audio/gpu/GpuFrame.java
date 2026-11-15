package com.aresstack.audio.gpu;

/**
 * What one picture on the graphics card shows: filled by the studio on the render thread before
 * every picture, read by the shaders. The arrays keep their length.
 */
public final class GpuFrame {
    /** The bands of the spectrum from low to high, 0 to 1. */
    public final float[] bands;
    /** The waveform of the window, mono, -1 to 1, as many points as there are bands. */
    public final float[] wave;
    public float bass;
    public float middle;
    public float treble;
    /** The flash of the last beat, 1 down to 0. */
    public float beat;
    /** The overall loudness, 0 to 1. */
    public float loudness;
    /** Seconds since the screen opened. */
    public float seconds;
    /** The effect to draw ({@link GpuScreen#EFFECTS}); one of them if unknown. */
    public String effect = GpuScreen.EFFECTS.get(0);

    public GpuFrame(int points) {
        bands = new float[Math.max(1, points)];
        wave = new float[Math.max(1, points)];
    }
}
