package com.aresstack.audio.sounds;

import java.util.List;

/** Define one validated, original game-event sound effect. */
public final class SoundDesign {
    private final String id;
    private final int durationMs;
    private final List<ToneEvent> events;

    public SoundDesign(String id, int durationMs, List<ToneEvent> events) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Sound ID must be a safe file stem");
        }
        if (durationMs < 100 || durationMs > 10000) {
            throw new IllegalArgumentException("Sound duration must be 100..10000 ms");
        }
        if (events == null || events.isEmpty() || events.size() > 64) {
            throw new IllegalArgumentException("Sound must contain 1..64 tone events");
        }
        for (ToneEvent event : events) {
            if (event == null || event.endMs() > durationMs) {
                throw new IllegalArgumentException("Invalid or out-of-bounds event in " + id);
            }
        }
        this.id = id;
        this.durationMs = durationMs;
        this.events = java.util.Collections.unmodifiableList(new java.util.ArrayList<ToneEvent>(events));
    }

    public String id() { return id; }
    public int durationMs() { return durationMs; }
    public List<ToneEvent> events() { return events; }
}
