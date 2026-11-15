package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.composition.edit.ScopeProof;
import com.aresstack.audio.studio.ai.ChatOrchestrationPlanner;
import com.aresstack.audio.studio.ai.Dramaturgy;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.ai.IntensityCurve;
import com.aresstack.audio.studio.ai.OrchestrationComposer;
import com.aresstack.audio.studio.ai.OrchestrationPlan;
import com.aresstack.audio.studio.ai.OrchestrationPreset;
import com.aresstack.audio.studio.ai.ReorchestratedRegion;
import com.aresstack.audio.studio.ai.ReorchestrationRequest;
import com.aresstack.audio.studio.progress.Progress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package C4, {@code REORCHESTRATE} (architecture review E4: chosen parts × passage, new parts
 * allowed, the planner redistributes the existing material) and the intensity curve of the form
 * (report 10.1): the material of bars 3-6 changes hands, a new instrument may join, everything
 * outside the passage stays provably as it was; a curve set on the sections decides the
 * instrumentation of a re-orchestration after it.
 */
public final class ReorchestrationTest {
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

    /** The frame ids a planning prompt lists. */
    static List<String> frames(String prompt) {
        Matcher m = Pattern.compile("\n  frame (f\\d+) ").matcher(prompt);
        List<String> ids = new java.util.ArrayList<>();
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /**
     * A model: the plan is made by {@code planner} from the frame ids; the generators write two
     * notes per bar, pitch after the part.
     */
    static FakeChatModel model(Function<List<String>, String> planner) {
        return new FakeChatModel((instruction, prompt) -> {
            if (instruction.equals(ChatOrchestrationPlanner.INSTRUCTION)) {
                return planner.apply(frames(prompt));
            }
            String part = FakeChatModel.quoted(prompt, "part");
            String section = FakeChatModel.quoted(prompt, "section");
            int bars = Integer.parseInt(prompt.substring(prompt.lastIndexOf("up to ") + 6).split(";")[0].trim());
            int pitch = switch (part) {
                case "melody" -> 76;
                case "harmony" -> 64;
                case "bass" -> 43;
                case "drums" -> 42;
                default -> 55;
            };
            StringBuilder notes = new StringBuilder();
            for (int bar = 1; bar <= bars; bar++) {
                notes.append(notes.length() == 0 ? "" : ",").append("{\"bar\":").append(bar)
                        .append(",\"beat\":0,\"duration\":2,\"pitch\":").append(pitch).append(",\"velocity\":70},")
                        .append("{\"bar\":").append(bar).append(",\"beat\":2,\"duration\":2,\"pitch\":")
                        .append(pitch + 3).append(",\"velocity\":65}");
            }
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":[" + notes + "]}";
        });
    }

    /** The melody hands its material to the harmony part, strings join, the bass keeps its line, the rest rests. */
    static String redistribute(List<String> frames) {
        StringBuilder text = new StringBuilder("{\"frames\":[");
        for (int i = 0; i < frames.size(); i++) {
            text.append(i == 0 ? "" : ",").append("{\"frame\":\"").append(frames.get(i)).append("\",\"assignments\":["
                    + "{\"part\":\"harmony\",\"job\":\"the melody an octave lower\",\"source\":\"melody\"},"
                    + "{\"part\":\"bass\",\"job\":\"its bass line\",\"source\":\"bass\"},"
                    + "{\"part\":\"strings\",\"job\":\"sustained chords\",\"source\":\"harmony\"},"
                    + "{\"part\":\"cello\",\"job\":\"unknown\"}]}");
        }
        return text.append("],\"newParts\":[{\"key\":\"strings\",\"name\":\"Streicher\",\"instrument\":\"String "
                + "Ensemble\",\"program\":48,\"role\":\"harmony\",\"idea\":\"carry the harmony\"}],"
                + "\"idea\":\"the melody sinks into the middle voice\"}").toString();
    }

    @Test
    public void barsThreeToSixChangeHandsAndEverythingElseStays() throws Exception {
        FakeChatModel model = model(ReorchestrationTest::redistribute);
        Composition before = session.composition();
        long from = 2 * BAR;
        long to = 6 * BAR;
        ReorchestrationRequest request = ReorchestrationRequest.of(3, 6, OrchestrationPreset.FULLER);
        assertEquals(2, request.newParts());

        Reorchestration work = Reorchestration.start(session, OrchestrationComposer.with(model), request,
                Ids.seeded(31), QUIET);

        ReorchestratedRegion region = work.region();
        int frameCount = region.task().frames().size();
        // one plan, then for every frame the three parts that play
        assertEquals(1 + 3 * frameCount, model.prompts().size());
        String planning = model.prompts().get(0);
        assertTrue(planning.contains("Re-orchestrate bars 3-6 of this piece"));
        assertTrue(planning.contains("re-orchestrate fuller, towards a tutti".substring(3)));
        assertTrue(planning.contains("You may bring in up to 2 NEW instruments"));
        // the generator of the harmony part sees the material, its job and its source
        String harmony = model.prompts().stream().filter(p -> p.contains("\"part\":\"harmony\"")).findFirst()
                .orElseThrow();
        assertTrue(harmony.contains("# MATERIAL OF THESE BARS AS IT IS NOW"));
        assertTrue(harmony.contains("Job: the melody an octave lower (take over the material of part melody"));
        assertTrue(harmony.contains("\n  3 0 ")); // notes of bar 3 as material
        assertTrue(harmony.contains("Dramaturgy for intensity "));
        // the plan was repaired and recorded: unknown cello dropped; the strings renamed
        OrchestrationPlan plan = region.plan();
        assertTrue(plan.repairs().stream().anyMatch(r -> r.contains("unbekannte Stimme cello")));
        assertEquals(List.of("new1"), plan.newParts().stream().map(p -> p.key()).toList());
        assertEquals(1, region.newParts().size());
        Part strings = region.newParts().get(0);
        assertEquals("Streicher", strings.name());

        Proposal proposal = work.proposal();
        Composition after = proposal.result();
        assertEquals(List.of(), CompositionValidator.validate(after));
        assertTrue(proposal.runId().startsWith("reorchestrate/1/"));
        // outside bars 3-6 nothing changed, for any part; form and time stay
        Set<String> chosen = Set.of("melody", "harmony", "bass", "drums");
        assertEquals(List.of(), ReorchestrateRegion.outsideKept(before, after, from, to, chosen,
                Set.of(strings.id())));
        assertEquals(ScopeProof.hashOutside(before, proposal.scope()),
                ScopeProof.hashOutside(after, proposal.scope(), before));
        assertEquals(before.sections(), after.sections());
        // inside: the melody and the drums rest, the harmony plays the new line, the strings join
        assertTrue(begins(after, "melody", from, to).isEmpty());
        assertTrue(begins(after, "drums", from, to).isEmpty());
        List<PlacedNote> middle = begins(after, "harmony", from, to);
        assertEquals(2 * 4, middle.size());
        assertTrue(middle.stream().allMatch(n -> n.note().pitch() == 64 || n.note().pitch() == 67));
        assertTrue(middle.stream().allMatch(n -> n.endTick() <= to));
        List<PlacedNote> added = after.notesOf(strings.id());
        assertEquals(2 * 4, added.size());
        assertTrue(added.stream().allMatch(n -> n.tick() >= from && n.endTick() <= to));
        // a note that rang into the passage from before stays
        for (Part part : before.parts()) {
            for (PlacedNote held : before.notesOf(part.id())) {
                if (held.tick() < from && held.endTick() > from) {
                    assertTrue(after.notesOf(part.id()).stream().anyMatch(n -> n.note().equals(held.note())));
                }
            }
        }
        // the edit core refuses the same patch in a scope without the bass
        EditScope narrower = new EditScope(false, Set.of("melody", "harmony", "drums", strings.id()),
                proposal.scope().trackIds(), Set.of(), from, to, ReorchestrateRegion.KINDS, false, 0);
        try {
            session.propose(proposal.patch(), narrower, Revision.Author.AI, null);
            fail("the bass is outside this scope");
        } catch (EditRefusedException expected) {
            assertFalse(expected.violations().isEmpty());
        }

        ChangeSet changes = work.accept();
        assertTrue(changes.partsToRender().containsAll(Set.of("melody", "harmony", strings.id())));
        assertFalse(changes.partsToRender().contains("bass") && !changesBass(before, after));
        assertNotNull(session.mix().channelOf(com.aresstack.audio.composition.mix.MixChannel.Owner.part(strings.id())));
        session.undo();
        assertEquals(before, session.composition());
    }

    @Test
    public void onlyTheChosenPartsAreTouched() throws Exception {
        Composition before = session.composition();
        FakeChatModel model = model(frames -> "{\"frames\":[]}");
        Reorchestration work = Reorchestration.start(session, OrchestrationComposer.with(model),
                ReorchestrationRequest.of(5, 8, OrchestrationPreset.REDISTRIBUTE).forParts(List.of("melody", "bass")),
                Ids.seeded(32), QUIET);
        Composition after = work.proposal().result();
        assertEquals(before.notesOf("harmony"), after.notesOf("harmony"));
        assertEquals(before.notesOf("drums"), after.notesOf("drums"));
        // the unusable plan gave the plan of the intensity, recorded
        assertTrue(work.region().plan().repairs().get(0).contains("keinen brauchbaren Abschnitt"));
        for (OrchestrationPlan.FramePlan frame : work.region().plan().frames()) {
            for (OrchestrationPlan.Assignment a : frame.assignments()) {
                assertTrue(Set.of("melody", "bass").contains(a.partKey()));
            }
        }
        for (EditOp op : work.proposal().patch().operations()) {
            assertFalse(op.toString(), op instanceof EditOp.AddPart || op instanceof EditOp.SetSections
                    || op instanceof EditOp.ShiftTime);
        }
    }

    @Test
    public void theIntensityCurveOfTheFormDecidesTheInstrumentationOfARecomposition() throws Exception {
        Composition before = session.composition();
        assertFalse(before.sections().isEmpty());
        // set the curve: everything very quiet; a change of the form only, nothing to render
        Map<String, Double> quiet = new java.util.LinkedHashMap<>();
        before.sections().forEach(s -> quiet.put(s.id(), 0.1));
        ChangeSet set = IntensityEdit.apply(session, quiet);
        assertTrue(set.partsToRender().isEmpty());
        Composition curved = session.composition();
        assertTrue(curved.sections().stream().allMatch(s -> s.intensity() == 0.1));
        for (Part part : before.parts()) {
            assertEquals(before.notesOf(part.id()), curved.notesOf(part.id()));
        }
        // the edit core refuses the same scope for a change of a note
        try {
            var note = before.notesOf("melody").get(0);
            session.apply(new Patch("heimlich", List.of(new EditOp.ChangeNote(note.clip().id(),
                    new com.aresstack.audio.composition.Note(note.note().id(), note.note().tick(), note.note().length(),
                            note.note().pitch() + 1, note.note().velocity(), note.note().voiceId())))),
                    IntensityEdit.scope());
            fail("only the form may change");
        } catch (EditRefusedException expected) {
            assertFalse(expected.violations().isEmpty());
        }

        // re-orchestrating after the curve: at 0.1 only two of four parts play (melody and bass)
        FakeChatModel model = model(frames -> "{\"text\":\"no plan\"}");
        Reorchestration work = Reorchestration.start(session, OrchestrationComposer.with(model),
                ReorchestrationRequest.of(1, 8, OrchestrationPreset.FOLLOW_INTENSITY), Ids.seeded(33), QUIET);
        assertEquals(2, Dramaturgy.ensembleSize(0.1, 4));
        for (OrchestrationPlan.FramePlan frame : work.region().plan().frames()) {
            assertEquals(List.of("melody", "bass"), frame.assignments().stream().map(a -> a.partKey()).toList());
        }
        assertTrue(work.region().task().frames().stream().allMatch(f -> f.intensity() == 0.1));
        Composition after = work.proposal().result();
        assertTrue(begins(after, "drums", 0, 8 * BAR).isEmpty());
        assertTrue(begins(after, "harmony", 0, 8 * BAR).isEmpty());
        // planning prompt told the instrumentation the intensity asks for
        assertTrue(model.prompts().get(0).contains("intensity=0.1 (about 2 of 4 parts)"));

        // a curve over the bars of the request wins over the sections
        work.discard();
        Reorchestration loud = Reorchestration.start(session, OrchestrationComposer.with(model(f -> "{}")),
                ReorchestrationRequest.of(1, 8, OrchestrationPreset.FOLLOW_INTENSITY)
                        .withCurve(IntensityCurve.flat(1.0)), Ids.seeded(34), QUIET);
        for (OrchestrationPlan.FramePlan frame : loud.region().plan().frames()) {
            assertEquals(4, frame.assignments().size());
        }
    }

    @Test
    public void aCurveCanBeLaidOverBars() throws Exception {
        Composition before = session.composition();
        Section first = before.sections().get(0);
        int bars = before.timeMap().barsCovering(before.endTick());
        IntensityEdit.apply(session, 1, bars, IntensityCurve.flat(0.75));
        assertEquals(0.75, session.composition().sections().get(0).intensity(), 0);
        assertEquals(first.startTick(), session.composition().sections().get(0).startTick());
        session.undo();
        assertEquals(before, session.composition());
    }

    private static List<PlacedNote> begins(Composition piece, String partId, long from, long to) {
        return piece.notesOf(partId).stream().filter(n -> n.tick() >= from && n.tick() < to).toList();
    }

    private static boolean changesBass(Composition before, Composition after) {
        return !before.notesOf("bass").equals(after.notesOf("bass"));
    }
}
