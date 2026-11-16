package com.aresstack.audio.studio.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the AI is writing: from one picture of the piece on its way to the next, the bar the new
 * notes begin at. The score follows it, so that the bars that arise are in view without scrolling,
 * also when a long piece is continued far from its start.
 *
 * <p>The first picture of a piece of work only says what is there already, e.g. the piece that is
 * being lengthened: nothing of it is new. The bar never runs back, so that voices that write at
 * different places do not pull the view to and fro.
 */
final class SketchFront {
    private Set<String> seen;
    private int front = -1;

    /** A new piece of work begins: its first picture says what is there already. */
    void begin() {
        seen = null;
        front = -1;
    }

    /**
     * Looks at the next picture.
     *
     * @param arrangement the lines of the piece on its way; a note is {@code note, track, tick, ...}
     * @param barTicks    the length of a bar in ticks
     * @return the bar to bring into view, counted from 0; -1 if the view stays where it is
     */
    int next(List<String> arrangement, long barTicks) {
        Set<String> notes = new HashSet<String>();
        long earliest = Long.MAX_VALUE;
        for (String line : arrangement) {
            if (!line.startsWith("note\t")) {
                continue;
            }
            notes.add(line);
            if (seen != null && !seen.contains(line)) {
                try {
                    earliest = Math.min(earliest, Long.parseLong(line.split("\t")[2]));
                } catch (RuntimeException unreadable) {
                    // a line that says no place is not followed
                }
            }
        }
        boolean first = seen == null;
        seen = notes;
        if (first || earliest == Long.MAX_VALUE || barTicks <= 0) {
            return -1;
        }
        int bar = (int) Math.min(Integer.MAX_VALUE, earliest / barTicks);
        if (bar <= front) {
            return -1;
        }
        front = bar;
        return bar;
    }
}
