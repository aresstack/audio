package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import java.util.ArrayList;
import java.util.List;

/**
 * A stretch of the piece one generator call writes the new voice for: a section of the form, or
 * the whole piece if it has no sections. A call never writes more than one frame (architecture
 * review E4, section-wise generation).
 *
 * @param sectionId the section, or {@link #WHOLE} for a piece without sections
 * @param firstBar  the bar the frame begins with, counted from 0
 * @param bars      how many bars the frame has
 */
public record VoiceFrame(String sectionId, String name, String function, long startTick, long endTick, int firstBar,
                         int bars, List<String> harmony, Double intensity, String instruction) {
    /** The id of the one frame of a piece without sections. */
    public static final String WHOLE = "whole";

    public VoiceFrame {
        harmony = List.copyOf(harmony == null ? List.of() : harmony);
        if (startTick < 0 || endTick <= startTick || bars < 1) {
            throw new IllegalArgumentException("A frame covers at least one bar: " + sectionId);
        }
    }

    /**
     * The frames of a piece for the chosen sections, in the order of the piece.
     *
     * @param sectionIds the sections, empty for all of them
     * @throws IllegalArgumentException if a section is unknown or the piece has no notes and no sections
     */
    public static List<VoiceFrame> of(Composition piece, List<String> sectionIds) {
        TimeMap time = piece.timeMap();
        List<VoiceFrame> frames = new ArrayList<>();
        if (piece.sections().isEmpty()) {
            if (!sectionIds.isEmpty() && !sectionIds.equals(List.of(WHOLE))) {
                throw new IllegalArgumentException("Das Stück hat keine Abschnitte: " + sectionIds);
            }
            long end = piece.endTick();
            if (end <= 0) {
                throw new IllegalArgumentException("Das Stück ist leer; eine neue Stimme braucht ein Arrangement.");
            }
            int bars = time.barsCovering(end);
            frames.add(new VoiceFrame(WHOLE, "Ganzes Stück", null, 0, time.tickOfBar(bars), 0, bars, List.of(), null,
                    null));
            return frames;
        }
        for (String id : sectionIds) {
            if (piece.sections().stream().noneMatch(s -> s.id().equals(id))) {
                throw new IllegalArgumentException("Unbekannter Abschnitt: " + id);
            }
        }
        for (Section section : piece.sections()) {
            if (!sectionIds.isEmpty() && !sectionIds.contains(section.id())) {
                continue;
            }
            int first = time.barOf(section.startTick());
            int bars = Math.max(1, time.barsCovering(section.endTick()) - first);
            frames.add(new VoiceFrame(section.id(), section.name(), section.function(), section.startTick(),
                    section.endTick(), first, bars, section.harmony(), section.intensity(), section.instruction()));
        }
        return frames;
    }

    /**
     * The frames of a stretch of the piece ({@code REPLACE_REGION}, {@code VARIATE_REGION}): every
     * section that overlaps it, cut to the stretch, so that one call still writes no more than one
     * section; the whole stretch as one frame if the piece has no sections. The chords of a cut
     * section are cut alike.
     */
    public static List<VoiceFrame> within(Composition piece, long fromTick, long toTick) {
        if (fromTick < 0 || toTick <= fromTick) {
            throw new IllegalArgumentException("Ein Bereich umfasst mindestens einen Tick: " + fromTick + ".." + toTick);
        }
        TimeMap time = piece.timeMap();
        List<VoiceFrame> frames = new ArrayList<>();
        for (Section section : piece.sections()) {
            long start = Math.max(fromTick, section.startTick());
            long end = Math.min(toTick, section.endTick());
            if (start >= end) {
                continue;
            }
            int first = time.barOf(start);
            int bars = Math.max(1, time.barsCovering(end) - first);
            int skip = first - time.barOf(section.startTick());
            List<String> harmony = section.harmony().size() > skip
                    ? section.harmony().subList(skip, Math.min(section.harmony().size(), skip + bars)) : List.of();
            frames.add(new VoiceFrame(section.id(), section.name(), section.function(), start, end, first, bars, harmony,
                    section.intensity(), section.instruction()));
        }
        long covered = frames.stream().mapToLong(VoiceFrame::lengthTicks).sum();
        if (frames.isEmpty() || covered < toTick - fromTick) {
            // no sections, or sections that leave part of the stretch uncovered (past the last one)
            frames.clear();
            int first = time.barOf(fromTick);
            frames.add(new VoiceFrame(WHOLE, "Auswahl", null, fromTick, toTick, first,
                    Math.max(1, time.barsCovering(toTick) - first), List.of(), null, null));
        }
        return frames;
    }

    public long lengthTicks() {
        return endTick - startTick;
    }
}
