package com.aresstack.audio.studio.render;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.studio.project.TsvExport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * One part alone as a Standard MIDI File, straight from the model (architecture review E7.1):
 * a first track with the tempo and meter events, then the notes of the part. No arrangement
 * file and no child process are in between.
 *
 * <p>It holds what {@link MidiStemExporter} cut out of the whole piece before, with one
 * difference: the part plays on channel 0, or on channel 9 if it plays drums. The channel a
 * part gets in the whole piece depends on the parts before it; here it would only make every
 * later stem render again when a part is added or removed in the middle, while a sampler
 * playing one part alone sounds the same on any melodic channel.
 */
public final class PartMidi {
    /** The program the part plays, as the model has it. */
    public static final int OWN_PROGRAM = -1;
    /** The MIDI controller of expression, which the hairpins drive while notes are held. */
    public static final int EXPRESSION = 11;

    private PartMidi() {
    }

    /** The channel a part plays on in its stem: 9 for drums, else 0. */
    public static int channel(Composition composition, Part part) {
        Integer channel = TsvExport.channels(composition).get(part.id());
        return channel != null && channel == 9 ? 9 : 0;
    }

    /**
     * The bytes of the MIDI file of one part.
     *
     * @param program the program to write, or {@link #OWN_PROGRAM} for the part's own
     */
    public static byte[] bytes(Composition composition, Part part, int program) throws IOException {
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
            int channel = channel(composition, part);
            Track notes = sequence.createTrack();
            if (channel != 9) {
                midi(notes, ShortMessage.PROGRAM_CHANGE, channel, program == OWN_PROGRAM ? part.program() : program,
                        0, 0);
            }
            for (Dynamics.ExpressionPoint point : composition.expression(part.id())) {
                // Held notes swell and fade with the hairpins of the part (controller 11, expression).
                midi(notes, ShortMessage.CONTROL_CHANGE, channel, EXPRESSION, point.value(), point.tick());
            }
            for (Composition.PlacedNote placed : composition.notesOf(part.id())) {
                midi(notes, ShortMessage.NOTE_ON, channel, placed.note().pitch(), composition.playedVelocity(placed),
                        placed.tick());
                midi(notes, ShortMessage.NOTE_OFF, channel, placed.note().pitch(), 0,
                        composition.playedEnd(placed));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (MidiSystem.write(sequence, 1, out) <= 0) {
                throw new IOException("Unable to write a MIDI file of type 1.");
            }
            return out.toByteArray();
        } catch (InvalidMidiDataException invalid) {
            throw new IOException("The part " + part.id() + " cannot be written as MIDI: " + invalid.getMessage(),
                    invalid);
        }
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
