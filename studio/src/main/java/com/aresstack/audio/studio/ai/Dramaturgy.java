package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.studio.ai.VoiceNormalizer.ShapedNote;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * What the intensity of a section means for the music (report 10.1 "kompositorische Dynamik",
 * architecture review 3: intensity in the plan acts on instrumentation and density). Intensity is
 * not a volume knob: it decides how many parts play, how many notes a part plays per bar, how
 * wide its register is, and only last how loud it plays. Every rule here is a pure function, so
 * plans without an answer of the model and the repairs of the composers stay reproducible.
 *
 * <ul>
 * <li><b>Instrumentation</b> ({@link #ensemble}): at intensity 0 about a third of the parts play,
 *     at 1 all of them; the parts that carry the piece (melody, bass, harmony) come first, texture
 *     and drums join last.</li>
 * <li><b>Density</b> ({@link #texture}): a range of notes per bar after the role of the part,
 *     growing with intensity.</li>
 * <li><b>Thinning</b> ({@link #thin}): a bar that has more than twice the notes the intensity
 *     allows is thinned to that limit, keeping the notes on the beat and the strongest ones; every
 *     thinning is recorded.</li>
 * </ul>
 */
public final class Dramaturgy {
    /** Share of the parts that play at intensity 0; at 1 all play. */
    public static final double LEAST_SHARE = 0.35;
    /** A bar is thinned once it has more than this many times the most notes its intensity allows. */
    public static final double THIN_ABOVE = 2.0;

    /**
     * How a part plays at an intensity.
     *
     * @param fewest  the fewest notes per bar the part should play when it plays
     * @param most    the most notes per bar
     * @param softest the lowest velocity
     * @param loudest the highest velocity
     * @param register how wide the part moves, in words for the model
     */
    public record Texture(double fewest, double most, int softest, int loudest, String register) {
    }

    private Dramaturgy() {
    }

    /** How many of {@code available} parts play at an intensity: at least one. */
    public static int ensembleSize(double intensity, int available) {
        if (available <= 0) {
            return 0;
        }
        double share = LEAST_SHARE + (1 - LEAST_SHARE) * clamp(intensity);
        return Math.max(1, Math.min(available, (int) Math.ceil(available * share - 1e-9)));
    }

    /**
     * The parts that play at an intensity, in their given order: the {@link #ensembleSize} parts
     * with the highest priority of their role (melody, bass, harmony, texture, none, drums), ties
     * kept in order.
     */
    public static <T> List<T> ensemble(double intensity, List<T> parts, Function<T, String> role) {
        int size = ensembleSize(intensity, parts.size());
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> priority(role.apply(parts.get(i)))).thenComparingInt(i -> i));
        List<Integer> chosen = new ArrayList<>(order.subList(0, size));
        chosen.sort(null);
        List<T> playing = new ArrayList<>();
        chosen.forEach(i -> playing.add(parts.get(i)));
        return playing;
    }

    /** How a part of a role plays at an intensity. */
    public static Texture texture(double intensity, String role) {
        double i = clamp(intensity);
        double full = switch (role == null ? "" : role) {
            case Role.MELODY -> 8;
            case Role.HARMONY -> 6;
            case Role.BASS -> 4;
            case Role.DRUMS -> 16;
            default -> 4;
        };
        double fewest = Math.max(0.5, round(full * (0.1 + 0.4 * i)));
        double most = Math.max(1, round(full * (0.35 + 0.85 * i)));
        int softest = (int) Math.round(40 + 50 * i);
        int loudest = Math.min(127, softest + 25);
        String register = i < 0.34 ? "narrow range in the middle register, long notes, many rests"
                : i < 0.67 ? "moderate range, motifs and some movement"
                : "wide range, the upper register for climaxes, active rhythm";
        return new Texture(fewest, most, softest, loudest, register);
    }

    /** What an intensity asks of a part of a role, in one line for the model. */
    public static String guidance(double intensity, String role) {
        Texture t = texture(intensity, role);
        return "Dramaturgy for intensity " + ContextBuilder.number(clamp(intensity)) + ": about "
                + ContextBuilder.number(t.fewest()) + " to " + ContextBuilder.number(t.most()) + " notes per bar, velocity "
                + t.softest() + "-" + t.loudest() + ", " + t.register() + ".";
    }

    /** The rule of instrumentation in one line for a planner. */
    public static String ensembleRule(int available) {
        return "Intensity shapes the instrumentation, not only the volume: at intensity 0.2 about "
                + ensembleSize(0.2, available) + ", at 0.5 about " + ensembleSize(0.5, available) + " and at 1 all "
                + available + " parts play; density, register and velocity grow with it.";
    }

    /**
     * The notes of a frame without the excess of overfull bars: a bar with more than
     * {@value #THIN_ABOVE} times the most notes of its intensity keeps that many, the notes on a
     * beat first, then the louder, then the earlier. The notes keep their order.
     *
     * @param corrections where each thinning is recorded, tab-separated like the normalizer's lines
     */
    public static List<ShapedNote> thin(VoiceFrame frame, TimeMap time, double intensity, String role,
                                        List<ShapedNote> notes, List<String> corrections) {
        int limit = (int) Math.ceil(texture(intensity, role).most() * THIN_ABOVE);
        Map<Integer, List<ShapedNote>> bars = new TreeMap<>();
        for (ShapedNote n : notes) {
            bars.computeIfAbsent(time.barOf(frame.startTick() + n.tick()), b -> new ArrayList<>()).add(n);
        }
        List<ShapedNote> dropped = new ArrayList<>();
        for (Map.Entry<Integer, List<ShapedNote>> bar : bars.entrySet()) {
            List<ShapedNote> inBar = bar.getValue();
            if (inBar.size() <= limit) {
                continue;
            }
            List<ShapedNote> ranked = new ArrayList<>(inBar);
            ranked.sort(Comparator.comparing((ShapedNote n) -> (frame.startTick() + n.tick()) % TimeMap.PPQ != 0)
                    .thenComparing(Comparator.comparingInt(ShapedNote::velocity).reversed())
                    .thenComparingLong(ShapedNote::tick));
            dropped.addAll(ranked.subList(limit, ranked.size()));
            corrections.add(frame.sectionId() + "\t-\tdensity\t" + inBar.size() + "\t" + limit + "\tTakt "
                    + (bar.getKey() + 1) + " zu dicht für Intensität " + String.format(Locale.ROOT, "%.2f", intensity)
                    + ", ausgedünnt");
        }
        if (dropped.isEmpty()) {
            return notes;
        }
        List<ShapedNote> kept = new ArrayList<>(notes);
        dropped.forEach(kept::remove);
        return kept;
    }

    private static int priority(String role) {
        if (role == null) {
            return 4;
        }
        return switch (role) {
            case Role.MELODY -> 0;
            case Role.BASS -> 1;
            case Role.HARMONY -> 2;
            case Role.TEXTURE -> 3;
            case Role.DRUMS -> 5;
            default -> 4;
        };
    }

    private static double clamp(double intensity) {
        return Double.isFinite(intensity) ? Math.max(0, Math.min(1, intensity)) : 0.5;
    }

    private static double round(double value) {
        return Math.round(value * 2) / 2.0;
    }
}
