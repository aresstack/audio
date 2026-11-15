package com.aresstack.audio.studio.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.studio.ai.ModelAssignment;
import com.aresstack.audio.studio.ai.ModelRole;
import com.aresstack.audio.studio.ai.ModelRoles;
import com.aresstack.audio.studio.application.ModelsUsed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** model.planning and model.composition in studio.properties, with model as the base of before. */
public final class ModelSettingsTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void settingsOfBeforeMeanOneModelForEverything() {
        Properties old = new Properties();
        old.setProperty("model", "my-model");
        ModelAssignment read = ModelSettings.read(old);
        for (ModelRole role : ModelRoles.all()) {
            assertEquals("my-model", read.modelFor(role));
        }
        assertEquals(AiDefaults.MODEL, ModelSettings.read(new Properties()).modelFor(ModelRoles.PLANNING));
    }

    @Test
    public void rolesAreReadWrittenAndKeptOverASave() throws Exception {
        Properties settings = new Properties();
        settings.setProperty("model", AiDefaults.MODEL);
        settings.setProperty("model.planning", "gpt-oss:20b");
        settings.setProperty("model.composition", " ");
        ModelAssignment read = ModelSettings.read(settings);
        assertEquals("gpt-oss:20b", read.modelFor(ModelRoles.PLANNING));
        assertEquals(AiDefaults.MODEL, read.modelFor(ModelRoles.COMPOSITION));

        StudioSettingsStore store = new StudioSettingsStore(folder.getRoot().toPath().resolve("studio.properties"));
        Properties saved = new Properties();
        ModelSettings.write(saved, read.with(ModelRoles.MIX, "mixer"));
        store.save(saved);
        Properties again = store.load();
        assertEquals(AiDefaults.MODEL, again.getProperty("model"));
        assertEquals("gpt-oss:20b", again.getProperty("model.planning"));
        assertEquals("mixer", again.getProperty("model.mix"));
        assertFalse("a role that follows has no entry", again.containsKey("model.composition"));
        assertEquals(read.with(ModelRoles.MIX, "mixer"), ModelSettings.read(again));

        ModelSettings.write(again, ModelSettings.read(again).with(ModelRoles.MIX, null));
        assertNull(again.getProperty("model.mix"));
    }

    @Test
    public void theModelsOfAProductionAreWrittenDownInThePiece() throws Exception {
        Path piece = folder.newFolder("stueck").toPath();
        ModelsUsed.record(piece, "Komposition",
                ModelAssignment.single(AiDefaults.MODEL).with(ModelRoles.PLANNING, "gpt-oss:20b"));
        String json = Files.readString(piece.resolve(ModelsUsed.FILE_NAME));
        assertTrue(json, json.contains("\"planning\": \"gpt-oss:20b\""));
        assertTrue(json, json.contains("\"composition\": \"" + AiDefaults.MODEL + "\""));
    }
}
