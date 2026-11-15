package com.aresstack.audio.core.mix;

/**
 * Where a {@link SpatialChannel} stands over time, everything included: gains, send, distance
 * shelf, wall echoes and direction (work package D4; {@link ChannelMotion} of D3 moves only the
 * gains). The channel asks every {@link #RAMP} frames, counted from the start of the mix, and
 * glides in between, so the result does not depend on the block size.
 *
 * <p>Called on the rendering thread: it must neither block nor allocate. Whoever makes it turns
 * the place of the source into numbers, so that the core knows no stage.
 */
@FunctionalInterface
public interface SpatialMotion {
    /** Frames between two looks at the motion. */
    int RAMP = ChannelMotion.RAMP;

    /** Writes where the channel stands at this frame into {@code seat}. */
    void seatAt(long frame, Seat seat);
}
