package com.aresstack.audio.studio.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import com.aresstack.audio.music.synth.BuiltinSynthesis;
import com.aresstack.audio.studio.project.MidiExport;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import org.junit.Test;

/**
 * A note held through a hairpin swells or fades while it sounds, as a player does it: the MIDI
 * files every sound bank renders from carry the course as expression (controller 11), and the
 * built-in sound follows the same curve.
 */
public final class HeldNoteSwellTest {
    private static final long BAR = 1920;
    private static final Part STRINGS = new Part("strings", null, Role.HARMONY, "Strings", 48, null,
            Part.StaffLayout.SINGLE, List.of(new Voice("v1", null, null, Voice.Stem.AUTO)), null);

    /** One note held over four bars at 120 BPM (eight seconds), then nothing. */
    private static Composition held(Dynamics dynamics) {
        Track track = new Track("strings", null, Track.Kind.MIDI, "strings",
                List.of(new MidiClip("c1", 0, 4 * BAR, null, List.of(new Note("n1", 0, 4 * BAR, 60, 80, "v1")))));
        return new Composition(Meta.EMPTY, TimeMap.of(120), List.of(), List.of(STRINGS), List.of(track), List.of(),
                dynamics);
    }

    private static Dynamics hairpin(Dynamics.Level from, Dynamics.Level to) {
        return new Dynamics(List.of(new Dynamics.Mark(0, from, null)), List.of(new Dynamics.Hairpin(0, 4 * BAR, to, null)),
                Dynamics.Scale.STANDARD);
    }

    private static double[] samples(Composition piece) {
        return BuiltinStemRenderer.samples(piece, STRINGS, BuiltinSynthesis.frames(StemJob.pieceSeconds(piece)));
    }

    private static double rms(double[] samples, double from, double to) {
        int a = (int) (from * BuiltinSynthesis.SAMPLE_RATE);
        int b = Math.min(samples.length, (int) (to * BuiltinSynthesis.SAMPLE_RATE));
        double sum = 0;
        for (int i = a; i < b; i++) {
            sum += samples[i] * samples[i];
        }
        return Math.sqrt(sum / Math.max(1, b - a));
    }

    @Test
    public void aHeldNoteSwellsInACrescendoAndFadesInADecrescendo() {
        double[] plain = samples(held(Dynamics.NONE));
        double[] crescendo = samples(held(hairpin(Dynamics.Level.PP, Dynamics.Level.FF)));
        double[] decrescendo = samples(held(hairpin(Dynamics.Level.FF, Dynamics.Level.PP)));
        double steady = rms(plain, 7, 8) / rms(plain, 0.3, 1);
        assertEquals("without a hairpin the held note stays as loud", 1.0, steady, 0.25);
        double swell = rms(crescendo, 7, 8) / rms(crescendo, 0.3, 1);
        assertTrue("the held note grows within the crescendo: " + swell, swell > 2.0);
        double fade = rms(decrescendo, 7, 8) / rms(decrescendo, 0.3, 1);
        assertTrue("the held note fades within the decrescendo: " + fade, fade < 0.5);
        // the start of the crescendo is as soft as pianissimo would be without it
        double[] pianissimo = samples(held(new Dynamics(List.of(new Dynamics.Mark(0, Dynamics.Level.PP, null)), List.of(),
                Dynamics.Scale.STANDARD)));
        assertEquals(rms(pianissimo, 0.2, 0.3), rms(crescendo, 0.2, 0.3), 0.25 * rms(pianissimo, 0.2, 0.3));
    }

    @Test
    public void theMidiFilesCarryTheSwellAsExpression() throws Exception {
        Composition piece = held(hairpin(Dynamics.Level.PP, Dynamics.Level.FF));
        for (byte[] bytes : List.of(MidiExport.bytes(piece), PartMidi.bytes(piece, STRINGS, PartMidi.OWN_PROGRAM))) {
            Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(bytes));
            List<long[]> expression = new ArrayList<>();
            for (javax.sound.midi.Track track : sequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    if (event.getMessage() instanceof ShortMessage message
                            && message.getCommand() == ShortMessage.CONTROL_CHANGE
                            && message.getData1() == PartMidi.EXPRESSION) {
                        expression.add(new long[]{event.getTick(), message.getData2()});
                    }
                }
            }
            assertTrue("a step at least every quarter", expression.size() > 16);
            assertEquals(0, expression.get(0)[0]);
            assertTrue("it starts soft", expression.get(0)[1] < 50);
            for (int i = 1; i < expression.size(); i++) {
                assertTrue("it only grows", expression.get(i)[1] >= expression.get(i - 1)[1]);
            }
            assertEquals(127, expression.get(expression.size() - 1)[1]);
        }
        assertTrue(held(Dynamics.NONE).expression("strings").isEmpty());
    }
}
