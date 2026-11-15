package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import java.util.ArrayList;
import java.util.List;

/**
 * A section of the form as the lanes and the score show it: where it lies in bars, what it is
 * called and does, how intense it is and which chords it has.
 *
 * @param firstBar  the bar the section begins in, counted from 0
 * @param bars      how many bars it covers, at least one
 * @param function  what it does in the form, or an empty text
 * @param intensity 0 to 1, or {@code null} if the piece does not say
 * @param chords    one chord per bar as far as the piece says them
 */
record SectionMark(String id, int firstBar, int bars, String name, String function, Double intensity,
                   List<String> chords) {
    SectionMark {
        name = name == null || name.isBlank() ? "Abschnitt" : name.strip();
        function = function == null ? "" : function.strip();
        chords = List.copyOf(chords == null ? List.of() : chords);
    }

    /** The sections of a piece in order; a piece without a form has none. */
    static List<SectionMark> of(Composition piece) {
        List<SectionMark> marks = new ArrayList<SectionMark>();
        if (piece == null) {
            return marks;
        }
        TimeMap time = piece.timeMap();
        for (Section section : piece.sections()) {
            int first = time.barOf(section.startTick());
            int end = time.barsCovering(section.startTick() + section.lengthTicks());
            marks.add(new SectionMark(section.id(), first, Math.max(1, end - first), section.name(),
                    section.function(), section.intensity(), section.harmony()));
        }
        return marks;
    }

    /** Whether the section is a gap: bars without music that the AI can fill or a section can be dragged into. */
    boolean gap() {
        return com.aresstack.audio.studio.application.SectionEdits.GAP.equals(function);
    }

    /** The chord of a bar of the piece (from 0), or {@code null} if the section names none there. */
    String chordAt(int bar) {
        int index = bar - firstBar;
        return index >= 0 && index < chords.size() ? chords.get(index) : null;
    }

    /** Name and function in one line: the function only if it says more than the name. */
    String title() {
        return function.isEmpty() || function.equalsIgnoreCase(name) ? name : name + " · " + function;
    }

    /** Everything the piece says of the section, for a tooltip. */
    String told() {
        StringBuilder text = new StringBuilder(title());
        text.append(bars == 1 ? " · Takt " + (firstBar + 1) : " · Takt " + (firstBar + 1) + " bis " + (firstBar + bars));
        if (intensity != null) {
            text.append(" · Intensität ").append(Math.round(intensity * 100)).append(" %");
        }
        if (!chords.isEmpty()) {
            text.append(" · ").append(String.join(" | ", chords));
        }
        return text.toString();
    }
}
