package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.ai.ContinuationComposer;
import com.aresstack.audio.studio.ai.RegionComposer;
import com.aresstack.audio.studio.ai.RegionRequest;
import com.aresstack.audio.studio.ai.ContinuationEnding;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.TransitionMode;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * Lets a piece grow to a length in steps of the continuation: the first composition of a long
 * piece and "Verlängern auf insgesamt …" both go this way. Each step is planned and written on
 * its own (one plan never covers more than a step) and is taken into the piece at once, so an
 * interrupted run goes on from the bars it has.
 */
public final class PieceGrowth {
    private PieceGrowth() {
    }

    /** How many bars the piece has. */
    public static int bars(ProjectSession session) {
        Composition piece = session.composition();
        return piece.timeMap().barsCovering(piece.endTick());
    }

    /**
     * The bars a piece has when it lasts this long: its bars so far, and the rest in the tempo and
     * meter of its end. At least the bars it has.
     */
    public static int barsLasting(Composition piece, double seconds) {
        int has = piece.timeMap().barsCovering(piece.endTick());
        if (seconds <= piece.timeMap().seconds(piece.endTick())) {
            return has;
        }
        return Math.max(has, piece.timeMap().barsCovering(piece.timeMap().tickAt(seconds)));
    }

    /**
     * Grows the piece to {@code target} bars. Every step but the last is taken at once; the last
     * is returned as it is, for the caller to take (or {@code null} if the piece is that long already).
     *
     * @param asked what the user asked for; each step gets its bars, the end only the last step
     *              (the others stay open), and the transition and new instruments only the first
     * @param step  the bars of one step, at most {@link ContinuationRequest#MAX_BARS}
     */
    public static ExtensionOffer growTo(ProjectSession session, Supplier<ContinuationComposer> composer,
                                        ContinuationRequest asked, int target, int step, Progress progress)
            throws Exception {
        return growTo(session, composer, null, asked, target, step, progress);
    }

    /**
     * As {@link #growTo(ProjectSession, Supplier, ContinuationRequest, int, int, Progress)}, and
     * before each step the end of the piece is looked at: a part whose notes stop a whole bar or
     * more before the end has those bars written anew by {@code repair} (an answer may have broken
     * off; if rests are meant, they come again). Nothing goes on over a gap.
     *
     * @param repair writes passages anew, or {@code null} to leave the end as it is
     */
    public static ExtensionOffer growTo(ProjectSession session, Supplier<ContinuationComposer> composer,
                                        Supplier<RegionComposer> repair, ContinuationRequest asked, int target,
                                        int step, Progress progress) throws Exception {
        int size = Math.max(1, Math.min(ContinuationRequest.MAX_BARS, step));
        if (repair != null) {
            fillTail(session, repair, size, asked.instruction(), progress);
        }
        int have = bars(session);
        boolean first = true;
        while (have < target) {
            int bars = Math.min(size, target - have);
            boolean last = have + bars >= target;
            progress.update("Verlängere das Stück von " + have + " auf " + (have + bars) + " von " + target
                    + " Takten ...");
            if (progress instanceof com.aresstack.audio.studio.progress.SketchProgress) {
                ((com.aresstack.audio.studio.progress.SketchProgress) progress)
                        .stepping("Takte " + (have + 1) + "–" + (have + bars) + " von " + target);
            }
            // The bar under the status line shows the whole piece: this step is its share from here.
            Progress part = com.aresstack.audio.studio.progress.PartOfWhole.of(progress, have / (double) target,
                    (have + bars) / (double) target);
            ExtensionOffer offer = ExtensionOffer.start(session, composer.get(), step(asked, bars, first, last), part);
            if (last) {
                return offer;
            }
            offer.confirm(); // nothing of the original is shortened when appending; no one to ask here
            offer.accept();
            if (repair != null) {
                fillTail(session, repair, size, asked.instruction(), progress);
            }
            int now = bars(session);
            if (now <= have) {
                throw new IOException("Die Verlängerung hat dem Stück keine Takte gegeben (bei Takt " + have + ").");
            }
            have = now;
            first = false;
        }
        return null;
    }

    /**
     * The bars at the end of the piece after each part's last note, within the last {@code window}
     * bars, as {@code {first, last}} (counted from 1) by part id; parts that play to the end are
     * not listed.
     */
    static java.util.Map<String, int[]> silentTails(Composition piece, int window) {
        java.util.Map<String, int[]> tails = new java.util.LinkedHashMap<>();
        int total = piece.timeMap().barsCovering(piece.endTick());
        int from = Math.max(1, total - window + 1);
        for (Part part : piece.parts()) {
            long end = 0;
            for (Composition.PlacedNote note : piece.notesOf(part.id())) {
                end = Math.max(end, note.endTick());
            }
            int sounding = end == 0 ? 0 : piece.timeMap().barsCovering(end);
            int first = Math.max(from, sounding + 1);
            if (first <= total) {
                tails.put(part.id(), new int[]{first, total});
            }
        }
        return tails;
    }

    /** Writes the silent end of each part anew and takes it into the piece. */
    static void fillTail(ProjectSession session, Supplier<RegionComposer> repair, int window, String words,
                         Progress progress) throws Exception {
        for (java.util.Map.Entry<String, int[]> tail : silentTails(session.composition(), window).entrySet()) {
            int[] bars = tail.getValue();
            Composition piece = session.composition();
            progress.update("„" + piece.part(tail.getKey()).name() + "“ schweigt nach der letzten Note in Takt "
                    + bars[0] + " bis " + bars[1] + ": diese Takte werden neu angefordert ...");
            RegionRequest request = RegionRequest.replace(Selection.bars(piece, java.util.List.of(tail.getKey()),
                    bars[0], bars[1]), "These bars of this part were left silent, likely by an answer that broke off."
                    + " Write the part on as the music around it goes" + (words == null || words.isBlank() ? ""
                    : " (" + words + ")") + "; rest only where the music really asks for it.");
            RegionRework rework = RegionRework.start(session, repair.get(), request, progress);
            rework.accept();
        }
    }

    /** Grows the piece to {@code target} bars and takes every step. */
    public static void grow(ProjectSession session, Supplier<ContinuationComposer> composer,
                            ContinuationRequest asked, int target, int step, Progress progress) throws Exception {
        grow(session, composer, null, asked, target, step, progress);
    }

    /** As {@link #grow(ProjectSession, Supplier, ContinuationRequest, int, int, Progress)}, with silent ends written anew. */
    public static void grow(ProjectSession session, Supplier<ContinuationComposer> composer,
                            Supplier<RegionComposer> repair, ContinuationRequest asked, int target, int step,
                            Progress progress) throws Exception {
        ExtensionOffer last = growTo(session, composer, repair, asked, target, step, progress);
        if (last != null) {
            last.confirm();
            last.accept();
        }
    }

    /** One step of what was asked. */
    static ContinuationRequest step(ContinuationRequest asked, int bars, boolean first, boolean last) {
        return new ContinuationRequest(bars, asked.preset(), asked.instruction(),
                last ? asked.ending() : ContinuationEnding.OPEN,
                first ? asked.transition() : TransitionMode.PROTECT_ORIGINAL, first ? asked.tailBars() : 0,
                asked.partIds(), first ? asked.newParts() : 0, 0, asked.curve());
    }
}
