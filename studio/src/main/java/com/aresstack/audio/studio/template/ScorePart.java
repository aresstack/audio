package com.aresstack.audio.studio.template;

import java.util.List;

/**
 * One part of a score given as a template: one instrument or one staff group, with its notes in
 * the order of their ticks.
 *
 * @param name    what the template calls it, e.g. "Violino principale"; may be empty
 * @param program the General MIDI program the template names, or {@code -1} if it names none
 * @param drums   whether the template plays it on the drum channel
 * @param staves  how many staves the template writes it on (2 for piano or harp)
 */
public record ScorePart(String name, int program, boolean drums, int staves, List<ScoreNote> notes) {
    public ScorePart {
        name = name == null ? "" : name.trim();
        program = program < 0 || program > 127 ? -1 : program;
        staves = Math.max(1, staves);
        notes = List.copyOf(notes);
    }

    public ScorePart withNotes(List<ScoreNote> changed) {
        return new ScorePart(name, program, drums, staves, changed);
    }

    /** The lowest and the highest pitch, or {@code {0, 0}} without notes. */
    public int[] range() {
        int low = 127;
        int high = 0;
        for (ScoreNote note : notes) {
            low = Math.min(low, note.pitch());
            high = Math.max(high, note.pitch());
        }
        return notes.isEmpty() ? new int[]{0, 0} : new int[]{low, high};
    }

    /** The pitch in the middle of all its notes, or 60 without notes. */
    public int medianPitch() {
        if (notes.isEmpty()) {
            return 60;
        }
        int[] pitches = notes.stream().mapToInt(ScoreNote::pitch).sorted().toArray();
        return pitches[pitches.length / 2];
    }

    public long end() {
        long end = 0;
        for (ScoreNote note : notes) {
            end = Math.max(end, note.end());
        }
        return end;
    }
}
