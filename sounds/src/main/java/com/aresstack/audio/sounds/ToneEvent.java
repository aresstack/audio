package com.aresstack.audio.sounds;

/** Define one bounded oscillator with a frequency sweep and attack/release envelope. */
public final class ToneEvent {
    private final Waveform waveform;
    private final int startMs;
    private final int durationMs;
    private final double startHz;
    private final double endHz;
    private final double volume;
    private final int attackMs;
    private final int releaseMs;

    public ToneEvent(Waveform waveform, int startMs, int durationMs, double startHz, double endHz,
                     double volume, int attackMs, int releaseMs) {
        if (waveform == null || startMs < 0 || durationMs < 1 ||
                !Double.isFinite(startHz) || !Double.isFinite(endHz) ||
                startHz < 20 || endHz < 20 || startHz > 6000 || endHz > 6000 ||
                !Double.isFinite(volume) || volume <= 0 || volume > 0.5 ||
                attackMs < 0 || releaseMs < 0 || attackMs > durationMs || releaseMs > durationMs) {
            throw new IllegalArgumentException("Invalid tone event parameters");
        }
        this.waveform = waveform;
        this.startMs = startMs;
        this.durationMs = durationMs;
        this.startHz = startHz;
        this.endHz = endHz;
        this.volume = volume;
        this.attackMs = attackMs;
        this.releaseMs = releaseMs;
    }

    public Waveform waveform() { return waveform; }
    public int startMs() { return startMs; }
    public int durationMs() { return durationMs; }
    public double startHz() { return startHz; }
    public double endHz() { return endHz; }
    public double volume() { return volume; }
    public int attackMs() { return attackMs; }
    public int releaseMs() { return releaseMs; }
    public int endMs() { return startMs + durationMs; }
}
