package com.aresstack.audio.studio.template;

/**
 * One note of a score given as a template, at {@code 480} ticks per quarter note as every
 * arrangement of the studio.
 *
 * @param tick         where it begins
 * @param length       how long it sounds, more than zero for a note that is kept
 * @param pitch        the MIDI pitch
 * @param velocity     how loud, 1 to 127
 * @param voice        the voice as {@code arrangement.tsv} writes it ({@code 1}, {@code rh},
 *                     {@code lh} ...), or empty where the template says none
 * @param articulation as {@code arrangement.tsv} writes it ({@code staccato} ...), or empty
 */
public record ScoreNote(long tick, long length, int pitch, int velocity, String voice, String articulation) {
    public ScoreNote {
        voice = voice == null ? "" : voice.trim();
        articulation = articulation == null ? "" : articulation.trim();
    }

    public ScoreNote(long tick, long length, int pitch, int velocity) {
        this(tick, length, pitch, velocity, "", "");
    }

    public long end() {
        return tick + length;
    }

    public ScoreNote withLength(long changed) {
        return new ScoreNote(tick, changed, pitch, velocity, voice, articulation);
    }

    public ScoreNote withPitch(int changed) {
        return new ScoreNote(tick, length, changed, velocity, voice, articulation);
    }

    public ScoreNote withVelocity(int changed) {
        return new ScoreNote(tick, length, pitch, changed, voice, articulation);
    }

    public ScoreNote shifted(long by) {
        return new ScoreNote(tick + by, length, pitch, velocity, voice, articulation);
    }
}
