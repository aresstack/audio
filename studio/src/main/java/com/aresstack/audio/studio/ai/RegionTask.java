package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything the generator of one part in a region is told (work package C2): the context of the
 * piece with the region left out, the request, the part, the frames of the region, the part's
 * range, the notes it plays just before and just after the region, the notes held into it, for a
 * variation the old notes, and which attempt this is.
 *
 * @param before   the part's notes in the bars before the region, in bars of the piece, one per line
 * @param held     the part's notes that begin before the region and sound into it; they stay
 * @param after    the part's notes in the bars after the region; the new passage leads there
 * @param template the selected notes of the part as they are, kept for {@link RegionRequest.Mode#VARIATE}
 */
public record RegionTask(MusicalContext context, RegionRequest request, Part part, TimeMap time,
                         List<VoiceFrame> frames, InstrumentRange range, String before, String held, String after,
                         List<PlacedNote> template, int attempt) {
    /** Bars before and after the region whose notes of the part are named in the task. */
    public static final int NEIGHBOUR_BARS = 2;

    public RegionTask {
        frames = List.copyOf(frames);
        template = List.copyOf(template);
        if (frames.isEmpty() || attempt < 1) {
            throw new IllegalArgumentException("A region task needs a frame and an attempt from 1.");
        }
    }

    /** The task of one part of a request on a piece. Pure: the same piece gives the same task. */
    public static RegionTask of(Composition piece, RegionRequest request, MusicalContext context, String partId,
                                int attempt) {
        Part part = piece.part(partId);
        if (part == null) {
            throw new IllegalArgumentException("Unbekannter Part: " + partId);
        }
        var selection = request.selection();
        TimeMap time = piece.timeMap();
        long from = selection.fromTick();
        long to = selection.toTick();
        long beforeFrom = time.tickOfBar(Math.max(0, time.barOf(from) - NEIGHBOUR_BARS));
        long afterTo = time.tickOfBar(time.barsCovering(to) + NEIGHBOUR_BARS);
        List<PlacedNote> before = new ArrayList<>();
        List<PlacedNote> held = new ArrayList<>();
        List<PlacedNote> after = new ArrayList<>();
        List<PlacedNote> template = new ArrayList<>();
        for (PlacedNote n : piece.notesOf(partId)) {
            if (selection.selects(n)) {
                template.add(n);
            } else if (n.tick() < from && n.endTick() > from) {
                held.add(n);
            } else if (n.tick() < from && n.tick() >= beforeFrom) {
                before.add(n);
            } else if (n.tick() >= to && n.tick() < afterTo) {
                after.add(n);
            }
        }
        return new RegionTask(context, request, part, time, VoiceFrame.within(piece, from, to),
                InstrumentRange.of(part.program(), part.drums()), lines(time, before), lines(time, held),
                lines(time, after), template, attempt);
    }

    /** Part, instrument and role of the part in one line, for prompts. */
    String target() {
        return "id=" + part.id() + ", name=\"" + part.name() + "\", instrument="
                + (part.instrument() == null ? part.name() : part.instrument()) + ", role="
                + (part.role() == null ? "-" : part.role()) + ", General MIDI program=" + part.program()
                + " (zero-based), playable range " + ContextBuilder.pitch(range.low()) + "/" + range.low() + " to "
                + ContextBuilder.pitch(range.high()) + "/" + range.high();
    }

    /** The template notes that begin in a frame, with bars counted from the start of the frame. */
    String template(VoiceFrame frame) {
        StringBuilder text = new StringBuilder();
        for (PlacedNote n : template) {
            if (n.tick() < frame.startTick() || n.tick() >= frame.endTick()) {
                continue;
            }
            int bar = time.barOf(n.tick());
            text.append("  ").append(bar - frame.firstBar() + 1).append(' ')
                    .append(ContextBuilder.quarters(n.tick() - time.tickOfBar(bar))).append(' ')
                    .append(ContextBuilder.quarters(n.note().length())).append(' ')
                    .append(ContextBuilder.pitch(n.note().pitch())).append('/').append(n.note().pitch()).append(' ')
                    .append(n.note().velocity()).append('\n');
        }
        return text.toString();
    }

    /** What makes a later attempt differ from the first; empty for the first. */
    String take() {
        return attempt == 1 ? "" : "\nAttempt " + attempt + ": write a clearly different version than before.";
    }

    private static String lines(TimeMap time, List<PlacedNote> notes) {
        StringBuilder text = new StringBuilder();
        notes.stream().sorted(Comparator.comparingLong(PlacedNote::tick).thenComparingInt(n -> n.note().pitch()))
                .forEach(n -> {
                    int bar = time.barOf(n.tick());
                    text.append("  ").append(bar + 1).append(' ').append(ContextBuilder.quarters(n.tick() - time.tickOfBar(bar)))
                            .append(' ').append(ContextBuilder.quarters(n.note().length())).append(' ')
                            .append(ContextBuilder.pitch(n.note().pitch())).append('/').append(n.note().pitch())
                            .append(' ').append(n.note().velocity()).append('\n');
                });
        return text.toString();
    }
}
