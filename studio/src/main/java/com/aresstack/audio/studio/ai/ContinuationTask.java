package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the planner and the generators of a continuation are told: the context of the
 * piece, the request, where the original ends and the continuation begins, the parts that go on,
 * and which attempt this is. It is a pure function of the piece and the request
 * ({@link #of}), so the same piece gives the same prompts and the cached answers are found again.
 *
 * @param tailStart the first tick that may change: {@code newStart} when the original is
 *                  protected, the start of the revised bars otherwise
 * @param newStart  the first tick of the continuation: the bar after the end of the original
 * @param newEnd    the first tick after the continuation
 * @param firstNewBar the bar the continuation begins with, counted from 0
 * @param parts     the existing parts that go on, in the order of the piece
 */
public record ContinuationTask(MusicalContext context, ContextTiers tiers, ContinuationRequest request, TimeMap time,
                               long tailStart, long newStart, long newEnd, int firstNewBar, List<Part> parts,
                               int attempt) {
    public ContinuationTask {
        parts = List.copyOf(parts);
        if (attempt < 1 || newEnd <= newStart || tailStart > newStart) {
            throw new IllegalArgumentException("A continuation task needs new bars and an attempt from 1.");
        }
    }

    /**
     * The task of a request on a piece.
     *
     * @throws IllegalArgumentException if the piece is empty, a named part is unknown, or the
     *                                  original is shorter than the bars to revise plus one
     */
    public static ContinuationTask of(Composition piece, ContinuationRequest request, int attempt) {
        if (piece.endTick() <= 0 || piece.parts().isEmpty()) {
            throw new IllegalArgumentException("Das Stück ist leer; verlängern lässt sich nur ein Stück mit Noten.");
        }
        List<Part> parts = new ArrayList<>();
        for (String id : request.partIds()) {
            if (piece.part(id) == null) {
                throw new IllegalArgumentException("Unbekannte Stimme: " + id);
            }
        }
        for (Part part : piece.parts()) {
            if (request.partIds().isEmpty() || request.partIds().contains(part.id())) {
                parts.add(part);
            }
        }
        TimeMap time = piece.timeMap();
        int originalBars = time.barsCovering(piece.endTick());
        if (request.tailBars() >= originalBars) {
            throw new IllegalArgumentException("Das Original hat nur " + originalBars + " Takte; überarbeitet werden "
                    + "dürfen höchstens " + (originalBars - 1) + ".");
        }
        long newStart = time.tickOfBar(originalBars);
        long tailStart = time.tickOfBar(originalBars - request.tailBars());
        long newEnd = time.tickOfBar(originalBars + request.bars());
        ContextTiers tiers = ContextTiers.forContinuation(piece, newStart, request.tailBars(),
                request.ending() == ContinuationEnding.LOOP);
        MusicalContext context = ContextBuilder.build(piece, tiers, null);
        return new ContinuationTask(context, tiers, request, time, tailStart, newStart, newEnd, originalBars, parts,
                attempt);
    }

    /** Whether the last bars of the original are rewritten. */
    public boolean revisesTail() {
        return tailStart < newStart;
    }

    /** The bar the revised tail begins with, counted from 0; {@link #firstNewBar} if none. */
    public int firstTailBar() {
        return firstNewBar - request.tailBars();
    }

    /** One line about a part that goes on, for prompts. */
    static String describe(Part part) {
        return "id=" + part.id() + ", name=\"" + part.name().replace('"', '\'') + "\", instrument="
                + (part.instrument() == null ? part.name() : part.instrument()).replace('"', '\'') + ", role="
                + (part.role() == null ? "-" : part.role()) + ", General MIDI program=" + part.program();
    }

    /** What makes a later attempt differ from the first; empty for the first. */
    String take() {
        return attempt == 1 ? "" : "\nAttempt " + attempt + ": write a clearly different version than before.";
    }
}
