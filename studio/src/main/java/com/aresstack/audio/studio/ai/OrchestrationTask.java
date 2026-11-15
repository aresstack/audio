package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything the planner and the generators of a re-orchestration are told: the context of the
 * piece around the passage, the request, the passage in ticks, the parts whose material is
 * redistributed, the frames one generator call writes, and which attempt this is. A pure function
 * of the piece and the request ({@link #of}).
 *
 * <p>The frames are the sections of the piece within the passage, cut at its edges and into
 * pieces of at most {@value ContinuationPlan#MAX_SECTION_BARS} bars; without sections, the
 * passage itself in such pieces. Each frame has an intensity: the curve of the request at its
 * middle if one is given, else that of its section, else that of the preset.
 *
 * @param fromTick the first tick of the passage
 * @param toTick   the first tick after it
 * @param parts    the parts whose material is redistributed, in the order of the piece
 */
public record OrchestrationTask(MusicalContext context, ReorchestrationRequest request, TimeMap time, long fromTick,
                                long toTick, List<Part> parts, List<VoiceFrame> frames, int attempt) {
    /** Bars before and after the passage whose notes the context gives in full. */
    public static final int AROUND_BARS = 2;

    public OrchestrationTask {
        parts = List.copyOf(parts);
        frames = List.copyOf(frames);
        if (attempt < 1 || toTick <= fromTick || frames.isEmpty() || parts.isEmpty()) {
            throw new IllegalArgumentException("A re-orchestration needs bars, parts and an attempt from 1.");
        }
    }

    /**
     * The task of a request on a piece.
     *
     * @throws IllegalArgumentException if the piece is empty, a named part is unknown or a drum
     *                                  part only, or the bars lie beyond the piece
     */
    public static OrchestrationTask of(Composition piece, ReorchestrationRequest request, int attempt) {
        if (piece.endTick() <= 0 || piece.parts().isEmpty()) {
            throw new IllegalArgumentException("Das Stück ist leer; umorchestrieren lässt sich nur Musik, die da ist.");
        }
        for (String id : request.partIds()) {
            if (piece.part(id) == null) {
                throw new IllegalArgumentException("Unbekannte Stimme: " + id);
            }
        }
        List<Part> parts = new ArrayList<>();
        for (Part part : piece.parts()) {
            if (request.partIds().isEmpty() || request.partIds().contains(part.id())) {
                parts.add(part);
            }
        }
        TimeMap time = piece.timeMap();
        int bars = time.barsCovering(piece.endTick());
        if (request.lastBar() > bars) {
            throw new IllegalArgumentException("Das Stück hat nur " + bars + " Takte: " + request.firstBar() + "-"
                    + request.lastBar());
        }
        long from = time.tickOfBar(request.firstBar() - 1);
        long to = time.tickOfBar(request.lastBar());
        List<VoiceFrame> frames = frames(piece, request, from, to);
        MusicalContext context = ContextBuilder.build(piece, from, to, AROUND_BARS, null);
        return new OrchestrationTask(context, request, time, from, to, parts, frames, attempt);
    }

    /** The frames of the passage; see the class comment. */
    static List<VoiceFrame> frames(Composition piece, ReorchestrationRequest request, long from, long to) {
        TimeMap time = piece.timeMap();
        record Stretch(Section section, long start, long end) {
        }
        List<Stretch> stretches = new ArrayList<>();
        long covered = from;
        for (Section section : piece.sections()) {
            long start = Math.max(from, section.startTick());
            long end = Math.min(to, section.endTick());
            if (start >= end) {
                continue;
            }
            if (start > covered) {
                stretches.add(new Stretch(null, covered, start));
            }
            stretches.add(new Stretch(section, start, end));
            covered = end;
        }
        if (covered < to) {
            stretches.add(new Stretch(null, covered, to));
        }
        int passageBars = request.bars();
        int firstBar = request.firstBar() - 1;
        List<VoiceFrame> frames = new ArrayList<>();
        for (Stretch stretch : stretches) {
            int first = time.barOf(stretch.start());
            int bars = Math.max(1, time.barsCovering(stretch.end()) - first);
            int pieces = (bars + ContinuationPlan.MAX_SECTION_BARS - 1) / ContinuationPlan.MAX_SECTION_BARS;
            int done = 0;
            for (int i = 0; i < pieces; i++) {
                int length = (bars - done) / (pieces - i);
                int bar = first + done;
                long start = time.tickOfBar(bar);
                long end = i == pieces - 1 ? stretch.end() : time.tickOfBar(bar + length);
                Section s = stretch.section();
                double position = (bar - firstBar + length / 2.0) / passageBars;
                double intensity = request.curve() != null ? request.curve().at(position)
                        : s != null && s.intensity() != null ? s.intensity() : request.preset().defaultIntensity();
                List<String> harmony = new ArrayList<>();
                if (s != null && !s.harmony().isEmpty()) {
                    int offset = bar - time.barOf(s.startTick());
                    for (int b = offset; b < offset + length && b < s.harmony().size(); b++) {
                        harmony.add(s.harmony().get(b));
                    }
                    if (harmony.size() != length) {
                        harmony.clear();
                    }
                }
                String name = s == null ? "Takte " + (bar + 1) + "-" + (bar + length) : s.name()
                        + (pieces > 1 ? " " + (i + 1) : "");
                frames.add(new VoiceFrame("f" + (frames.size() + 1), name, s == null ? null : s.function(), start, end,
                        bar, length, harmony, intensity, s == null ? null : s.instruction()));
                done += length;
            }
        }
        return frames;
    }

    /**
     * The material of a frame: every note of the chosen parts that begins in it, part by part, as
     * the generators are to redistribute it (bar beat dur pitch vel, bars of the piece).
     */
    public static String material(Composition piece, OrchestrationTask task, VoiceFrame frame) {
        TimeMap time = task.time();
        StringBuilder text = new StringBuilder();
        for (Part part : task.parts()) {
            List<PlacedNote> notes = new ArrayList<>();
            for (PlacedNote n : piece.notesOf(part.id())) {
                if (n.tick() >= frame.startTick() && n.tick() < frame.endTick()) {
                    notes.add(n);
                }
            }
            notes.sort(Comparator.comparingLong(PlacedNote::tick).thenComparingInt(n -> n.note().pitch()));
            text.append("part ").append(part.id()).append(" (").append(part.role() == null ? "-" : part.role())
                    .append(")\n");
            if (notes.isEmpty()) {
                text.append("  rests\n");
            }
            for (PlacedNote n : notes) {
                int bar = time.barOf(n.tick());
                text.append("  ").append(bar + 1).append(' ').append(ContextBuilder.quarters(n.tick() - time.tickOfBar(bar)))
                        .append(' ').append(ContextBuilder.quarters(n.note().length())).append(' ')
                        .append(ContextBuilder.pitch(n.note().pitch())).append('/').append(n.note().pitch()).append(' ')
                        .append(n.note().velocity()).append('\n');
            }
        }
        return text.toString();
    }

    /** What makes a later attempt differ from the first; empty for the first. */
    String take() {
        return attempt == 1 ? "" : "\nAttempt " + attempt + ": write a clearly different version than before.";
    }
}
