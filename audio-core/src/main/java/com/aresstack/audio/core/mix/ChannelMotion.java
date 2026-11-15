package com.aresstack.audio.core.mix;

/**
 * How a channel moves: where its direct sound goes and how much of it reaches the room, at a
 * frame counted from the start of the mix (architecture review, work package D3). A
 * {@link StemChannel} asks every {@link #RAMP} frames and glides in between, so that a moving
 * source never clicks; the grid counts from the start of the mix, so the result does not depend
 * on the block size.
 *
 * <p>Called on the rendering thread: it must neither block nor allocate. Whoever makes it turns
 * the place of the source into gains, so that the core knows no stage.
 */
@FunctionalInterface
public interface ChannelMotion {
    /** Frames between two looks at the motion, as for a moving level. */
    int RAMP = 64;

    /** Index of the gain of the direct sound to the left of the master bus. */
    int TO_LEFT = 0;
    /** Index of the gain of the direct sound to the right of the master bus. */
    int TO_RIGHT = 1;
    /** Index of the share of the left side sent to the room bus. */
    int SEND_LEFT = 2;
    /** Index of the share of the right side sent to the room bus. */
    int SEND_RIGHT = 3;
    /** How many gains a motion writes. */
    int GAINS = 4;

    /** Writes the four gains of the channel at this frame into {@code gains[0..3]}. */
    void gainsAt(long frame, float[] gains);
}
