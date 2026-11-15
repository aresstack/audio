package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Smooths the loudness at a seam, where new bars were put behind the end of a piece: a level the
 * new bars begin with that differs from the level the piece had before is reached by a hairpin
 * over the first new bar instead of a sudden jump. The old bars end at their level, the new ones
 * grow or fade into theirs. Used while a piece is lengthened, and afterwards for the seams of
 * pieces lengthened before; no notes change.
 */
public final class SeamSmoothing {
    private SeamSmoothing() {
    }

    /**
     * The dynamics with the jump at {@code seam} smoothed. Only a mark in the first bar from the
     * seam that {@code mayGlide} allows, with one clear level before the seam for all its parts,
     * no other mark of that bar for the same parts and no hairpin there already. Applied twice,
     * it changes nothing the second time.
     *
     * @param end      where the first new bar ends at the latest (the end of the new bars)
     * @param said     what was smoothed, one line per hairpin, for the user
     */
    public static Dynamics smooth(Composition piece, Dynamics dynamics, long seam, long end,
                                  Predicate<Dynamics.Mark> mayGlide, List<String> said) {
        if (seam <= 0) {
            return dynamics;
        }
        long firstBarEnd = Math.min(end, seam + piece.timeMap().meterAt(seam).barTicks());
        List<Dynamics.Mark> inBar = new ArrayList<>();
        List<Dynamics.Mark> earlierMarks = new ArrayList<>();
        for (Dynamics.Mark mark : dynamics.marks()) {
            if (mark.tick() < seam) {
                earlierMarks.add(mark);
            } else if (mark.tick() < firstBarEnd) {
                inBar.add(mark);
            }
        }
        List<Dynamics.Hairpin> earlierPins = new ArrayList<>();
        for (Dynamics.Hairpin pin : dynamics.hairpins()) {
            if (pin.startTick() < seam) {
                earlierPins.add(pin);
            }
        }
        // The level the piece has at the seam before anything of the new bars sounds.
        Dynamics before = new Dynamics(earlierMarks, earlierPins, dynamics.scale(), dynamics.articulations());
        List<Dynamics.Mark> allMarks = new ArrayList<>(dynamics.marks());
        List<Dynamics.Hairpin> allPins = new ArrayList<>(dynamics.hairpins());
        boolean changed = false;
        for (Dynamics.Mark mark : inBar) {
            if (!mayGlide.test(mark)) {
                continue;
            }
            List<String> parts = mark.partId() == null ? ContinuationPerformance.partIds(piece)
                    : List.of(mark.partId());
            Dynamics.Level from = null;
            boolean same = true;
            for (String part : parts) {
                Dynamics.Level level = levelOf(before, before.levelVelocity(part, seam));
                same &= level != null && (from == null || from == level);
                from = level;
            }
            if (!same || from == null || from == mark.level()) {
                continue; // no clear level to come from, or no change at all
            }
            boolean shaped = inBar.stream().anyMatch(other -> other != mark
                    && (other.partId() == null || mark.partId() == null || other.partId().equals(mark.partId())));
            if (shaped) {
                continue;
            }
            long glideEnd = mark.tick() > seam ? mark.tick() : firstBarEnd;
            Dynamics.Hairpin glide = new Dynamics.Hairpin(seam, glideEnd, mark.level(), mark.partId());
            if (allPins.stream().anyMatch(other -> ContinuationPerformance.overlaps(glide, other))) {
                continue; // a hairpin already leads there
            }
            allPins.add(glide);
            if (mark.tick() < glideEnd) {
                allMarks.remove(mark);
                allMarks.add(new Dynamics.Mark(glideEnd, mark.level(), mark.partId()));
            }
            changed = true;
            said.add("Lautstärke an der Naht geglättet: " + from.symbol() + " → " + mark.level().symbol()
                    + " über den ersten neuen Takt statt eines Sprungs"
                    + (mark.partId() == null ? "" : " (" + mark.partId() + ")") + ".");
        }
        return changed ? new Dynamics(allMarks, allPins, dynamics.scale(), dynamics.articulations()) : dynamics;
    }

    private static Dynamics.Level levelOf(Dynamics dynamics, double velocity) {
        for (Dynamics.Level level : Dynamics.Level.values()) {
            if (dynamics.scale().velocity(level) == velocity) {
                return level;
            }
        }
        return null;
    }
}
