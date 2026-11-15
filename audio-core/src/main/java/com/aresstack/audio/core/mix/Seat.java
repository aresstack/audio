package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.BiquadCoefficients;

/**
 * Where a {@link SpatialChannel} stands at one moment, as the numbers of its chain: written by a
 * {@link SpatialMotion} into an object the channel owns, so that asking for it allocates nothing.
 * The fields are public on purpose: this is a scratch record of the rendering thread, not a value
 * to keep.
 *
 * <ul>
 *   <li>{@code toLeft}, {@code toRight}: gains of the direct sound (for headphones both are the
 *       distance gain; the direction does the rest).</li>
 *   <li>{@code sendLeft}, {@code sendRight}: what goes to the room.</li>
 *   <li>{@code shelf}: the dulling with distance, {@code null} for none. Take it from a table made
 *       beforehand; the channel glides between sets and recognises a set by its identity.</li>
 *   <li>{@code azimuth}, {@code elevation}: direction in degrees for headphones (see
 *       {@link com.aresstack.audio.core.spatial.HrirSet}); ignored on loudspeakers.</li>
 *   <li>{@code echoDelay}, {@code echoLeft}, {@code echoRight}: per wall the delay after the
 *       direct sound in frames (between samples allowed) and the gains.</li>
 * </ul>
 */
public final class Seat {
    public float toLeft;
    public float toRight;
    public float sendLeft;
    public float sendRight;
    public BiquadCoefficients shelf;
    public double azimuth;
    public double elevation;
    public final float[] echoDelay;
    public final float[] echoLeft;
    public final float[] echoRight;

    public Seat(int walls) {
        if (walls < 0) {
            throw new IllegalArgumentException("A room has no negative number of walls.");
        }
        echoDelay = new float[walls];
        echoLeft = new float[walls];
        echoRight = new float[walls];
    }

    public int walls() {
        return echoDelay.length;
    }

    /** Copies all values from another seat with as many walls. */
    public void copyFrom(Seat other) {
        toLeft = other.toLeft;
        toRight = other.toRight;
        sendLeft = other.sendLeft;
        sendRight = other.sendRight;
        shelf = other.shelf;
        azimuth = other.azimuth;
        elevation = other.elevation;
        System.arraycopy(other.echoDelay, 0, echoDelay, 0, echoDelay.length);
        System.arraycopy(other.echoLeft, 0, echoLeft, 0, echoLeft.length);
        System.arraycopy(other.echoRight, 0, echoRight, 0, echoRight.length);
    }
}
