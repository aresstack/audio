package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelAssignment;
import com.aresstack.audio.studio.ai.ModelLineup;
import com.aresstack.audio.studio.ai.ModelRoles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The plan comes from the planning model, the notes from the composition model. */
public final class RoleModelsCompositionTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void planningAndCompositionAreAskedOfTheirOwnModels() throws Exception {
        Map<String, FakeChatModel> models = new HashMap<>();
        ModelAssignment assignment = ModelAssignment.single("gemma").with(ModelRoles.PLANNING, "gpt-oss");
        ModelLineup lineup = new ModelLineup(assignment, name -> models.computeIfAbsent(name,
                n -> new FakeChatModel("C", "G", "Am", "F", "C", "G", "F", "G")));
        Path dir = folder.newFolder("rollen").toPath();
        CompositionSpec spec = CompositionSpec.of(8, 100, Key.parse("C-Dur"), 4, 4);

        Path tsv = new GemmaMusicComposer(lineup, 4096, 4).compose("Weihnacht", dir, TrackArrangement.defaults(),
                spec, message -> { });

        assertTrue(Files.isRegularFile(tsv));
        FakeChatModel planner = models.get("gpt-oss");
        FakeChatModel composer = models.get("gemma");
        assertEquals(1, planner.prompts.size());
        assertTrue(planner.prompts.get(0).contains("No notes yet"));
        assertEquals(TrackArrangement.defaults().tracks().size(), composer.prompts.size());
        assertTrue(composer.prompts.stream().noneMatch(p -> p.contains("No notes yet")));
    }

    @Test
    public void oneModelForBothRolesIsTheSameAsBefore() throws Exception {
        FakeChatModel one = new FakeChatModel("C", "G", "Am", "F", "C", "G", "F", "G");
        Path dir = folder.newFolder("eins").toPath();
        new GemmaMusicComposer(ModelLineup.of(one), 4096, 4).compose("x", dir, TrackArrangement.defaults(),
                message -> { });
        assertEquals(1 + TrackArrangement.defaults().tracks().size(), one.prompts.size());
    }

    @Test
    public void proposalAndMixTakeAnyChatModel() {
        ChatModel any = ModelLineup.of(new FakeChatModel("C")).model(ModelRoles.INSTRUMENTS);
        new InstrumentProposalService(any, 4096);
        new MixDirector(any, 4096);
    }
}
