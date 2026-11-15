package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;

/**
 * Places a channel between left and right with equal power, so that it sounds equally loud
 * wherever it stands, and adds it to a bus. Position -1 is hard left, 0 the middle (both sides
 * at full level), +1 hard right. A distance gain scales both sides.
 */
public final class StereoPanner {
    private final float toLeft;
    private final float toRight;

    public StereoPanner(double position, float distanceGain) {
        if (!(position >= -1 && position <= 1)) {
            throw new IllegalArgumentException("A position lies between -1 and +1.");
        }
        toLeft = leftGain(position, distanceGain);
        toRight = rightGain(position, distanceGain);
    }

    /**
     * The gain to the left of a channel at this position with this distance gain; a moving
     * channel calculates its gains with the same formula as a standing one.
     */
    public static float leftGain(double position, float distanceGain) {
        double angle = (position + 1) * Math.PI / 4;
        return (float) (Math.cos(angle) * Math.sqrt(2)) * distanceGain;
    }

    /** The gain to the right of a channel at this position with this distance gain. */
    public static float rightGain(double position, float distanceGain) {
        double angle = (position + 1) * Math.PI / 4;
        return (float) (Math.sin(angle) * Math.sqrt(2)) * distanceGain;
    }

    private StereoPanner(float toLeft, float toRight) {
        this.toLeft = toLeft;
        this.toRight = toRight;
    }

    /** A panner with these two gains, as {@link #toLeft()} and {@link #toRight()} of another one gave them. */
    public static StereoPanner ofGains(float toLeft, float toRight) {
        if (!Float.isFinite(toLeft) || !Float.isFinite(toRight)) {
            throw new IllegalArgumentException("The gains of a panner are finite.");
        }
        return new StereoPanner(toLeft, toRight);
    }

    public float toLeft() {
        return toLeft;
    }

    public float toRight() {
        return toRight;
    }

    /** Adds the first {@code count} frames of the block, placed, to the bus. */
    public void addTo(AudioBlock block, int count, AudioBlock bus) {
        block.addTo(bus, count, toLeft, toRight);
    }
}
