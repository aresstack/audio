package com.aresstack.audio.studio.progress;

import java.util.List;

/**
 * A section of the form as far as it is known while the AI still plans it: where it lies, what it
 * is called, and the chords that have arrived so far.
 *
 * @param firstBar  the bar of the piece it begins in, counted from 0
 * @param bars      how many bars it covers, at least one
 * @param name      its name, or {@code null} as long as the plan has not said it
 * @param function  what it does in the form, or {@code null}
 * @param intensity 0 to 1, or {@code null}
 * @param chords    one chord per bar as far as they are known
 */
public record FormSection(int firstBar, int bars, String name, String function, Double intensity,
                          List<String> chords) {
    public FormSection {
        bars = Math.max(1, bars);
        chords = List.copyOf(chords == null ? List.of() : chords);
    }
}
