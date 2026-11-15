package com.aresstack.audio.studio.ai;

import com.aresstack.audio.studio.settings.ModelSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Test;

/** Each role may let its model think more or less before it answers, kept like the choice of the model. */
public final class ThinkingPerRoleTest {
    @Test
    public void aRoleThatThinksOfItsOwnGetsAModelOfItsOwn() {
        ModelAssignment assignment = ModelAssignment.single("gemma").withEffort(ModelRoles.PLANNING, "high");
        List<String> made = new ArrayList<>();
        ModelLineup lineup = new ModelLineup(assignment, name -> {
            made.add(name);
            return new ModelLineupTestModel();
        });
        lineup.model(ModelRoles.PLANNING);
        lineup.model(ModelRoles.COMPOSITION);
        Assert.assertEquals(List.of("gemma#high", "gemma"), made);
        Assert.assertEquals("gemma", ModelLineup.modelIn("gemma#high"));
        Assert.assertEquals("high", ModelLineup.effortIn("gemma#high"));
        Assert.assertNull(ModelLineup.effortIn("gemma"));
    }

    @Test
    public void theWayOfThinkingIsKeptInTheSettings() {
        ModelAssignment assignment = ModelAssignment.single("gemma").withEffort(ModelRoles.PLANNING, "high")
                .withEffort(ModelRoles.COMPOSITION, "Standard");
        Properties settings = new Properties();
        ModelSettings.write(settings, assignment);
        Assert.assertEquals("high", settings.getProperty("thinking.planning"));
        Assert.assertNull(settings.getProperty("thinking.composition"));
        Assert.assertEquals(assignment, ModelSettings.read(settings));
    }

    /** A model that is never asked. */
    private static final class ModelLineupTestModel implements ChatModel {
        @Override
        public com.google.gson.JsonObject generate(String instruction, String prompt, int maxTokens,
                                                   java.nio.file.Path cache) {
            throw new UnsupportedOperationException();
        }
    }
}
