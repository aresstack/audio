package com.aresstack.audio.sounds;

/** Support a small controlled vocabulary for model-generated oscillator shapes. */
public enum Waveform {
    SINE, TRIANGLE, SQUARE;

    public double sample(double phase) {
        switch (this) {
            case SINE: return Math.sin(2 * Math.PI * phase);
            case TRIANGLE: return 1.0 - 4.0 * Math.abs(phase - 0.5);
            case SQUARE: return phase < 0.5 ? 1.0 : -1.0;
            default: throw new IllegalStateException("Unsupported waveform");
        }
    }
}
