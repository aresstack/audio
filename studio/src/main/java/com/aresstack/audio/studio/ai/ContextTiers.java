package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.TimeMap;
import java.util.ArrayList;
import java.util.List;

/**
 * How much of a piece a model is told in full (report 8.3, architecture review E4: context in
 * fixed layers). Profile, form and a summary of every part are always there; what grows with the
 * length of the piece is the notes layer, so it is cut into windows:
 * <ul>
 * <li>{@link Tier#FULL}: up to {@value #FULL_BARS} bars, every note of the piece;</li>
 * <li>{@link Tier#WINDOWED}: up to {@value #WINDOWED_BARS} bars, the beginning of the piece (main
 *     theme, and the loop target of a loop) and the last bars before the continuation;</li>
 * <li>{@link Tier#LONG}: longer pieces, shorter windows, and the part summaries counted per
 *     {@value #COMPACT_BARS} bars instead of per bar.</li>
 * </ul>
 * The choice is a pure function of the piece and the request, so the context stays reproducible.
 *
 * @param compact whether the part summaries count notes per {@value #COMPACT_BARS} bars
 * @param windows the stretches whose notes are given in full, in the order of the piece
 */
public record ContextTiers(Tier tier, boolean compact, List<Window> windows) {
    public static final int FULL_BARS = 16;
    public static final int WINDOWED_BARS = 64;
    public static final int COMPACT_BARS = 4;
    /** Bars of the beginning and of the end given in full for a piece of middle length. */
    public static final int WINDOW_BARS = 8;
    /** The same for a long piece. */
    public static final int LONG_WINDOW_BARS = 4;

    public enum Tier { FULL, WINDOWED, LONG }

    /**
     * A stretch whose notes are given in full.
     *
     * @param label what the stretch is, for the model; {@code null} for the plain notes layer
     */
    public record Window(String label, long fromTick, long toTick) {
        public Window {
            if (fromTick < 0 || toTick <= fromTick) {
                throw new IllegalArgumentException("A window covers at least one tick: " + fromTick + ".." + toTick);
            }
        }
    }

    public ContextTiers {
        windows = List.copyOf(windows);
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("A context gives at least one window of notes.");
        }
    }

    /**
     * The tiers for continuing a piece whose original ends at {@code newStart}.
     *
     * @param tailBars the bars at the end of the original that may be rewritten; the end window
     *                 always reaches two bars further back than these
     */
    public static ContextTiers forContinuation(Composition piece, long newStart, int tailBars, boolean loop) {
        TimeMap time = piece.timeMap();
        int bars = time.barsCovering(newStart);
        if (bars <= FULL_BARS) {
            return new ContextTiers(Tier.FULL, false, List.of(new Window(null, 0, newStart)));
        }
        boolean longPiece = bars > WINDOWED_BARS;
        int around = longPiece ? LONG_WINDOW_BARS : WINDOW_BARS;
        int endBars = Math.min(bars, Math.max(around, tailBars + 2));
        int endFrom = bars - endBars;
        int themeBars = Math.min(around, endFrom);
        List<Window> windows = new ArrayList<>();
        if (themeBars > 0) {
            windows.add(new Window(loop ? "beginning of the piece: main theme and loop target"
                    : "beginning of the piece: main theme", 0, time.tickOfBar(themeBars)));
        }
        windows.add(new Window("end of the original, where the continuation joins", time.tickOfBar(endFrom), newStart));
        return new ContextTiers(longPiece ? Tier.LONG : Tier.WINDOWED, longPiece, windows);
    }

    /** The last window: the end of the original. */
    public Window end() {
        return windows.get(windows.size() - 1);
    }
}
