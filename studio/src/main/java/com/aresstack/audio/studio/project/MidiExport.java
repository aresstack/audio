package com.aresstack.audio.studio.project;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Writes a composition as a Standard MIDI File of type 1, the format to exchange a piece in:
 * a first track with the tempo, meter and key events, then one track per part, named by the
 * part's id. For a piece of the first stage the bytes are exactly those the reference renderer
 * of the music module writes for the same piece as {@code arrangement.tsv}; tracks that share a
 * channel get a port of their own, as there.
 */
public final class MidiExport {
    /** The MIDI controller of expression, which the hairpins drive while notes are held. */
    public static final int EXPRESSION = 11;
    private MidiExport() {
    }

    public static void write(Composition composition, Path target) throws IOException {
        Files.write(target, bytes(composition));
    }

    public static byte[] bytes(Composition composition) throws IOException {
        try {
            TimeMap time = composition.timeMap();
            Sequence sequence = new Sequence(Sequence.PPQ, TimeMap.PPQ);
            Track first = sequence.createTrack();
            for (TimeMap.Tempo tempo : time.playedTempos()) {
                int microseconds = (int) (60000000 / tempo.bpm());
                meta(first, 0x51, new byte[]{(byte) (microseconds >>> 16), (byte) (microseconds >>> 8),
                        (byte) microseconds}, tempo.tick());
            }
            for (TimeMap.Meter meter : time.meters()) {
                meta(first, 0x58, new byte[]{(byte) meter.numerator(),
                        (byte) Integer.numberOfTrailingZeros(meter.denominator()), 24, 8}, meter.tick());
            }
            if (!time.keys().equals(TimeMap.of(120).keys())) {
                for (TimeMap.KeyChange change : time.keys()) {
                    meta(first, 0x59, keySignature(change), change.tick());
                }
            }
            Map<String, Integer> channels = TsvExport.channels(composition);
            Map<Integer, Integer> used = new HashMap<>();
            for (Part part : composition.parts()) {
                int channel = channels.get(part.id());
                Integer before = used.get(channel);
                int port = before == null ? 0 : before;
                used.put(channel, port + 1);
                Track track = sequence.createTrack();
                meta(track, 0x03, part.id().getBytes(StandardCharsets.UTF_8), 0);
                if (port > 0) {
                    meta(track, 0x21, new byte[]{(byte) port}, 0);
                }
                if (channel != 9) {
                    midi(track, ShortMessage.PROGRAM_CHANGE, channel, part.program(), 0, 0);
                }
                for (Dynamics.ExpressionPoint point : composition.expression(part.id())) {
                    // Held notes swell and fade with the hairpins of the part (controller 11, expression).
                    midi(track, ShortMessage.CONTROL_CHANGE, channel, EXPRESSION, point.value(), point.tick());
                }
                for (Composition.PlacedNote placed : composition.notesOf(part.id())) {
                    midi(track, ShortMessage.NOTE_ON, channel, placed.note().pitch(), composition.playedVelocity(placed),
                            placed.tick());
                    midi(track, ShortMessage.NOTE_OFF, channel, placed.note().pitch(), 0,
                        composition.playedEnd(placed));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (MidiSystem.write(sequence, 1, out) <= 0) {
                throw new IOException("Unable to write a MIDI file of type 1.");
            }
            return out.toByteArray();
        } catch (InvalidMidiDataException invalid) {
            throw new IOException("The piece cannot be written as MIDI: " + invalid.getMessage(), invalid);
        }
    }

    /** The key signature event: sharps (positive) or flats (negative) as the key writes them, and the mode. */
    private static byte[] keySignature(TimeMap.KeyChange change) {
        return new byte[]{(byte) change.key().fifths(), (byte) (change.key().mode() == com.aresstack.audio.composition.Key.Mode.MINOR ? 1 : 0)};
    }

    private static void midi(Track track, int command, int channel, int data1, int data2, long tick)
            throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(command, channel, data1, data2);
        track.add(new MidiEvent(message, tick));
    }

    private static void meta(Track track, int type, byte[] data, long tick) throws InvalidMidiDataException {
        MetaMessage message = new MetaMessage();
        message.setMessage(type, data, data.length);
        track.add(new MidiEvent(message, tick));
    }
}
