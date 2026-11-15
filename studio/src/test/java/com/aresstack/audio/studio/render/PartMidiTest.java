package com.aresstack.audio.studio.render;

import static org.junit.Assert.assertEquals;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.studio.golden.Golden;
import com.aresstack.audio.studio.project.MidiExport;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class PartMidiTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * The MIDI of a part from the model holds what was cut out of the whole piece before: the
     * same events at the same ticks; only the channel number may differ (0, or 9 for drums).
     */
    @Test
    public void holdsWhatTheOldIsolatedTrackHeld() throws Exception {
        for (String fixture : Golden.FIXTURES) {
            Path home = folder.getRoot().toPath().resolve(fixture);
            Composition composition = RenderFixtures.open(fixture, home).composition();
            Path whole = home.resolve("whole.mid");
            MidiExport.write(composition, whole);
            for (Part part : composition.parts()) {
                Path isolated = home.resolve(part.id() + "-isolated.mid");
                new MidiStemExporter().isolate(whole, part.id(), -1, isolated);
                assertEquals(fixture + "/" + part.id(), events(Files.readAllBytes(isolated)),
                        events(PartMidi.bytes(composition, part, PartMidi.OWN_PROGRAM)));
            }
        }
    }

    @Test
    public void writesTheProgramItIsGiven() throws Exception {
        Composition composition = RenderFixtures.open("district-dawn", folder.getRoot().toPath().resolve("p"))
                .composition();
        Part part = RenderFixtures.melodic(composition);
        Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(PartMidi.bytes(composition, part, 0)));
        ShortMessage program = (ShortMessage) sequence.getTracks()[1].get(0).getMessage();
        assertEquals(ShortMessage.PROGRAM_CHANGE, program.getCommand());
        assertEquals(0, program.getData1());
        assertEquals(0, program.getChannel());
    }

    /** Every event as text, the channel left out. */
    private static List<String> events(byte[] midi) throws Exception {
        Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(midi));
        List<String> events = new ArrayList<>();
        events.add("resolution " + sequence.getResolution());
        for (Track track : sequence.getTracks()) {
            events.add("track");
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage s) {
                    events.add(event.getTick() + " " + s.getCommand() + " " + s.getData1() + " " + s.getData2()
                            + (s.getChannel() == 9 ? " drums" : ""));
                } else if (message instanceof MetaMessage m) {
                    events.add(event.getTick() + " meta " + m.getType() + " " + Arrays.toString(m.getData()));
                }
            }
        }
        return events;
    }
}
