package com.aresstack.audio.studio.template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sets right what is plainly wrong in a template, without asking anyone, and finds the places a
 * musician would look at twice. Plainly wrong: a note without length, a pitch or loudness MIDI
 * does not have, the same note twice at the same time, a note that still sounds when the same
 * pitch begins again. Worth a second look: a single note an octave or more away from the notes
 * before and after it, which are close to each other (a wrong octave in a hand-written score).
 * What is worth a look is not changed here; the AI decides it ({@link TemplateReview}).
 */
public final class TemplateCleanup {
    /** The loudness of a note whose loudness is not given or impossible. */
    public static final int DEFAULT_VELOCITY = 80;
    /** A leap of more than this many semitones away and back is an outlier. */
    public static final int OUTLIER_SEMITONES = 12;

    private TemplateCleanup() {
    }

    /**
     * A place worth a second look.
     *
     * @param part  the index of the part in the template
     * @param note  the index of the note in its part
     * @param why   in words, for the AI
     */
    public record Flag(int part, int note, String why) {
    }

    public static TemplateScore clean(TemplateScore score) {
        List<ScorePart> parts = new ArrayList<ScorePart>();
        int lengthless = 0;
        int impossible = 0;
        int doubled = 0;
        int overlapping = 0;
        int loudness = 0;
        int empty = 0;
        for (ScorePart part : score.parts()) {
            List<ScoreNote> kept = new ArrayList<ScoreNote>();
            for (ScoreNote note : part.notes()) {
                if (note.length() <= 0) {
                    lengthless++;
                    continue;
                }
                if (note.pitch() < 0 || note.pitch() > 127 || note.tick() < 0) {
                    impossible++;
                    continue;
                }
                if (note.velocity() < 1 || note.velocity() > 127) {
                    loudness++;
                    note = note.withVelocity(note.velocity() > 127 ? 127 : DEFAULT_VELOCITY);
                }
                kept.add(note);
            }
            kept.sort(Comparator.comparingLong(ScoreNote::tick).thenComparingInt(ScoreNote::pitch)
                    .thenComparing(Comparator.comparingLong(ScoreNote::length).reversed()));
            // The same pitch twice at once: the longer stays. Still sounding when it begins again: ends there.
            List<ScoreNote> single = new ArrayList<ScoreNote>();
            Map<Integer, Integer> lastOfPitch = new HashMap<Integer, Integer>();
            for (ScoreNote note : kept) {
                Integer last = lastOfPitch.get(note.pitch());
                if (last != null) {
                    ScoreNote before = single.get(last);
                    if (before.tick() == note.tick()) {
                        doubled++;
                        continue;
                    }
                    if (before.end() > note.tick()) {
                        single.set(last, before.withLength(note.tick() - before.tick()));
                        overlapping++;
                    }
                }
                lastOfPitch.put(note.pitch(), single.size());
                single.add(note);
            }
            if (single.isEmpty()) {
                empty++;
                continue;
            }
            parts.add(part.withNotes(single));
        }
        List<String> remarks = new ArrayList<String>();
        add(remarks, lengthless, " Noten ohne Länge weggelassen");
        add(remarks, impossible, " Noten außerhalb des MIDI-Umfangs weggelassen");
        add(remarks, doubled, " doppelte Noten weggelassen");
        add(remarks, overlapping, " Noten gekürzt, die beim erneuten Anschlag derselben Tonhöhe noch klangen");
        add(remarks, loudness, " Lautstärken ohne gültigen Wert auf " + DEFAULT_VELOCITY + " gesetzt");
        add(remarks, empty, " Stimmen ohne spielbare Noten weggelassen");
        return score.withParts(parts, remarks);
    }

    private static void add(List<String> remarks, int count, String what) {
        if (count > 0) {
            remarks.add(count + what + ".");
        }
    }

    /**
     * The places worth a second look, at most {@code most}, in the order of the parts and their
     * notes. Only lines of single notes are judged; in chords a wide spread is usual.
     */
    public static List<Flag> flags(TemplateScore score, int most) {
        List<Flag> flags = new ArrayList<Flag>();
        for (int p = 0; p < score.parts().size() && flags.size() < most; p++) {
            ScorePart part = score.parts().get(p);
            if (part.drums()) {
                continue;
            }
            List<ScoreNote> notes = part.notes();
            for (int n = 1; n + 1 < notes.size() && flags.size() < most; n++) {
                ScoreNote before = notes.get(n - 1);
                ScoreNote note = notes.get(n);
                ScoreNote after = notes.get(n + 1);
                boolean alone = before.tick() < note.tick() && note.tick() < after.tick()
                        && (n < 2 || notes.get(n - 2).tick() < before.tick())
                        && (n + 2 >= notes.size() || after.tick() < notes.get(n + 2).tick());
                if (!alone) {
                    continue;
                }
                int away = Math.min(Math.abs(note.pitch() - before.pitch()), Math.abs(note.pitch() - after.pitch()));
                int between = Math.abs(before.pitch() - after.pitch());
                if (away > OUTLIER_SEMITONES && between <= OUTLIER_SEMITONES / 2
                        && Integer.signum(note.pitch() - before.pitch()) == Integer.signum(note.pitch() - after.pitch())) {
                    flags.add(new Flag(p, n, "a single note " + away + " semitones away from the notes around it ("
                            + before.pitch() + ", " + note.pitch() + ", " + after.pitch() + ")"));
                }
            }
        }
        return flags;
    }
}
