package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.composition.edit.ScopeProof;
import com.aresstack.audio.composition.edit.TimeShift;
import com.aresstack.audio.studio.ai.ChatContinuationPlanner;
import com.aresstack.audio.studio.ai.ComposedContinuation;
import com.aresstack.audio.studio.ai.ContinuationComposer;
import com.aresstack.audio.studio.ai.ContinuationPreset;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.ContinuationTask;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.ai.IntensityCurve;
import com.aresstack.audio.studio.progress.Progress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package C4, {@code INSERT} (architecture review E4, E5; condition A of the product owner):
 * new bars are inserted into an old eight-bar piece of the studio. The music after the insertion
 * point moves back by an explicit, deterministic {@code ShiftTime} of the application, the AI
 * writes only the room, with the music before and after as context, and the edit core proves
 * "original plus approved shift" equal to "after"; the same shift with one more change outside the
 * room is refused.
 */
public final class PieceInsertionTest {
    private static final long BAR = 1920;
    private static final Progress QUIET = message -> { };

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path piece;
    private ProjectSession session;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        piece = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, piece.resolve(file.getFileName()));
            }
        }
        session = ProjectSession.open(piece).orElseThrow();
    }

    /** Plans two four-bar sections for the room; generators write two notes per bar of every section. */
    static FakeChatModel model() {
        return new FakeChatModel((instruction, prompt) -> {
            if (instruction.equals(ChatContinuationPlanner.INSTRUCTION)) {
                String all = "\"melody\",\"harmony\",\"bass\",\"drums\"";
                return "{\"sections\":["
                        + "{\"name\":\"Zwischenspiel\",\"function\":\"interlude\",\"bars\":4,\"intensity\":0.3,"
                        + "\"chords\":[\"Am\",\"F\",\"C\",\"G\"],\"idea\":\"quiet episode\",\"parts\":[\"melody\",\"harmony\"]},"
                        + "{\"name\":\"Rückführung\",\"function\":\"transition\",\"bars\":4,\"intensity\":0.6,"
                        + "\"chords\":[\"F\",\"G\",\"F\",\"G\"],\"idea\":\"lead back\",\"parts\":[" + all + "]}],"
                        + "\"newParts\":[],\"motifs\":\"the opening arpeggio\",\"arc\":\"breathe, then lead back\"}";
            }
            String part = FakeChatModel.quoted(prompt, "part");
            String section = FakeChatModel.quoted(prompt, "section");
            int bars = Integer.parseInt(prompt.substring(prompt.lastIndexOf("up to ") + 6).split(";")[0].trim());
            int pitch = switch (part) {
                case "melody" -> 74;
                case "harmony" -> 62;
                case "bass" -> 41;
                default -> 38;
            };
            StringBuilder notes = new StringBuilder();
            for (int bar = 1; bar <= bars; bar++) {
                notes.append(notes.length() == 0 ? "" : ",").append("{\"bar\":").append(bar)
                        .append(",\"beat\":0,\"duration\":2,\"pitch\":").append(pitch).append(",\"velocity\":70},")
                        .append("{\"bar\":").append(bar).append(",\"beat\":2,\"duration\":2,\"pitch\":")
                        .append(pitch + 2).append(",\"velocity\":65}");
            }
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":[" + notes + "]}";
        });
    }

    @Test
    public void eightBarsAreInsertedBeforeBarFiveAndTheMusicAfterMovesBack() throws Exception {
        FakeChatModel model = model();
        Composition before = session.composition();
        long point = 4 * BAR;
        long room = 8 * BAR;
        ContinuationRequest request = ContinuationRequest.insert(5, 8, ContinuationPreset.STANDALONE, "");

        PieceExtension insertion = PieceExtension.start(session, ContinuationComposer.with(model), request,
                Ids.seeded(21), QUIET);

        // one plan, then one call per part and planned section in which it plays
        assertEquals(1 + 2 + 4, model.prompts().size());
        String planning = model.prompts().get(0);
        assertTrue(planning.contains("Plan 8 new bars to be INSERTED into this piece, bars 5-12 of the piece"));
        assertTrue(planning.contains("the original bars 5-8 follow them as bars 13-16"));
        assertTrue(planning.contains("leads seamlessly into bar 13"));
        // the context tells the music before and the music after the room
        assertTrue(planning.contains("# BEFORE THE INSERTED BARS"));
        assertTrue(planning.contains("# AFTER THE INSERTED BARS: THE MUSIC THE INSERTION LEADS INTO"));
        assertTrue(planning.contains("# NOTES bars 13-16"));
        String last = model.prompts().stream().filter(p -> p.contains("\"section\":\"s2\"")).findFirst().orElseThrow();
        assertTrue(last.contains("# PLAN OF THE INSERTED BARS (bars 5-12)"));
        assertTrue(last.contains("This is the last inserted section: lead seamlessly into bar 13"));
        assertTrue(last.contains("Dramaturgy for intensity 0.6"));

        Proposal proposal = insertion.proposal();
        // the shift is an explicit operation of the application and comes first
        EditOp first = proposal.patch().operations().get(0);
        assertEquals(new EditOp.ShiftTime(point, room), first);
        assertEquals(1, proposal.patch().shifts().size());
        assertTrue(proposal.scope().allowsShift());
        assertEquals(point, proposal.scope().fromTick());
        assertEquals(point + room, proposal.scope().toTick());
        assertTrue(proposal.runId().startsWith("insert/1/"));

        Composition after = proposal.result();
        assertEquals(List.of(), CompositionValidator.validate(after));
        assertEquals(before.endTick() + room, after.endTick());
        // bars 1-4 as they were, bars 5-8 of the original now as bars 13-16, note by note
        assertEquals(AppendContinuation.hashBefore(before, point), AppendContinuation.hashBefore(after, point));
        assertEquals(InsertPassage.hashFrom(before, point), InsertPassage.hashFrom(after, point + room));
        assertEquals(List.of(), InsertPassage.originalKept(before, after, insertion.continuation().task(), point,
                ids(before.parts()), Set.of()));
        // the proof: original with the approved shift against after
        Composition shifted = TimeShift.shift(before, point, room);
        assertEquals(ScopeProof.hashOutside(shifted, proposal.scope()),
                ScopeProof.hashOutside(after, proposal.scope(), shifted));
        // the room holds the new notes, after the plan: the drums only in the second section
        long second = point + 4 * BAR;
        for (Part part : before.parts()) {
            List<PlacedNote> inRoom = after.notesOf(part.id()).stream()
                    .filter(n -> n.tick() >= point && n.tick() < point + room).toList();
            boolean quiet = part.id().equals("melody") || part.id().equals("harmony");
            assertEquals(part.id(), quiet ? 16 : 8, inRoom.size());
            assertTrue(inRoom.stream().allMatch(n -> n.endTick() <= point + room));
            if (!quiet) {
                assertTrue(inRoom.stream().allMatch(n -> n.tick() >= second));
            }
        }
        // the form: the new sections fill the room without gap; a section across the point is cut there
        List<String> names = after.sections().stream().map(Section::name).toList();
        assertTrue(names.containsAll(List.of("Zwischenspiel", "Rückführung")));
        Section interlude = after.sections().stream().filter(s -> s.name().equals("Zwischenspiel")).findFirst()
                .orElseThrow();
        assertEquals(point, interlude.startTick());
        assertEquals(0.3, interlude.intensity(), 0);
        assertEquals(List.of("Am", "F", "C", "G"), interlude.harmony());
        for (Section s : before.sections()) {
            if (s.startTick() < point && s.endTick() > point) {
                assertTrue(after.sections().stream().anyMatch(a -> a.id().equals(s.id()) && a.endTick() == point));
                assertTrue(after.sections().stream().anyMatch(a -> !a.id().equals(s.id()) && a.name().equals(s.name())
                        && a.startTick() == point + room && a.endTick() == s.endTick() + room));
            }
        }

        insertion.accept();
        assertEquals(after, session.composition());
        assertTrue(session.revisions().stream().anyMatch(r -> r.composition().equals(before)));
        session.undo();
        assertEquals(before, session.composition());
    }

    @Test
    public void aNoteSoundingAcrossTheInsertionPointKeepsSounding() throws Exception {
        long point = 4 * BAR;
        long room = 8 * BAR;
        // make one note of the old piece sound from bar 4 into bar 5, across the coming insertion point
        PlacedNote held = session.composition().parts().stream()
                .flatMap(part -> session.composition().notesOf(part.id()).stream())
                .filter(n -> n.tick() < point && n.clip().startTick() < point && n.clip().endTick() > point + BAR)
                .max(java.util.Comparator.comparingLong(PlacedNote::tick))
                .orElseThrow(() -> new AssertionError("the fixture needs a clip across bar 5"));
        long length = point + BAR - held.tick();
        Note longer = new Note(held.note().id(), held.note().tick(), length, held.note().pitch(),
                held.note().velocity(), held.note().voiceId());
        session.apply(new Patch("longer note", List.of(new EditOp.ChangeNote(held.clip().id(), longer))),
                EditScope.everything());
        String partId = session.composition().parts().stream().map(Part::id)
                .filter(id -> session.composition().notesOf(id).stream()
                        .anyMatch(n -> n.note().id().equals(held.note().id())))
                .findFirst().orElseThrow();
        Composition before = session.composition();

        PieceExtension insertion = PieceExtension.start(session, ContinuationComposer.with(model()),
                ContinuationRequest.insert(5, 8, ContinuationPreset.STANDALONE, ""), Ids.seeded(21), QUIET);
        Proposal proposal = insertion.proposal();
        Composition after = proposal.result();

        assertEquals(List.of(), CompositionValidator.validate(after));
        // the note keeps its full length instead of ending at the insertion point, and its clip grew with it
        PlacedNote kept = after.notesOf(partId).stream().filter(n -> n.note().id().equals(held.note().id()))
                .findFirst().orElseThrow();
        assertEquals(held.tick(), kept.tick());
        assertEquals(length, kept.note().length());
        assertEquals(held.clip().id(), kept.clip().id());
        assertTrue(kept.clip().endTick() >= point + BAR);
        assertTrue(kept.clip().endTick() <= point + room);
        assertTrue(proposal.patch().operations().stream().anyMatch(op -> op instanceof EditOp.ResizeClip));
        assertEquals(List.of(), insertion.continuation().shortened());
        // the transition zone reaches back to the start of the held note; everything else stays proven
        assertTrue(proposal.scope().fromTick() - proposal.scope().transitionTicks() <= held.tick());
        assertEquals(InsertPassage.hashFrom(before, point), InsertPassage.hashFrom(after, point + room));
        assertEquals(List.of(), InsertPassage.originalKept(before, after, insertion.continuation().task(),
                insertion.continuation().task().tailStart(), ids(before.parts()), Set.of()));
        // without the insertion's hold the shift alone would cut the note at the point
        PlacedNote cut = TimeShift.shift(before, point, room).notesOf(partId).stream()
                .filter(n -> n.note().id().equals(held.note().id())).findFirst().orElseThrow();
        assertEquals(point - held.tick(), cut.note().length());

        insertion.accept();
        assertEquals(after, session.composition());
    }

    @Test
    public void aNoteSoundingBeyondTheInsertedBarsIsShortenedOnlyOpenly() throws Exception {
        long point = 4 * BAR;
        long room = 2 * BAR;
        // one note of the old piece sounds from bar 4 to the end of its clip, longer than the two inserted bars
        PlacedNote held = session.composition().parts().stream()
                .flatMap(part -> session.composition().notesOf(part.id()).stream())
                .filter(n -> n.tick() < point && n.clip().startTick() < point
                        && n.clip().endTick() > point + room + BAR)
                .max(java.util.Comparator.comparingLong(PlacedNote::tick))
                .orElseThrow(() -> new AssertionError("the fixture needs a clip across bars 5-7"));
        long length = held.clip().endTick() - held.tick();
        Note longer = new Note(held.note().id(), held.note().tick(), length, held.note().pitch(),
                held.note().velocity(), held.note().voiceId());
        session.apply(new Patch("longer note", List.of(new EditOp.ChangeNote(held.clip().id(), longer))),
                EditScope.everything());
        String partId = session.composition().parts().stream().map(Part::id)
                .filter(id -> session.composition().notesOf(id).stream()
                        .anyMatch(n -> n.note().id().equals(held.note().id())))
                .findFirst().orElseThrow();
        Composition before = session.composition();

        PieceExtension insertion = PieceExtension.start(session, ContinuationComposer.with(model()),
                ContinuationRequest.insert(5, 2, ContinuationPreset.STANDALONE, ""), Ids.seeded(21), QUIET);
        Proposal proposal = insertion.proposal();
        Composition after = proposal.result();
        ComposedContinuation composed = insertion.continuation();

        assertEquals(List.of(), CompositionValidator.validate(after));
        // the note sounds on to the end of the room, where the moved original begins, and not further
        PlacedNote kept = after.notesOf(partId).stream().filter(n -> n.note().id().equals(held.note().id()))
                .findFirst().orElseThrow();
        assertEquals(held.tick(), kept.tick());
        assertEquals(point + room, kept.endTick());
        assertTrue(kept.note().length() < length);
        // the shortening is not silent: listed, among the corrections and in the description of the proposal
        assertEquals(1, composed.shortened().size());
        assertEquals(held.note().id(), composed.shortened().get(0).noteId());
        assertEquals(length, composed.shortened().get(0).length());
        assertEquals(point + room - held.tick(), composed.keptLength(composed.shortened().get(0)));
        assertTrue(composed.corrections().stream().anyMatch(line -> line.contains(held.note().id())
                && line.contains("Originaldauer nicht erhaltbar")));
        assertTrue(proposal.patch().description().contains("1 über den Einfügepunkt klingende Note(n) gekürzt"));
        // the check of the original accepts exactly this listed shortening, nothing silent
        ContinuationTask task = composed.task();
        assertEquals(List.of(), InsertPassage.originalKept(before, after, task, task.tailStart(),
                ids(before.parts()), Set.of(), InsertPassage.shortened(composed)));
        assertFalse(InsertPassage.originalKept(before, after, task, task.tailStart(), ids(before.parts()),
                Set.of()).isEmpty());
    }

    @Test
    public void theSameShiftWithOneMoreChangeAfterTheRoomIsRefused() throws Exception {
        // condition A of the product owner: approved shift passes, any further change outside does not
        Composition before = session.composition();
        PieceExtension insertion = PieceExtension.start(session, ContinuationComposer.with(model()),
                ContinuationRequest.insert(5, 8, ContinuationPreset.STANDALONE, ""), Ids.seeded(22), QUIET);
        Proposal proposal = insertion.proposal();
        insertion.discard();
        assertEquals(before, session.composition());

        // a melody note of bar 7 of the original is bar 15 after the shift; change its pitch as well
        PlacedNote target = proposal.result().notesOf("melody").stream().filter(n -> n.tick() >= 14 * BAR)
                .findFirst().orElseThrow();
        Note note = target.note();
        List<EditOp> operations = new ArrayList<>(proposal.patch().operations());
        operations.add(new EditOp.ChangeNote(target.clip().id(), new Note(note.id(), note.tick(), note.length(),
                note.pitch() + 1, note.velocity(), note.voiceId())));
        try {
            session.propose(new Patch("Einfügen und heimlich ändern", operations), proposal.scope(),
                    Revision.Author.AI, null);
            fail("a change after the room is outside the scope");
        } catch (EditRefusedException expected) {
            assertFalse(expected.violations().isEmpty());
        }
        // and without the approval to shift, the insertion itself is refused
        EditScope noShift = new EditScope(false, proposal.scope().partIds(), proposal.scope().trackIds(), Set.of(),
                proposal.scope().fromTick(), proposal.scope().toTick(), AppendContinuation.kinds(false), false, 0);
        try {
            session.propose(proposal.patch(), noShift, Revision.Author.AI, null);
            fail("a shift needs the approval of the scope");
        } catch (EditRefusedException expected) {
            assertTrue(expected.violations().toString().contains("SHIFT_TIME"));
        }
        assertEquals(before, session.composition());
    }

    @Test
    public void barsCanBeInsertedAtTheVeryBeginningAndFollowACurve() throws Exception {
        Composition before = session.composition();
        ContinuationRequest request = ContinuationRequest.insert(1, 8, ContinuationPreset.FREE, "ein Vorspiel")
                .withCurve(IntensityCurve.ramp(0.2, 0.8));
        PieceExtension insertion = PieceExtension.start(session, ContinuationComposer.with(model()), request,
                Ids.seeded(23), QUIET);
        ComposedContinuation composed = insertion.continuation();
        // the plan follows the curve of the user, and says so
        assertEquals(List.of(0.35, 0.65), composed.plan().sections().stream().map(s -> s.intensity()).toList());
        assertTrue(composed.plan().repairs().stream().anyMatch(r -> r.contains("Kurve")));
        Composition after = insertion.proposal().result();
        assertEquals(List.of(), CompositionValidator.validate(after));
        assertEquals(InsertPassage.hashFrom(before, 0), InsertPassage.hashFrom(after, 8 * BAR));
        assertEquals(0, after.sections().get(0).startTick());
        assertEquals("Zwischenspiel", after.sections().get(0).name());
        // the clips of the original all start eight bars later
        for (var track : before.tracks()) {
            for (var clip : track.clips()) {
                assertEquals(clip.startTick() + 8 * BAR, after.tracks().stream().flatMap(t -> t.clips().stream())
                        .filter(c -> c.id().equals(clip.id())).findFirst().orElseThrow().startTick());
            }
        }
    }

    @Test
    public void theBarsBeforeTheInsertionMayBeRevisedForTheTransition() throws Exception {
        Composition before = session.composition();
        long point = 4 * BAR;
        ContinuationRequest request = ContinuationRequest.insert(5, 8, ContinuationPreset.STANDALONE, "")
                .revisingTail(1);
        PieceExtension insertion = PieceExtension.start(session, ContinuationComposer.with(model()), request,
                Ids.seeded(26), QUIET);
        Proposal proposal = insertion.proposal();
        assertEquals(BAR, proposal.scope().transitionTicks());
        Composition after = proposal.result();
        assertEquals(List.of(), CompositionValidator.validate(after));
        // bars 1-3 and the music after the room as they were; bar 4 written anew
        assertEquals(AppendContinuation.hashBefore(before, 3 * BAR), AppendContinuation.hashBefore(after, 3 * BAR));
        assertNotEquals(AppendContinuation.hashBefore(before, point), AppendContinuation.hashBefore(after, point));
        assertEquals(InsertPassage.hashFrom(before, point), InsertPassage.hashFrom(after, point + 8 * BAR));
        assertEquals(List.of(), InsertPassage.originalKept(before, after, insertion.continuation().task(), 3 * BAR,
                ids(before.parts()), Set.of()));
    }

    @Test
    public void anInsertionWritesOnlyIntoNewClips() throws Exception {
        Composition before = session.composition();
        PieceExtension insertion = PieceExtension.start(session, ContinuationComposer.with(model()),
                ContinuationRequest.insert(3, 8, ContinuationPreset.UNDERSCORE, ""), Ids.seeded(24), QUIET);
        Set<String> clipsBefore = new java.util.HashSet<>();
        before.tracks().forEach(t -> t.clips().forEach(c -> clipsBefore.add(c.id())));
        for (EditOp op : insertion.proposal().patch().operations()) {
            assertTrue(op.toString(), op instanceof EditOp.ShiftTime || op instanceof EditOp.AddClip
                    || op instanceof EditOp.SetSections || op instanceof EditOp.AddPart || op instanceof EditOp.AddTrack);
            if (op instanceof EditOp.AddClip add) {
                assertFalse(clipsBefore.contains(add.clip().id()));
                assertTrue(add.clip() instanceof MidiClip);
                assertTrue(add.clip().startTick() >= 2 * BAR && ((MidiClip) add.clip()).endTick() <= 10 * BAR);
            }
        }
        assertNotEquals(before, insertion.proposal().result());
    }

    @Test
    public void anInsertionBehindTheEndIsRefusedAsAContinuation() throws Exception {
        try {
            PieceExtension.start(session, ContinuationComposer.with(model()),
                    ContinuationRequest.insert(9, 4, ContinuationPreset.STANDALONE, ""), Ids.seeded(25), QUIET);
            fail("bar 9 is behind an eight-bar piece");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("verlängern"));
        }
        assertTrue(session.pending().isEmpty());
    }

    private static Set<String> ids(List<Part> parts) {
        return parts.stream().map(Part::id).collect(Collectors.toSet());
    }
}
