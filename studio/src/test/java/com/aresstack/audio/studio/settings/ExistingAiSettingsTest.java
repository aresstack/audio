package com.aresstack.audio.studio.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Ensure the settings of an existing game are suggestions, not hard dependencies. */
public final class ExistingAiSettingsTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void importsGameEndpointModelAndKeepassReferenceWithoutImportingSecrets() throws Exception {
        String original = System.getProperty("user.home");
        Path home = folder.getRoot().toPath();
        Path directory = Files.createDirectories(home.resolve(".3d4j"));
        Files.write(directory.resolve("generator.properties"),
                ("chat.baseUrl=https://kipitz.example/v1\n"
                + "chat.model=google/gemma-4-31B-it\n"
                + "chat.apiKeyRef=keepass:KIPITZ\n").getBytes("UTF-8"));
        try {
            System.setProperty("user.home", home.toString());
            Properties result = ExistingAiSettings.withDefaults(new Properties());
            Assert.assertEquals("https://kipitz.example/v1/chat/completions",
                    result.getProperty("endpoint"));
            Assert.assertEquals("google/gemma-4-31B-it", result.getProperty("model"));
            Assert.assertEquals("KIPITZ", result.getProperty("keepassEntry"));
            Assert.assertNull(result.getProperty("apiKey"));
        } finally {
            if (original == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", original);
            }
        }
    }

    @Test
    public void keepsAudioOverridesInsteadOfReplacingThem() {
        Properties own = new Properties();
        own.setProperty("endpoint", "https://audio.example/v1/chat/completions");
        own.setProperty("model", "local-model");
        own.setProperty("keepassEntry", "Audio entry");
        Properties result = ExistingAiSettings.withDefaults(own);
        Assert.assertEquals("local-model", result.getProperty("model"));
        Assert.assertEquals("Audio entry", result.getProperty("keepassEntry"));
    }

    @Test
    public void rejectsInvalidBaseUrl() {
        Assert.assertNull(ExistingAiSettings.completionsEndpoint("not a URI"));
    }
}
