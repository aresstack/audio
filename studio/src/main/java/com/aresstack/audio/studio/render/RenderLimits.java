package com.aresstack.audio.studio.render;

import java.io.IOException;
import java.time.Duration;

/**
 * The one limit of the render path (architecture review E7.4): how long a piece may be, and
 * how long an external sampler may take for it, derived from the length of the piece. It is
 * handed to the MIDI writer, the Gervill child process and the process runner instead of each
 * having a limit of its own (before, three minutes in Gervill and in the stem mixer, five
 * minutes of process time regardless of the length).
 *
 * @param maxPiece   the longest piece that is rendered at all
 * @param minProcess the time any sampler gets, however short the piece
 */
public record RenderLimits(Duration maxPiece, Duration minProcess) {
    /** An hour of music, at least five minutes for a sampler: far beyond the twelve minutes of A14. */
    public static final RenderLimits STANDARD = new RenderLimits(Duration.ofMinutes(60), Duration.ofMinutes(5));

    public RenderLimits {
        if (maxPiece == null || maxPiece.isNegative() || maxPiece.isZero()
                || minProcess == null || minProcess.isNegative() || minProcess.isZero()) {
            throw new IllegalArgumentException("Render limits must be positive.");
        }
    }

    /** Refuses a piece longer than {@link #maxPiece}, saying what it is. */
    public void checkPiece(double seconds, String what) throws IOException {
        if (!(seconds <= maxPiece.toMillis() / 1000.0)) {
            throw new IOException(what + " is " + minutes(seconds) + " long; the render limit is "
                    + minutes(maxPiece.toMillis() / 1000.0) + ".");
        }
    }

    /**
     * How long a sampler may run for a piece of this length: the minimum plus twice the length
     * of the piece. Offline samplers run far faster than real time; this only catches a hang.
     */
    public Duration processTimeout(double pieceSeconds) {
        double extra = Math.max(0, Math.min(pieceSeconds, maxPiece.toMillis() / 1000.0)) * 2;
        return minProcess.plusMillis((long) Math.ceil(extra * 1000));
    }

    private static String minutes(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.1f min", seconds / 60.0);
    }
}
