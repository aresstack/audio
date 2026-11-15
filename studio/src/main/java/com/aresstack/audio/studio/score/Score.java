package com.aresstack.audio.studio.score;

import com.aresstack.audio.music.arrangement.ArrangementFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A written arrangement as a conductor reads it: for every track a staff with a
 * clef, and in every bar the notes and rests with their written lengths. The
 * arrangement gives each note a start and a length in ticks; here that becomes
 * sixteenths, chords, rests for the gaps, and ties where a note is longer than a
 * bar or than one written value can say. A track whose notes all move together is
 * one voice. Where some notes are held while others move, the staff gets two
 * voices, an upper and a lower one, as in printed music; only what even two voices
 * cannot say is written with a held note repeated in the next chord and tied.
 * The score knows nothing of how it is drawn.
 */
public final class Score {
    /** Sixteenths in a bar of four quarters. */
    public static final int BAR = 16;
    private static final int TICKS_PER_SIXTEENTH = 120;
    /** The lengths a single note can have, in sixteenths: whole, dotted half, half, dotted quarter, and so on. */
    private static final int[] NOTE_VALUES = {16, 12, 8, 6, 4, 3, 2, 1};
    private static final int[] REST_VALUES = {16, 8, 4, 2, 1};

    public enum Clef { TREBLE, BASS, PERCUSSION }

    /** A chord (one note or several) or, without pitches, a rest. */
    public static final class Event {
        private final int start;
        private final int length;
        private final int[] pitches;
        private final boolean[] ties;

        Event(int start, int length, int[] pitches, boolean[] ties) {
            this.start = start;
            this.length = length;
            this.pitches = pitches;
            this.ties = ties;
        }

        /** Where in its bar the event begins, in sixteenths from 0. */
        public int start() { return start; }
        /** Its written length in sixteenths: 16, 12, 8, 6, 4, 3, 2 or 1. */
        public int length() { return length; }
        /** The MIDI pitches from low to high; none for a rest. */
        public int[] pitches() { return pitches.clone(); }
        public boolean rest() { return pitches.length == 0; }
        /** Whether any note of the event is tied to the event that follows it. */
        public boolean tied() {
            for (boolean tie : ties) {
                if (tie) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the note at this place of {@link #pitches()} is tied to the event that follows. */
        public boolean tied(int index) { return ties[index]; }
        public boolean dotted() { return length == 12 || length == 6 || length == 3; }
    }

    /** The staff of one track: its clef and its bars. */
    public static final class Staff {
        private final String trackId;
        private final Clef clef;
        private final List<List<List<Event>>> voices;

        Staff(String trackId, Clef clef, List<List<List<Event>>> voices) {
            this.trackId = trackId;
            this.clef = clef;
            this.voices = voices;
        }

        public String trackId() { return trackId; }
        public Clef clef() { return clef; }
        /** The events of a bar, in the order they sound; a bar without notes holds one whole rest. */
        public List<Event> bar(int index) { return bar(0, index); }

        /** One voice or two: the first is the upper one, the second the lower one. */
        public int voices() { return voices.size(); }

        /** The events of a bar in one of the voices. */
        public List<Event> bar(int voice, int index) {
            return Collections.unmodifiableList(voices.get(voice).get(index));
        }

        /** Whether a voice has nothing but rests in a bar. */
        public boolean silent(int voice, int index) {
            for (Event event : voices.get(voice).get(index)) {
                if (!event.rest()) {
                    return false;
                }
            }
            return true;
        }
    }

    private final int tempo;
    private final int bars;
    private final List<Staff> staves;

    private Score(int tempo, int bars, List<Staff> staves) {
        this.tempo = tempo;
        this.bars = bars;
        this.staves = Collections.unmodifiableList(staves);
    }

    public int tempo() { return tempo; }
    public int bars() { return bars; }
    public List<Staff> staves() { return staves; }
    public double secondsPerBar() { return 4 * 60.0 / tempo; }

    /** Where a pitch sits on a staff: 0 is the lowest line, 2 the next line, 8 the top line; odd numbers are spaces. */
    public static int position(int pitch, Clef clef) {
        if (clef == Clef.PERCUSSION) {
            switch (pitch) {
                case 35: case 36: return 1;                      // bass drum
                case 41: case 43: return 2;                      // low toms
                case 45: case 47: return 4;
                case 48: case 50: return 6;                      // high toms
                case 42: case 44: case 46: return 9;             // hi-hat
                case 49: case 51: case 52: case 53: case 55: case 57: case 59: return 10; // cymbals
                default: return 5;                               // snare and everything else
            }
        }
        int[] steps = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6};
        int diatonic = (pitch / 12 - 1) * 7 + steps[pitch % 12];
        return diatonic - (clef == Clef.BASS ? 18 : 30); // lowest line: G2 in the bass clef, E4 in the treble clef
    }

    /** Whether a pitch is written with a sharp (the piece is in C major, so every black key is). */
    public static boolean sharp(int pitch) {
        int tone = pitch % 12;
        return tone == 1 || tone == 3 || tone == 6 || tone == 8 || tone == 10;
    }

    /** Whether a drum sound is written with a cross as its head: hi-hats and cymbals. */
    public static boolean cross(int pitch) {
        return position(pitch, Clef.PERCUSSION) >= 9;
    }

    /** Reads an arrangement file; {@code null} if there is none or it cannot be read. */
    public static Score read(Path arrangement) {
        if (arrangement == null || !Files.isRegularFile(arrangement)) {
            return null;
        }
        try {
            return of(ArrangementFile.read(arrangement));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The score of the lines of an arrangement: tempo, tracks and notes. */
    public static Score of(List<String> lines) {
        return of(ArrangementFile.parse(lines));
    }

    /** The score of a read arrangement; a note counts only after the line that names its track. */
    public static Score of(ArrangementFile arrangement) {
        Map<String, Boolean> drums = new LinkedHashMap<String, Boolean>();
        Map<String, TreeMap<Integer, List<int[]>>> sounding = new LinkedHashMap<String, TreeMap<Integer, List<int[]>>>();
        int end = 0;
        for (ArrangementFile.Entry entry : arrangement.entries()) {
            if (entry instanceof ArrangementFile.Track line) {
                drums.put(line.id(), line.onDrumChannel() || (line.role() != null && "drums".equals(line.role().trim())));
                sounding.put(line.id(), new TreeMap<Integer, List<int[]>>());
            } else if (entry instanceof ArrangementFile.Note note && sounding.containsKey(note.trackId())) {
                long startTicks = note.tick();
                long lengthTicks = note.length();
                int start = (int) Math.round(startTicks / (double) TICKS_PER_SIXTEENTH);
                int stop = Math.max(start + 1, (int) Math.round((startTicks + lengthTicks) / (double) TICKS_PER_SIXTEENTH));
                TreeMap<Integer, List<int[]>> track = sounding.get(note.trackId());
                List<int[]> together = track.get(start);
                if (together == null) {
                    together = new ArrayList<int[]>();
                    track.put(start, together);
                }
                together.add(new int[]{note.pitch(), stop});
                end = Math.max(end, stop);
            }
        }
        int tempo = arrangement.tempoOrDefault();
        int bars = Math.max(1, (end + BAR - 1) / BAR);
        List<Staff> staves = new ArrayList<Staff>();
        for (Map.Entry<String, TreeMap<Integer, List<int[]>>> track : sounding.entrySet()) {
            boolean kit = drums.get(track.getKey());
            staves.add(new Staff(track.getKey(), kit ? Clef.PERCUSSION : clef(track.getValue()),
                    voices(track.getValue(), bars, kit)));
        }
        return new Score(tempo, bars, staves);
    }

    /** The clef in which most of a track lies: below the A under middle C it is the bass clef. */
    private static Clef clef(TreeMap<Integer, List<int[]>> track) {
        long sum = 0;
        int count = 0;
        for (List<int[]> together : track.values()) {
            for (int[] note : together) {
                sum += note[0];
                count++;
            }
        }
        return count > 0 && sum / (double) count < 57 ? Clef.BASS : Clef.TREBLE;
    }

    /**
     * The notes of a track as one voice or two.
     *
     * @param struck whether the track is struck, not held (drums): a hit ends when the next one comes
     */
    private static List<List<List<Event>>> voices(TreeMap<Integer, List<int[]>> track, int bars, boolean struck) {
        int end = bars * BAR;
        List<int[]> notes = new ArrayList<int[]>(); // pitch, start, stop
        for (Map.Entry<Integer, List<int[]>> together : track.entrySet()) {
            for (int[] note : together.getValue()) {
                int start = Math.min(end, together.getKey());
                int stop = Math.min(end, note[1]);
                Integer following = track.higherKey(together.getKey());
                if (struck && following != null) {
                    stop = Math.min(stop, following);
                }
                if (start < stop) {
                    notes.add(new int[]{note[0], start, stop});
                }
            }
        }
        List<List<List<Event>>> written = new ArrayList<List<List<Event>>>();
        List<List<int[]>> parts = struck ? null : separate(notes);
        if (parts == null) {
            written.add(write(notes, bars));
        } else {
            written.add(write(parts.get(0), bars));
            written.add(write(parts.get(1), bars));
        }
        return written;
    }

    /**
     * Divides notes into an upper and a lower voice, if they need it: notes that begin and end
     * together are a chord and stay together; a chord goes to the voice that is free when it
     * begins, the higher of two that begin together to the upper voice. {@code null} when one
     * voice says it all, that is when no note is held while another begins or ends.
     */
    private static List<List<int[]>> separate(List<int[]> notes) {
        boolean needed = false;
        for (int[] one : notes) {
            for (int[] other : notes) {
                if (one[1] < other[2] && other[1] < one[2] && (one[1] != other[1] || one[2] != other[2])) {
                    needed = true;
                }
            }
        }
        if (!needed) {
            return null;
        }
        List<int[]> sorted = new ArrayList<int[]>(notes);
        Collections.sort(sorted, (first, second) -> first[1] != second[1] ? Integer.compare(first[1], second[1])
                : Integer.compare(second[0], first[0]));
        List<int[]> upper = new ArrayList<int[]>();
        List<int[]> lower = new ArrayList<int[]>();
        int upperBusy = 0;
        int lowerBusy = 0;
        int upperStart = -1;
        int upperStop = -1;
        int lowerStart = -1;
        int lowerStop = -1;
        for (int[] note : sorted) {
            if (note[1] == upperStart && note[2] == upperStop) {
                upper.add(note); // part of the chord the upper voice is playing
            } else if (note[1] == lowerStart && note[2] == lowerStop) {
                lower.add(note);
            } else if (note[1] >= upperBusy) {
                upper.add(note);
                upperStart = note[1];
                upperStop = note[2];
                upperBusy = note[2];
            } else if (note[1] >= lowerBusy) {
                lower.add(note);
                lowerStart = note[1];
                lowerStop = note[2];
                lowerBusy = note[2];
            } else {
                lower.add(note); // a third line: the lower voice takes it and ties what it holds
                lowerBusy = Math.max(lowerBusy, note[2]);
            }
        }
        if (lower.isEmpty()) {
            return null;
        }
        // The voice that lies higher on the whole is the upper one.
        if (mean(upper) < mean(lower)) {
            List<int[]> swap = upper;
            upper = lower;
            lower = swap;
        }
        List<List<int[]>> parts = new ArrayList<List<int[]>>();
        parts.add(upper);
        parts.add(lower);
        return parts;
    }

    private static double mean(List<int[]> notes) {
        double sum = 0;
        for (int[] note : notes) {
            sum += note[0] * (double) (note[2] - note[1]);
        }
        double time = 0;
        for (int[] note : notes) {
            time += note[2] - note[1];
        }
        return time == 0 ? 0 : sum / time;
    }

    /** Writes the notes of one voice into bars. */
    private static List<List<Event>> write(List<int[]> notes, int bars) {
        List<List<Event>> written = new ArrayList<List<Event>>();
        for (int bar = 0; bar < bars; bar++) {
            written.add(new ArrayList<Event>());
        }
        // Every beginning and every end of a note is a place where what sounds changes.
        int end = bars * BAR;
        TreeMap<Integer, Boolean> changes = new TreeMap<Integer, Boolean>();
        changes.put(0, Boolean.TRUE);
        changes.put(end, Boolean.TRUE);
        for (int[] note : notes) {
            changes.put(note[1], Boolean.TRUE);
            changes.put(note[2], Boolean.TRUE);
        }
        Integer from = null;
        for (Integer to : changes.keySet()) {
            if (from != null) {
                TreeMap<Integer, Boolean> sounding = new TreeMap<Integer, Boolean>(); // pitch -> held beyond
                for (int[] note : notes) {
                    if (note[1] <= from && note[2] > from) {
                        boolean held = note[2] > to;
                        Boolean before = sounding.get(note[0]);
                        sounding.put(note[0], held || Boolean.TRUE.equals(before));
                    }
                }
                int[] pitches = new int[sounding.size()];
                boolean[] held = new boolean[sounding.size()];
                int place = 0;
                for (Map.Entry<Integer, Boolean> pitch : sounding.entrySet()) {
                    pitches[place] = pitch.getKey();
                    held[place++] = pitch.getValue();
                }
                fill(written, from, to, pitches, held);
            }
            from = to;
        }
        return written;
    }

    /**
     * Writes one sound (or silence, for no pitches) from a sixteenth to another as notes of written
     * lengths, tied to one another; the last of them is tied on for the notes that are held beyond.
     */
    private static void fill(List<List<Event>> written, int from, int to, int[] pitches, boolean[] held) {
        boolean rest = pitches.length == 0;
        boolean[] all = new boolean[pitches.length];
        java.util.Arrays.fill(all, true);
        int at = from;
        while (at < to) {
            int bar = at / BAR;
            int inBar = at % BAR;
            int left = Math.min(to, (bar + 1) * BAR) - at;
            int value = 1;
            for (int candidate : rest ? REST_VALUES : NOTE_VALUES) {
                if (candidate <= left && fits(candidate, inBar, rest)) {
                    value = candidate;
                    break;
                }
            }
            written.get(bar).add(new Event(inBar, value, pitches, at + value < to ? all : held));
            at += value;
        }
    }

    /** Whether a written length may begin at this place of the bar, so that the beats stay readable. */
    private static boolean fits(int value, int inBar, boolean rest) {
        switch (value) {
            case 16: return inBar == 0;
            case 12: return inBar == 0;
            case 8: return rest ? inBar % 8 == 0 : inBar % 4 == 0;
            case 6: return inBar % 4 == 0;
            case 4: return rest ? inBar % 4 == 0 : inBar % 2 == 0;
            case 3: return inBar % 4 == 0;
            case 2: return inBar % 2 == 0;
            default: return true;
        }
    }
}
