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
        double angle = (position + 1) * Math.PI / 4;
        toLeft = (float) (Math.cos(angle) * Math.sqrt(2)) * distanceGain;
        toRight = (float) (Math.sin(angle) * Math.sqrt(2)) * distanceGain;
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
