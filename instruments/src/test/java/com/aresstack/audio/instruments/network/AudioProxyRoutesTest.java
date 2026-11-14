package com.aresstack.audio.instruments.network;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Test;

/** The proxy routing guarantees, checked without contacting the network. */
public final class AudioProxyRoutesTest {
    @Test
    public void respectsManualProxyForExternalHttpsTargets() throws Exception {
        Properties settings = new Properties();
        settings.setProperty("proxyMode", "MANUAL_PROXY");
        settings.setProperty("proxyHost", "proxy.example.invalid");
        settings.setProperty("proxyPort", "3128");
        AudioProxyRoutes routes = new AudioProxyRoutes(settings);

        Proxy result = routes.proxyFor(URI.create("https://example.com/v1/chat/completions"));

        Assert.assertEquals(Proxy.Type.HTTP, result.type());
        InetSocketAddress proxyAddress = (InetSocketAddress) result.address();
        Assert.assertEquals("proxy.example.invalid", proxyAddress.getHostString());
        Assert.assertEquals(3128, proxyAddress.getPort());
    }

    @Test
    public void sendsLoopbackDirectEvenWhenManualProxyIsEnabled() throws Exception {
        Properties settings = new Properties();
        settings.setProperty("proxyMode", "MANUAL_PROXY");
        settings.setProperty("proxyHost", "proxy.example.invalid");
        settings.setProperty("proxyPort", "3128");
        AudioProxyRoutes routes = new AudioProxyRoutes(settings);

        Assert.assertSame(Proxy.NO_PROXY, routes.proxyFor(
                URI.create("http://127.0.0.1:11434/api/chat")));
    }

    @Test
    public void handlesNoProxyPatternsFromGameConfiguration() throws Exception {
        Properties settings = new Properties();
        settings.setProperty("proxyMode", "MANUAL_PROXY");
        settings.setProperty("proxyHost", "proxy.example.invalid");
        settings.setProperty("proxyPort", "3128");
        settings.setProperty("proxyNonProxyHosts", "*.internal.example");
        AudioProxyRoutes routes = new AudioProxyRoutes(settings);

        Assert.assertSame(Proxy.NO_PROXY, routes.proxyFor(
                URI.create("https://ki.internal.example/v1/chat/completions")));
    }

    @Test
    public void explicitlyDisabledProxyDoesNotResolvePac() throws Exception {
        Properties settings = new Properties();
        settings.setProperty("proxyMode", "DISABLED");
        AudioProxyRoutes routes = new AudioProxyRoutes(settings);

        Assert.assertEquals("DISABLED", routes.describe());
        Assert.assertSame(Proxy.NO_PROXY, routes.proxyFor(
                URI.create("https://example.com/")));
    }

    @Test(expected = java.io.IOException.class)
    public void invalidProxyModeDoesNotFallBackToDirectNetwork() throws Exception {
        Properties settings = new Properties();
        settings.setProperty("proxyMode", "INVALID_MODE");
        new AudioProxyRoutes(settings).proxyFor(URI.create("https://example.com/"));
    }

    @Test
    public void readsNoProxySettingsOfOtherPrograms() throws Exception {
        java.nio.file.Path home = java.nio.file.Files.createTempDirectory("audio-proxy-home-");
        java.nio.file.Path game = java.nio.file.Files.createDirectories(home.resolve(".3d4j"));
        java.nio.file.Files.write(game.resolve("generator.properties"),
                ("network.proxy.mode=MANUAL_PROXY\nnetwork.proxy.host=proxy.of.the.game.invalid\n"
                        + "network.proxy.port=3128\nnetwork.proxy.nonProxyHosts=*.game.invalid\n").getBytes("UTF-8"));
        String before = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
            // Nothing chosen: the studio's own default, whatever 3d4j has set for itself.
            Assert.assertEquals(windows ? "PAC_URL_POWERSHELL" : "DISABLED",
                    new AudioProxyRoutes(new Properties()).describe());
            Assert.assertNull(AudioProxyRoutes.basicCredentialReference(new Properties()));
        } finally {
            System.setProperty("user.home", before);
        }
    }
}
