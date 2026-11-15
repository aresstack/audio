package com.aresstack.audio.studio.ai;

import com.aresstack.audio.instruments.network.AudioProxyRoutes;
import com.aresstack.audio.instruments.network.AudioWindowsTrust;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Isolate the authenticated OpenAI-compatible KIPITZ protocol from music composition. */
public final class KipitzChatClient implements ChatModel {
    private final Gson gson = new Gson();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final AudioProxyRoutes routes;
    private final String proxyHost;
    private final int proxyPort;
    private final java.util.Set<HttpURLConnection> activeConnections =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>());

    public KipitzChatClient(String endpoint, String apiKey, String model, String proxyHost, int proxyPort) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.proxyHost = proxyHost == null ? "" : proxyHost.trim();
        this.proxyPort = proxyPort;
        this.routes = null;
    }

    public KipitzChatClient(String endpoint, String apiKey, String model, AudioProxyRoutes routes) {
        if (routes == null) {
            throw new IllegalArgumentException("Proxy route resolver cannot be null.");
        }
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.proxyHost = "";
        this.proxyPort = 0;
        this.routes = routes;
    }

    public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException {
        return generate(instruction, prompt, maxTokens, cache, null);
    }

    /** Answers and lets the answer be read while it arrives, see {@link ChatModel#generate(String, String, int, Path, java.util.function.Consumer)}. */
    @Override
    public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache,
                               java.util.function.Consumer<String> heard) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("AI request was cancelled.");
        }
        AnswerOrigin origin = origin(instruction, prompt, maxTokens);
        JsonObject kept = AnswerCache.reuse(cache, origin, legacyModel);
        if (kept != null) {
            return kept;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("Enter an API key to generate the missing composition part.");
        }
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("temperature", TEMPERATURE);
        request.addProperty("top_p", TOP_P);
        request.addProperty("top_k", TOP_K);
        request.addProperty("max_tokens", maxTokens);
        request.addProperty("user", "audio-swing-studio");
        JsonArray messages = new JsonArray();
        messages.add(message("system", instruction));
        messages.add(message("user", prompt));
        request.add("messages", messages);

        Answer answer = null;
        int budget = maxTokens;
        int longer = 0;
        for (int attempt = 1; answer == null; attempt++) {
            // Streamed: the answer arrives piece by piece while the model writes. A proxy or gateway
            // on the way gives up on a connection that stays silent (HTTP 504), and a long answer
            // keeps a connection silent for minutes when it only comes at the end.
            request.addProperty("stream", streaming);
            request.addProperty("max_tokens", budget);
            try {
                answer = exchange(gson.toJson(request).getBytes(StandardCharsets.UTF_8), heard);
                if ("length".equals(answer.finishReason) && longer < LONGER_ANSWERS
                        && !cancelled && !Thread.currentThread().isInterrupted()) {
                    // Cut off at the limit: a model that thinks before it answers spends the tokens of
                    // its answer on that. Asked again with room for twice as much.
                    longer++;
                    budget *= 2;
                    answer = null;
                    attempt = 0;
                    continue;
                }
            } catch (Refused refused) {
                if (longer > 0) {
                    throw new IOException("The model output was not complete (finish_reason=length), and the "
                            + "service refused a longer answer of " + budget + " tokens (HTTP " + refused.status
                            + "). Choose a shorter piece or a model with a longer answer.");
                }
                if (streaming && (refused.status == 400 || refused.status == 404 || refused.status == 415
                        || refused.status == 422 || refused.status == 501)) {
                    streaming = false; // a service that does not stream: asked the old way from now on
                    continue;
                }
                throw new IOException("KIPITZ returned HTTP " + refused.status
                        + ". Check endpoint, proxy, credentials and model.");
            } catch (Unusable unusable) {
                throw unusable;
            } catch (IOException broken) {
                // The connection failed or broke off on the way: that says nothing about the request.
                if (cancelled || Thread.currentThread().isInterrupted() || attempt >= TRANSPORT_ATTEMPTS) {
                    throw broken;
                }
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw broken;
                }
            }
        }
        try {
            if (!"stop".equals(answer.finishReason)) {
                throw new IOException("The model output was not complete (finish_reason=" + answer.finishReason
                        + ("length".equals(answer.finishReason) ? ", also with " + budget + " tokens" : "") + ").");
            }
            String content = answer.text.trim();
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IOException("The model returned no JSON object.");
            }
            JsonObject parsed = new JsonParser().parse(content.substring(start, end + 1)).getAsJsonObject();
            AnswerCache.store(cache, origin, gson.toJson(parsed));
            return parsed;
        } catch (RuntimeException malformed) {
            throw new IOException("Could not parse the KIPITZ model response.", malformed);
        }
    }

    /** How often an answer that was cut off at its limit is asked for again, each time with twice the room. */
    static final int LONGER_ANSWERS = 2;

    /** The sampling of every request; part of the identity of a kept answer. */
    static final double TEMPERATURE = 0.55;
    static final double TOP_P = 0.9;
    static final int TOP_K = 40;

    /**
     * The model an answer kept before origins were written down is taken to come from: the
     * studio's single model of before. Until told otherwise the studio's default.
     */
    private volatile String legacyModel = FORMER_DEFAULT_MODEL;

    /** The studio's default model ({@code AiDefaults.MODEL}, not imported: settings sit above the AI). */
    static final String FORMER_DEFAULT_MODEL = "google/gemma-4-31B-it";

    /**
     * Says which model the answers kept without an origin came from, normally the {@code model}
     * of the settings. Such an answer is given again only when this client asks that model.
     */
    public KipitzChatClient legacyModel(String model) {
        this.legacyModel = model == null || model.isBlank() ? FORMER_DEFAULT_MODEL : model.trim();
        return this;
    }

    @Override
    public String name() {
        return model == null ? "" : model.trim();
    }

    @Override
    public boolean hasAnswer(String instruction, String prompt, int maxTokens, Path cache) {
        return AnswerCache.reusable(cache, origin(instruction, prompt, maxTokens), legacyModel);
    }

    private AnswerOrigin origin(String instruction, String prompt, int maxTokens) {
        return new AnswerOrigin(name(), instruction, prompt, maxTokens, TEMPERATURE, TOP_P, TOP_K);
    }

    /** How often a request is sent again when the connection fails or breaks off, before the failure counts. */
    private static final int TRANSPORT_ATTEMPTS = 3;
    private static final int RESPONSE_LIMIT = 2_000_000;

    /** Whether answers are asked for as a stream; switched off for a service that refuses to stream. */
    private volatile boolean streaming = true;
    private volatile boolean cancelled;

    /** The text of an answer and why the model ended it. */
    static final class Answer {
        final String text;
        final String finishReason;

        Answer(String text, String finishReason) {
            this.text = text;
            this.finishReason = finishReason;
        }
    }

    /** The service answered with an error status: sending the same request again would not help. */
    private static final class Refused extends IOException {
        private final int status;

        Refused(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    /** The service answered, but not with what can be read as an answer: asking again would not help either. */
    private static final class Unusable extends IOException {
        Unusable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Sends one request and reads its answer, streamed or whole, whichever the service sends. */
    private Answer exchange(byte[] body, java.util.function.Consumer<String> heard) throws IOException {
        HttpURLConnection connection = openConnection();
        activeConnections.add(connection);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(300000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new Refused(status);
            }
            String type = String.valueOf(connection.getContentType());
            try (InputStream input = connection.getInputStream()) {
                if (type.contains("event-stream")) {
                    return readStream(input, heard);
                }
                String response = readText(input, RESPONSE_LIMIT);
                try {
                    JsonObject choice = new JsonParser().parse(response).getAsJsonObject()
                            .getAsJsonArray("choices").get(0).getAsJsonObject();
                    return new Answer(choice.getAsJsonObject("message").get("content").getAsString(),
                            choice.get("finish_reason").getAsString());
                } catch (RuntimeException malformed) {
                    throw new Unusable("Could not parse the KIPITZ model response.", malformed);
                }
            }
        } finally {
            activeConnections.remove(connection);
            connection.disconnect();
        }
    }

    /**
     * Reads a streamed answer: lines {@code data: {...}} with a piece of text each in
     * {@code choices[0].delta.content}, ended by {@code data: [DONE]}. Reading stops at that end
     * mark instead of waiting for the connection to close, because a proxy may cut the connection
     * without ending it properly ("Premature EOF"). If the connection breaks after the model has
     * said why it ended, the answer is whole and is taken; if it breaks earlier, that is an error
     * and the request is sent again. Thinking arrives in other fields and is left out.
     */
    static Answer readStream(InputStream input) throws IOException {
        return readStream(input, null);
    }

    /** The time between two looks at an answer that is still arriving, in milliseconds. */
    private static final long HEARD_EVERY = 200;

    /**
     * Reads a streamed answer as {@link #readStream(InputStream)} does and shows the text so far
     * to a listener while it arrives, a few times a second at most.
     */
    static Answer readStream(InputStream input, java.util.function.Consumer<String> heard) throws IOException {
        StringBuilder text = new StringBuilder();
        long lastHeard = 0;
        String finishReason = null;
        String broken = null;
        boolean done = false;
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        try {
            for (String line = reader.readLine(); line != null && !done; line = reader.readLine()) {
                if (!line.startsWith("data:")) {
                    continue; // empty lines between events, comments that keep the connection alive
                }
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    done = true;
                    break;
                }
                if (data.isEmpty()) {
                    continue;
                }
                JsonObject event;
                try {
                    event = new JsonParser().parse(data).getAsJsonObject();
                } catch (RuntimeException malformed) {
                    throw new Unusable("The stream carried something that is no JSON.", malformed);
                }
                if (event.has("error")) {
                    throw new Unusable("KIPITZ reported: " + event.get("error"), null);
                }
                if (!event.has("choices") || !event.get("choices").isJsonArray()
                        || event.getAsJsonArray("choices").size() == 0) {
                    continue; // e.g. the usage at the end
                }
                JsonObject choice = event.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("delta") && choice.get("delta").isJsonObject()) {
                    com.google.gson.JsonElement content = choice.getAsJsonObject("delta").get("content");
                    if (content != null && content.isJsonPrimitive()) {
                        text.append(content.getAsString());
                        if (text.length() > RESPONSE_LIMIT) {
                            throw new Unusable("KIPITZ response exceeds the configured size limit.", null);
                        }
                        long now = System.currentTimeMillis();
                        if (heard != null && now - lastHeard >= HEARD_EVERY) {
                            lastHeard = now;
                            try {
                                heard.accept(text.toString());
                            } catch (RuntimeException unheard) {
                                // looking at the answer on its way must never break the answer
                            }
                        }
                    }
                }
                com.google.gson.JsonElement finish = choice.get("finish_reason");
                if (finish != null && finish.isJsonPrimitive()) {
                    finishReason = finish.getAsString();
                }
            }
            if (!done) {
                broken = "the connection was closed"; // quietly, without the end mark
            }
        } catch (Unusable unusable) {
            throw unusable;
        } catch (IOException cut) {
            broken = String.valueOf(cut.getMessage()); // "Premature EOF", a reset, a silence too long
        }
        if (finishReason == null) {
            throw new IOException("The stream broke off after " + text.length() + " characters: " + broken);
        }
        return new Answer(text.toString(), finishReason);
    }

    /** Disconnect a pending request when the user cancels the composition. */
    public void cancel() {
        cancelled = true;
        for (HttpURLConnection connection : activeConnections) {
            connection.disconnect();
        }
    }

    /** Open through the PAC-aware proxy route and the Windows trust store. */
    private HttpURLConnection openConnection() throws IOException {
        return openConnection(URI.create(endpoint));
    }

    private HttpURLConnection openConnection(URI destination) throws IOException {
        URL url = destination.toURL();
        if (!"https".equalsIgnoreCase(url.getProtocol()) && !"http".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Only HTTP(S) endpoints are supported.");
        }
        HttpURLConnection connection;
        if (routes != null) {
            connection = (HttpURLConnection) url.openConnection(routes.proxyFor(destination));
        } else if (proxyHost.isEmpty()) {
            connection = (HttpURLConnection) url.openConnection();
        } else {
            if (proxyPort < 1 || proxyPort > 65535) {
                throw new IOException("Invalid proxy port: " + proxyPort);
            }
            connection = (HttpURLConnection) url.openConnection(
                    new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        }
        if (connection instanceof HttpsURLConnection) {
            AudioWindowsTrust.configure((HttpsURLConnection) connection);
        }
        return connection;
    }

    /** Read the available OpenAI-compatible model identifiers without consuming completion tokens. */
    public java.util.List<String> listModels() throws IOException {
        String suffix = "/chat/completions";
        String normalized = endpoint.trim().replaceAll("/+$", "");
        if (!normalized.endsWith(suffix)) {
            throw new IOException("The KIPITZ URL must end with /chat/completions to list models.");
        }
        URI address = URI.create(normalized.substring(0, normalized.length() - suffix.length()) + "/models");
        HttpURLConnection connection = openConnection(address);
        activeConnections.add(connection);
        try {
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("Model listing returned HTTP " + status + " (proxy " +
                        (routes == null ? "legacy" : routes.describe()) + ").");
            }
            try (InputStream input = connection.getInputStream()) {
                com.google.gson.JsonArray data = new JsonParser().parse(readText(input, 2_000_000))
                        .getAsJsonObject().getAsJsonArray("data");
                java.util.List<String> models = new java.util.ArrayList<String>();
                for (com.google.gson.JsonElement element : data) {
                    models.add(element.getAsJsonObject().get("id").getAsString());
                }
                java.util.Collections.sort(models);
                return models;
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Could not read the model list.", invalid);
        } finally {
            activeConnections.remove(connection);
            connection.disconnect();
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject result = new JsonObject();
        result.addProperty("role", role);
        result.addProperty("content", content);
        return result;
    }

    private static String readText(InputStream input, int limit) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = input.read(block)) >= 0) {
            bytes.write(block, 0, count);
            if (bytes.size() > limit) {
                throw new IOException("KIPITZ response exceeds the configured size limit.");
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
