package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.MixValidator;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The AI proposes movements on the stage in a request of its own, only when the user switched it
 * on: what it is told, what is kept of its answer, and how a taken proposal reaches the mix
 * document. No real model is asked: the answers are written here.
 */
public final class MovementDirectorTest {
    private static final long BAR = 1920;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path piece;

    /** Answers with what it was given, keeps it in the cache file as the real client does, remembers prompts. */
    private static final class Answering implements ChatModel {
        final List<String> prompts = new ArrayList<>();
        private final String answer;

        Answering(String answer) {
            this.answer = answer;
        }

        @Override
        public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException {
            prompts.add(prompt);
            Files.write(cache, answer.getBytes(StandardCharsets.UTF_8));
            return JsonParser.parseString(answer).getAsJsonObject();
        }
    }

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        piece = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, piece.resolve(file.getFileName()));
            }
        }
    }

    @Test
    public void theSwitchIsOffUnlessTheUserSetIt() {
        Properties settings = new Properties();
        assertFalse("off by default", MovementDirector.wanted(settings));
        MovementDirector.into(settings, true);
        assertTrue(MovementDirector.wanted(settings));
        MovementDirector.into(settings, false);
        assertFalse(MovementDirector.wanted(settings));
        // A role of its own for the model, which follows the mix until the user gives it another.
        assertEquals(com.aresstack.audio.studio.ai.ModelRoles.MIX.key(),
                com.aresstack.audio.studio.ai.ModelRoles.byKey("movements").fallback());
    }

    @Test
    public void theAiIsToldWhereTracksStandAndWhatIsFixedAndOnlyWhatMayMoveIsKept() throws Exception {
        Path arrangement = piece.resolve("arrangement.tsv");
        SpatialScene stage = new SpatialSceneStore().load(piece).fitting(StageMixService.tracks(arrangement));
        assertTrue("the melody of this piece is protected", stage.placement("melody").locked());
        PieceModel model = new PieceModel(line -> { });
        model.open(piece);
        Set<String> movable = MovementDirector.movable(stage, model.movements(), MovementDirector.of(arrangement));
        assertFalse(movable.contains("melody"));
        assertTrue(movable.contains("harmony"));
        int bars = MovementDirector.bars(arrangement);

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            many.append(i == 0 ? "" : ",").append("{\"bar\":").append(i * 0.5).append(",\"value\":0.").append(i).append('}');
        }
        Answering ai = new Answering("{\"movements\":["
                // kept: out of range values and bars are brought within, the points put in order
                + "{\"track\":\"harmony\",\"parameter\":\"x\",\"points\":[{\"bar\":99,\"value\":1.7},"
                + "{\"bar\":0,\"value\":-0.45},{\"bar\":\"four\",\"value\":0},{\"bar\":4}]},"
                // a second curve of the same kind: the first counts
                + "{\"track\":\"harmony\",\"parameter\":\"side\",\"points\":[{\"bar\":0,\"value\":0},{\"bar\":2,\"value\":0}]},"
                // a word for a parameter the model might use
                + "{\"track\":\"harmony\",\"parameter\":\"elevation\",\"points\":[{\"bar\":2,\"value\":0},{\"bar\":6,\"value\":-3}]},"
                // the user protected the melody
                + "{\"track\":\"melody\",\"parameter\":\"x\",\"points\":[{\"bar\":0,\"value\":0},{\"bar\":4,\"value\":1}]},"
                // the level is the mix proposal's, not this one's
                + "{\"track\":\"bass\",\"parameter\":\"level\",\"points\":[{\"bar\":0,\"value\":0},{\"bar\":4,\"value\":1}]},"
                // one point does not move
                + "{\"track\":\"bass\",\"parameter\":\"depth\",\"points\":[{\"bar\":0,\"value\":0.4}]},"
                // too many points: the first eight stay
                + "{\"track\":\"bass\",\"parameter\":\"reverb\",\"points\":[" + many + "]},"
                + "{\"track\":\"choir\",\"parameter\":\"x\",\"points\":[{\"bar\":0,\"value\":0},{\"bar\":4,\"value\":1}]},"
                + "\"nonsense\"]}");

        MovementDirector.Proposal proposal = new MovementDirector(ai, 8192)
                .propose("A quiet morning", MovementDirector.of(arrangement), stage, movable, piece, message -> { });

        assertEquals(1, ai.prompts.size());
        String prompt = ai.prompts.get(0);
        assertTrue(prompt.contains("A quiet morning"));
        assertTrue(prompt.contains("108 BPM"));
        assertTrue(prompt.contains(bars + " bars"));
        assertTrue("where it stands", prompt.contains("- harmony (harmony): notes per bar"));
        assertTrue(prompt.contains("x=-0.45, depth=0.48, reverb=0.40, height=0.00"));
        assertTrue(prompt.contains("melody (melody)") && prompt.contains("FIXED by the user: it does not move"));
        assertFalse(prompt.substring(prompt.indexOf("- harmony"), prompt.indexOf("- bass")).contains("FIXED"));
        assertTrue(prompt.contains("\"movements\"") && prompt.contains("At most 8 points"));
        assertTrue("the answer is kept to be read", Files.isRegularFile(piece.resolve(MovementDirector.FILE_NAME)));

        assertEquals(Set.of("harmony", "bass"), proposal.curves().keySet());
        assertEquals(List.of(new StageMovements.Point(0, -0.45), new StageMovements.Point(bars, 1)),
                proposal.of("harmony", AutomationLane.X));
        assertEquals(List.of(new StageMovements.Point(2, 0), new StageMovements.Point(6, 0)),
                proposal.of("harmony", AutomationLane.HEIGHT));
        assertFalse(proposal.has("melody", AutomationLane.X));
        assertFalse(proposal.has("bass", AutomationLane.LEVEL));
        assertFalse(proposal.has("bass", AutomationLane.DEPTH));
        assertEquals(MovementDirector.MAX_POINTS, proposal.of("bass", AutomationLane.REVERB).size());
        assertEquals(3.5, proposal.of("bass", AutomationLane.REVERB).get(7).bar(), 0);
        assertEquals(2, proposal.tracks());
    }

    @Test
    public void withoutATrackThatMayMoveNoOneIsAskedAndABrokenAnswerIsSaid() throws Exception {
        Path arrangement = piece.resolve("arrangement.tsv");
        Answering ai = new Answering("{\"stage\":[]}");
        MovementDirector director = new MovementDirector(ai, 4096);
        assertTrue(director.propose("x", MovementDirector.of(arrangement), null, Set.of(), piece, message -> { }).empty());
        assertTrue("no request when nothing may move", ai.prompts.isEmpty());
        try {
            director.propose("x", MovementDirector.of(arrangement), null, Set.of("harmony"), piece, message -> { });
            fail("an answer without movements is not a proposal");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("invalid movement proposal"));
        }
        assertTrue("an empty list is a fine answer", new MovementDirector(new Answering("{\"movements\":[]}"), 4096)
                .propose("x", MovementDirector.of(arrangement), null, Set.of("harmony"), piece, message -> { }).empty());
    }

    @Test
    public void onlyCurvesOfTracksThatMayMoveAreTakenInTicks() throws Exception {
        PieceModel model = new PieceModel(line -> { });
        model.open(piece);
        SpatialScene stage = new SpatialSceneStore().load(piece).fitting(StageMixService.tracks(piece.resolve("arrangement.tsv")));
        MovementDirector.Proposal proposal = new MovementDirector.Proposal(Map.of(
                "harmony", Map.of(AutomationLane.X, List.of(new StageMovements.Point(0, -0.45),
                        new StageMovements.Point(4, 0.2))),
                // protected by the user after the proposal came: it stays
                "melody", Map.of(AutomationLane.X, List.of(new StageMovements.Point(0, 0),
                        new StageMovements.Point(4, 1))),
                "choir", Map.of(AutomationLane.X, List.of(new StageMovements.Point(0, 0),
                        new StageMovements.Point(4, 1)))));
        List<ProjectSession.Curve> curves = MixProposalTaking.curves(model.movements(), proposal, stage);
        assertEquals(List.of(new ProjectSession.Curve("harmony", AutomationLane.X,
                List.of(new AutomationLane.Point(0, -0.45), new AutomationLane.Point(4 * BAR, 0.2)))), curves);
        assertTrue(MixProposalTaking.curves(model.movements(), MovementDirector.Proposal.NONE, stage).isEmpty());
    }

    @Test
    public void aPieceWithAModelIsToldFromTheModelNotFromTheFile() throws Exception {
        PieceModel model = new PieceModel(line -> { });
        model.open(piece);
        com.aresstack.audio.composition.Composition composition = model.session().orElseThrow().composition();
        MovementDirector.Piece fromModel = MovementDirector.of(composition);
        MovementDirector.Piece fromFile = MovementDirector.of(piece.resolve("arrangement.tsv"));
        assertEquals(108, fromModel.tempo());
        assertEquals("the same parts and the same notes per bar", fromFile.tracks(), fromModel.tracks());
        assertEquals(fromFile.bars(), fromModel.bars());
        assertTrue(fromModel.meterAndKey().startsWith("4/4"));

        // The file gone: the request still knows the piece.
        Files.delete(piece.resolve("arrangement.tsv"));
        Answering ai = new Answering("{\"movements\":[]}");
        new MovementDirector(ai, 4096).propose("x", MovementDirector.of(composition), null, Set.of("harmony"),
                piece, message -> { });
        assertTrue(ai.prompts.get(0).contains("- harmony (harmony): notes per bar"));
    }

    @Test
    public void theMixRequestStaysAsItWas() throws Exception {
        Path arrangement = piece.resolve("arrangement.tsv");
        String prompt = MixDirector.prompt("A quiet morning", StageMixService.tracks(arrangement), arrangement, null);
        assertFalse("movements are a request of their own", prompt.contains("movements"));
        assertFalse(prompt.contains("height"));
    }

    @Test
    public void theCurvesAreSeenWhileTheAnswerArrives() {
        java.util.Set<String> movable = new java.util.LinkedHashSet<String>(java.util.List.of("harmony", "bass"));
        String answer = "{\"movements\":[{\"track\":\"harmony\",\"parameter\":\"x\",\"points\":[{\"bar\":0,\"value\":-0.4},"
                + "{\"bar\":4,\"value\":0.4}]},{\"track\":\"bass\",\"parameter\":\"depth\",\"points\":[{\"bar\":0,"
                + "\"value\":0.2},{\"bar\":6,\"value\":0.8}]}]}";

        org.junit.Assert.assertNull(MovementDirector.seenMoves("", movable, 8));
        int tracks = 0;
        for (int cut = 0; cut <= answer.length(); cut++) {
            MovementDirector.Proposal seen = MovementDirector.seenMoves(answer.substring(0, cut), movable, 8);
            int now = seen == null ? 0 : seen.tracks();
            assertTrue("curves only come, at cut " + cut, now >= tracks);
            tracks = now;
        }
        assertEquals(2, tracks);
        MovementDirector.Proposal first = MovementDirector.seenMoves(
                answer.substring(0, answer.indexOf("{\"track\":\"bass\"")), movable, 8);
        assertEquals(1, first.tracks());
        assertEquals(2, first.of("harmony", "x").size());
    }
}
