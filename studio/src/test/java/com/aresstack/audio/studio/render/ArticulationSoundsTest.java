package com.aresstack.audio.studio.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Articulation;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.Meta;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Where a sound bank has samples of its own for an articulation, the notes play them: the stem
 * MIDI selects them before the note, by keyswitch or by program, as the file beside the bank
 * says; a bank without such a file gets the notes as before.
 */
public final class ArticulationSoundsTest {
    private static final Part VIOLIN = new Part("violin", null, Role.MELODY, "Violin", 40, null,
            Part.StaffLayout.SINGLE, List.of(new Voice("v1", null, null, Voice.Stem.AUTO)), null);

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** Four quarters: plain, staccato, staccato, plain. */
    private static Composition piece() {
        List<Note> notes = List.of(new Note("n1", 0, 480, 67, 80, "v1"),
                new Note("n2", 480, 480, 69, 80, "v1", Articulation.STACCATO),
                new Note("n3", 960, 480, 71, 80, "v1", Articulation.STACCATO),
                new Note("n4", 1440, 480, 72, 80, "v1"));
        Track track = new Track("violin", null, Track.Kind.MIDI, "violin",
                List.of(new MidiClip("c1", 0, 1920, null, notes)));
        return new Composition(Meta.EMPTY, TimeMap.of(120), List.of(), List.of(VIOLIN), List.of(track), List.of(),
                Dynamics.NONE);
    }

    /** The note-ons and program changes of a MIDI file in order, as "on <key>" and "program <number>". */
    private static List<String> events(byte[] bytes) throws Exception {
        Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(bytes));
        List<String> events = new ArrayList<>();
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message) {
                    if (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0) {
                        events.add("on " + message.getData1());
                    } else if (message.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                        events.add("program " + message.getData1());
                    }
                }
            }
        }
        return events;
    }

    private static ArticulationSounds sounds(String text) throws Exception {
        Properties lines = new Properties();
        lines.load(new java.io.StringReader(text));
        return ArticulationSounds.of(lines);
    }

    @Test
    public void keyswitchesSelectTheStaccatoSamplesAndBackToNormal() throws Exception {
        ArticulationSounds sounds = sounds("normal = keyswitch 24\nstaccato = keyswitch 26\n");
        assertEquals(Arrays.asList("program 40", "on 24", "on 67", "on 26", "on 69", "on 71", "on 24", "on 72"),
                events(PartMidi.bytes(piece(), VIOLIN, PartMidi.OWN_PROGRAM, sounds)));
    }

    @Test
    public void anotherProgramOfTheBankPlaysTheStaccatoNotes() throws Exception {
        ArticulationSounds sounds = sounds("staccato = program 45\n");
        assertEquals(Arrays.asList("program 40", "on 67", "program 45", "on 69", "on 71", "program 40", "on 72"),
                events(PartMidi.bytes(piece(), VIOLIN, PartMidi.OWN_PROGRAM, sounds)));
    }

    @Test
    public void withoutAFileBesideTheBankNothingChanges() throws Exception {
        Path bank = temporary.newFile("General.sf2").toPath();
        assertSame(ArticulationSounds.NONE, ArticulationSounds.beside(bank));
        assertTrue(Arrays.equals(PartMidi.bytes(piece(), VIOLIN, PartMidi.OWN_PROGRAM),
                PartMidi.bytes(piece(), VIOLIN, PartMidi.OWN_PROGRAM, ArticulationSounds.beside(bank))));
    }

    @Test
    public void theFileBesideTheBankIsRead() throws Exception {
        Path bank = temporary.newFile("Strings.sfz").toPath();
        Files.writeString(ArticulationSounds.fileOf(bank), "# VSCO strings\nnormal = keyswitch 24\n"
                + "staccato = keyswitch 26\naccent = program 41\nkeyswitch.velocity = 90\nkeyswitch.lead = 4\n"
                + "nonsense = keyswitch 300\n", StandardCharsets.UTF_8);
        ArticulationSounds sounds = ArticulationSounds.beside(bank);
        assertEquals(new ArticulationSounds.Sound(ArticulationSounds.Kind.KEYSWITCH, 24), sounds.normal());
        assertEquals(new ArticulationSounds.Sound(ArticulationSounds.Kind.KEYSWITCH, 26),
                sounds.of(Articulation.STACCATO));
        assertEquals(new ArticulationSounds.Sound(ArticulationSounds.Kind.PROGRAM, 41), sounds.of(Articulation.ACCENT));
        assertEquals(90, sounds.velocity());
        assertEquals(4, sounds.leadTicks());
        assertEquals(2, sounds.sounds().size());
    }
}
