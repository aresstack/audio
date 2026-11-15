package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Articulation;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.studio.ai.ChatContinuationPlanner;
import com.aresstack.audio.studio.ai.ContinuationComposer;
import com.aresstack.audio.studio.ai.ContinuationEnding;
import com.aresstack.audio.studio.ai.ContinuationPerformance;
import com.aresstack.audio.studio.ai.ContinuationPreset;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.progress.Progress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The AI plans how new bars are played, as it plans how a whole piece is played: when an old
 * eight-bar piece is continued or bars are inserted into it, the planning answer's tempo changes
 * (with an accelerando or ritardando), dynamic marks and hairpins go into the new bars and sound
 * there; the original bars before them sound exactly as before, and after an insertion the
 * original goes on in its own tempo and at its own level.
 */
public final class NewBarsPerformanceTest {
    private static final long BAR = 1920;
    private static final Progress QUIET = message -> { };

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private ProjectSession session;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        Path piece = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, piece.resolve(file.getFileName()));
            }
        }
        session = ProjectSession.open(piece).orElseThrow();
    }

    /** Two four-bar sections for eight new bars, with the given performance keys; two notes per bar. */
    private static FakeChatModel model(String performance) {
        return new FakeChatModel((instruction, prompt) -> {
            if (instruction.equals(ChatContinuationPlanner.INSTRUCTION)) {
                String all = "\"melody\",\"harmony\",\"bass\",\"drums\"";
                return "{\"sections\":["
                        + "{\"name\":\"A\",\"function\":\"variation\",\"bars\":4,\"intensity\":0.4,"
                        + "\"chords\":[\"C\",\"G\",\"Am\",\"F\"],\"idea\":\"vary\",\"parts\":[" + all + "]},"
                        + "{\"name\":\"B\",\"function\":\"outro\",\"bars\":4,\"intensity\":0.6,"
                        + "\"chords\":[\"F\",\"G\",\"C\",\"C\"],\"idea\":\"close\",\"parts\":[" + all + "]}],"
                        + "\"newParts\":[],\"motifs\":\"the opening\",\"arc\":\"grow and close\"," + performance + "}";
            }
            String part = FakeChatModel.quoted(prompt, "part");
            String section = FakeChatModel.quoted(prompt, "section");
            int bars = Integer.parseInt(prompt.substring(prompt.lastIndexOf("up to ") + 6).split(";")[0].trim());
            int pitch = switch (part) {
                case "melody" -> 72;
                case "harmony" -> 60;
                case "bass" -> 40;
                default -> 36;
            };
            StringBuilder notes = new StringBuilder();
            for (int bar = 1; bar <= bars; bar++) {
                notes.append(notes.length() == 0 ? "" : ",").append("{\"bar\":").append(bar)
                        .append(",\"beat\":0,\"duration\":2,\"pitch\":").append(pitch).append(",\"velocity\":80")
                        .append(part.equals("melody") ? ",\"articulation\":\"staccato\"" : "").append("},")
                        .append("{\"bar\":").append(bar).append(",\"beat\":2,\"duration\":2,\"pitch\":")
                        .append(pitch + 2).append(",\"velocity\":80}");
            }
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":[" + notes + "]}";
        });
    }

    @Test
    public void aContinuationIsPlayedAsItsPlanSays() throws Exception {
        Composition before = session.composition();
        double bpm = before.timeMap().tempos().get(0).bpm();
        FakeChatModel model = model("\"tempoChanges\":[{\"bar\":13,\"bpm\":" + (bpm + 20) + ",\"glide\":true},"
                + "{\"bar\":16,\"bpm\":" + (bpm - 20) + "}],"
                + "\"dynamics\":[{\"bar\":9,\"level\":\"pp\"},{\"bar\":2,\"level\":\"ff\"}],"
                + "\"hairpins\":[{\"fromBar\":9,\"toBar\":13,\"to\":\"ff\"}]");
        PieceExtension extension = PieceExtension.start(session, ContinuationComposer.with(model),
                ContinuationRequest.of(8, ContinuationPreset.STANDALONE, ContinuationEnding.OPEN), Ids.seeded(5), QUIET);

        assertTrue(model.prompts().get(0).contains("Plan the performance of the new bars 9-16"));
        Composition after = extension.proposal().result();
        assertEquals(List.of(), CompositionValidator.validate(after));

        // the tempo: as before up to bar 13, an accelerando over bars 13-15, slower from bar 16
        TimeMap time = after.timeMap();
        assertEquals(List.of(before.timeMap().tempos().get(0), new TimeMap.Tempo(12 * BAR, bpm + 20, true),
                new TimeMap.Tempo(15 * BAR, bpm - 20)), time.tempos());
        assertEquals(before.timeMap().seconds(8 * BAR), time.seconds(8 * BAR), 1e-9);
        assertTrue(time.glidingAt(13 * BAR));

        // the dynamics: pp from bar 9, swelling to ff at bar 13; the mark in bar 2 is not taken
        Dynamics dynamics = after.dynamics();
        assertEquals(List.of(new Dynamics.Mark(8 * BAR, Dynamics.Level.PP, null)), dynamics.marks());
        assertEquals(List.of(new Dynamics.Hairpin(8 * BAR, 12 * BAR, Dynamics.Level.FF, null)), dynamics.hairpins());
        assertTrue(extension.continuation().plan().repairs().stream().anyMatch(r -> r.contains("außerhalb")));
        assertTrue(extension.proposal().scope().allows(EditOp.Kind.SET_DYNAMICS));

        // and it sounds: every note of the original as written, the new bars quiet, then loud
        for (Part part : before.parts()) {
            for (PlacedNote note : after.notesOf(part.id())) {
                int played = after.playedVelocity(note);
                if (note.tick() < 8 * BAR) {
                    assertEquals(note.note().velocity(), played);
                } else if (note.tick() == 8 * BAR) {
                    // the crescendo starts pianissimo: the note starts at the loudest level of the
                    // hairpin, and the expression brings it down to pianissimo while it is held
                    Dynamics.Scale scale = Dynamics.Scale.STANDARD;
                    assertEquals(scale.velocity(Dynamics.Level.PP) / (double) scale.velocity(Dynamics.Level.FF),
                            after.dynamics().expression(part.id(), note.tick()), 1e-9);
                    assertTrue(after.expression(part.id()).get(0).value() < 127 / 2);
                } else if (note.tick() >= 12 * BAR) {
                    assertTrue(played + " from bar 13", played > note.note().velocity());
                }
            }
        }
        // the notes the AI marked staccato are staccato and sound shorter than written
        String noteprompt = model.prompts().stream().filter(p -> p.contains("\"part\":\"melody\"")).findFirst()
                .orElseThrow();
        assertTrue(noteprompt.contains("may also carry \"articulation\""));
        List<PlacedNote> marked = after.notesOf("melody").stream().filter(n -> n.tick() >= 8 * BAR
                && n.note().articulation() == Articulation.STACCATO).toList();
        assertEquals(8, marked.size());
        for (PlacedNote note : marked) {
            assertTrue(after.playedEnd(note) < note.tick() + note.note().length());
        }
        assertEquals(List.of(), AppendContinuation.originalKept(before, after, 8 * BAR,
                before.parts().stream().map(Part::id).collect(Collectors.toSet()), java.util.Set.of()));

        extension.accept();
        assertEquals(dynamics, session.composition().dynamics());
        assertEquals(time, session.composition().timeMap());
    }

    @Test
    public void aLengthenedPieceGrowsIntoItsNewLevelInsteadOfJumping() throws Exception {
        FakeChatModel model = model("\"dynamics\":[{\"bar\":9,\"level\":\"f\"}]");
        PieceExtension extension = PieceExtension.start(session, ContinuationComposer.with(model),
                ContinuationRequest.of(8, ContinuationPreset.STANDALONE, ContinuationEnding.OPEN), Ids.seeded(5), QUIET);
        Dynamics dynamics = extension.proposal().result().dynamics();
        // mezzoforte at the join (the piece had no marks), forte reached at the end of the first new bar
        assertEquals(List.of(new Dynamics.Hairpin(8 * BAR, 9 * BAR, Dynamics.Level.F, null)), dynamics.hairpins());
        assertEquals(List.of(new Dynamics.Mark(9 * BAR, Dynamics.Level.F, null)), dynamics.marks());
    }

    @Test
    public void aPieceLengthenedBeforeIsSmoothedAtItsSeamWithoutNewNotes() throws Exception {
        Composition base = session.composition();
        FakeChatModel model = model("\"dynamics\":[{\"bar\":9,\"level\":\"f\"}]");
        PieceExtension extension = PieceExtension.start(session, ContinuationComposer.with(model),
                ContinuationRequest.of(8, ContinuationPreset.STANDALONE, ContinuationEnding.OPEN), Ids.seeded(5), QUIET);
        Composition lengthened = extension.proposal().result();
        // as a piece lengthened before the smoothing: forte straight at the join
        Dynamics jump = new Dynamics(List.of(new Dynamics.Mark(8 * BAR, Dynamics.Level.F, null)), List.of(),
                lengthened.dynamics().scale(), lengthened.dynamics().articulations());
        Composition old = lengthened.withDynamics(jump);
        List<Revision> revisions = List.of(
                new Revision("a", 1, null, Revision.Author.ORIGINAL, 0, "Originalfassung", null, base, null),
                new Revision("b", 2, "a", Revision.Author.AI, 1, "Stück um 8 Takte verlängert (eigenständig)", null,
                        old, null));

        List<String> said = new ArrayList<>();
        Dynamics smoothed = SeamRepair.smoothed(old, revisions, said);
        assertEquals(List.of(new Dynamics.Hairpin(8 * BAR, 9 * BAR, Dynamics.Level.F, null)), smoothed.hairpins());
        assertEquals(List.of(new Dynamics.Mark(9 * BAR, Dynamics.Level.F, null)), smoothed.marks());
        assertEquals(1, said.size());
        // done once: a second look changes nothing
        assertEquals(smoothed, SeamRepair.smoothed(old.withDynamics(smoothed), revisions, new ArrayList<>()));
        // a mark set later by hand at the seam is meant to jump and stays
        Dynamics byHand = new Dynamics(List.of(new Dynamics.Mark(8 * BAR, Dynamics.Level.P, null)), List.of(),
                jump.scale(), jump.articulations());
        assertEquals(byHand, SeamRepair.smoothed(old.withDynamics(byHand), revisions, new ArrayList<>()));
    }

    @Test
    public void insertedBarsAreQuietAndSlowAndTheOriginalGoesOnAsBefore() throws Exception {
        Composition before = session.composition();
        double bpm = before.timeMap().tempos().get(0).bpm();
        FakeChatModel model = model("\"tempoChanges\":[{\"bar\":5,\"bpm\":" + (bpm - 30) + ",\"glide\":true},"
                + "{\"bar\":9,\"bpm\":" + (bpm - 10) + ",\"glide\":true}],"
                + "\"dynamics\":[{\"bar\":5,\"level\":\"p\",\"part\":\"melody\"}],"
                + "\"hairpins\":[{\"fromBar\":9,\"toBar\":13,\"to\":\"ppp\",\"part\":\"melody\"}]");
        PieceExtension insertion = PieceExtension.start(session, ContinuationComposer.with(model),
                ContinuationRequest.insert(5, 8, ContinuationPreset.STANDALONE, ""), Ids.seeded(7), QUIET);
        Composition after = insertion.proposal().result();
        assertEquals(List.of(), CompositionValidator.validate(after));

        long room = 4 * BAR;
        long resume = 12 * BAR;
        TimeMap time = after.timeMap();
        // slower in the room, gliding back towards the tempo of the piece, which holds again from bar 13
        assertEquals(bpm - 30, time.tempoAt(room), 1e-9);
        assertTrue(time.glidingAt(room + BAR));
        assertEquals(bpm, time.tempoAt(resume), 1e-9);
        assertFalse(time.glidingAt(resume));
        assertEquals(before.timeMap().seconds(room), time.seconds(room), 1e-9);
        // bars 13-16 last as long as bars 5-8 did
        assertEquals(before.timeMap().seconds(8 * BAR) - before.timeMap().seconds(room),
                time.seconds(16 * BAR) - time.seconds(resume), 1e-9);

        // the melody is quiet in the room and fades, then plays at its own level again
        for (PlacedNote note : after.notesOf("melody")) {
            int played = after.playedVelocity(note);
            if (note.tick() < room || note.tick() >= resume) {
                assertEquals(note.note().velocity(), played);
            } else {
                assertTrue(played < note.note().velocity());
            }
        }
        for (PlacedNote note : after.notesOf("bass")) {
            assertEquals(note.note().velocity(), after.playedVelocity(note));
        }
        assertTrue(after.dynamics().marks().contains(new Dynamics.Mark(resume, Dynamics.Level.MF, "melody")));
    }

    @Test
    public void withoutPerformanceInTheAnswerNothingButTheNotesChanges() throws Exception {
        Composition before = session.composition();
        PieceExtension extension = PieceExtension.start(session, ContinuationComposer.with(model("\"x\":0")),
                ContinuationRequest.of(8, ContinuationPreset.STANDALONE, ContinuationEnding.OPEN), Ids.seeded(9), QUIET);
        Composition after = extension.proposal().result();
        assertEquals(ContinuationPerformance.NONE, extension.continuation().plan().performance());
        assertEquals(before.timeMap(), after.timeMap());
        assertEquals(Dynamics.NONE, after.dynamics());
        assertFalse(extension.proposal().scope().allows(EditOp.Kind.SET_DYNAMICS));
    }
}
