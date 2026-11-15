package com.aresstack.audio.studio.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.Test;

/** A MIDI file given as notes: every channel a part, its notes at 480 ticks per quarter, tempo, meter and key read. */
public final class MidiTemplateTest {
    private static MidiEvent meta(int type, byte[] data, long tick) throws Exception {
        return new MidiEvent(new MetaMessage(type, data, data.length), tick);
    }

    private static MidiEvent shortMessage(int command, int channel, int data1, int data2, long tick) throws Exception {
        return new MidiEvent(new ShortMessage(command, channel, data1, data2), tick);
    }

    static byte[] vivaldiLike() throws Exception {
        Sequence sequence = new Sequence(Sequence.PPQ, 96);
        Track conductor = sequence.createTrack();
        int mpq = 60_000_000 / 90;
        conductor.add(meta(0x51, new byte[]{(byte) (mpq >> 16), (byte) (mpq >> 8), (byte) mpq}, 0));
        conductor.add(meta(0x58, new byte[]{3, 2, 24, 8}, 0)); // 3/4
        conductor.add(meta(0x59, new byte[]{4, 0}, 0)); // four sharps: E major
        Track violin = sequence.createTrack();
        violin.add(meta(0x03, "Violino principale".getBytes(StandardCharsets.ISO_8859_1), 0));
        violin.add(shortMessage(ShortMessage.PROGRAM_CHANGE, 0, 40, 0, 0));
        violin.add(shortMessage(ShortMessage.NOTE_ON, 0, 76, 90, 0));
        violin.add(shortMessage(ShortMessage.NOTE_OFF, 0, 76, 0, 96));
        violin.add(shortMessage(ShortMessage.NOTE_ON, 0, 80, 90, 96));
        violin.add(shortMessage(ShortMessage.NOTE_ON, 0, 80, 0, 144)); // note on with velocity 0 ends it
        // A second channel in the same track becomes a part of its own.
        violin.add(shortMessage(ShortMessage.NOTE_ON, 1, 52, 70, 0));
        violin.add(shortMessage(ShortMessage.NOTE_OFF, 1, 52, 0, 288));
        Track drums = sequence.createTrack();
        drums.add(shortMessage(ShortMessage.NOTE_ON, 9, 36, 100, 0));
        drums.add(shortMessage(ShortMessage.NOTE_OFF, 9, 36, 0, 48));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MidiSystem.write(sequence, 1, out);
        return out.toByteArray();
    }

    @Test
    public void everyChannelBecomesAPartAtTheStudiosTicks() throws Exception {
        byte[] bytes = vivaldiLike();
        assertTrue(MidiTemplate.looksLikeMidi(bytes));
        TemplateScore score = MidiTemplate.read(bytes, "La primavera");

        assertEquals("MIDI", score.format());
        assertEquals(90, score.bpm());
        assertEquals(3, score.numerator());
        assertEquals(4, score.denominator());
        assertEquals(new Key(4, Key.Mode.MAJOR), score.key());
        assertEquals(3, score.parts().size());

        ScorePart violin = score.parts().get(0);
        assertEquals("Violino principale", violin.name());
        assertEquals(40, violin.program());
        assertEquals(2, violin.notes().size());
        assertEquals("96 ticks of the file are a quarter: 480 in the studio",
                new ScoreNote(0, 480, 76, 90), violin.notes().get(0));
        assertEquals(new ScoreNote(480, 240, 80, 90), violin.notes().get(1));

        ScorePart second = score.parts().get(1);
        assertEquals(-1, second.program());
        assertEquals(new ScoreNote(0, 1440, 52, 70), second.notes().get(0));

        ScorePart drums = score.parts().get(2);
        assertTrue(drums.drums());
        assertEquals("Kanal 10", drums.name());
    }
}
