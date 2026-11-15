package com.aresstack.audio.music.arrangement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The one reader of {@code arrangement.tsv}. Every part of the studio that needs the tempo,
 * the tracks or the notes of a written arrangement reads them here, so that the format is
 * understood in one place only.
 *
 * <p>The format, one entry per line, columns separated by a tab:
 * <pre>
 * tempo  &lt;bpm&gt;
 * meter  &lt;numerator&gt;  &lt;denominator&gt;          (optional; 4 4 if missing)
 * key    &lt;tonic&gt;  &lt;major|minor&gt;              (optional; C major if missing)
 * bars   &lt;number of bars&gt;                     (optional; see {@link #bars()})
 * track  &lt;id&gt;  &lt;program&gt;  &lt;channel&gt;  [&lt;role&gt;]
 * staff  &lt;track id&gt;  &lt;single|grand&gt;            (optional; single if missing)
 * note   &lt;track id&gt;  &lt;tick&gt;  &lt;length&gt;  &lt;pitch&gt;  &lt;velocity&gt;  [&lt;voice&gt;]
 * </pre>
 * Ticks are at {@link #PPQ} per quarter note. The tonic of a key is written as a letter with
 * {@code #} or {@code b}, for example {@code key  D  minor} or {@code key  Bb  major}. Pieces
 * from before work package F2 have no {@code meter}, {@code key} or {@code bars} line; they
 * are four quarters to the bar in C major, as the studio then only knew.
 *
 * <p>Since work package S1 a track may be written on a grand staff ({@code staff  piano  grand}:
 * treble and bass system, braced, as for piano, harp or organ), and a note may name the voice it
 * belongs to in a seventh column. A voice is written as a short word: {@code 1}, {@code 2},
 * {@code 3}, ... on a single staff, from the top voice down; {@code rh}, {@code rh2}, ... for the
 * voices of the upper system of a grand staff (right hand) and {@code lh}, {@code lh2}, ... for
 * those of the lower one (left hand). A track whose notes name no voice is set by the heuristic
 * of the score, as every piece from before S1. Neither line nor column changes what sounds.
 * Byte order marks are ignored, and so are blank lines and lines that start with {@code #}.
 *
 * <p>The reader itself is lenient: it records what each line says, in the order of the
 * lines, and keeps what it cannot understand as a {@link Problem} instead of failing. What a
 * caller accepts is the caller's decision: the reference renderer refuses any problem, the
 * views show what can be shown. Numbers are read with surrounding blanks removed; the values
 * are not checked for range here.
 */
public final class ArrangementFile {
    /** The tempo a view assumes when the arrangement names none, or none that can be read. */
    public static final int DEFAULT_TEMPO = 108;

    /** Ticks per quarter note in every arrangement. */
    public static final int PPQ = 480;

    /** The length of every piece from before F2, which had no {@code bars} line: eight bars. */
    public static final int LEGACY_BARS = 8;

    /** One understood or not understood line, in the order of the file. */
    public sealed interface Entry permits Tempo, Meter, Key, Bars, Track, Staff, Note, Problem {
        /** The line number, counted from 1. */
        int line();

        /** The line as written, without byte order marks. */
        String text();
    }

    /** A {@code tempo} line. {@code value} is the column as written. */
    public record Tempo(int line, String text, String value) implements Entry {
        /** The beats per minute, if the column is a whole number. */
        public OptionalInt bpm() {
            return number(value);
        }
    }

    /** A {@code meter} line whose two numbers can be read; not checked for range here. */
    public record Meter(int line, String text, int numerator, int denominator) implements Entry {
        /** Whether this is four quarters to the bar. */
        public boolean fourFour() {
            return numerator == 4 && denominator == 4;
        }

        /** How many quarter notes a bar has: 3 for 3/4, 3 for 6/8, 3.5 for 7/8. */
        public double quartersPerBar() {
            return numerator * 4.0 / denominator;
        }

        /** The length of a bar in ticks. */
        public long barTicks() {
            return (long) PPQ * 4 * numerator / denominator;
        }
    }

    /** A {@code key} line with a tonic and a mode, as written. */
    public record Key(int line, String text, String tonic, String mode) implements Entry {
        /** Whether the key is C major, the key of the pieces from before. */
        public boolean cMajor() {
            return "C".equals(tonic) && "major".equals(mode);
        }

        /** For example {@code "D minor"}. */
        public String name() {
            return tonic + " " + mode;
        }
    }

    /** A {@code bars} line whose number can be read. */
    public record Bars(int line, String text, int value) implements Entry {
    }

    /**
     * A {@code track} line with at least four columns. Program and channel are kept as written,
     * because the views and the renderer treat an unreadable one differently; {@code role} is
     * {@code null} if the line has no fifth column.
     */
    public record Track(int line, String text, int columns, String id, String program, String channel, String role)
            implements Entry {
        public OptionalInt programNumber() {
            return number(program);
        }

        public OptionalInt channelNumber() {
            return number(channel);
        }

        /** Whether the channel column, without blanks, is the drum channel 9. */
        public boolean onDrumChannel() {
            return "9".equals(channel.trim());
        }
    }

    /** A {@code staff} line: whether a track is written on a grand staff (since S1). */
    public record Staff(int line, String text, String trackId, boolean grand) implements Entry {
    }

    /**
     * A {@code note} line with at least six columns whose four numbers can all be read.
     *
     * @param voice the voice the seventh column names, without blanks; {@code null} if the line
     *              has no seventh column or it is blank (since S1)
     */
    public record Note(int line, String text, int columns, String trackId, long tick, long length, int pitch,
                       int velocity, String voice) implements Entry {
    }

    /** A line that is neither a comment nor any of the above. */
    public record Problem(int line, String text, String reason) implements Entry {
    }

    private final List<Entry> entries;

    private ArrangementFile(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    /** Reads an arrangement file as UTF-8. */
    public static ArrangementFile read(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /** Understands the lines of an arrangement. */
    public static ArrangementFile parse(List<String> lines) {
        List<Entry> entries = new ArrayList<Entry>();
        int number = 0;
        for (String raw : lines) {
            number++;
            String line = raw.replace("﻿", "");
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\t");
            String keyword = fields[0];
            if ("tempo".equals(keyword)) {
                entries.add(fields.length == 2 ? new Tempo(number, line, fields[1])
                        : new Problem(number, line, "a tempo line has two columns"));
            } else if ("meter".equals(keyword)) {
                entries.add(meter(number, line, fields));
            } else if ("key".equals(keyword)) {
                entries.add(fields.length == 3 && fields[1].trim().matches("[A-G][#b]?")
                        && fields[2].trim().matches("major|minor")
                        ? new Key(number, line, fields[1].trim(), fields[2].trim())
                        : new Problem(number, line, "a key line names a tonic (C, F#, Bb, ...) and major or minor"));
            } else if ("bars".equals(keyword)) {
                OptionalInt bars = fields.length == 2 ? number(fields[1]) : OptionalInt.empty();
                entries.add(bars.isPresent() && bars.getAsInt() > 0 ? new Bars(number, line, bars.getAsInt())
                        : new Problem(number, line, "a bars line has one whole number above 0"));
            } else if ("track".equals(keyword)) {
                entries.add(fields.length >= 4
                        ? new Track(number, line, fields.length, fields[1], fields[2], fields[3],
                                fields.length >= 5 ? fields[4] : null)
                        : new Problem(number, line, "a track line has at least four columns"));
            } else if ("staff".equals(keyword)) {
                entries.add(fields.length == 3 && fields[2].trim().matches("single|grand")
                        ? new Staff(number, line, fields[1], "grand".equals(fields[2].trim()))
                        : new Problem(number, line, "a staff line names a track and single or grand"));
            } else if ("note".equals(keyword)) {
                entries.add(note(number, line, fields));
            } else {
                entries.add(new Problem(number, line, "unknown kind of line"));
            }
        }
        return new ArrangementFile(entries);
    }

    private static Entry meter(int number, String line, String[] fields) {
        if (fields.length == 3) {
            OptionalInt numerator = number(fields[1]);
            OptionalInt denominator = number(fields[2]);
            if (numerator.isPresent() && denominator.isPresent() && numerator.getAsInt() > 0
                    && denominator.getAsInt() > 0 && Integer.bitCount(denominator.getAsInt()) == 1) {
                return new Meter(number, line, numerator.getAsInt(), denominator.getAsInt());
            }
        }
        return new Problem(number, line, "a meter line has a numerator and a power of two as denominator");
    }

    private static Entry note(int number, String line, String[] fields) {
        if (fields.length < 6) {
            return new Problem(number, line, "a note line has six columns");
        }
        try {
            return new Note(number, line, fields.length, fields[1], Long.parseLong(fields[2].trim()),
                    Long.parseLong(fields[3].trim()), Integer.parseInt(fields[4].trim()),
                    Integer.parseInt(fields[5].trim()),
                    fields.length >= 7 && !fields[6].trim().isEmpty() ? fields[6].trim() : null);
        } catch (NumberFormatException odd) {
            return new Problem(number, line, "a note needs whole numbers for tick, length, pitch and velocity");
        }
    }

    /** Every line that is not blank or a comment, in the order of the file. */
    public List<Entry> entries() {
        return entries;
    }

    /** The tempo as the last tempo line says, if it can be read; not checked for range. */
    public OptionalInt tempo() {
        OptionalInt tempo = OptionalInt.empty();
        for (Entry entry : entries) {
            if (entry instanceof Tempo t) {
                tempo = t.bpm();
            }
        }
        return tempo;
    }

    /** The tempo a view shows: the written one kept within 20..400, else {@link #DEFAULT_TEMPO}. */
    public int tempoOrDefault() {
        OptionalInt tempo = tempo();
        return tempo.isPresent() ? Math.max(20, Math.min(400, tempo.getAsInt())) : DEFAULT_TEMPO;
    }

    /** The meter as the last meter line says it; empty for four quarters to the bar, as before F2. */
    public Optional<Meter> meter() {
        return last(Meter.class);
    }

    /** Quarter notes per bar: 4 unless a meter line says otherwise. */
    public double quartersPerBar() {
        return meter().map(Meter::quartersPerBar).orElse(4.0);
    }

    /** The length of a bar in ticks: 1920 unless a meter line says otherwise. */
    public long barTicks() {
        return meter().map(Meter::barTicks).orElse((long) PPQ * 4);
    }

    /** The key as the last key line says it; empty for C major, as before F2. */
    public Optional<Key> key() {
        return last(Key.class);
    }

    /**
     * The number of bars as the last bars line says it. Empty for a piece without one: then the
     * piece ends with the bar of its last note, and the reference renderer, as before F2, makes
     * at least {@link #LEGACY_BARS} bars of sound.
     */
    public OptionalInt bars() {
        return last(Bars.class).map(b -> OptionalInt.of(b.value())).orElse(OptionalInt.empty());
    }

    /** The number of bars that hold every note, at least one. */
    public int barsCovering() {
        long end = 0;
        for (Note note : notes()) {
            end = Math.max(end, note.tick() + note.length());
        }
        return barsCovering(end, barTicks());
    }

    /** The number of whole bars of this length that hold the time up to this tick, at least one. */
    public static int barsCovering(long endTick, long barTicks) {
        return (int) Math.max(1, (endTick + barTicks - 1) / barTicks);
    }

    /**
     * Whether a piece of this many bars needs a {@code bars} line, that is whether a reader would
     * otherwise take it for shorter: only when it is longer than its notes. Eight bars in four
     * quarters is the length of every piece from before F2 and needs no line, so that those
     * files stay as they were.
     */
    public static boolean needsBarsLine(int bars, int barsCovering, boolean fourFour) {
        return bars > barsCovering && !(fourFour && bars == LEGACY_BARS);
    }

    private <T extends Entry> Optional<T> last(Class<T> kind) {
        T found = null;
        for (Entry entry : entries) {
            if (kind.isInstance(entry)) {
                found = kind.cast(entry);
            }
        }
        return Optional.ofNullable(found);
    }

    /** The track lines in their written order, a repeated id as often as it is written. */
    public List<Track> tracks() {
        List<Track> tracks = new ArrayList<Track>();
        for (Entry entry : entries) {
            if (entry instanceof Track track) {
                tracks.add(track);
            }
        }
        return tracks;
    }

    /** The ids of {@link #tracks()}. */
    public List<String> trackIds() {
        List<String> ids = new ArrayList<String>();
        for (Track track : tracks()) {
            ids.add(track.id());
        }
        return ids;
    }

    /** The note lines that could be read, in their written order, whatever track they name. */
    public List<Note> notes() {
        List<Note> notes = new ArrayList<Note>();
        for (Entry entry : entries) {
            if (entry instanceof Note note) {
                notes.add(note);
            }
        }
        return notes;
    }

    /** Whether a track is written on a grand staff, as the last {@code staff} line for it says (since S1). */
    public boolean grandStaff(String trackId) {
        boolean grand = false;
        for (Entry entry : entries) {
            if (entry instanceof Staff staff && staff.trackId().equals(trackId)) {
                grand = staff.grand();
            }
        }
        return grand;
    }

    /** The lines that could not be understood. */
    public List<Problem> problems() {
        List<Problem> problems = new ArrayList<Problem>();
        for (Entry entry : entries) {
            if (entry instanceof Problem problem) {
                problems.add(problem);
            }
        }
        return problems;
    }

    /**
     * The lines of an arrangement with a track renamed: its {@code track} line, its {@code staff}
     * line and its {@code note} lines name the new id; every other line, and every other column, stays exactly
     * as written, including empty trailing columns.
     */
    public static List<String> renameTrack(List<String> lines, String from, String to) {
        List<String> renamed = new ArrayList<String>(lines.size());
        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            String keyword = fields[0].replace("﻿", "");
            if (fields.length >= 2 && ("track".equals(keyword) || "staff".equals(keyword)
                    || "note".equals(keyword)) && fields[1].equals(from)) {
                fields[1] = to;
                line = String.join("\t", fields);
            }
            renamed.add(line);
        }
        return renamed;
    }

    private static OptionalInt number(String text) {
        try {
            return OptionalInt.of(Integer.parseInt(text.trim()));
        } catch (NumberFormatException odd) {
            return OptionalInt.empty();
        }
    }
}
