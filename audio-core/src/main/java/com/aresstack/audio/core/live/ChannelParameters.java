package com.aresstack.audio.core.live;

import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.dsp.StereoStrip;
import java.util.Arrays;

/**
 * Where a channel stands, as numbers the audio thread can use without further work: one immutable
 * value, made and checked on the thread that moves the source (architecture review 2.6,
 * {@code ParameterSnapshot}), handed over atomically and glided to by the {@link LiveChannel}.
 *
 * <ul>
 *   <li>{@code level} and {@code width}: as in {@link StereoStrip}; {@code curve} is the level
 *       that moves with the piece (or {@code null}).</li>
 *   <li>{@code toLeft}, {@code toRight}: the gains of the panner, distance included.</li>
 *   <li>{@code sendLeft}, {@code sendRight}: what goes to the room.</li>
 *   <li>{@code shelf}: the dulling of the heights with distance, {@code null} for none.</li>
 *   <li>{@code echoDelays}, {@code echoLeft}, {@code echoRight}: one entry per wall; a channel
 *       keeps the number of walls it was made with.</li>
 * </ul>
 */
public final class ChannelParameters {
    private final float level;
    private final float width;
    private final StereoStrip.LevelCurve curve;
    private final float toLeft;
    private final float toRight;
    private final float sendLeft;
    private final float sendRight;
    private final BiquadCoefficients shelf;
    private final int[] echoDelays;
    private final float[] echoLeft;
    private final float[] echoRight;

    private ChannelParameters(Builder builder) {
        level = finite(builder.level, "level");
        if (level < 0) {
            throw new IllegalArgumentException("A level is a factor of at least 0.");
        }
        width = finite(builder.width, "width");
        curve = builder.curve;
        toLeft = finite(builder.toLeft, "gain to the left");
        toRight = finite(builder.toRight, "gain to the right");
        sendLeft = finite(builder.sendLeft, "send to the left");
        sendRight = finite(builder.sendRight, "send to the right");
        shelf = builder.shelf;
        if (shelf != null && !(Math.abs(shelf.a2) < 1 && Math.abs(shelf.a1) < 1 + shelf.a2)) {
            throw new IllegalArgumentException("The shelf is not a stable filter.");
        }
        echoDelays = builder.echoDelays.clone();
        echoLeft = builder.echoLeft.clone();
        echoRight = builder.echoRight.clone();
        if (echoLeft.length != echoDelays.length || echoRight.length != echoDelays.length) {
            throw new IllegalArgumentException("Every echo needs its delay and both gains.");
        }
        for (int i = 0; i < echoDelays.length; i++) {
            if (echoDelays[i] < 0) {
                throw new IllegalArgumentException("An echo cannot come before the sound.");
            }
            finite(echoLeft[i], "echo gain");
            finite(echoRight[i], "echo gain");
        }
    }

    private static float finite(float value, String what) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("The " + what + " is not a finite number: " + value);
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder with the values of these parameters, to change some of them. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.level = level;
        builder.width = width;
        builder.curve = curve;
        builder.toLeft = toLeft;
        builder.toRight = toRight;
        builder.sendLeft = sendLeft;
        builder.sendRight = sendRight;
        builder.shelf = shelf;
        builder.echoDelays = echoDelays.clone();
        builder.echoLeft = echoLeft.clone();
        builder.echoRight = echoRight.clone();
        return builder;
    }

    public float level() {
        return level;
    }

    public float width() {
        return width;
    }

    public StereoStrip.LevelCurve curve() {
        return curve;
    }

    public float toLeft() {
        return toLeft;
    }

    public float toRight() {
        return toRight;
    }

    public float sendLeft() {
        return sendLeft;
    }

    public float sendRight() {
        return sendRight;
    }

    public BiquadCoefficients shelf() {
        return shelf;
    }

    public int echoes() {
        return echoDelays.length;
    }

    public int echoDelay(int wall) {
        return echoDelays[wall];
    }

    public float echoLeft(int wall) {
        return echoLeft[wall];
    }

    public float echoRight(int wall) {
        return echoRight[wall];
    }

    /** The longest echo delay, in frames. */
    public int longestEcho() {
        int most = 0;
        for (int delay : echoDelays) {
            most = Math.max(most, delay);
        }
        return most;
    }

    @Override
    public String toString() {
        return "level " + level + ", width " + width + ", pan " + toLeft + "/" + toRight + ", send " + sendLeft + "/"
                + sendRight + ", shelf " + (shelf != null) + ", echoes " + Arrays.toString(echoDelays);
    }

    /** Collects the values; {@link #build} checks them. */
    public static final class Builder {
        private float level = 1;
        private float width = 1;
        private StereoStrip.LevelCurve curve;
        private float toLeft = 1;
        private float toRight = 1;
        private float sendLeft;
        private float sendRight;
        private BiquadCoefficients shelf;
        private int[] echoDelays = new int[0];
        private float[] echoLeft = new float[0];
        private float[] echoRight = new float[0];

        private Builder() {
        }

        public Builder strip(float level, float width, StereoStrip.LevelCurve curve) {
            this.level = level;
            this.width = width;
            this.curve = curve;
            return this;
        }

        public Builder level(float level) {
            this.level = level;
            return this;
        }

        public Builder pan(float toLeft, float toRight) {
            this.toLeft = toLeft;
            this.toRight = toRight;
            return this;
        }

        public Builder send(float sendLeft, float sendRight) {
            this.sendLeft = sendLeft;
            this.sendRight = sendRight;
            return this;
        }

        public Builder shelf(BiquadCoefficients shelf) {
            this.shelf = shelf;
            return this;
        }

        /** The echoes of the walls, one entry per wall in each array. */
        public Builder echoes(int[] delays, float[] toLeft, float[] toRight) {
            this.echoDelays = delays.clone();
            this.echoLeft = toLeft.clone();
            this.echoRight = toRight.clone();
            return this;
        }

        public ChannelParameters build() {
            return new ChannelParameters(this);
        }
    }
}
