package com.aresstack.audio.studio.template;

import com.aresstack.audio.composition.Key;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Reads a Standard MIDI File as a template. Every channel of every track becomes a part, so a
 * file of type 0 (all channels in one track) is split as well as one of type 1. Names come from
 * the track name or instrument name, programs from the first program change of the channel;
 * tempo, meter and key from the first such events. Ticks are brought to 480 per quarter note.
 */
public final class MidiTemplate {
    private static final int TEMPO = 0x51;
    private static final int METER = 0x58;
    private static final int KEY = 0x59;
    private static final int TRACK_NAME = 0x03;
    private static final int INSTRUMENT_NAME = 0x04;
    private static final int DRUM_CHANNEL = 9;
    private static final double MICROSECONDS_PER_MINUTE = 60_000_000.0;

    private MidiTemplate() {
    }

    /** Whether these bytes begin as a Standard MIDI File does. */
    public static boolean looksLikeMidi(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'M' && bytes[1] == 'T' && bytes[2] == 'h' && bytes[3] == 'd';
    }

    public static TemplateScore read(byte[] bytes, String title) throws IOException {
        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(new ByteArrayInputStream(bytes));
        } catch (InvalidMidiDataException broken) {
            throw new IOException("Die MIDI-Datei ist beschädigt: " + broken.getMessage(), broken);
        }
        List<String> remarks = new ArrayList<String>();
        double scale;
        if (sequence.getDivisionType() == Sequence.PPQ) {
            scale = TemplateScore.PPQ / (double) sequence.getResolution();
        } else {
            // Frames per second instead of quarters: read as if a quarter lasted half a second (120 BPM).
            double ticksPerSecond = sequence.getDivisionType() * sequence.getResolution();
            scale = TemplateScore.PPQ / (ticksPerSecond / 2);
            remarks.add("Die MIDI-Datei zählt in SMPTE-Zeit; sie wird mit 120 BPM gelesen.");
        }
        int bpm = 0;
        int numerator = 0;
        int denominator = 0;
        Key key = null;
        int tempi = 0;
        int meters = 0;
        int[] programs = new int[16];
        java.util.Arrays.fill(programs, -1);
        Map<String, Builder> parts = new LinkedHashMap<String, Builder>();
        Track[] tracks = sequence.getTracks();
        for (int t = 0; t < tracks.length; t++) {
            Track track = tracks[t];
            String trackName = "";
            List<MidiEvent> events = new ArrayList<MidiEvent>();
            for (int i = 0; i < track.size(); i++) {
                events.add(track.get(i));
            }
            events.sort(Comparator.comparingLong(MidiEvent::getTick));
            Map<Integer, Deque<long[]>> sounding = new HashMap<Integer, Deque<long[]>>();
            for (MidiEvent event : events) {
                long tick = Math.round(event.getTick() * scale);
                MidiMessage message = event.getMessage();
                if (message instanceof MetaMessage meta) {
                    byte[] data = meta.getData();
                    switch (meta.getType()) {
                        case TEMPO -> {
                            if (data.length >= 3) {
                                int mpq = ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                                if (mpq > 0 && bpm == 0) {
                                    bpm = (int) Math.round(MICROSECONDS_PER_MINUTE / mpq);
                                }
                                tempi++;
                            }
                        }
                        case METER -> {
                            if (data.length >= 2 && numerator == 0) {
                                numerator = data[0] & 0xff;
                                denominator = 1 << Math.min(6, data[1] & 0xff);
                            }
                            meters++;
                        }
                        case KEY -> {
                            if (data.length >= 2 && key == null) {
                                key = TemplateKeys.ofFifths(data[0], data[1] != 0);
                            }
                        }
                        case TRACK_NAME, INSTRUMENT_NAME -> {
                            if (trackName.isEmpty()) {
                                trackName = new String(data, StandardCharsets.ISO_8859_1).trim();
                            }
                        }
                        default -> {
                        }
                    }
                    continue;
                }
                if (!(message instanceof ShortMessage shortMessage)) {
                    continue;
                }
                int channel = shortMessage.getChannel();
                int command = shortMessage.getCommand();
                if (command == ShortMessage.PROGRAM_CHANGE) {
                    if (programs[channel] < 0) {
                        programs[channel] = shortMessage.getData1();
                    }
                    continue;
                }
                boolean on = command == ShortMessage.NOTE_ON && shortMessage.getData2() > 0;
                boolean off = command == ShortMessage.NOTE_OFF
                        || (command == ShortMessage.NOTE_ON && shortMessage.getData2() == 0);
                if (!on && !off) {
                    continue;
                }
                int pitch = shortMessage.getData1();
                String id = t + ":" + channel;
                Builder part = parts.computeIfAbsent(id, k -> new Builder(channel));
                if (part.name.isEmpty()) {
                    part.name = trackName;
                }
                Deque<long[]> open = sounding.computeIfAbsent(channel * 128 + pitch, k -> new ArrayDeque<long[]>());
                if (on) {
                    open.addLast(new long[]{tick, shortMessage.getData2()});
                } else if (!open.isEmpty()) {
                    long[] started = open.removeFirst();
                    part.notes.add(new ScoreNote(started[0], tick - started[0], pitch, (int) started[1]));
                }
            }
            // A note that is never ended sounds to the end of its track.
            long last = events.isEmpty() ? 0 : Math.round(events.get(events.size() - 1).getTick() * scale);
            for (Map.Entry<Integer, Deque<long[]>> open : sounding.entrySet()) {
                int channel = open.getKey() / 128;
                for (long[] started : open.getValue()) {
                    parts.get(t + ":" + channel).notes.add(new ScoreNote(started[0], Math.max(0, last - started[0]),
                            open.getKey() % 128, (int) started[1]));
                    parts.get(t + ":" + channel).unended++;
                }
            }
        }
        if (tempi > 1) {
            remarks.add("Die MIDI-Datei wechselt das Tempo " + (tempi - 1) + "-mal; übernommen ist das erste ("
                    + bpm + " BPM).");
        }
        if (meters > 1) {
            remarks.add("Die MIDI-Datei nennt " + meters + " Taktarten; übernommen ist die erste.");
        }
        List<ScorePart> read = new ArrayList<ScorePart>();
        for (Builder part : parts.values()) {
            if (part.notes.isEmpty()) {
                continue;
            }
            if (part.unended > 0) {
                remarks.add(part.unended + " Noten ohne Ende in „" + part.label() + "“ klingen bis zum Ende der Spur.");
            }
            part.notes.sort(Comparator.comparingLong(ScoreNote::tick).thenComparingInt(ScoreNote::pitch));
            boolean drums = part.channel == DRUM_CHANNEL;
            read.add(new ScorePart(part.label(), drums ? 0 : programs[part.channel], drums, 1, part.notes));
        }
        return new TemplateScore(title, bpm, numerator, denominator, key, read, remarks, "MIDI");
    }

    private static final class Builder {
        final int channel;
        String name = "";
        int unended;
        final List<ScoreNote> notes = new ArrayList<ScoreNote>();

        Builder(int channel) {
            this.channel = channel;
        }

        String label() {
            return name.isEmpty() ? "Kanal " + (channel + 1) : name;
        }
    }
}
