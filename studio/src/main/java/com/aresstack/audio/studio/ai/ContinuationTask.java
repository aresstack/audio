package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.edit.TimeShift;
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
 * @param originalBars how many bars the piece had before the change
 * @param shiftTicks how far the music from {@code newStart} on moves back for an insertion
 *                  ({@code ShiftTime}); 0 for a continuation after the end
 *
 * <p>For an insertion ({@link ContinuationRequest#inserts()}) every tick and the time map are
 * those of the piece <em>after</em> the shift ({@link #room}): the new bars are an empty room
 * between the music before and the music after, and the context shows both.
 */
public record ContinuationTask(MusicalContext context, ContextTiers tiers, ContinuationRequest request, TimeMap time,
                               long tailStart, long newStart, long newEnd, int firstNewBar, List<Part> parts,
                               int attempt, int originalBars, long shiftTicks) {
    public ContinuationTask {
        parts = List.copyOf(parts);
        if (attempt < 1 || newEnd <= newStart || tailStart > newStart || shiftTicks < 0) {
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
            throw new IllegalArgumentException(request.inserts()
                    ? "Das Stück ist leer; einfügen lässt sich nur in ein Stück mit Noten."
                    : "Das Stück ist leer; verlängern lässt sich nur ein Stück mit Noten.");
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
        int originalBars = piece.timeMap().barsCovering(piece.endTick());
        if (request.inserts()) {
            return insertion(piece, request, attempt, parts, originalBars);
        }
        TimeMap time = piece.timeMap();
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
                attempt, originalBars, 0);
    }

    /**
     * The task of an insertion: the room is made first, by the same deterministic shift the patch
     * will carry, and everything is told on the piece with the room.
     */
    private static ContinuationTask insertion(Composition piece, ContinuationRequest request, int attempt,
                                              List<Part> parts, int originalBars) {
        int before = request.insertBeforeBar() - 1;
        if (before >= originalBars) {
            throw new IllegalArgumentException("Das Stück hat nur " + originalBars + " Takte; eingefügt werden kann "
                    + "vor Takt 1 bis " + originalBars + ". Hinten anfügen heißt verlängern.");
        }
        if (request.tailBars() > before) {
            throw new IllegalArgumentException("Vor Takt " + request.insertBeforeBar() + " liegen nur " + before
                    + " Takte; so viele dürfen höchstens überarbeitet werden.");
        }
        long newStart = piece.timeMap().tickOfBar(before);
        long shift = request.bars() * piece.timeMap().meterAt(newStart).barTicks();
        Composition room = TimeShift.shift(piece, newStart, shift);
        TimeMap time = room.timeMap();
        long tailStart = time.tickOfBar(before - request.tailBars());
        ContextTiers tiers = ContextTiers.forInsertion(room, newStart, newStart + shift, request.tailBars());
        MusicalContext context = ContextBuilder.build(room, tiers, null);
        return new ContinuationTask(context, tiers, request, time, tailStart, newStart, newStart + shift, before, parts,
                attempt, originalBars, shift);
    }

    /** Whether the new bars are inserted and the music after them moves back. */
    public boolean inserts() {
        return shiftTicks > 0;
    }

    /**
     * The piece the new bars are written into: for an insertion the piece with the room made by
     * {@code ShiftTime(newStart, shiftTicks)}, otherwise the piece itself.
     */
    public Composition room(Composition piece) {
        return inserts() ? TimeShift.shift(piece, newStart, shiftTicks) : piece;
    }

    /** The bar of the piece, counted from 1, at which the original goes on after an insertion. */
    public int barAfter() {
        return firstNewBar + request.bars() + 1;
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
