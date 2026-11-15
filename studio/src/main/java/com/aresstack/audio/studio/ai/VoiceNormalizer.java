package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.studio.ai.GeneratedNotes.RawNote;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Repairs the notes of a new voice formally and keeps a record of every repair (architecture
 * review E4, policy of the normalizer): onto the sixteenth grid, at least a sixteenth long, within
 * the frame (a note that would sound past its end, or past an allowed ring-out, is cut there), velocity within MIDI, pitch only
 * limited to the range of the instrument. Notes outside the key are recorded, not changed. A note
 * that begins outside the frame, or a second note of the same pitch at the same tick, is left out.
 */
public final class VoiceNormalizer {
    /** A sixteenth note in ticks: the grid of new notes. */
    public static final long GRID = TimeMap.PPQ / 4;
    private static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};
    private static final int[] MINOR = {0, 2, 3, 5, 7, 8, 10, 11}; // natural minor and the leading note

    /**
     * A repaired note.
     *
     * @param tick  ticks from the start of the frame
     * @param voice the voice the model named for the note, as written, or {@code null}
     */
    public record ShapedNote(long tick, long length, int pitch, int velocity, String voice) {
        public ShapedNote(long tick, long length, int pitch, int velocity) {
            this(tick, length, pitch, velocity, null);
        }
    }

    private final TimeMap time;
    private final InstrumentRange range;
    private final boolean drums;
    private final List<String> corrections = new ArrayList<>();

    public VoiceNormalizer(TimeMap time, InstrumentRange range, boolean drums) {
        this.time = time;
        this.range = range;
        this.drums = drums;
    }

    /** The repairs so far: frame, note, field, original, corrected, reason, tab-separated. */
    public List<String> corrections() {
        return List.copyOf(corrections);
    }

    /** The notes of one frame, repaired, in the order of the answer. */
    public List<ShapedNote> shape(VoiceFrame frame, GeneratedNotes generated) {
        return shape(frame, generated, 0);
    }

    /**
     * The notes of one frame, repaired, where a note may sound up to {@code ringOut} ticks past the
     * end of the frame before it is cut (the revised transition of a continuation rings into the
     * new bars). Notes still begin within the frame.
     */
    public List<ShapedNote> shape(VoiceFrame frame, GeneratedNotes generated, long ringOut) {
        if (ringOut < 0) {
            throw new IllegalArgumentException("A note rings out zero ticks or more: " + ringOut);
        }
        long soundsUntil = frame.endTick() + ringOut;
        corrections.addAll(generated.skipped());
        String where = frame.sectionId();
        List<ShapedNote> shaped = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (RawNote raw : generated.notes()) {
            int index = raw.index();
            if (!Double.isFinite(raw.bar()) || raw.bar() != Math.rint(raw.bar()) || raw.bar() < 1
                    || raw.bar() > frame.bars()) {
                skip(where, index, "Takt " + number(raw.bar()) + " liegt nicht im Abschnitt (1-" + frame.bars() + ")");
                continue;
            }
            long barStart = time.tickOfBar(frame.firstBar() + (int) raw.bar() - 1);
            long barTicks = time.meterAt(barStart).barTicks();
            if (!Double.isFinite(raw.beat()) || raw.beat() < 0 || raw.beat() * TimeMap.PPQ >= barTicks) {
                skip(where, index, "Schlag " + number(raw.beat()) + " liegt nicht im Takt");
                continue;
            }
            if (!Double.isFinite(raw.duration()) || raw.duration() <= 0) {
                skip(where, index, "Dauer " + number(raw.duration()) + " ist nicht positiv");
                continue;
            }
            long offset = Math.round(raw.beat() * TimeMap.PPQ / GRID) * GRID;
            if (offset >= barTicks) {
                offset = barTicks - GRID;
            }
            report(where, index, "beat", raw.beat(), offset / (double) TimeMap.PPQ, "auf Sechzehntelraster");
            long tick = barStart + offset;
            if (tick < frame.startTick() || tick >= frame.endTick()) {
                skip(where, index, "beginnt außerhalb des Abschnitts");
                continue;
            }
            long length = Math.max(GRID, Math.round(raw.duration() * TimeMap.PPQ / GRID) * GRID);
            report(where, index, "duration", raw.duration(), length / (double) TimeMap.PPQ, "auf Sechzehntelraster");
            if (tick + length > soundsUntil) {
                long cut = soundsUntil - tick;
                report(where, index, "duration", length / (double) TimeMap.PPQ, cut / (double) TimeMap.PPQ,
                        ringOut == 0 ? "am Abschnittsende abgeschnitten" : "nach dem Ausklingen abgeschnitten");
                length = cut;
            }
            int pitch = range.clamp(Math.max(0, Math.min(127, raw.pitch())));
            report(where, index, "pitch", raw.pitch(), pitch, "auf den Tonumfang des Instruments begrenzt");
            int velocity = Math.max(1, Math.min(127, raw.velocity()));
            report(where, index, "velocity", raw.velocity(), velocity, "auf den MIDI-Bereich begrenzt");
            if (!taken.add(tick + "/" + pitch)) {
                skip(where, index, "gleiche Tonhöhe zur gleichen Zeit schon vorhanden");
                continue;
            }
            if (!drums && !inKey(time.keyAt(tick), pitch)) {
                corrections.add(where + "\t" + index + "\tpitch\t" + pitch + "\t" + pitch + "\tleiterfremd in "
                        + ContextBuilder.key(time.keyAt(tick)) + " (nicht verändert)");
            }
            shaped.add(new ShapedNote(tick - frame.startTick(), length, pitch, velocity, raw.voice()));
        }
        return shaped;
    }

    private static boolean inKey(Key key, int pitch) {
        int degree = Math.floorMod(pitch - key.tonic(), 12);
        for (int step : key.mode() == Key.Mode.MAJOR ? MAJOR : MINOR) {
            if (step == degree) {
                return true;
            }
        }
        return false;
    }

    private void skip(String where, int index, String reason) {
        corrections.add(where + "\t" + index + "\tnote\tpresent\tskipped\t" + reason);
    }

    private void report(String where, int index, String field, double original, double corrected, String reason) {
        if (Double.compare(original, corrected) != 0) {
            corrections.add(where + "\t" + index + "\t" + field + "\t" + number(original) + "\t" + number(corrected)
                    + "\t" + reason);
        }
    }

    private static String number(double value) {
        return Double.isFinite(value) ? ContextBuilder.number(value) : String.format(Locale.ROOT, "%s", value);
    }
}
