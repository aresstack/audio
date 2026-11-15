package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Chord;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.progress.Progress;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Repair small, deterministic note-level deviations without changing the model's JSON.
 * Keep a readable audit trail for every change made to the playable arrangement.
 *
 * <p>Formal repairs always happen: the grid of sixteenths, the length of a note, the bounds of
 * the section, the velocity. What happens to a pitch depends on the {@link CompositionSpec}
 * (architecture review E4): with {@link CompositionSpec#snapToHarmony()} a melody note is moved
 * to the nearest tone of the key and a harmony or bass note to the nearest tone of the chord of
 * its bar, as the studio always did; without it a pitch is only moved by octaves into the range
 * of its role, and a tone outside the key is reported, not changed.
 */
public final class CompositionNoteNormalizer {
    private static final double STEP = 0.25;

    private final Progress progress;
    private final CompositionSpec spec;
    private final List<Chord> chords;
    private final List<String> corrections = new ArrayList<String>();

    /** The normalizer of every piece from before F2: eight bars of C major on the locked chords. */
    public CompositionNoteNormalizer(Progress progress) {
        this(progress, CompositionSpec.legacy(), CompositionSpec.LEGACY_CHORDS);
    }

    /**
     * @param chordsByBar the chord of each bar from the plan, as written; bars without one, or
     *                    with a symbol that is no chord, fall back to the scale of the key
     */
    public CompositionNoteNormalizer(Progress progress, CompositionSpec spec, List<String> chordsByBar) {
        this.progress = progress;
        this.spec = spec;
        List<Chord> parsed = new ArrayList<Chord>();
        for (String symbol : chordsByBar == null ? List.<String>of() : chordsByBar) {
            parsed.add(Chord.tryParse(symbol));
        }
        this.chords = parsed;
        corrections.add("part\tnote\tfield\toriginal\tcorrected\treason");
    }

    public NormalizedNote normalize(JsonObject source, String part, int noteIndex) {
        return normalize(source, part, part, noteIndex);
    }

    /** A note of an answer for the whole piece, its beat counted from the start of the piece. */
    public NormalizedNote normalize(JsonObject source, String part, String role, int noteIndex) {
        return normalize(source, part, role, noteIndex, 0, spec.bars());
    }

    /**
     * A note of an answer for one section, its beat counted in quarter notes from the start of the
     * section; the note that is returned counts from the start of the piece.
     *
     * @param firstBar the bar the section begins with, counted from 0
     * @param bars     the bars of the section
     */
    public NormalizedNote normalize(JsonObject source, String part, String role, int noteIndex,
                                    int firstBar, int bars) {
        return normalize(source, part, role, noteIndex, firstBar, bars, range(role));
    }

    /**
     * A note of an answer for one section within the range of pitches given, for example that of
     * a hand of a grand staff ({@link #range(String, boolean, String)}).
     *
     * @param range lowest and highest pitch the note may have
     */
    public NormalizedNote normalize(JsonObject source, String part, String role, int noteIndex,
                                    int firstBar, int bars, int[] range) {
        final double sectionBeats = bars * spec.quartersPerBar();
        final double offset = firstBar * spec.quartersPerBar();
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
        if (!Double.isFinite(beat) || beat < 0 || beat >= sectionBeats) {
            skip(part, noteIndex, "Start beat outside the " + bars + "-bar " + (bars == spec.bars() ? "song" : "section")
                    + ": " + beat);
            return null;
        }
        if (!Double.isFinite(duration) || duration <= 0) {
            skip(part, noteIndex, "Nonpositive or nonfinite duration: " + duration);
            return null;
        }

        double alignedBeat = Math.round(beat / STEP) * STEP;
        if (alignedBeat >= sectionBeats) {
            alignedBeat = sectionBeats - STEP;
        }
        double alignedDuration = Math.round(duration / STEP) * STEP;
        alignedDuration = Math.max(STEP, Math.min(4.0, alignedDuration));
        alignedDuration = Math.min(alignedDuration, sectionBeats - alignedBeat);

        int midiPitch = Math.max(0, Math.min(127, pitch));
        int correctedPitch = normalizePitch(role, offset + alignedBeat, midiPitch, range);
        int correctedVelocity = Math.max(35, Math.min(105, velocity));

        report(part, noteIndex, "beat", beat, alignedBeat, "Align to 16th-note grid");
        report(part, noteIndex, "duration", duration, alignedDuration,
                "Align and fit within the " + format(sectionBeats) + "-beat composition");
        report(part, noteIndex, "pitch", pitch, correctedPitch, spec.snapToHarmony()
                ? "Choose allowed instrument pitch and active chord"
                : "Move by octaves into the range of the instrument");
        report(part, noteIndex, "velocity", velocity, correctedVelocity,
                "Keep velocity in the supported range");
        if (!spec.snapToHarmony() && !"drums".equals(role) && !spec.key().contains(correctedPitch)) {
            corrections.add(part + "\t" + noteIndex + "\tpitch\t" + correctedPitch + "\t" + correctedPitch
                    + "\tOutside " + spec.key().name() + "; kept");
        }
        return new NormalizedNote(offset + alignedBeat, alignedDuration, correctedPitch, correctedVelocity);
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

    private static String format(double beats) {
        return beats == Math.rint(beats) ? String.valueOf((long) beats) : String.valueOf(beats);
    }

    /** The range of the right hand of a grand staff: F below middle C up to C three octaves above it. */
    static final int[] RIGHT_HAND = {53, 96};
    /** The range of the left hand of a grand staff: the low A of the bass up to G above middle C. */
    static final int[] LEFT_HAND = {33, 67};

    /**
     * The lowest and highest pitch of a note (since S1): on a grand staff (piano, organ, harp) the
     * range of the hand the note names, or both hands together if it names none, whatever the
     * role; on a single staff the range of the role, as before.
     *
     * @param voice the voice the note names in the words of {@code score.Voicing}, or {@code null}
     */
    static int[] range(String role, boolean grand, String voice) {
        if (!grand || "drums".equals(role)) {
            return range(role);
        }
        if (voice != null && voice.startsWith("rh")) {
            return RIGHT_HAND;
        }
        if (voice != null && voice.startsWith("lh")) {
            return LEFT_HAND;
        }
        return new int[]{LEFT_HAND[0], RIGHT_HAND[1]};
    }

    /** The lowest and highest pitch of a role. */
    private static int[] range(String role) {
        if ("melody".equals(role)) {
            return new int[]{60, 88};
        }
        if ("texture".equals(role)) {
            return new int[]{48, 88};
        }
        if ("bass".equals(role)) {
            return new int[]{36, 60};
        }
        return new int[]{48, 76};
    }

    /** @param beat the beat of the note in quarter notes from the start of the piece */
    private int normalizePitch(String role, double beat, int pitch, int[] range) {
        if ("drums".equals(role)) {
            if (!spec.snapToHarmony() && pitch >= 35 && pitch <= 81) {
                return pitch; // any sound of the General MIDI kit
            }
            return nearest(pitch, new int[]{36, 38, 42, 46}, 36, 46, false);
        }
        if (!spec.snapToHarmony()) {
            int moved = pitch;
            while (moved < range[0]) {
                moved += 12;
            }
            while (moved > range[1]) {
                moved -= 12;
            }
            return moved >= range[0] ? moved : Math.max(range[0], Math.min(range[1], pitch));
        }
        int[] scale = scale(spec.key());
        if ("melody".equals(role) || "texture".equals(role)) {
            return nearest(pitch, scale, range[0], range[1], true);
        }
        int bar = (int) Math.floor(beat / spec.quartersPerBar() + 1e-9);
        Chord chord = bar < chords.size() ? chords.get(bar) : null;
        return nearest(pitch, chord == null ? scale : chord.tones(), range[0], range[1], true);
    }

    /** The pitch classes a melody may use in a key: its scale, in minor also the raised sixth and seventh. */
    private static int[] scale(Key key) {
        List<Integer> tones = new ArrayList<Integer>();
        for (int pc = 0; pc < 12; pc++) {
            if (key.contains(pc)) {
                tones.add(pc);
            }
        }
        int[] scale = new int[tones.size()];
        for (int i = 0; i < scale.length; i++) {
            scale[i] = tones.get(i);
        }
        return scale;
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
