package com.aresstack.audio.composition;

import java.util.List;

/**
 * How the time of a piece runs: ticks at {@link #PPQ} per quarter are the canonical time;
 * bars, beats and seconds are derived from the events here. There are lists of tempo, meter
 * and key events from the start, so that a change within a piece later needs no new format;
 * for now every piece has exactly one of each at tick 0.
 */
public record TimeMap(List<Tempo> tempos, List<Meter> meters, List<KeyChange> keys) {
    /** Ticks per quarter note, as in every file the studio has written. */
    public static final int PPQ = 480;

    /** From this tick on, this many quarters per minute. */
    public record Tempo(long tick, double bpm) {
        public Tempo {
            if (tick < 0 || !(bpm > 0) || Double.isInfinite(bpm)) {
                throw new IllegalArgumentException("Invalid tempo " + bpm + " at tick " + tick);
            }
        }
    }

    /** From this tick on (the start of a bar), this meter. */
    public record Meter(long tick, int numerator, int denominator) {
        public Meter {
            if (tick < 0 || numerator < 1 || numerator > 64 || Integer.bitCount(denominator) != 1
                    || denominator > 64) {
                throw new IllegalArgumentException("Invalid meter " + numerator + "/" + denominator + " at tick " + tick);
            }
        }

        /** The length of one bar in ticks. */
        public long barTicks() {
            return (long) PPQ * 4 * numerator / denominator;
        }
    }

    /** From this tick on, this key. */
    public record KeyChange(long tick, Key key) {
        public KeyChange {
            if (tick < 0 || key == null) {
                throw new IllegalArgumentException("Invalid key change at tick " + tick);
            }
        }
    }

    public TimeMap {
        tempos = ordered(tempos, "tempo");
        meters = ordered(meters, "meter");
        keys = ordered(keys, "key");
        for (int i = 1; i < meters.size(); i++) {
            Meter before = meters.get(i - 1);
            if ((meters.get(i).tick() - before.tick()) % before.barTicks() != 0) {
                throw new IllegalArgumentException("A meter changes only at the start of a bar: tick " + meters.get(i).tick());
            }
        }
    }

    /** One tempo, four quarters to the bar, C major: what every piece from before the model has. */
    public static TimeMap of(double bpm) {
        return new TimeMap(List.of(new Tempo(0, bpm)), List.of(new Meter(0, 4, 4)),
                List.of(new KeyChange(0, Key.C_MAJOR)));
    }

    /** The tempo at a tick. */
    public double tempoAt(long tick) {
        return at(tempos, tick).bpm();
    }

    public Meter meterAt(long tick) {
        return at(meters, tick);
    }

    public Key keyAt(long tick) {
        return at(keys, tick).key();
    }

    /** Whether the piece has one tempo, one meter and one key only. */
    public boolean constant() {
        return tempos.size() == 1 && meters.size() == 1 && keys.size() == 1;
    }

    /** The tick at which a bar begins, bars counted from 0. */
    public long tickOfBar(int bar) {
        if (bar < 0) {
            throw new IllegalArgumentException("Bars are counted from 0: " + bar);
        }
        long tick = 0;
        int counted = 0;
        for (int i = 0; i < meters.size(); i++) {
            Meter meter = meters.get(i);
            long until = i + 1 < meters.size() ? meters.get(i + 1).tick() : Long.MAX_VALUE;
            long barsHere = until == Long.MAX_VALUE ? Long.MAX_VALUE : (until - meter.tick()) / meter.barTicks();
            if (bar - counted < barsHere) {
                return meter.tick() + (bar - counted) * meter.barTicks();
            }
            counted += (int) barsHere;
            tick = until;
        }
        return tick;
    }

    /** The bar a tick lies in, counted from 0. */
    public int barOf(long tick) {
        int bars = 0;
        for (int i = 0; i < meters.size(); i++) {
            Meter meter = meters.get(i);
            long until = i + 1 < meters.size() ? meters.get(i + 1).tick() : Long.MAX_VALUE;
            if (tick < until) {
                return bars + (int) ((tick - meter.tick()) / meter.barTicks());
            }
            bars += (int) ((until - meter.tick()) / meter.barTicks());
        }
        return bars;
    }

    /** The number of whole bars that cover the time up to this tick: at least one. */
    public int barsCovering(long endTick) {
        if (endTick <= 0) {
            return 1;
        }
        int bar = barOf(endTick - 1);
        return bar + 1;
    }

    /** The first tick at or after a time in seconds from the start of the piece. */
    public long tickAt(double seconds) {
        if (seconds <= 0) {
            return 0;
        }
        double passed = 0;
        for (int i = 0; i < tempos.size(); i++) {
            Tempo tempo = tempos.get(i);
            double ticksPerSecond = PPQ * tempo.bpm() / 60.0;
            boolean last = i + 1 == tempos.size();
            double length = last ? Double.MAX_VALUE : (tempos.get(i + 1).tick() - tempo.tick()) / ticksPerSecond;
            if (seconds - passed <= length) {
                return tempo.tick() + (long) Math.ceil((seconds - passed) * ticksPerSecond - 1e-9);
            }
            passed += length;
        }
        return 0;
    }

    /** The time from the start of the piece to a tick, in seconds. */
    public double seconds(long tick) {
        double seconds = 0;
        for (int i = 0; i < tempos.size(); i++) {
            Tempo tempo = tempos.get(i);
            long until = i + 1 < tempos.size() ? Math.min(tick, tempos.get(i + 1).tick()) : tick;
            if (until <= tempo.tick()) {
                break;
            }
            seconds += (until - tempo.tick()) / (double) PPQ * 60.0 / tempo.bpm();
        }
        return seconds;
    }

    private static <T extends Record> List<T> ordered(List<T> events, String what) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("A time map needs at least one " + what + " event.");
        }
        List<T> copy = List.copyOf(events);
        long before = -1;
        for (T event : copy) {
            long tick = tick(event);
            if (before < 0 && tick != 0) {
                throw new IllegalArgumentException("The first " + what + " event is at tick 0.");
            }
            if (tick <= before) {
                throw new IllegalArgumentException("The " + what + " events are in order, one per tick.");
            }
            before = tick;
        }
        return copy;
    }

    private static long tick(Record event) {
        if (event instanceof Tempo t) {
            return t.tick();
        }
        if (event instanceof Meter m) {
            return m.tick();
        }
        return ((KeyChange) event).tick();
    }

    private static <T extends Record> T at(List<T> events, long tick) {
        T found = events.get(0);
        for (T event : events) {
            if (tick(event) <= tick) {
                found = event;
            }
        }
        return found;
    }
}
