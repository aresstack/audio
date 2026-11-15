package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.Meta;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the {@link MusicalContext} of a piece: a pure function of the piece and the window,
 * independent of any model (architecture review 2.4, E4). Four layers:
 * <ol>
 * <li>the profile of the piece: title, genre, mood, brief, tempo, meter, key, length;</li>
 * <li>the form: every section with its bars, function, intensity, chords and instruction;</li>
 * <li>a summary of every part: instrument, role, range, notes per bar over the whole piece;</li>
 * <li>the notes of every part in the window, in full, with notes that sound into it from before
 *     marked as held.</li>
 * </ol>
 * The text is the same for the same piece and window, byte for byte: everything is in the order
 * of the model or sorted, numbers are written with {@link Locale#ROOT}, nothing depends on the
 * clock or on hash order.
 */
public final class ContextBuilder {
    private static final String[] NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final Map<Integer, String> DRUMS = Map.ofEntries(Map.entry(35, "kick2"), Map.entry(36, "kick"),
            Map.entry(37, "stick"), Map.entry(38, "snare"), Map.entry(39, "clap"), Map.entry(40, "snare2"),
            Map.entry(41, "tom-low2"), Map.entry(42, "hihat-closed"), Map.entry(43, "tom-low"),
            Map.entry(44, "hihat-pedal"), Map.entry(45, "tom-mid2"), Map.entry(46, "hihat-open"),
            Map.entry(47, "tom-mid"), Map.entry(48, "tom-high2"), Map.entry(49, "crash"), Map.entry(50, "tom-high"),
            Map.entry(51, "ride"), Map.entry(52, "china"), Map.entry(53, "ride-bell"), Map.entry(54, "tambourine"),
            Map.entry(55, "splash"), Map.entry(56, "cowbell"), Map.entry(57, "crash2"), Map.entry(59, "ride2"));

    private ContextBuilder() {
    }

    /**
     * The context of a piece for a change within {@code fromTick..toTick}, with the notes of
     * {@code aroundBars} bars before and after given in full as well.
     *
     * @param highlightPartId a part to mark as the source of motifs, or {@code null}
     */
    public static MusicalContext build(Composition piece, long fromTick, long toTick, int aroundBars,
                                       String highlightPartId) {
        if (fromTick < 0 || toTick <= fromTick || aroundBars < 0) {
            throw new IllegalArgumentException("A window covers at least one tick: " + fromTick + ".." + toTick);
        }
        TimeMap time = piece.timeMap();
        int pieceBars = time.barsCovering(Math.max(piece.endTick(), 1));
        int firstBar = Math.max(0, time.barOf(fromTick) - aroundBars);
        int endBar = Math.min(pieceBars, time.barsCovering(toTick) + aroundBars);
        long windowFrom = time.tickOfBar(firstBar);
        long windowTo = Math.max(windowFrom + 1, time.tickOfBar(Math.max(endBar, firstBar + 1)));

        StringBuilder text = new StringBuilder();
        profile(text, piece, pieceBars);
        form(text, piece);
        parts(text, piece, pieceBars, highlightPartId);
        notes(text, piece, windowFrom, windowTo, highlightPartId);
        String written = text.toString();
        return new MusicalContext(written, sha256(written), windowFrom, windowTo);
    }

    private static void profile(StringBuilder text, Composition piece, int bars) {
        Meta meta = piece.meta();
        TimeMap time = piece.timeMap();
        text.append("# PIECE\n");
        line(text, "title", meta.title());
        line(text, "genre", meta.genre());
        line(text, "mood", meta.mood());
        line(text, "brief", meta.brief());
        line(text, "remarks", meta.remarks());
        text.append("tempo: ");
        join(text, time.tempos(), t -> "bar " + (time.barOf(t.tick()) + 1) + " " + number(t.bpm()) + " BPM");
        text.append("\nmeter: ");
        join(text, time.meters(), m -> "bar " + (time.barOf(m.tick()) + 1) + " " + m.numerator() + "/" + m.denominator());
        text.append("\nkey: ");
        join(text, time.keys(), k -> "bar " + (time.barOf(k.tick()) + 1) + " " + key(k.key()));
        text.append("\nlength: ").append(bars).append(" bars\n");
        text.append("time: bar counts from 1 at the start of the piece; beat is the offset within the bar in "
                + "quarter notes, from 0; dur is in quarter notes; pitch is the MIDI number with its name "
                + "(60 = C4); vel is the MIDI velocity.\n\n");
    }

    private static void form(StringBuilder text, Composition piece) {
        TimeMap time = piece.timeMap();
        text.append("# FORM\n");
        if (piece.sections().isEmpty()) {
            text.append("(no sections)\n\n");
            return;
        }
        for (var section : piece.sections()) {
            int first = time.barOf(section.startTick()) + 1;
            int last = time.barsCovering(section.endTick());
            text.append("section ").append(section.id()).append(" \"").append(clean(section.name())).append("\" bars ")
                    .append(first).append('-').append(last);
            if (section.function() != null) {
                text.append(" function=").append(clean(section.function()));
            }
            if (section.intensity() != null) {
                text.append(" intensity=").append(number(section.intensity()));
            }
            text.append('\n');
            if (!section.harmony().isEmpty()) {
                text.append("  chords by bar: ").append(String.join(" | ", section.harmony())).append('\n');
            }
            if (section.instruction() != null && !section.instruction().isBlank()) {
                text.append("  instruction: ").append(clean(section.instruction())).append('\n');
            }
        }
        text.append('\n');
    }

    private static void parts(StringBuilder text, Composition piece, int bars, String highlight) {
        TimeMap time = piece.timeMap();
        text.append("# PARTS\n");
        for (Part part : piece.parts()) {
            List<PlacedNote> notes = piece.notesOf(part.id());
            text.append("part ").append(part.id()).append(" \"").append(clean(part.name())).append("\" instrument=")
                    .append(clean(part.instrument() == null ? part.name() : part.instrument())).append(" role=")
                    .append(part.role() == null ? "-" : part.role()).append(" program=").append(part.program());
            if (part.id().equals(highlight)) {
                text.append(" MOTIF-SOURCE");
            }
            text.append('\n');
            if (notes.isEmpty()) {
                text.append("  silent\n");
                continue;
            }
            if (part.drums()) {
                TreeMap<Integer, Integer> used = new TreeMap<>();
                for (PlacedNote n : notes) {
                    used.merge(n.note().pitch(), 1, Integer::sum);
                }
                text.append("  sounds:");
                used.forEach((pitch, count) -> text.append(' ').append(drum(pitch)).append('x').append(count));
                text.append('\n');
            } else {
                int low = notes.stream().mapToInt(n -> n.note().pitch()).min().orElse(0);
                int high = notes.stream().mapToInt(n -> n.note().pitch()).max().orElse(0);
                text.append("  range: ").append(pitch(low)).append('-').append(pitch(high)).append('\n');
            }
            int[] perBar = new int[bars];
            for (PlacedNote n : notes) {
                int bar = time.barOf(n.tick());
                if (bar < bars) {
                    perBar[bar]++;
                }
            }
            text.append("  notes per bar:");
            for (int count : perBar) {
                text.append(' ').append(count);
            }
            text.append('\n');
        }
        text.append('\n');
    }

    private static void notes(StringBuilder text, Composition piece, long from, long to, String highlight) {
        TimeMap time = piece.timeMap();
        text.append("# NOTES bars ").append(time.barOf(from) + 1).append('-').append(time.barsCovering(to))
                .append(" (bar beat dur pitch vel)\n");
        for (Part part : piece.parts()) {
            List<PlacedNote> inWindow = new ArrayList<>();
            for (PlacedNote n : piece.notesOf(part.id())) {
                if (n.tick() < to && n.endTick() > from) {
                    inWindow.add(n);
                }
            }
            inWindow.sort(Comparator.comparingLong(PlacedNote::tick).thenComparingInt(n -> n.note().pitch())
                    .thenComparingLong(n -> n.note().length()).thenComparingInt(n -> n.note().velocity()));
            text.append("part ").append(part.id());
            if (part.id().equals(highlight)) {
                text.append(" MOTIF-SOURCE");
            }
            text.append('\n');
            if (inWindow.isEmpty()) {
                text.append("  rests\n");
                continue;
            }
            for (PlacedNote n : inWindow) {
                int bar = time.barOf(n.tick());
                long barStart = time.tickOfBar(bar);
                text.append(n.tick() < from ? "  held " : "  ").append(bar + 1).append(' ')
                        .append(quarters(n.tick() - barStart)).append(' ').append(quarters(n.note().length())).append(' ')
                        .append(part.drums() ? drum(n.note().pitch()) : pitch(n.note().pitch())).append('/')
                        .append(n.note().pitch()).append(' ').append(n.note().velocity()).append('\n');
            }
        }
    }

    /** A MIDI pitch by name, 60 = C4, sharps only. */
    public static String pitch(int midi) {
        return NAMES[midi % 12] + (midi / 12 - 1);
    }

    /** A key in words, for example "D minor". */
    public static String key(Key key) {
        return NAMES[key.tonic()] + (key.mode() == Key.Mode.MAJOR ? " major" : " minor");
    }

    /** Ticks as quarter notes, as short as exact: 480 is "1", 120 is "0.25". */
    public static String quarters(long ticks) {
        return number(ticks / (double) TimeMap.PPQ);
    }

    static String number(double value) {
        String text = String.format(Locale.ROOT, "%.4f", value);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private static String drum(int pitch) {
        return DRUMS.getOrDefault(pitch, "drum" + pitch);
    }

    private static void line(StringBuilder text, String name, String value) {
        if (value != null && !value.isBlank()) {
            text.append(name).append(": ").append(clean(value)).append('\n');
        }
    }

    private static <T> void join(StringBuilder text, List<T> items, java.util.function.Function<T, String> show) {
        for (int i = 0; i < items.size(); i++) {
            text.append(i == 0 ? "" : ", ").append(show.apply(items.get(i)));
        }
    }

    /** One line, no quotes that would break the layout. */
    private static String clean(String value) {
        return value.replaceAll("\\s+", " ").replace('"', '\'').strip();
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
