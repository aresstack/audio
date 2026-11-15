package com.aresstack.audio.studio.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Articulation;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.Key;
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
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import org.junit.Test;

/**
 * Dynamics, articulation and tempo changes are not only written: the stems and the MIDI file
 * sound different. Measured on the samples of the built-in sound and on the events of the MIDI
 * file every sound bank renders from (PO: "sie müssen auch funktionieren").
 */
public final class PerformanceSoundTest {
    private static final long BAR = 1920;
    private static final Part MELODY = new Part("melody", null, Role.MELODY, "Flute", 73, null,
            Part.StaffLayout.SINGLE, List.of(new Voice("v1", null, null, Voice.Stem.AUTO)), null);

    /** Eight bars, a quarter note on every beat, all written alike. */
    private static Composition piece(TimeMap time, Dynamics dynamics, Articulation articulation) {
        List<Note> notes = new ArrayList<>();
        for (int beat = 0; beat < 32; beat++) {
            notes.add(new Note("n" + beat, beat * 480L, 480, 72, 80, "v1", articulation));
        }
        Track track = new Track("melody", null, Track.Kind.MIDI, "melody",
                List.of(new MidiClip("c1", 0, 8 * BAR, null, notes)));
        return new Composition(Meta.EMPTY, time, List.of(), List.of(MELODY), List.of(track), List.of(), dynamics);
    }

    private static double[] samples(Composition piece) {
        return BuiltinStemRenderer.samples(piece, MELODY,
                BuiltinSynthesis.frames(StemJob.pieceSeconds(piece)));
    }

    /** The root mean square of the samples between two times in seconds. */
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
    public void pianoIsSofterThanFortissimo() {
        Dynamics dynamics = new Dynamics(List.of(new Dynamics.Mark(0, Dynamics.Level.PP, null),
                new Dynamics.Mark(4 * BAR, Dynamics.Level.FF, null)), List.of(), Dynamics.Scale.STANDARD);
        Composition piece = piece(TimeMap.of(120), dynamics, null);
        double[] played = samples(piece);
        double[] written = samples(piece(TimeMap.of(120), Dynamics.NONE, null));
        double soft = rms(played, 0, 8);
        double loud = rms(played, 8, 16);
        double plain = rms(written, 0, 8);
        assertTrue("pp " + soft + " is clearly softer than ff " + loud, loud > 2.5 * soft);
        assertTrue("pp is softer than the notes as written", soft < 0.6 * plain);
        assertTrue("ff is louder than the notes as written", rms(played, 8, 16) > 1.3 * rms(written, 8, 16));
    }

    @Test
    public void aCrescendoGrowsBarByBar() {
        Dynamics dynamics = new Dynamics(List.of(new Dynamics.Mark(0, Dynamics.Level.PP, null)),
                List.of(new Dynamics.Hairpin(0, 8 * BAR, Dynamics.Level.FF, null)), Dynamics.Scale.STANDARD);
        double[] played = samples(piece(TimeMap.of(120), dynamics, null));
        double before = 0;
        for (int bar = 0; bar < 8; bar++) {
            double level = rms(played, bar * 2.0, bar * 2.0 + 2.0);
            assertTrue("bar " + (bar + 1) + " is louder than the one before", level > before);
            before = level;
        }
    }

    @Test
    public void staccatoNotesStopEarlierAndAnAccentIsLouder() {
        double[] plain = samples(piece(TimeMap.of(120), Dynamics.NONE, null));
        double[] staccato = samples(piece(TimeMap.of(120), Dynamics.NONE, Articulation.STACCATO));
        double[] accent = samples(piece(TimeMap.of(120), Dynamics.NONE, Articulation.ACCENT));
        // A quarter at 120 BPM lasts half a second; staccato sounds half of it. In the second
        // half of each beat the staccato line has (nearly) only the release left.
        double plainTail = 0;
        double staccatoTail = 0;
        for (int beat = 4; beat < 12; beat++) {
            plainTail += rms(plain, beat * 0.5 + 0.3, beat * 0.5 + 0.5);
            staccatoTail += rms(staccato, beat * 0.5 + 0.3, beat * 0.5 + 0.5);
        }
        assertTrue("staccato " + staccatoTail + " against " + plainTail, staccatoTail < 0.7 * plainTail);
        assertTrue(rms(accent, 0, 16) > 1.15 * rms(plain, 0, 16));
    }

    @Test
    public void aRitardandoMakesThePieceLongerAndMovesItsNotes() {
        TimeMap steady = TimeMap.of(120);
        TimeMap slowing = new TimeMap(List.of(new TimeMap.Tempo(0, 120), new TimeMap.Tempo(4 * BAR, 120, true),
                new TimeMap.Tempo(8 * BAR, 60)), List.of(new TimeMap.Meter(0, 4, 4)),
                List.of(new TimeMap.KeyChange(0, Key.C_MAJOR)));
        Composition plain = piece(steady, Dynamics.NONE, null);
        Composition ritardando = piece(slowing, Dynamics.NONE, null);
        assertEquals(16.0, StemJob.pieceSeconds(plain) - 0, 1.0);
        assertTrue(StemJob.pieceSeconds(ritardando) > StemJob.pieceSeconds(plain) + 2.5);
        double[] a = samples(plain);
        double[] b = samples(ritardando);
        assertTrue("the stem of the ritardando is longer", b.length > a.length);
        // Before the ritardando both sound alike, sample for sample.
        int same = (int) (7.9 * BuiltinSynthesis.SAMPLE_RATE);
        for (int i = 0; i < same; i++) {
            assertEquals(a[i], b[i], 1e-12);
        }
    }

    @Test
    public void theMidiFileCarriesDynamicsArticulationAndTheGlide() throws Exception {
        TimeMap slowing = new TimeMap(List.of(new TimeMap.Tempo(0, 120, true), new TimeMap.Tempo(2 * BAR, 60)),
                List.of(new TimeMap.Meter(0, 4, 4)), List.of(new TimeMap.KeyChange(0, Key.C_MAJOR)));
        Dynamics dynamics = new Dynamics(List.of(new Dynamics.Mark(0, Dynamics.Level.P, null),
                new Dynamics.Mark(4 * BAR, Dynamics.Level.F, null)), List.of(), Dynamics.Scale.STANDARD);
        Composition piece = piece(slowing, dynamics, Articulation.STACCATO);
        for (byte[] bytes : List.of(MidiExport.bytes(piece), PartMidi.bytes(piece, MELODY, PartMidi.OWN_PROGRAM))) {
            Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(bytes));
            int tempos = 0;
            List<int[]> ons = new ArrayList<>();
            List<Long> offs = new ArrayList<>();
            List<Long> onTicks = new ArrayList<>();
            for (javax.sound.midi.Track track : sequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                        tempos++;
                    } else if (event.getMessage() instanceof ShortMessage note) {
                        if (note.getCommand() == ShortMessage.NOTE_ON && note.getData2() > 0) {
                            ons.add(new int[]{note.getData2()});
                            onTicks.add(event.getTick());
                        } else if (note.getCommand() == ShortMessage.NOTE_OFF) {
                            offs.add(event.getTick());
                        }
                    }
                }
            }
            assertEquals("a tempo step every " + TimeMap.GLIDE_STEP_TICKS + " ticks of the glide, then the last",
                    2 * BAR / TimeMap.GLIDE_STEP_TICKS + 1, tempos);
            int p = dynamics.velocity("melody", 0, 80);
            int f = dynamics.velocity("melody", 4 * BAR, 80);
            assertEquals(p, ons.get(0)[0]);
            assertEquals(f, ons.get(ons.size() - 1)[0]);
            assertTrue(f > p);
            assertEquals("staccato: the note ends after half its value", onTicks.get(0) + 240, (long) offs.get(0));
        }
    }

    @Test
    public void theStemCacheKnowsADynamicChange() throws Exception {
        Composition plain = piece(TimeMap.of(120), Dynamics.NONE, null);
        Composition marked = plain.withDynamics(new Dynamics(List.of(new Dynamics.Mark(0, Dynamics.Level.FF, null)),
                List.of(), Dynamics.Scale.STANDARD));
        byte[] a = PartMidi.bytes(plain, MELODY, PartMidi.OWN_PROGRAM);
        byte[] b = PartMidi.bytes(marked, MELODY, PartMidi.OWN_PROGRAM);
        assertTrue(!StemCache.key("e", "i", a).equals(StemCache.key("e", "i", b)));
        assertTrue(!com.aresstack.audio.studio.project.StemManifest.digest(plain, "melody")
                .equals(com.aresstack.audio.studio.project.StemManifest.digest(marked, "melody")));
    }
}
