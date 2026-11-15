package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.aresstack.audio.studio.ai.ModelAssignment;
import com.aresstack.audio.studio.ai.ModelRoles;
import com.aresstack.audio.studio.settings.AiDefaults;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** The two choices next to the prompt: Gemma by default, a choice is a model of its own for the role. */
public final class ModelChoicePanelTest {
    @Test
    public void gemmaIsTheDefaultAndAChoiceChangesOnlyItsRole() throws Exception {
        javax.swing.UIManager.put("defaultFont", new java.awt.Font("Dialog", java.awt.Font.PLAIN, 12));
        AtomicInteger changes = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        ModelChoicePanel[] panel = new ModelChoicePanel[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ModelChoicePanel(changes::incrementAndGet, loads::incrementAndGet);
            assertEquals(AiDefaults.MODEL, panel[0].choice(ModelRoles.PLANNING).getSelectedItem());
            assertEquals(AiDefaults.MODEL, panel[0].choice(ModelRoles.COMPOSITION).getSelectedItem());

            panel[0].setModels(List.of("gpt-oss:20b", AiDefaults.MODEL));
            assertEquals(0, changes.get());
            panel[0].choice(ModelRoles.PLANNING).setSelectedItem("gpt-oss:20b");
            ModelAssignment chosen = panel[0].assignment();
            assertEquals("gpt-oss:20b", chosen.modelFor(ModelRoles.PLANNING));
            assertEquals(AiDefaults.MODEL, chosen.modelFor(ModelRoles.COMPOSITION));
            assertEquals(1, changes.get());

            panel[0].choice(ModelRoles.PLANNING).setSelectedItem(AiDefaults.MODEL);
            assertNull("the base model again: the role follows it", panel[0].assignment().ownModel(ModelRoles.PLANNING));

            // Read from the settings: shown without counting as a change.
            panel[0].setAssignment(ModelAssignment.single(AiDefaults.MODEL).with(ModelRoles.COMPOSITION, "gpt-oss:20b"));
            assertEquals("gpt-oss:20b", panel[0].choice(ModelRoles.COMPOSITION).getSelectedItem());
            assertEquals(2, changes.get());
        });
    }
}
