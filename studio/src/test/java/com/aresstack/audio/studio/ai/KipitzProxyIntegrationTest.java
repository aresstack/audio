package com.aresstack.audio.studio.ai;

import com.aresstack.audio.instruments.network.AudioProxyRoutes;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Ensure KIPITZ calls take the configured HTTP proxy, not a silent direct route. */
public final class KipitzProxyIntegrationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void sendsCompletionsAndModelDiscoveryThroughManualProxy() throws Exception {
        final AtomicInteger completions = new AtomicInteger();
        final AtomicInteger models = new AtomicInteger();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            String target = exchange.getRequestURI().toString();
            String body;
            if (target.contains("/chat/completions")) {
                completions.incrementAndGet();
                body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                        + "\"{\\\"part\\\":\\\"melody\\\",\\\"program\\\":10,\\\"notes\\\":[]}\"}}]}";
            } else {
                models.incrementAndGet();
                body = "{\"data\":[{\"id\":\"google/gemma-4-31B-it\"}]}";
            }
            byte[] response = body.getBytes("UTF-8");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        proxy.start();
        try {
            Properties settings = new Properties();
            settings.setProperty("proxyMode", "MANUAL_PROXY");
            settings.setProperty("proxyHost", "127.0.0.1");
            settings.setProperty("proxyPort", String.valueOf(proxy.getAddress().getPort()));
            AudioProxyRoutes routes = new AudioProxyRoutes(settings);
            KipitzChatClient client = new KipitzChatClient(
                    "http://ki.example.invalid/v1/chat/completions",
                    "integration-test-only", "google/gemma-4-31B-it", routes);

            Path cache = folder.getRoot().toPath().resolve("melody.json");
            JsonObject response = client.generate("Return JSON", "Compose", 2048, cache);
            Assert.assertEquals("melody", response.get("part").getAsString());
            Assert.assertEquals(1, completions.get());

            client.generate("Return JSON", "Compose", 2048, cache);
            Assert.assertEquals(1, completions.get());

            List<String> available = client.listModels();
            Assert.assertEquals("google/gemma-4-31B-it", available.get(0));
            Assert.assertEquals(1, models.get());
        } finally {
            proxy.stop(0);
        }
    }
}
