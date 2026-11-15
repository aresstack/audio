package com.aresstack.audio.core.live;

/**
 * A value that glides to a new target in a straight line over a fixed number of frames instead of
 * jumping, so that a changed parameter never clicks: two neighbouring frames differ by at most
 * {@code |target - start| / frames}. A new target in the middle of a glide starts a new glide from
 * where the value is. At the end of the glide the value is exactly the target.
 *
 * <p>Used only on the audio thread; it neither allocates nor locks.
 */
public final class Ramp {
    private float value;
    private float target;
    private float step;
    private int remaining;

    public Ramp(float value) {
        reset(value);
    }

    /** Jumps to a value at once, without gliding (before playback starts, or after a seek). */
    public void reset(float value) {
        this.value = value;
        this.target = value;
        this.step = 0;
        this.remaining = 0;
    }

    /** Glides to the target over this many frames; at once if {@code frames} is 0 or less. */
    public void glideTo(float target, int frames) {
        if (target == this.target) {
            return;
        }
        this.target = target;
        if (frames <= 0) {
            reset(target);
            return;
        }
        remaining = frames;
        step = (target - value) / frames;
    }

    /** The value for the next frame; the glide advances by one frame. */
    public float next() {
        if (remaining == 0) {
            return value;
        }
        if (--remaining == 0) {
            value = target;
        } else {
            value += step;
        }
        return value;
    }

    /** The value now, without advancing. */
    public float value() {
        return value;
    }

    public float target() {
        return target;
    }

    /** Whether the value has reached its target. */
    public boolean settled() {
        return remaining == 0;
    }

    /** How many frames the glide still takes. */
    public int remaining() {
        return remaining;
    }
}
