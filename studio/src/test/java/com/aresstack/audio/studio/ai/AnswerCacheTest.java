package com.aresstack.audio.studio.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A kept answer belongs to the model, instruction, prompt and sampling it was given for: another
 * model is asked anew, so that switching models is heard. Answers kept before origins were
 * written down stay valid for the studio's former single model.
 */
public final class AnswerCacheTest {
    private static final String GEMMA = "google/gemma-4-31B-it";
    private static final String GPT = "gpt-oss:20b";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();
    private HttpServer server;
    /** The model named in each request that reached the fake service. */
    private final List<String> asked = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void startService() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String model = com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("model").getAsString();
            asked.add(model);
            String content = "{\\\"by\\\":\\\"" + model + "\\\"}";
            byte[] answer = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"" + content
                    + "\"}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(answer);
            }
        });
        server.start();
    }

    @After
    public void stopService() {
        server.stop(0);
    }

    private KipitzChatClient client(String model) {
        return new KipitzChatClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "test-only", model, "", 0);
    }

    private static String by(JsonObject answer) {
        return answer.get("by").getAsString();
    }

    @Test
    public void anotherModelIsAskedAnewAndTheFormerAnswerIsKeptAside() throws Exception {
        Path plan = folder.getRoot().toPath().resolve("composition-plan.json");
        assertEquals(GEMMA, by(client(GEMMA).generate("Return JSON", "Plan it", 2048, plan)));
        assertTrue(Files.isRegularFile(AnswerCache.originOf(plan)));
        String origin = Files.readString(AnswerCache.originOf(plan));
        assertTrue(origin, origin.contains("\"model\": \"" + GEMMA + "\""));

        // Same model, same words: the kept answer, no request.
        assertTrue(client(GEMMA).hasAnswer("Return JSON", "Plan it", 2048, plan));
        assertEquals(GEMMA, by(client(GEMMA).generate("Return JSON", "Plan it", 4096, plan)));
        assertEquals(List.of(GEMMA), asked);

        // GPT-OSS for planning: asked anew; Gemma's answer lies in earlier/.
        assertFalse(client(GPT).hasAnswer("Return JSON", "Plan it", 2048, plan));
        assertEquals(GPT, by(client(GPT).generate("Return JSON", "Plan it", 2048, plan)));
        assertEquals(List.of(GEMMA, GPT), asked);
        try (var earlier = Files.list(plan.resolveSibling(AnswerCache.EARLIER))) {
            assertEquals(2, earlier.count()); // the answer and its origin
        }
    }

    @Test
    public void anotherInstructionOrPromptIsAskedAnew() throws Exception {
        Path cache = folder.getRoot().toPath().resolve("melody-abc.json");
        client(GEMMA).generate("Return JSON", "Compose", 2048, cache);
        client(GEMMA).generate("Return JSON only", "Compose", 2048, cache);
        client(GEMMA).generate("Return JSON only", "Compose more", 2048, cache);
        assertEquals(3, asked.size());
    }

    @Test
    public void anAnswerKeptBeforeOriginsCountsAsTheFormerSingleModels() throws Exception {
        Path cache = folder.getRoot().toPath().resolve("melody.json");
        Files.writeString(cache, "{\"by\":\"old\"}");

        // Gemma was the studio's model: the old answer stays valid, nothing is asked.
        assertEquals("old", by(client(GEMMA).generate("i", "p", 2048, cache)));
        // A user whose single model was another one keeps theirs for that one.
        assertEquals("old", by(client("my-model").legacyModel("my-model").generate("i", "p", 2048, cache)));
        assertEquals(List.of(), asked);
        assertFalse("nothing is claimed about an old answer", Files.exists(AnswerCache.originOf(cache)));

        // Another model: asked anew.
        assertEquals(GPT, by(client(GPT).generate("i", "p", 2048, cache)));
        assertEquals(List.of(GPT), asked);
    }

    @Test
    public void theFormerSingleModelIsTheStudiosDefault() {
        assertEquals(com.aresstack.audio.studio.settings.AiDefaults.MODEL, KipitzChatClient.FORMER_DEFAULT_MODEL);
    }

    @Test
    public void theSamplingIsPartOfTheIdentityTheAnswerLengthIsNot() {
        AnswerOrigin one = new AnswerOrigin(GEMMA, "i", "p", 2048, 0.55, 0.9, 40);
        assertEquals(one.key(), new AnswerOrigin(GEMMA, "i", "p", 16384, 0.55, 0.9, 40).key());
        assertFalse(one.key().equals(new AnswerOrigin(GEMMA, "i", "p", 2048, 0.7, 0.9, 40).key()));
        assertFalse(one.key().equals(new AnswerOrigin(GPT, "i", "p", 2048, 0.55, 0.9, 40).key()));
    }

    @Test
    public void anAnswerCutOffByACrashCountsAsNotKept() throws Exception {
        // Test Techno, 2026-11-16: the program ended while answers were written; they are asked again.
        Path cache = Files.createTempDirectory("cut").resolve("bass-s3-abc.json");
        AnswerOrigin origin = new AnswerOrigin(GEMMA, "i", "p", 2048, 0.55, 0.9, 40);
        AnswerCache.store(cache, origin, "{\"notes\":[]}");
        Files.writeString(cache, "{\"notes\":[{\"bar\":1,\"be", StandardCharsets.UTF_8);
        assertEquals(null, AnswerCache.reuse(cache, origin, null));
        assertFalse(Files.exists(cache));
        // Written whole or not at all: no temporary file stays beside it.
        AnswerCache.store(cache, origin, "{\"notes\":[]}");
        try (java.util.stream.Stream<Path> files = Files.list(cache.getParent())) {
            assertFalse(files.anyMatch(file -> file.toString().endsWith(".tmp")));
        }
        assertTrue(AnswerCache.reuse(cache, origin, null).has("notes"));
    }
}
