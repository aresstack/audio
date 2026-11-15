package com.aresstack.audio.studio.ai;

import java.util.List;

/**
 * What the user asks for when a piece is to be continued ({@code APPEND}, architecture review E4;
 * report 5) or new bars are to be inserted into it ({@code INSERT}, work package C4): how many
 * bars, where, of which kind, how it ends, how it joins the original, which parts go on, whether
 * new instruments may enter, and optionally the intensity curve the new bars follow.
 *
 * <p>With {@code insertBeforeBar} the new bars come before that bar of the original; everything
 * from there on moves back by them. Moving is the application's deterministic {@code ShiftTime},
 * never the AI's. An insertion has no ending of its own: it leads into the bars that follow, so
 * its ending is always {@link ContinuationEnding#OPEN}.
 *
 * @param bars        how many bars come after the original, 1 to {@value #MAX_BARS}
 * @param preset      the kind of continuation
 * @param instruction what the continuation should be, in the user's words; may be empty except
 *                    for {@link ContinuationPreset#FREE}
 * @param ending      how the continuation ends
 * @param transition  whether the original is protected or its last bars may be revised
 * @param tailBars    with {@link TransitionMode#REVISE_TAIL} how many bars at the end of the
 *                    original may change, 1 to {@value #MAX_TAIL_BARS}; 0 otherwise
 * @param partIds     the parts that go on; empty for all parts of the piece
 * @param newParts    how many new instruments the plan may bring in, 0 to {@value #MAX_NEW_PARTS}
 * @param insertBeforeBar the bar of the original, counted from 1, before which the new bars are
 *                    inserted; 0 to append them after the end
 * @param curve       the intensity the new bars follow from their first to their last bar, or
 *                    {@code null} to leave it to the plan and the preset
 */
public record ContinuationRequest(int bars, ContinuationPreset preset, String instruction,
                                  ContinuationEnding ending, TransitionMode transition, int tailBars,
                                  List<String> partIds, int newParts, int insertBeforeBar, IntensityCurve curve) {
    public static final int MAX_BARS = 256;
    public static final int MAX_TAIL_BARS = 8;
    public static final int MAX_NEW_PARTS = 4;
    /** Bars of the original rewritten in the transition mode if the user names no number. */
    public static final int DEFAULT_TAIL_BARS = 2;

    public ContinuationRequest {
        if (bars < 1 || bars > MAX_BARS) {
            throw new IllegalArgumentException("Eine Verlängerung hat 1 bis " + MAX_BARS + " Takte: " + bars);
        }
        preset = preset == null ? ContinuationPreset.STANDALONE : preset;
        instruction = instruction == null ? "" : instruction.strip();
        if (preset == ContinuationPreset.FREE && instruction.isEmpty()) {
            throw new IllegalArgumentException("Die freie Beschreibung braucht eine Anweisung.");
        }
        ending = ending == null ? ContinuationEnding.CLOSED : ending;
        transition = transition == null ? TransitionMode.PROTECT_ORIGINAL : transition;
        if (transition == TransitionMode.PROTECT_ORIGINAL) {
            if (tailBars != 0) {
                throw new IllegalArgumentException("Beim Schutz des Originals wird kein Takt des Originals überarbeitet.");
            }
        } else if (tailBars < 1 || tailBars > MAX_TAIL_BARS) {
            throw new IllegalArgumentException("Für den Übergang dürfen 1 bis " + MAX_TAIL_BARS
                    + " Takte des Originals überarbeitet werden: " + tailBars);
        }
        partIds = List.copyOf(partIds == null ? List.of() : partIds);
        if (newParts < 0 || newParts > MAX_NEW_PARTS) {
            throw new IllegalArgumentException("Höchstens " + MAX_NEW_PARTS + " neue Instrumente: " + newParts);
        }
        if (insertBeforeBar < 0) {
            throw new IllegalArgumentException("Eingefügt wird vor einem Takt ab 1: " + insertBeforeBar);
        }
        if (insertBeforeBar > 0) {
            ending = ContinuationEnding.OPEN;
        }
    }

    /** As the canonical constructor, appending after the end without intensity curve. */
    public ContinuationRequest(int bars, ContinuationPreset preset, String instruction, ContinuationEnding ending,
                               TransitionMode transition, int tailBars, List<String> partIds, int newParts) {
        this(bars, preset, instruction, ending, transition, tailBars, partIds, newParts, 0, null);
    }

    /**
     * New bars inserted before a bar of the original ({@code INSERT}), with the defaults of the
     * preset: all parts play, the bars around are protected, new instruments only where the
     * preset brings them.
     *
     * @param beforeBar the bar of the original, from 1, before which the new bars come
     */
    public static ContinuationRequest insert(int beforeBar, int bars, ContinuationPreset preset, String instruction) {
        if (beforeBar < 1) {
            throw new IllegalArgumentException("Eingefügt wird vor einem Takt ab 1: " + beforeBar);
        }
        ContinuationPreset kind = preset == null ? ContinuationPreset.STANDALONE : preset;
        return new ContinuationRequest(bars, kind, instruction, ContinuationEnding.OPEN, TransitionMode.PROTECT_ORIGINAL,
                0, List.of(), kind.newPartsByDefault() ? 2 : 0, beforeBar, null);
    }

    /** Whether the new bars are inserted into the piece rather than appended. */
    public boolean inserts() {
        return insertBeforeBar > 0;
    }

    /** The same request with an intensity curve for the new bars; {@code null} for none. */
    public ContinuationRequest withCurve(IntensityCurve given) {
        return new ContinuationRequest(bars, preset, instruction, ending, transition, tailBars, partIds, newParts,
                insertBeforeBar, given);
    }

    /**
     * A request with the defaults of its preset: all parts go on, the original is protected, new
     * instruments (up to two) only where the preset brings them.
     */
    public static ContinuationRequest of(int bars, ContinuationPreset preset, ContinuationEnding ending) {
        return of(bars, preset, ending, "");
    }

    /** As {@link #of(int, ContinuationPreset, ContinuationEnding)}, with an instruction in the user's words. */
    public static ContinuationRequest of(int bars, ContinuationPreset preset, ContinuationEnding ending,
                                         String instruction) {
        ContinuationPreset kind = preset == null ? ContinuationPreset.STANDALONE : preset;
        return new ContinuationRequest(bars, kind, instruction, ending, TransitionMode.PROTECT_ORIGINAL, 0, List.of(),
                kind.newPartsByDefault() ? 2 : 0);
    }

    /** The same request with an instruction in the user's words. */
    public ContinuationRequest withInstruction(String words) {
        return new ContinuationRequest(bars, preset, words, ending, transition, tailBars, partIds, newParts,
                insertBeforeBar, curve);
    }

    /**
     * The same request, revising the last {@code bars} bars of the original for the transition:
     * before the end, or before the insertion point.
     */
    public ContinuationRequest revisingTail(int lastBars) {
        return new ContinuationRequest(bars, preset, instruction, ending, TransitionMode.REVISE_TAIL, lastBars, partIds,
                newParts, insertBeforeBar, curve);
    }

    /** The same request for some parts only. */
    public ContinuationRequest forParts(List<String> parts) {
        return new ContinuationRequest(bars, preset, instruction, ending, transition, tailBars, parts, newParts,
                insertBeforeBar, curve);
    }

    /** The same request with another number of new instruments allowed. */
    public ContinuationRequest withNewParts(int count) {
        return new ContinuationRequest(bars, preset, instruction, ending, transition, tailBars, partIds, count,
                insertBeforeBar, curve);
    }
}
