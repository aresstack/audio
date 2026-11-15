package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.composition.edit.ScopeProof;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import com.aresstack.audio.studio.ai.ChatContinuationPlanner;
import com.aresstack.audio.studio.ai.ComposedContinuation;
import com.aresstack.audio.studio.ai.ContinuationComposer;
import com.aresstack.audio.studio.ai.ContinuationEnding;
import com.aresstack.audio.studio.ai.ContinuationPreset;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.ai.ForeignPartException;
import com.aresstack.audio.studio.ai.SectionGenerator;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.PreviewFolder;
import com.aresstack.audio.studio.project.ProjectFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package C3, acceptance A2 and A3: an old eight-bar piece of the studio is continued by the
 * AI. The plan covers exactly the new bars, every part is written after it with the real notes of
 * the piece as context, the original stays provably unchanged (or, with a revised transition,
 * changes only in its last bars), a dramatic build brings in new instruments with a rising
 * intensity, and the continuation can be listened to, composed again, discarded and accepted.
 */
public final class PieceExtensionTest {
    private static final long BAR = 1920;
    private static final long ORIGINAL_END = 8 * BAR;
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

    /** A plan in three sections over 16 bars; the dramatic one brings timpani into the climax. */
    private static String plan(String prompt) {
        boolean dramatic = prompt.contains("dramatic build");
        String all = "\"melody\",\"harmony\",\"bass\",\"drums\"";
        if (dramatic) {
            return "{\"sections\":["
                    + "{\"name\":\"Aufbau\",\"function\":\"build\",\"bars\":8,\"intensity\":0.5,"
                    + "\"chords\":[\"Am\",\"F\",\"C\",\"G\",\"Am\",\"F\",\"G\",\"G\"],\"idea\":\"strings swell\","
                    + "\"parts\":[\"melody\",\"harmony\",\"bass\"]},"
                    + "{\"name\":\"Höhepunkt\",\"function\":\"climax\",\"bars\":4,\"intensity\":1.0,"
                    + "\"chords\":[\"C\",\"G\",\"Am\",\"F\"],\"idea\":\"tutti\",\"parts\":[" + all + ",\"new1\"]},"
                    + "{\"name\":\"Auflösung\",\"function\":\"outro\",\"bars\":4,\"intensity\":0.4,"
                    + "\"chords\":[\"F\",\"G\",\"C\",\"C\"],\"idea\":\"calm down\",\"parts\":[\"melody\",\"harmony\",\"new1\"]}],"
                    + "\"newParts\":[{\"key\":\"new1\",\"name\":\"Pauke\",\"instrument\":\"Timpani\",\"program\":47,"
                    + "\"role\":\"bass\",\"idea\":\"rolls into the climax\"}],"
                    + "\"motifs\":\"the opening arpeggio returns in the climax\",\"arc\":\"rise and resolve\"}";
        }
        return "{\"sections\":["
                + "{\"name\":\"Variation\",\"function\":\"variation\",\"bars\":8,\"intensity\":0.5,"
                + "\"chords\":[\"C\",\"G\",\"Am\",\"F\",\"C\",\"G\",\"F\",\"G\"],\"idea\":\"vary the theme\",\"parts\":[" + all + "]},"
                + "{\"name\":\"Kontrast\",\"function\":\"contrast\",\"bars\":4,\"intensity\":0.7,"
                + "\"chords\":[\"Am\",\"Em\",\"F\",\"G\"],\"idea\":\"minor colour\",\"parts\":[" + all + "]},"
                + "{\"name\":\"Schluss\",\"function\":\"outro\",\"bars\":4,\"intensity\":0.4,"
                + "\"chords\":[\"F\",\"G\",\"C\",\"C\"],\"idea\":\"close\",\"parts\":[" + all + "]}],"
                + "\"newParts\":[],\"motifs\":\"the opening arpeggio\",\"arc\":\"variation, contrast, close\"}";
    }

    /**
     * A model that answers plan and notes: two notes per bar of every section, pitch after the
     * part, one higher in a second attempt; the transition gets its own pitch.
     */
    private static FakeChatModel composer() {
        return model((instruction, prompt) -> null);
    }

    private static FakeChatModel model(BiFunction<String, String, String> override) {
        return new FakeChatModel((instruction, prompt) -> {
            String special = override.apply(instruction, prompt);
            if (special != null) {
                return special;
            }
            if (instruction.equals(ChatContinuationPlanner.INSTRUCTION)) {
                return plan(prompt);
            }
            String part = FakeChatModel.quoted(prompt, "part");
            String section = FakeChatModel.quoted(prompt, "section");
            int bars = Integer.parseInt(prompt.substring(prompt.lastIndexOf("up to ") + 6).split(";")[0].trim());
            int pitch = switch (part) {
                case "melody" -> 72;
                case "harmony" -> 60;
                case "bass" -> 40;
                case "drums" -> 36;
                default -> 45;
            };
            if (section.equals(SectionGenerator.TRANSITION)) {
                pitch += 3;
            }
            if (prompt.contains("Attempt 2")) {
                pitch += 1;
            }
            StringBuilder notes = new StringBuilder();
            for (int bar = 1; bar <= bars; bar++) {
                notes.append(notes.length() == 0 ? "" : ",").append("{\"bar\":").append(bar)
                        .append(",\"beat\":0,\"duration\":2,\"pitch\":").append(pitch).append(",\"velocity\":80},")
                        .append("{\"bar\":").append(bar).append(",\"beat\":2,\"duration\":2,\"pitch\":")
                        .append(pitch + 2).append(",\"velocity\":75}");
            }
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":[" + notes + "]}";
        });
    }

    private static ContinuationComposer composerWith(FakeChatModel model) {
        return ContinuationComposer.with(model);
    }

    private static Set<String> ids(List<Part> parts) {
        return parts.stream().map(Part::id).collect(Collectors.toSet());
    }

    @Test
    public void eightBarsAreContinuedBySixteenAndTheOriginalStays() throws Exception {
        // A2: Acht Takte um weitere 16 Takte verlängern, Original bleibt erhalten
        FakeChatModel model = composer();
        Composition before = session.composition();
        byte[] modelOnDisk = Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME));
        ContinuationRequest request = ContinuationRequest.of(16, ContinuationPreset.STANDALONE, ContinuationEnding.CLOSED);

        PieceExtension extension = PieceExtension.start(session, composerWith(model), request, Ids.seeded(3), QUIET);

        // one plan, then one call per part and section, all with the real notes of every part
        assertEquals(1 + 4 * 3, model.prompts().size());
        for (String prompt : model.prompts()) {
            for (Part part : before.parts()) {
                assertTrue(prompt.contains("\npart " + part.id()));
            }
            assertTrue(prompt.contains("  1 0 0.5 C4/60 80")); // first melody note of the original
            assertTrue(prompt.contains("A standalone piece of music"));
        }
        assertTrue(model.prompts().get(0).contains("16 new bars, bars 9-24 of the piece"));
        // the generators follow the shared plan
        String generator = model.prompts().get(1);
        assertTrue(generator.contains("# PLAN OF THE CONTINUATION (bars 9-24)"));
        assertTrue(generator.contains("section s2 \"Kontrast\" bars 17-20"));

        ComposedContinuation continuation = extension.continuation();
        // the plan covers exactly the new bars
        assertEquals(16, continuation.plan().bars());
        assertEquals(List.of(), continuation.plan().repairs());

        Proposal proposal = extension.proposal();
        Composition after = proposal.result();
        assertEquals(24 * BAR, after.endTick());
        assertEquals(List.of(), CompositionValidator.validate(after));
        // the form: the old section, then the three planned ones, without gap
        assertEquals(before.sections(), after.sections().subList(0, before.sections().size()));
        List<Section> added = after.sections().subList(before.sections().size(), after.sections().size());
        assertEquals(List.of("Variation", "Kontrast", "Schluss"), added.stream().map(Section::name).toList());
        assertEquals(ORIGINAL_END, added.get(0).startTick());
        assertEquals(List.of("Am", "Em", "F", "G"), added.get(1).harmony());
        assertEquals(0.7, added.get(1).intensity(), 0);

        // Original schützen: every note of bars 1-8 is exactly as it was
        assertEquals(AppendContinuation.hashBefore(before, ORIGINAL_END),
                AppendContinuation.hashBefore(after, ORIGINAL_END));
        assertEquals(List.of(), AppendContinuation.originalKept(before, after, ORIGINAL_END, ids(before.parts()), Set.of()));
        EditScope scope = proposal.scope();
        assertEquals(0, scope.transitionTicks());
        assertEquals(ScopeProof.hashOutside(before, scope), ScopeProof.hashOutside(after, scope, before));
        // the parts go on in the new bars, nothing begins before them that was not there
        for (Part part : before.parts()) {
            List<PlacedNote> beyond = after.notesOf(part.id()).stream().filter(n -> n.tick() >= ORIGINAL_END).toList();
            assertEquals(32, beyond.size());
            assertTrue(beyond.stream().allMatch(n -> n.endTick() <= 24 * BAR));
        }
        assertEquals(Revision.Author.AI, proposal.author());
        assertTrue(proposal.runId().startsWith("extend/1/"));

        // nothing of the piece is written before acceptance
        assertEquals(before, session.composition());
        assertArrayEquals(modelOnDisk, Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME)));

        Path preview = extension.preview();
        assertEquals(PreviewFolder.of(piece, proposal.id()), preview);
        assertTrue(Files.isRegularFile(preview.resolve(ProjectFolder.ARRANGEMENT)));

        extension.accept();
        assertEquals(after, session.composition());
        assertEquals(after, CompositionJson.load(piece));
        // the original is kept as a revision of its own, and undo brings it back
        List<Revision> revisions = session.revisions();
        assertTrue(revisions.stream().anyMatch(r -> r.composition().equals(before)));
        assertEquals(Revision.Author.AI, revisions.get(revisions.size() - 1).author());
        session.undo();
        assertEquals(before, session.composition());
    }

    @Test
    public void aRevisedTransitionChangesOnlyTheLastBarsOfTheOriginal() throws Exception {
        FakeChatModel model = composer();
        Composition before = session.composition();
        ContinuationRequest request = ContinuationRequest.of(16, ContinuationPreset.STANDALONE, ContinuationEnding.OPEN)
                .revisingTail(2);
        long tailStart = 6 * BAR;

        PieceExtension extension = PieceExtension.start(session, composerWith(model), request, Ids.seeded(5), QUIET);

        // the transition is written first for every part, then the sections
        assertEquals(1 + 4 * 4, model.prompts().size());
        assertTrue(model.prompts().get(0).contains("the last 2 bars of the original (bars 7-8) will be rewritten"));
        assertTrue(model.prompts().stream().anyMatch(p -> p.contains("Rewrite the last 2 bars of the original for ONE "
                + "part (bars 7-8 of the piece)")));
        Composition after = extension.proposal().result();
        assertEquals(List.of(), CompositionValidator.validate(after));
        EditScope scope = extension.proposal().scope();
        assertEquals(2 * BAR, scope.transitionTicks());

        // bars 1-6 untouched, bars 7-8 may differ and do
        assertEquals(AppendContinuation.hashBefore(before, tailStart), AppendContinuation.hashBefore(after, tailStart));
        assertNotEquals(AppendContinuation.hashBefore(before, ORIGINAL_END),
                AppendContinuation.hashBefore(after, ORIGINAL_END));
        assertEquals(List.of(), AppendContinuation.originalKept(before, after, tailStart, ids(before.parts()), Set.of()));
        for (Part part : before.parts()) {
            List<PlacedNote> tail = after.notesOf(part.id()).stream()
                    .filter(n -> n.tick() >= tailStart && n.tick() < ORIGINAL_END).toList();
            assertEquals(4, tail.size()); // two per revised bar, as the model wrote them
            int written = switch (part.id()) {
                case "melody" -> 75;
                case "harmony" -> 63;
                case "bass" -> 43;
                default -> 39;
            };
            assertTrue(part.id(), tail.stream().allMatch(n -> n.note().pitch() == written
                    || n.note().pitch() == written + 2 || part.drums()));
            // a note that rang into the tail from before stays as it was
            for (PlacedNote held : before.notesOf(part.id())) {
                if (held.tick() < tailStart) {
                    assertTrue(after.notesOf(part.id()).stream().anyMatch(n -> n.note().id().equals(held.note().id())));
                }
            }
        }
        // the original is not lost: discard gives it back, and acceptance keeps it as a revision
        extension.accept();
        assertTrue(session.revisions().stream().anyMatch(r -> r.composition().equals(before)));
    }

    @Test
    public void aDramaticBuildBringsNewInstrumentsAndARisingIntensity() throws Exception {
        // A3: Verlängerung mit dramatischem Höhepunkt
        FakeChatModel model = composer();
        Composition before = session.composition();
        ContinuationRequest request = ContinuationRequest.of(16, ContinuationPreset.DRAMATIC_BUILD,
                ContinuationEnding.CLOSED);
        assertEquals(2, request.newParts());

        PieceExtension extension = PieceExtension.start(session, composerWith(model), request, Ids.seeded(9), QUIET);

        String planning = model.prompts().get(0);
        assertTrue(planning.contains("A dramatic build"));
        assertTrue(planning.contains("You may bring in up to 2 NEW instruments"));
        ComposedContinuation continuation = extension.continuation();
        assertEquals(1, continuation.newParts().size());
        Part timpani = continuation.newParts().get(0);
        assertEquals("Pauke", timpani.name());
        assertEquals(47, timpani.program());
        // new instruments sound like the rest of the piece: the sound library most parts use
        assertEquals("builtin", timpani.libraryId());

        Composition after = extension.proposal().result();
        assertEquals(List.of(), CompositionValidator.validate(after));
        List<Section> added = after.sections().subList(before.sections().size(), after.sections().size());
        assertEquals(List.of(0.5, 1.0, 0.4), added.stream().map(Section::intensity).toList());
        assertEquals("climax", added.get(1).function());
        // the timpani play from the climax on, only in the sections the plan gives them
        long climax = added.get(1).startTick();
        List<PlacedNote> rolls = after.notesOf(timpani.id());
        assertEquals(2 * 4 + 2 * 4, rolls.size());
        assertTrue(rolls.stream().allMatch(n -> n.tick() >= climax));
        // the drums rest in the build and join in the climax
        assertTrue(after.notesOf("drums").stream().filter(n -> n.tick() >= ORIGINAL_END)
                .allMatch(n -> n.tick() >= climax && n.tick() < added.get(2).startTick()));
        // the generator of the new part is told that it is new and how intense the section is
        String timpaniPrompt = model.prompts().stream().filter(p -> p.contains("\"part\":\"new1\"")).findFirst()
                .orElseThrow();
        assertTrue(timpaniPrompt.contains("This part is a NEW instrument in the piece: rolls into the climax"));
        assertTrue(timpaniPrompt.contains("Intensity: 1 (0 = very quiet"));
        // the last section is told how to end
        assertTrue(model.prompts().stream().anyMatch(p -> p.contains("This is the last section. Ending: End the piece")));

        Set<String> goOn = ids(before.parts());
        assertEquals(List.of(), AppendContinuation.originalKept(before, after, ORIGINAL_END, goOn, Set.of(timpani.id())));
        assertEquals(AppendContinuation.hashBefore(before, ORIGINAL_END),
                AppendContinuation.hashBefore(after, ORIGINAL_END));
        assertTrue(extension.proposal().changes().partsToRender().contains(timpani.id()));
    }

    @Test
    public void newInstrumentsGetASeatAChannelAndAnEntryInTheOldFilesOnAcceptance() throws Exception {
        MixScene mixBefore = session.mix();
        int tracksBefore = new TrackArrangementStore().load(piece).tracks().size();
        SpatialScene stageBefore = new SpatialSceneStore().load(piece);
        PieceExtension extension = PieceExtension.start(session, composerWith(composer()),
                ContinuationRequest.of(16, ContinuationPreset.DRAMATIC_BUILD, ContinuationEnding.CLOSED), Ids.seeded(11),
                QUIET);
        String id = extension.continuation().newParts().get(0).id();
        assertEquals("nothing before acceptance", mixBefore, session.mix());
        extension.accept();

        // the timpani get a channel with the usual seat of their role; the existing channels stay
        MixChannel channel = session.mix().channelOf(MixChannel.Owner.part(id));
        assertNotNull(channel);
        assertEquals(mixBefore.channels().size() + 1, session.mix().channels().size());
        assertTrue(session.mix().channels().containsAll(mixBefore.channels()));
        assertEquals(session.mix(), com.aresstack.audio.studio.project.MixJson.load(piece));
        List<MusicTrack> tracks = new TrackArrangementStore().load(piece).tracks();
        assertEquals(tracksBefore + 1, tracks.size());
        assertEquals(id, tracks.get(tracks.size() - 1).id());
        assertEquals(47, tracks.get(tracks.size() - 1).program());
        SpatialScene stage = new SpatialSceneStore().load(piece);
        assertEquals(channel.x(), stage.placement(id).x(), 1e-3);
        assertEquals(stageBefore.placements().size() + 1, stage.placements().size());
        assertFalse("our own writes are no external change", session.externalChanges());

        // undo takes them out of mix and old files again, redo brings them back
        session.undo();
        assertEquals(mixBefore, session.mix());
        assertEquals(tracksBefore, new TrackArrangementStore().load(piece).tracks().size());
        assertEquals(null, new SpatialSceneStore().load(piece).placement(id));
        assertFalse(session.externalChanges());
        session.redo();
        assertEquals(channel, session.mix().channelOf(MixChannel.Owner.part(id)));
        assertNotNull(new SpatialSceneStore().load(piece).placement(id));
    }

    @Test
    public void discardLeavesThePieceAndRegenerateMakesAnotherVersion() throws Exception {
        FakeChatModel model = composer();
        Composition before = session.composition();
        ContinuationRequest request = ContinuationRequest.of(8, ContinuationPreset.VARIATION_REPRISE,
                ContinuationEnding.LOOP);
        PieceExtension extension = PieceExtension.start(session, composerWith(model), request, Ids.seeded(1), QUIET);
        Composition first = extension.proposal().result();
        assertTrue(model.prompts().get(0).contains("seamless loop"));

        extension.discard();
        assertNull(extension.proposal());
        assertEquals(before, session.composition());

        extension.regenerate(QUIET);
        assertEquals(2, extension.attempt());
        Composition second = extension.proposal().result();
        assertNotEquals(first.notesOf("melody").stream().filter(n -> n.tick() >= ORIGINAL_END).map(n -> n.note().pitch())
                .toList(), second.notesOf("melody").stream().filter(n -> n.tick() >= ORIGINAL_END)
                .map(n -> n.note().pitch()).toList());
        // the same attempt is not asked twice: its answers are in the cache of the piece
        int asked = model.prompts().size();
        assertTrue(Files.isDirectory(piece.resolve(PieceExtension.CACHE)));
        extension.accept();
        try {
            extension.regenerate(QUIET);
            fail("accepted continuations are not composed again");
        } catch (IllegalStateException expected) {
            assertEquals(asked, model.prompts().size());
        }
    }

    @Test
    public void anAnswerForAnotherPartIsRefusedAndNothingIsProposed() throws Exception {
        FakeChatModel model = model((instruction, prompt) -> instruction.equals(ChatContinuationPlanner.INSTRUCTION)
                ? null : "{\"part\":\"harmony\",\"section\":\"s1\",\"notes\":[]}");
        ContinuationRequest request = ContinuationRequest.of(8, ContinuationPreset.STANDALONE, ContinuationEnding.OPEN)
                .forParts(List.of("melody"));
        try {
            PieceExtension.start(session, composerWith(model), request, Ids.seeded(2), QUIET);
            fail("an answer for another part is refused");
        } catch (ForeignPartException expected) {
            assertTrue(expected.getMessage().contains("harmony"));
        }
        assertTrue(session.pending().isEmpty());
    }

    @Test
    public void onlyTheChosenPartsGoOn() throws Exception {
        FakeChatModel model = composer();
        Composition before = session.composition();
        ContinuationRequest request = ContinuationRequest.of(16, ContinuationPreset.STANDALONE, ContinuationEnding.CLOSED)
                .forParts(List.of("melody", "bass"));
        PieceExtension extension = PieceExtension.start(session, composerWith(model), request, Ids.seeded(4), QUIET);
        Composition after = extension.proposal().result();
        // the plan named all four parts; the ones that do not go on are left out and recorded
        assertTrue(extension.continuation().plan().repairs().stream().anyMatch(r -> r.contains("unbekannte Stimme harmony")));
        assertEquals(before.notesOf("harmony"), after.notesOf("harmony"));
        assertEquals(before.notesOf("drums"), after.notesOf("drums"));
        assertTrue(after.notesOf("bass").size() > before.notesOf("bass").size());
        // the edit core refuses the same patch with a part that does not go on
        Patch patch = extension.proposal().patch();
        EditScope narrower = new EditScope(false, Set.of("melody"), Set.of(), Set.of(), ORIGINAL_END, EditScope.END,
                AppendContinuation.kinds(false), false, 0);
        try {
            session.propose(patch, narrower, Revision.Author.AI, null);
            fail("the bass is not in this scope");
        } catch (EditRefusedException expected) {
            assertFalse(expected.violations().isEmpty());
        }
    }

    @Test
    public void aPlanThatMissesTheNewBarsIsRepairedAndRecorded() throws Exception {
        FakeChatModel model = model((instruction, prompt) -> instruction.equals(ChatContinuationPlanner.INSTRUCTION)
                ? "{\"sections\":[{\"name\":\"Lang\",\"bars\":20,\"intensity\":1.4,\"chords\":[\"C\",\"X9\"],"
                + "\"parts\":[\"melody\",\"cello\"]}],\"newParts\":[]}" : null);
        ContinuationRequest request = ContinuationRequest.of(12, ContinuationPreset.UNDERSCORE,
                ContinuationEnding.CLOSED);
        PieceExtension extension = PieceExtension.start(session, composerWith(model), request, Ids.seeded(6), QUIET);
        var plan = extension.continuation().plan();
        assertEquals(12, plan.bars());
        assertEquals(1, plan.sections().size());
        assertEquals(12, plan.sections().get(0).chords().size());
        assertEquals("C", plan.sections().get(0).chords().get(11)); // closes on the tonic of C major
        assertEquals(List.of("melody"), plan.sections().get(0).parts());
        assertTrue(plan.repairs().size() >= 4);
        assertEquals(20 * BAR, extension.proposal().result().endTick());
    }

    @Test
    public void theAiNeverAddressesExistingObjects() throws Exception {
        FakeChatModel model = composer();
        Composition before = session.composition();
        PieceExtension extension = PieceExtension.start(session, composerWith(model),
                ContinuationRequest.of(8, ContinuationPreset.UNDERSCORE, ContinuationEnding.OPEN), Ids.seeded(8), QUIET);
        Set<String> clipsBefore = new java.util.HashSet<>();
        before.tracks().forEach(t -> t.clips().forEach(c -> clipsBefore.add(c.id())));
        for (EditOp op : extension.proposal().patch().operations()) {
            assertTrue(op.toString(), op instanceof EditOp.AddClip || op instanceof EditOp.SetSections
                    || op instanceof EditOp.AddPart || op instanceof EditOp.AddTrack);
            if (op instanceof EditOp.AddClip add) {
                assertFalse(clipsBefore.contains(add.clip().id()));
            }
        }
    }
}
