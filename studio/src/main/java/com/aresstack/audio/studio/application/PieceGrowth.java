package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.studio.ai.ContinuationComposer;
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
        int size = Math.max(1, Math.min(ContinuationRequest.MAX_BARS, step));
        int have = bars(session);
        boolean first = true;
        while (have < target) {
            int bars = Math.min(size, target - have);
            boolean last = have + bars >= target;
            progress.update("Verlängere das Stück von " + have + " auf " + (have + bars) + " von " + target
                    + " Takten ...");
            ExtensionOffer offer = ExtensionOffer.start(session, composer.get(), step(asked, bars, first, last),
                    progress);
            if (last) {
                return offer;
            }
            offer.confirm(); // nothing of the original is shortened when appending; no one to ask here
            offer.accept();
            int now = bars(session);
            if (now <= have) {
                throw new IOException("Die Verlängerung hat dem Stück keine Takte gegeben (bei Takt " + have + ").");
            }
            have = now;
            first = false;
        }
        return null;
    }

    /** Grows the piece to {@code target} bars and takes every step. */
    public static void grow(ProjectSession session, Supplier<ContinuationComposer> composer,
                            ContinuationRequest asked, int target, int step, Progress progress) throws Exception {
        ExtensionOffer last = growTo(session, composer, asked, target, step, progress);
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
