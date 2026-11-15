package com.aresstack.audio.studio.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.score.Score;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Tempo glides, dynamics and articulations in the files of a piece, in the plan of the AI and in the score. */
public final class PerformanceFilesTest {
    private static final long BAR = 1920;
    private static final Part MELODY = new Part("melody", null, Role.MELODY, "Flute", 73, null,
            Part.StaffLayout.SINGLE, List.of(new Voice("v1", null, null, Voice.Stem.AUTO)), null);

    private static Composition piece(TimeMap time, Dynamics dynamics, Articulation articulation) {
        List<Note> notes = new ArrayList<>();
        for (int beat = 0; beat < 16; beat++) {
            notes.add(new Note("n" + beat, beat * 480L, 480, 72, 80, "v1", beat % 2 == 0 ? articulation : null));
        }
        Track track = new Track("melody", null, Track.Kind.MIDI, "melody",
                List.of(new MidiClip("c1", 0, 4 * BAR, null, notes)));
        return new Composition(Meta.EMPTY, time, List.of(), List.of(MELODY), List.of(track), List.of(), dynamics);
    }

    private static TimeMap ritardando() {
        return new TimeMap(List.of(new TimeMap.Tempo(0, 120), new TimeMap.Tempo(2 * BAR, 120, true),
                new TimeMap.Tempo(3 * BAR, 80)), List.of(new TimeMap.Meter(0, 4, 4)),
                List.of(new TimeMap.KeyChange(0, Key.C_MAJOR)));
    }

    private static Dynamics marks() {
        return new Dynamics(List.of(new Dynamics.Mark(0, Dynamics.Level.P, null),
                new Dynamics.Mark(2 * BAR, Dynamics.Level.FF, "melody")),
                List.of(new Dynamics.Hairpin(BAR, 2 * BAR, Dynamics.Level.F, null)),
                Dynamics.Scale.STANDARD.with(Dynamics.Level.FFF, 120),
                Dynamics.Articulations.STANDARD.withLength(Articulation.STACCATO, 0.4));
    }

    @Test
    public void theModelFileKeepsEverythingAndSaysItsVersion() throws Exception {
        Composition rich = piece(ritardando(), marks(), Articulation.STACCATO);
        JsonObject written = CompositionJson.write(rich);
        assertEquals(CompositionJson.VERSION, written.get("version").getAsInt());
        Composition read = CompositionJson.read(JsonParser.parseString(written.toString()).getAsJsonObject());
        assertEquals(rich, read);
        assertEquals(Articulation.STACCATO, read.notesOf("melody").get(0).note().articulation());
        assertTrue(read.timeMap().tempos().get(1).glide());

        Composition plain = piece(TimeMap.of(120), Dynamics.NONE, null);
        JsonObject first = CompositionJson.write(plain);
        assertEquals("a piece without them is still written for older studios", CompositionJson.FIRST_VERSION,
                first.get("version").getAsInt());
        assertTrue(!first.has("dynamics"));
    }

    @Test
    public void theOldFileWritesArticulationsButNotDynamics() {
        Composition articulated = piece(TimeMap.of(120), Dynamics.NONE, Articulation.ACCENT);
        List<String> lines = TsvExport.lines(articulated);
        ArrangementFile file = ArrangementFile.parse(lines);
        assertEquals(List.of(), file.problems());
        assertEquals("accent", file.notes().get(0).articulation());
        assertNull(file.notes().get(1).articulation());
        assertNull("a blank voice column before it", file.notes().get(0).voice());
        try {
            TsvExport.lines(piece(TimeMap.of(120), marks(), null));
            throw new AssertionError("dynamics are beyond arrangement.tsv");
        } catch (TsvExport.NotExpressible expected) {
            // arrangement.mid is written instead
        }
    }

    @Test
    public void thePlanOfTheAiBecomesTicks() {
        JsonObject plan = JsonParser.parseString("{\"tempoBpm\":120,"
                + "\"tempoChanges\":[{\"bar\":3,\"bpm\":120,\"glide\":true},{\"bar\":4,\"bpm\":80},{\"bar\":9,\"bpm\":999}],"
                + "\"dynamics\":[{\"bar\":1,\"level\":\"p\"},{\"bar\":3,\"beat\":3,\"level\":\"ff\",\"part\":\"melody\"},"
                + "{\"bar\":2,\"level\":\"loud\"},{\"bar\":2,\"level\":\"f\",\"part\":\"tuba\"}],"
                + "\"hairpins\":[{\"fromBar\":2,\"toBar\":3,\"to\":\"f\"}]}").getAsJsonObject();
        List<String> warnings = new ArrayList<>();
        PlanShape.Shape shape = PlanShape.read(plan, TimeMap.of(120), Set.of("melody"), PlanShape.MIN_BPM,
                PlanShape.MAX_BPM, 8 * BAR, warnings::add);
        assertEquals(ritardando().tempos(), shape.timeMap().tempos());
        assertEquals(List.of(new Dynamics.Mark(0, Dynamics.Level.P, null),
                new Dynamics.Mark(2 * BAR + 960, Dynamics.Level.FF, "melody")), shape.dynamics().marks());
        assertEquals(List.of(new Dynamics.Hairpin(BAR, 2 * BAR, Dynamics.Level.F, null)), shape.dynamics().hairpins());
        assertEquals("the tempo out of range, the unknown level and the unknown part are said: " + warnings,
                3, warnings.size());
        PlanShape.Shape none = PlanShape.read(JsonParser.parseString("{}").getAsJsonObject(), TimeMap.of(120),
                Set.of(), PlanShape.MIN_BPM, PlanShape.MAX_BPM, 8 * BAR, warnings::add);
        assertEquals(Dynamics.NONE, none.dynamics());
        assertEquals(TimeMap.of(120), none.timeMap());
    }

    @Test
    public void theScoreShowsTempoDynamicsAndArticulation() {
        Score score = Score.of(piece(ritardando(), marks(), Articulation.STACCATO));
        assertEquals(2, score.tempoMarks().size());
        Score.TempoMark rit = score.tempoMarks().get(0);
        assertTrue(rit.text(), rit.text().startsWith("rit."));
        assertEquals(32, rit.position());
        assertEquals(48, rit.until());
        assertEquals("♩ = 80", score.tempoMarks().get(1).text());
        Score.Staff staff = score.staves().get(0);
        assertEquals(List.of(new Score.DynamicMark(0, Dynamics.Level.P), new Score.DynamicMark(32, Dynamics.Level.FF)),
                staff.dynamics());
        assertEquals(List.of(new Score.HairpinMark(16, 32, true)), staff.hairpins());
        assertEquals(Articulation.STACCATO, staff.articulationAt(0));
        assertNull(staff.articulationAt(4));
        // The cursor follows the ritardando: the third bar lasts longer than the first.
        double third = score.barAt(ritardando().seconds(2 * BAR) + 1.0) - 2;
        double first = score.barAt(1.0);
        assertTrue(third < first);
        Score plain = Score.of(piece(TimeMap.of(120), Dynamics.NONE, null));
        assertTrue(plain.tempoMarks().isEmpty());
        assertTrue(plain.staves().get(0).dynamics().isEmpty());
    }
}
