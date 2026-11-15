package com.aresstack.audio.studio.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The intensity curve the user gives for a stretch of the piece (report 10.1, work package C4):
 * values from 0 (very quiet and sparse) to 1 (full and dense) at evenly spaced points from the
 * start to the end of the stretch, linear in between. One point is a flat curve. The curve says
 * how intense each section shall be; how intensity shapes instrumentation and density is the
 * business of {@link Dramaturgy}.
 *
 * @param points the values at the start, evenly spaced, and at the end; 1 to {@value #MAX_POINTS}
 */
public record IntensityCurve(List<Double> points) {
    public static final int MAX_POINTS = 32;

    public IntensityCurve {
        points = List.copyOf(points == null ? List.of() : points);
        if (points.isEmpty() || points.size() > MAX_POINTS) {
            throw new IllegalArgumentException("Eine Intensitätskurve hat 1 bis " + MAX_POINTS + " Punkte: "
                    + points.size());
        }
        for (Double value : points) {
            if (value == null || !(value >= 0 && value <= 1)) {
                throw new IllegalArgumentException("Intensität läuft von 0 bis 1: " + value);
            }
        }
    }

    /** A curve through these values. */
    public static IntensityCurve of(double... values) {
        List<Double> list = new ArrayList<>();
        for (double value : values) {
            list.add(value);
        }
        return new IntensityCurve(list);
    }

    /** The same intensity everywhere. */
    public static IntensityCurve flat(double value) {
        return of(value);
    }

    /** From {@code from} to {@code to} in a straight line: a build if it rises, a decay if it falls. */
    public static IntensityCurve ramp(double from, double to) {
        return of(from, to);
    }

    /** Rises to a peak in the middle and falls again: the arc of a climax. */
    public static IntensityCurve arch(double from, double peak, double to) {
        return of(from, peak, to);
    }

    /** The intensity at a position from 0 (start of the stretch) to 1 (its end), to two decimals. */
    public double at(double position) {
        double p = Math.max(0, Math.min(1, position));
        if (points.size() == 1) {
            return points.get(0);
        }
        double scaled = p * (points.size() - 1);
        int index = Math.min(points.size() - 2, (int) Math.floor(scaled));
        double fraction = scaled - index;
        double value = points.get(index) + (points.get(index + 1) - points.get(index)) * fraction;
        return Math.round(value * 100) / 100.0;
    }

    /**
     * The intensity of each of a row of sections of the given lengths in bars, taken at the
     * middle of each: what a section of the plan or of the piece shall have.
     */
    public List<Double> forSections(List<Integer> bars) {
        int total = bars.stream().mapToInt(Integer::intValue).sum();
        List<Double> values = new ArrayList<>();
        int start = 0;
        for (int length : bars) {
            values.add(at(total == 0 ? 0 : (start + length / 2.0) / total));
            start += length;
        }
        return values;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (Double value : points) {
            text.append(text.length() == 0 ? "" : " -> ").append(String.format(Locale.ROOT, "%.2f", value));
        }
        return text.toString();
    }
}
