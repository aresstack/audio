package com.aresstack.audio.studio.ai;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Answers arrive as a stream, so that no proxy on the way gives up on a silent connection. */
public final class KipitzStreamingTest {
    private static final String FIRST = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":"
            + "\"{\\\"part\\\":\\\"mel\"},\"finish_reason\":null}]}\n\n";
    private static final String THINKING = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hm\"},"
            + "\"finish_reason\":null}]}\n\n";
    private static final String SECOND = "data: {\"choices\":[{\"delta\":{\"content\":\"ody\\\",\\\"notes\\\":[]}\"},"
            + "\"finish_reason\":null}]}\n\n";
    private static final String END = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
    private static final String WHOLE = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
            + "\"{\\\"part\\\":\\\"melody\\\",\\\"notes\\\":[]}\"}}]}";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        for (int count = input.read(block); count >= 0; count = input.read(block)) {
            bytes.write(block, 0, count);
        }
        return new String(bytes.toByteArray(), "UTF-8");
    }

    private static void send(HttpExchange exchange, int status, String type, String... pieces) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, 0); // chunked: the pieces go out one by one
        try (OutputStream output = exchange.getResponseBody()) {
            for (String piece : pieces) {
                output.write(piece.getBytes("UTF-8"));
                output.flush();
            }
        }
    }

    private KipitzChatClient client(HttpServer server) {
        return new KipitzChatClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "test-only", "google/gemma-4-31B-it", "", 0);
    }

    @Test
    public void putsTheStreamedPiecesTogetherAndLeavesThinkingOut() throws Exception {
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            asked.add(read(exchange.getRequestBody()));
            send(exchange, 200, "text/event-stream", ": keep-alive\n\n", FIRST, THINKING, SECOND, END);
        });
        server.start();
        try {
            JsonObject answer = client(server).generate("Return JSON", "Compose", 2048,
                    folder.getRoot().toPath().resolve("melody.json"));
            Assert.assertEquals("melody", answer.get("part").getAsString());
            Assert.assertEquals(1, asked.size());
            Assert.assertTrue(asked.get(0), asked.get(0).contains("\"stream\":true"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void sendsTheRequestAgainWhenTheStreamBreaksOffInTheMiddle() throws Exception {
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            asked.add(read(exchange.getRequestBody()));
            if (asked.size() == 1) {
                send(exchange, 200, "text/event-stream", FIRST); // and then the connection ends
            } else {
                send(exchange, 200, "text/event-stream", FIRST, SECOND, END);
            }
        });
        server.start();
        try {
            JsonObject answer = client(server).generate("Return JSON", "Compose", 2048,
                    folder.getRoot().toPath().resolve("melody.json"));
            Assert.assertEquals("melody", answer.get("part").getAsString());
            Assert.assertEquals("the broken request is sent once more", 2, asked.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void asksAServiceThatRefusesToStreamTheOldWay() throws Exception {
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = read(exchange.getRequestBody());
            asked.add(body);
            if (body.contains("\"stream\":true")) {
                send(exchange, 400, "application/json", "{\"error\":\"streaming is not supported\"}");
            } else {
                send(exchange, 200, "application/json", WHOLE);
            }
        });
        server.start();
        try {
            KipitzChatClient client = client(server);
            JsonObject answer = client.generate("Return JSON", "Compose", 2048,
                    folder.getRoot().toPath().resolve("melody.json"));
            Assert.assertEquals("melody", answer.get("part").getAsString());
            Assert.assertEquals(2, asked.size());
            client.generate("Return JSON", "Compose", 2048, folder.getRoot().toPath().resolve("bass.json"));
            Assert.assertEquals("remembered: the third request does not try to stream", 3, asked.size());
            Assert.assertTrue(asked.get(2).contains("\"stream\":false"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void takesAnAnswerWhoseConnectionBreaksAfterTheModelFinished() throws Exception {
        String finished = FIRST + SECOND + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n";
        KipitzChatClient.Answer answer = KipitzChatClient.readStream(
                new java.io.ByteArrayInputStream(finished.getBytes("UTF-8")));
        Assert.assertEquals("{\"part\":\"melody\",\"notes\":[]}", answer.text);
        Assert.assertEquals("stop", answer.finishReason);
    }

    @Test
    public void theAnswerCanBeReadWhileItArrives() throws Exception {
        String finished = FIRST + SECOND + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n";
        List<String> heard = new ArrayList<String>();
        KipitzChatClient.Answer answer = KipitzChatClient.readStream(
                new java.io.ByteArrayInputStream(finished.getBytes("UTF-8")), heard::add);
        Assert.assertFalse("the text so far is shown at least once", heard.isEmpty());
        for (String soFar : heard) {
            Assert.assertTrue(soFar, answer.text.startsWith(soFar));
        }
    }

    @Test
    public void aListenerThatFailsDoesNotBreakTheAnswer() throws Exception {
        String finished = FIRST + SECOND + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n";
        KipitzChatClient.Answer answer = KipitzChatClient.readStream(
                new java.io.ByteArrayInputStream(finished.getBytes("UTF-8")), text -> {
                    throw new IllegalStateException("cannot draw");
                });
        Assert.assertEquals("{\"part\":\"melody\",\"notes\":[]}", answer.text);
    }

    @Test
    public void anAnswerCutOffAtItsLimitIsAskedForAgainWithTwiceTheRoom() throws Exception {
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
        final String cut = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            asked.add(read(exchange.getRequestBody()));
            if (asked.size() == 1) {
                send(exchange, 200, "text/event-stream", FIRST, cut);
            } else {
                send(exchange, 200, "text/event-stream", FIRST, SECOND, END);
            }
        });
        server.start();
        try {
            JsonObject answer = client(server).generate("Return JSON", "Compose", 2048,
                    folder.getRoot().toPath().resolve("melody.json"));
            Assert.assertEquals("melody", answer.get("part").getAsString());
            Assert.assertEquals(2, asked.size());
            Assert.assertTrue(asked.get(0), asked.get(0).contains("\"max_tokens\":2048"));
            Assert.assertTrue(asked.get(1), asked.get(1).contains("\"max_tokens\":4096"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void anAnswerThatStaysCutOffIsAnErrorThatSaysHowMuchRoomItHad() throws Exception {
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
        final String cut = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            asked.add(read(exchange.getRequestBody()));
            send(exchange, 200, "text/event-stream", FIRST, cut);
        });
        server.start();
        try {
            client(server).generate("Return JSON", "Compose", 2048, folder.getRoot().toPath().resolve("melody.json"));
            Assert.fail("a cut answer is no answer");
        } catch (IOException cutOff) {
            Assert.assertEquals("asked four times: 2048, 4096, 8192, 16384", 4, asked.size());
            Assert.assertTrue(cutOff.getMessage(), cutOff.getMessage().contains("finish_reason=length")
                    && cutOff.getMessage().contains("16384"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void aConnectionThatFallsSilentIsEndedSaidAndTheRequestSentAgain() throws Exception {
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
        final List<String> troubles = Collections.synchronizedList(new ArrayList<String>());
        final java.util.concurrent.CountDownLatch over = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            asked.add(read(exchange.getRequestBody()));
            if (asked.size() == 1) {
                // The answer begins and then nothing more comes, as on a Wi-Fi connection that died.
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream output = exchange.getResponseBody();
                output.write(FIRST.getBytes("UTF-8"));
                output.flush();
                try {
                    over.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            } else {
                send(exchange, 200, "text/event-stream", FIRST, SECOND, END);
            }
        });
        server.start();
        long silence = KipitzChatClient.silenceMillis;
        KipitzChatClient.silenceMillis = 500;
        try {
            JsonObject answer = client(server).troubles(troubles::add).generate("Return JSON", "Compose", 2048,
                    folder.getRoot().toPath().resolve("melody.json"));
            Assert.assertEquals("melody", answer.get("part").getAsString());
            Assert.assertEquals("asked again after the silence", 2, asked.size());
            Assert.assertEquals(troubles.toString(), 1, troubles.size());
            Assert.assertTrue(troubles.get(0), troubles.get(0).contains("kein Text mehr")
                    && troubles.get(0).contains("Versuch 2 in"));
        } finally {
            KipitzChatClient.silenceMillis = silence;
            over.countDown();
            server.stop(0);
        }
    }
}
