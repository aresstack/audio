package com.aresstack.audio.core.automation;

import java.util.Arrays;

/**
 * A parameter over time as points joined by straight lines: before the first point it holds the
 * value of the first, after the last the value of the last, and two points at the same time make
 * a jump. The points are given in a unit of time the caller chooses (seconds, bars, ...) together
 * with how many frames make one unit; a frame is turned into that unit before the curve is read,
 * so a curve in bars gives exactly what the studio has always calculated from bars.
 *
 * <p>A curve is immutable and may be read from the audio thread: {@link #valueAt(long)} neither
 * allocates nor locks, and finds its place among the points by halving.
 */
public final class AutomationCurve {
    private final double[] times;
    private final double[] values;
    private final double framesPerUnit;

    private AutomationCurve(double[] times, double[] values, double framesPerUnit) {
        this.times = times;
        this.values = values;
        this.framesPerUnit = framesPerUnit;
    }

    /**
     * @param times         when the points are, in order, in the unit of the curve
     * @param values        the value at each point
     * @param framesPerUnit how many frames make one unit of time, above 0
     */
    public static AutomationCurve of(double[] times, double[] values, double framesPerUnit) {
        if (times == null || values == null || times.length != values.length || times.length == 0) {
            throw new IllegalArgumentException("A curve has at least one point, each with a time and a value.");
        }
        if (!(framesPerUnit > 0) || Double.isInfinite(framesPerUnit)) {
            throw new IllegalArgumentException("A unit of time lasts a finite number of frames above 0.");
        }
        for (int i = 0; i < times.length; i++) {
            if (!Double.isFinite(times[i]) || !Double.isFinite(values[i])) {
                throw new IllegalArgumentException("The points of a curve are finite: point " + i);
            }
            if (i > 0 && times[i] < times[i - 1]) {
                throw new IllegalArgumentException("The points of a curve are in order: point " + i);
            }
        }
        return new AutomationCurve(times.clone(), values.clone(), framesPerUnit);
    }

    /** A curve that holds one value all the time. */
    public static AutomationCurve constant(double value) {
        return of(new double[] {0}, new double[] {value}, 1);
    }

    /** How many points the curve has. */
    public int size() {
        return times.length;
    }

    /** Whether the curve holds one value all the time. */
    public boolean constant() {
        for (double value : values) {
            if (value != values[0]) {
                return false;
            }
        }
        return true;
    }

    /** The value at a frame counted from the start. */
    public double valueAt(long frame) {
        return valueAtTime(frame / framesPerUnit);
    }

    /** The value at a time in the unit of the curve. */
    public double valueAtTime(double time) {
        if (time <= times[0]) {
            return values[0];
        }
        int last = times.length - 1;
        if (time > times[last]) {
            return values[last];
        }
        // The first point at or after the time; the one before it lies strictly before.
        int low = 1;
        int high = last;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (times[middle] >= time) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        double span = times[low] - times[low - 1];
        return span <= 0 ? values[low]
                : values[low - 1] + (values[low] - values[low - 1]) * (time - times[low - 1]) / span;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AutomationCurve curve && framesPerUnit == curve.framesPerUnit
                && Arrays.equals(times, curve.times) && Arrays.equals(values, curve.values);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(times) + Arrays.hashCode(values);
    }
}
