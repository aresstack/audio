package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.progress.Progress;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Repair small, deterministic note-level deviations without changing the model's JSON.
 * Keep a readable audit trail for every change made to the playable arrangement.
 */
public final class CompositionNoteNormalizer {
    private static final double SONG_BEATS = 32.0;
    private static final double STEP = 0.25;
    private static final String[] CHORDS = {"C", "G", "Am", "F", "C", "G", "F", "G"};

    private final Progress progress;
    private final List<String> corrections = new ArrayList<String>();

    public CompositionNoteNormalizer(Progress progress) {
        this.progress = progress;
        corrections.add("part\tnote\tfield\toriginal\tcorrected\treason");
    }

    public NormalizedNote normalize(JsonObject source, String part, int noteIndex) {
        return normalize(source, part, part, noteIndex);
    }

    public NormalizedNote normalize(JsonObject source, String part, String role, int noteIndex) {
        final double beat;
        final double duration;
        final int pitch;
        final int velocity;
        try {
            beat = source.get("beat").getAsDouble();
            duration = source.get("duration").getAsDouble();
            pitch = source.get("pitch").getAsInt();
            velocity = source.get("velocity").getAsInt();
        } catch (RuntimeException invalid) {
            skip(part, noteIndex, "Malformed or missing note values");
            return null;
        }
        if (!Double.isFinite(beat) || beat < 0 || beat >= SONG_BEATS) {
            skip(part, noteIndex, "Start beat outside the eight-bar song: " + beat);
            return null;
        }
        if (!Double.isFinite(duration) || duration <= 0) {
            skip(part, noteIndex, "Nonpositive or nonfinite duration: " + duration);
            return null;
        }

        double alignedBeat = Math.round(beat / STEP) * STEP;
        if (alignedBeat >= SONG_BEATS) {
            alignedBeat = SONG_BEATS - STEP;
        }
        double alignedDuration = Math.round(duration / STEP) * STEP;
        alignedDuration = Math.max(STEP, Math.min(4.0, alignedDuration));
        alignedDuration = Math.min(alignedDuration, SONG_BEATS - alignedBeat);

        int midiPitch = Math.max(0, Math.min(127, pitch));
        int correctedPitch = normalizePitch(role, alignedBeat, midiPitch);
        int correctedVelocity = Math.max(35, Math.min(105, velocity));

        report(part, noteIndex, "beat", beat, alignedBeat, "Align to 16th-note grid");
        report(part, noteIndex, "duration", duration, alignedDuration,
                "Align and fit within the 32-beat composition");
        report(part, noteIndex, "pitch", pitch, correctedPitch,
                "Choose allowed instrument pitch and active chord");
        report(part, noteIndex, "velocity", velocity, correctedVelocity,
                "Keep velocity in the supported range");
        return new NormalizedNote(alignedBeat, alignedDuration, correctedPitch, correctedVelocity);
    }

    public List<String> corrections() {
        return Collections.unmodifiableList(corrections);
    }

    public int correctionCount() {
        return corrections.size() - 1;
    }

    private void skip(String part, int noteIndex, String reason) {
        corrections.add(part + "\t" + noteIndex + "\tnote\tpresent\tskipped\t" + reason);
        progress.update("WARN: Skipped " + part + " note " + noteIndex + ": " + reason);
    }

    private void report(String part, int index, String field, double original, double corrected,
                        String reason) {
        if (Double.compare(original, corrected) == 0) {
            return;
        }
        corrections.add(part + "\t" + index + "\t" + field + "\t" + original
                + "\t" + corrected + "\t" + reason);
        progress.update("WARN: Repaired " + part + " note " + index + " " + field
                + " " + original + " -> " + corrected);
    }

    private int normalizePitch(String role, double beat, int pitch) {
        if ("drums".equals(role)) {
            return nearest(pitch, new int[]{36, 38, 42, 46}, 36, 46, false);
        }
        if ("melody".equals(role)) {
            return nearest(pitch, new int[]{0, 2, 4, 5, 7, 9, 11}, 60, 88, true);
        }
        String chord = CHORDS[(int) Math.floor(beat / 4)];
        int[] chordTones = "C".equals(chord) ? new int[]{0, 4, 7}
                : "G".equals(chord) ? new int[]{7, 11, 2}
                : "Am".equals(chord) ? new int[]{9, 0, 4} : new int[]{5, 9, 0};
        if ("texture".equals(role)) {
            return nearest(pitch, new int[]{0, 2, 4, 5, 7, 9, 11}, 48, 88, true);
        }
        return nearest(pitch, chordTones, "bass".equals(role) ? 36 : 48,
                "bass".equals(role) ? 60 : 76, true);
    }

    private static int nearest(int pitch, int[] allowed, int minimum, int maximum,
                               boolean pitchClasses) {
        int closest = minimum;
        int difference = Integer.MAX_VALUE;
        for (int candidate = minimum; candidate <= maximum; candidate++) {
            int key = pitchClasses ? candidate % 12 : candidate;
            for (int expected : allowed) {
                int distance = Math.abs(candidate - pitch);
                if (expected == key && distance < difference) {
                    closest = candidate;
                    difference = distance;
                }
            }
        }
        return closest;
    }

    public static final class NormalizedNote {
        private final double beat;
        private final double duration;
        private final int pitch;
        private final int velocity;

        private NormalizedNote(double beat, double duration, int pitch, int velocity) {
            this.beat = beat;
            this.duration = duration;
            this.pitch = pitch;
            this.velocity = velocity;
        }

        public long startTick() {
            return Math.round(beat * 480);
        }

        public long durationTicks() {
            return Math.round(duration * 480);
        }

        public int pitch() {
            return pitch;
        }

        public int velocity() {
            return velocity;
        }
    }
}
