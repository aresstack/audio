package com.aresstack.audio.studio.application;

import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercise proposal, concurrent track generation, caching, MIDI and WAV without external API tokens. */
public final class ParallelArrangementIntegrationTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void composesSixEditableTracksConcurrentlyAndReusesCachedParts() throws Exception {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximum = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final ExecutorService handlers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            int inside = active.incrementAndGet();
            maximum.accumulateAndGet(inside, Math::max);
            requests.incrementAndGet();
            try {
                String request = read(exchange.getRequestBody());
                String content = new JsonParser().parse(request).getAsJsonObject()
                        .getAsJsonArray("messages").get(1).getAsJsonObject()
                        .get("content").getAsString();
                JsonObject answer = content.contains("Set part=")
                        ? instrumentResponse(content)
                        : compositionPlan();
                if (content.contains("Set part=")) {
                    Thread.sleep(150);
                }
                JsonObject result = new JsonObject();
                JsonObject choice = new JsonObject();
                JsonObject message = new JsonObject();
                message.addProperty("content", answer.toString());
                choice.addProperty("finish_reason", "stop");
                choice.add("message", message);
                JsonArray choices = new JsonArray();
                choices.add(choice);
                result.add("choices", choices);
                byte[] bytes = new Gson().toJson(result).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } catch (Exception failed) {
                byte[] bytes = failed.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            } finally {
                active.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
        try {
            List<MusicTrack> instruments = new ArrayList<MusicTrack>(MusicTrack.defaults());
            instruments.add(new MusicTrack("flute", MusicTrack.MELODY,
                    "Solo flute", 73, "builtin"));
            instruments.add(new MusicTrack("cello", MusicTrack.HARMONY,
                    "Solo cello", 42, "builtin"));
            TrackArrangement selected = new TrackArrangement(instruments);
            Path song = folder.newFolder("generated-song").toPath();
            TrackArrangementStore store = new TrackArrangementStore();
            store.save(song, selected);
            KipitzChatClient client = new KipitzChatClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                    "test-key", "test-model", "", 8080);
            GemmaMusicComposer composer = new GemmaMusicComposer(client, 8192, 32);
            GemmaMusicComposer.Progress output = message -> { };

            Path tsv = composer.compose("A quiet cinematic stroll", song, selected, output);
            Assert.assertTrue(Files.size(tsv) > 200);
            Assert.assertEquals(7, requests.get());
            Assert.assertTrue("Track generation should overlap", maximum.get() >= 2);
            String data = new String(Files.readAllBytes(tsv), StandardCharsets.UTF_8);
            Assert.assertTrue(data.contains("track\tflute\t73\t3\tmelody"));
            Assert.assertTrue(data.contains("track\tcello\t42\t4\tharmony"));
            Assert.assertEquals(6, store.load(song).tracks().size());

            composer.compose("A quiet cinematic stroll", song, selected, output);
            Assert.assertEquals("All seven model results must be reused from cache", 7, requests.get());

            Path mix = new StudioMusicRenderer(
                    new InstrumentRegistry(folder.getRoot().toPath().resolve("libraries.properties")))
                    .render(tsv, song.resolve("render"),
                            java.util.Collections.emptyMap(), "fluidsynth", "sfizz_render", output);
            Assert.assertTrue(Files.size(mix) > 1000);
            Assert.assertTrue(Files.size(mix.getParent().resolve("flute.wav")) > 1000);
            Assert.assertTrue(Files.size(mix.getParent().resolve("cello.wav")) > 1000);
            Assert.assertTrue(Files.size(mix.getParent().resolve("arrangement.mid")) > 200);
            Assert.assertEquals(7, javax.sound.midi.MidiSystem.getSequence(
                    mix.getParent().resolve("arrangement.mid").toFile()).getTracks().length);
        } finally {
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    private static JsonObject compositionPlan() {
        JsonObject plan = new JsonObject();
        plan.addProperty("tempoBpm", 108);
        plan.addProperty("bars", 8);
        plan.addProperty("key", "C major");
        plan.addProperty("timeSignature", "4/4");
        plan.addProperty("arrangementNotes", "Keep every instrumental voice coordinated.");
        JsonArray chords = new JsonArray();
        for (String chord : new String[]{"C", "G", "Am", "F", "C", "G", "F", "G"}) {
            chords.add(chord);
        }
        plan.add("chords", chords);
        return plan;
    }

    private static JsonObject instrumentResponse(String prompt) {
        Matcher match = Pattern.compile("Set part=\\\"([a-z0-9_-]+)\\\" and program=(\\d+)")
                .matcher(prompt);
        if (!match.find()) {
            throw new IllegalArgumentException("Cannot parse requested instrument identity.");
        }
        String id = match.group(1);
        int program = Integer.parseInt(match.group(2));
        JsonObject result = new JsonObject();
        result.addProperty("part", id);
        result.addProperty("program", program);
        JsonArray notes = new JsonArray();
        for (int i = 0; i < 8; i++) {
            JsonObject note = new JsonObject();
            note.addProperty("beat", i * 4);
            note.addProperty("duration", 1);
            note.addProperty("pitch", "drums".equals(id) ? 36 : "bass".equals(id) ? 48 : 72);
            note.addProperty("velocity", 72);
            notes.add(note);
        }
        result.add("notes", notes);
        return result;
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        int count;
        while ((count = input.read(block)) != -1) {
            bytes.write(block, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
