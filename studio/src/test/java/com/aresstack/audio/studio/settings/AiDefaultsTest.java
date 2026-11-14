package com.aresstack.audio.studio.settings;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The studio's defaults are its own: nothing is taken from the settings of another program. */
public final class AiDefaultsTest {
    @Test
    public void takesNothingFromTheSettingsOfOtherPrograms() throws Exception {
        Path home = Files.createTempDirectory("audio-defaults-home-");
        Path game = Files.createDirectories(home.resolve(".3d4j"));
        Files.write(game.resolve("generator.properties"),
                ("chat.baseUrl=https://ki.of.the.game.invalid/v1\nchat.model=some/other-model\n"
                        + "chat.maxTokens=24576\nchat.apiKeyRef=keepass:Game entry\n").getBytes("UTF-8"));
        Path enterprise = Files.createDirectories(home.resolve(".enterprise-ai-client"));
        Files.write(enterprise.resolve("enterprise-ai-client.properties"),
                "chat.baseUrl=https://ki.of.the.client.invalid/v1\n".getBytes("UTF-8"));
        String before = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            Properties result = AiDefaults.withDefaults(new Properties());
            Assert.assertNull("no address is suggested", result.getProperty("endpoint"));
            Assert.assertNull("no KeePass entry is suggested", result.getProperty("keepassEntry"));
            Assert.assertEquals(AiDefaults.MODEL, result.getProperty("model"));
            Assert.assertEquals(AiDefaults.MAX_TOKENS, AiDefaults.maxTokens(result));
        } finally {
            System.setProperty("user.home", before);
        }
    }

    @Test
    public void keepsWhatTheUserSet() {
        Properties own = new Properties();
        own.setProperty("endpoint", "https://own.example/v1/chat/completions");
        own.setProperty("model", "own/model");
        own.setProperty("maxTokens", "4096");
        Properties result = AiDefaults.withDefaults(own);
        Assert.assertEquals("https://own.example/v1/chat/completions", result.getProperty("endpoint"));
        Assert.assertEquals("own/model", result.getProperty("model"));
        Assert.assertEquals(4096, AiDefaults.maxTokens(result));
    }

    @Test
    public void answerLengthIsAtLeast256AndTheDefaultWhenMalformed() {
        Properties own = new Properties();
        own.setProperty("maxTokens", "12");
        Assert.assertEquals(256, AiDefaults.maxTokens(own));
        own.setProperty("maxTokens", "viele");
        Assert.assertEquals(16384, AiDefaults.maxTokens(own));
    }

    @Test
    public void completesABaseAddressAndRejectsWhatIsNone() {
        Assert.assertEquals("https://ki.example/v1/chat/completions",
                AiDefaults.completionsEndpoint("https://ki.example/v1/"));
        Assert.assertEquals("https://ki.example/v1/chat/completions",
                AiDefaults.completionsEndpoint("https://ki.example/v1/chat/completions"));
        Assert.assertNull(AiDefaults.completionsEndpoint("not a URI"));
    }
}
