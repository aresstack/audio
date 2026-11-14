package com.aresstack.audio.sounds;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parse and validate the strict JSON schema produced by the Gemma sound experiment. */
public final class SoundPlanReader {
    public List<SoundDesign> read(Path input) throws IOException {
        try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            Plan plan = new Gson().fromJson(reader, Plan.class);
            if (plan == null || plan.sounds == null || plan.sounds.length == 0 || plan.sounds.length > 32) {
                throw new IllegalArgumentException("Expected 1..32 sounds");
            }
            List<SoundDesign> sounds = new ArrayList<SoundDesign>();
            Set<String> ids = new HashSet<String>();
            for (Entry entry : plan.sounds) {
                if (entry == null || entry.events == null) {
                    throw new IllegalArgumentException("Missing sound or events");
                }
                List<ToneEvent> events = new ArrayList<ToneEvent>();
                for (Event event : entry.events) {
                    if (event == null || event.waveform == null) {
                        throw new IllegalArgumentException("Missing tone waveform");
                    }
                    Waveform wave = Waveform.valueOf(event.waveform.toUpperCase(Locale.ROOT));
                    events.add(new ToneEvent(wave, event.startMs, event.durationMs, event.startHz,
                            event.endHz, event.volume, event.attackMs, event.releaseMs));
                }
                SoundDesign sound = new SoundDesign(entry.id, entry.durationMs, events);
                if (!ids.add(sound.id())) {
                    throw new IllegalArgumentException("Duplicate sound ID: " + sound.id());
                }
                sounds.add(sound);
            }
            return Collections.unmodifiableList(sounds);
        } catch (JsonParseException error) {
            throw new IllegalArgumentException("Malformed sound-plan JSON", error);
        }
    }

    private static final class Plan { private Entry[] sounds; }
    private static final class Entry { private String id; private int durationMs; private Event[] events; }
    private static final class Event {
        private String waveform;
        private int startMs;
        private int durationMs;
        private double startHz;
        private double endHz;
        private double volume;
        private int attackMs;
        private int releaseMs;
    }
}
