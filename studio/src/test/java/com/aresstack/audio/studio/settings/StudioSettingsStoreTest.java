package com.aresstack.audio.studio.settings;

import java.nio.file.Path;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class StudioSettingsStoreTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void persistsAndReloadsEndpointApiKeyAndProxy() throws Exception {
        Path path = folder.getRoot().toPath().resolve(".aresstack/audio/studio.properties");
        StudioSettingsStore store = new StudioSettingsStore(path);
        Properties initial = new Properties();
        initial.setProperty("endpoint", "https://example.invalid/v1/chat/completions");
        initial.setProperty("apiKey", "local-secret-test-value");
        initial.setProperty("model", "google/gemma-4-31B-it");
        initial.setProperty("proxyHost", "proxy.local");
        initial.setProperty("proxyPort", "8080");
        store.save(initial);
        Assert.assertEquals("local-secret-test-value", store.load().getProperty("apiKey"));
        Assert.assertEquals("proxy.local", store.load().getProperty("proxyHost"));
        Assert.assertEquals(path, store.location());
    }
}
