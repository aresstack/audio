package com.aresstack.audio.music.arrangement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

/**
 * The one reader of {@code arrangement.tsv}. Every part of the studio that needs the tempo,
 * the tracks or the notes of a written arrangement reads them here, so that the format is
 * understood in one place only.
 *
 * <p>The format, one entry per line, columns separated by a tab:
 * <pre>
 * tempo  &lt;bpm&gt;
 * track  &lt;id&gt;  &lt;program&gt;  &lt;channel&gt;  [&lt;role&gt;]
 * note   &lt;track id&gt;  &lt;tick&gt;  &lt;length&gt;  &lt;pitch&gt;  &lt;velocity&gt;
 * </pre>
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

    /** One understood or not understood line, in the order of the file. */
    public sealed interface Entry permits Tempo, Track, Note, Problem {
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

    /** A {@code note} line with at least six columns whose four numbers can all be read. */
    public record Note(int line, String text, int columns, String trackId, long tick, long length, int pitch,
                       int velocity) implements Entry {
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
            } else if ("track".equals(keyword)) {
                entries.add(fields.length >= 4
                        ? new Track(number, line, fields.length, fields[1], fields[2], fields[3],
                                fields.length >= 5 ? fields[4] : null)
                        : new Problem(number, line, "a track line has at least four columns"));
            } else if ("note".equals(keyword)) {
                entries.add(note(number, line, fields));
            } else {
                entries.add(new Problem(number, line, "unknown kind of line"));
            }
        }
        return new ArrangementFile(entries);
    }

    private static Entry note(int number, String line, String[] fields) {
        if (fields.length < 6) {
            return new Problem(number, line, "a note line has six columns");
        }
        try {
            return new Note(number, line, fields.length, fields[1], Long.parseLong(fields[2].trim()),
                    Long.parseLong(fields[3].trim()), Integer.parseInt(fields[4].trim()),
                    Integer.parseInt(fields[5].trim()));
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
     * The lines of an arrangement with a track renamed: its {@code track} line and its
     * {@code note} lines name the new id; every other line, and every other column, stays exactly
     * as written, including empty trailing columns.
     */
    public static List<String> renameTrack(List<String> lines, String from, String to) {
        List<String> renamed = new ArrayList<String>(lines.size());
        for (String line : lines) {
            String[] fields = line.split("\\t", -1);
            String keyword = fields[0].replace("﻿", "");
            if (fields.length >= 2 && ("track".equals(keyword) || "note".equals(keyword)) && fields[1].equals(from)) {
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
