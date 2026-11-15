package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a new piece is to be: how many bars, how fast, in which key and meter (architecture
 * review 6.1 F2). The composer plans and composes against it, the normalizer repairs against it,
 * and the arrangement it writes says it. One tempo, one meter and one key for the whole piece;
 * the model could hold changes, the request cannot ask for them yet.
 *
 * @param bars          the length of the piece in bars, 1 to {@link #MAX_BARS}
 * @param bpm           quarter notes per minute, {@link #MIN_BPM} to {@link #MAX_BPM}
 * @param key           the key of the piece
 * @param numerator     beats per bar, 1 to 32
 * @param denominator   the note value of a beat: 1, 2, 4, 8 or 16
 * @param snapToHarmony whether the normalizer moves pitches onto the scale and the chord of the
 *                      bar (as the studio always did), or only reports notes outside the key and
 *                      keeps them (review E4: "logged, not silently changed")
 */
public record CompositionSpec(int bars, int bpm, Key key, int numerator, int denominator, boolean snapToHarmony) {
    public static final int MIN_BPM = 20;
    public static final int MAX_BPM = 400;
    public static final int MAX_BARS = 1024;

    /** The chords every piece from before F2 was locked to. */
    public static final List<String> LEGACY_CHORDS = List.of("C", "G", "Am", "F", "C", "G", "F", "G");

    /** At most this many bars are composed in one request (review E4: 8 to 16 bars per call). */
    public static final int MAX_SECTION_BARS = 16;
    /** Longer pieces are composed in sections of about this many bars. */
    public static final int SECTION_BARS = 8;

    public CompositionSpec {
        if (bars < 1 || bars > MAX_BARS) {
            throw new IllegalArgumentException("Ein Stück hat 1 bis " + MAX_BARS + " Takte: " + bars);
        }
        if (bpm < MIN_BPM || bpm > MAX_BPM) {
            throw new IllegalArgumentException("Das Tempo liegt zwischen " + MIN_BPM + " und " + MAX_BPM + " BPM: " + bpm);
        }
        if (key == null) {
            throw new IllegalArgumentException("Ein Stück braucht eine Tonart.");
        }
        if (numerator < 1 || numerator > 32 || denominator < 1 || denominator > 16
                || Integer.bitCount(denominator) != 1) {
            throw new IllegalArgumentException("Keine Taktart: " + numerator + "/" + denominator);
        }
    }

    /** What every piece was before F2: 8 bars of 4/4 at 108 BPM in C major, pitches pulled onto the harmony. */
    public static CompositionSpec legacy() {
        return new CompositionSpec(8, 108, Key.C_MAJOR, 4, 4, true);
    }

    /** A new piece; notes outside the key are reported and kept. */
    public static CompositionSpec of(int bars, int bpm, Key key, int numerator, int denominator) {
        return new CompositionSpec(bars, bpm, key, numerator, denominator, false);
    }

    /** Whether this is exactly what every piece was before F2; then prompts and files are as they were. */
    public boolean isLegacy() {
        return equals(legacy());
    }

    public CompositionSpec withSnapToHarmony(boolean snap) {
        return new CompositionSpec(bars, bpm, key, numerator, denominator, snap);
    }

    /** For example {@code "4/4"}, {@code "6/8"}. */
    public String meter() {
        return numerator + "/" + denominator;
    }

    /** Whether the meter is four quarters to the bar. */
    public boolean fourFour() {
        return numerator == 4 && denominator == 4;
    }

    /** Quarter notes per bar: 4 for 4/4, 3 for 3/4 and 6/8. */
    public double quartersPerBar() {
        return numerator * 4.0 / denominator;
    }

    /** The length of the piece in quarter notes. */
    public double quarters() {
        return bars * quartersPerBar();
    }

    public long barTicks() {
        return (long) TimeMap.PPQ * 4 * numerator / denominator;
    }

    public long ticks() {
        return bars * barTicks();
    }

    /** How long the piece lasts. */
    public double seconds() {
        return quarters() * 60.0 / bpm;
    }

    /** The time map of a piece of this spec: one tempo, one meter, one key. */
    public TimeMap timeMap() {
        return new TimeMap(List.of(new TimeMap.Tempo(0, bpm)), List.of(new TimeMap.Meter(0, numerator, denominator)),
                List.of(new TimeMap.KeyChange(0, key)));
    }

    /** As the studio shows it: {@code "24 Takte · 4/4 · 96 BPM · d-Moll"}. */
    public String label() {
        return bars + (bars == 1 ? " Takt" : " Takte") + " · " + meter() + " · " + bpm + " BPM · " + key.germanName();
    }

    /**
     * The sections the piece is composed in, as {@code {first bar, bars}}: the whole piece in one
     * when it has at most {@link #MAX_SECTION_BARS} bars, else sections of {@link #SECTION_BARS}
     * bars, the last one taking a remainder of fewer than four bars.
     */
    public List<int[]> sections() {
        List<int[]> sections = new ArrayList<>();
        if (bars <= MAX_SECTION_BARS) {
            sections.add(new int[]{0, bars});
            return sections;
        }
        int start = 0;
        while (start < bars) {
            int length = Math.min(SECTION_BARS, bars - start);
            int rest = bars - start - length;
            if (rest > 0 && rest < 4) {
                length += rest;
            }
            sections.add(new int[]{start, length});
            start += length;
        }
        return sections;
    }

    /**
     * The lines that open an arrangement of this spec: the tempo and, where the piece differs
     * from what every piece was before F2, meter, key and length. A piece of the legacy spec
     * opens with the tempo line only, as before.
     *
     * @param barsCovering the bars the notes of the piece reach into
     */
    public List<String> headerLines(int barsCovering) {
        List<String> lines = new ArrayList<>();
        lines.add("tempo\t" + bpm);
        if (!fourFour()) {
            lines.add("meter\t" + numerator + "\t" + denominator);
        }
        if (!key.equals(Key.C_MAJOR)) {
            lines.add("key\t" + key.tonicName() + "\t" + (key.mode() == Key.Mode.MAJOR ? "major" : "minor"));
        }
        if (ArrangementFile.needsBarsLine(bars, barsCovering, fourFour() && key.equals(Key.C_MAJOR))) {
            lines.add("bars\t" + bars);
        }
        return lines;
    }

    /**
     * The spec of a piece that was planned before: from its {@code composition-plan.json}, or
     * {@code null} if there is none or it cannot be read. A plan names tempo, key, meter and bars
     * as the composer asked for them.
     */
    public static CompositionSpec ofPlan(Path directory) {
        Path plan = directory.resolve("composition-plan.json");
        if (!Files.isRegularFile(plan)) {
            return null;
        }
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(
                    new String(Files.readAllBytes(plan), StandardCharsets.UTF_8)).getAsJsonObject();
            return ofPlan(json);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The spec a plan names, or {@code null} if it does not name all of it. */
    static CompositionSpec ofPlan(JsonObject plan) {
        try {
            String[] meter = plan.get("timeSignature").getAsString().trim().split("/");
            CompositionSpec spec = new CompositionSpec(plan.get("bars").getAsInt(), plan.get("tempoBpm").getAsInt(),
                    Key.parse(plan.get("key").getAsString()), Integer.parseInt(meter[0].trim()),
                    Integer.parseInt(meter[1].trim()), false);
            if (plan.has("snapToHarmony")) {
                return spec.withSnapToHarmony(plan.get("snapToHarmony").getAsBoolean());
            }
            // A plan from before F2 has no such field; it was the legacy spec, which snaps.
            return spec.withSnapToHarmony(spec.withSnapToHarmony(true).isLegacy());
        } catch (RuntimeException incomplete) {
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%d bars, %s, %d BPM, %s", bars, meter(), bpm, key.name());
    }
}
