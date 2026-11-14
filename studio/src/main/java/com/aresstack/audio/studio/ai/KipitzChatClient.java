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
public final class KipitzChatClient {
    private final Gson gson = new Gson();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final AudioProxyRoutes routes;
    private final String proxyHost;
    private final int proxyPort;
    private volatile HttpURLConnection inFlight;

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
        if (Files.isRegularFile(cache)) {
            try {
                return new JsonParser().parse(new String(Files.readAllBytes(cache), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (RuntimeException invalidCache) {
                throw new IOException("Cached JSON is invalid: " + cache, invalidCache);
            }
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("Enter an API key to generate the missing composition part.");
        }
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("temperature", 0.55);
        request.addProperty("top_p", 0.9);
        request.addProperty("top_k", 40);
        request.addProperty("max_tokens", maxTokens);
        request.addProperty("stream", false);
        request.addProperty("user", "audio-swing-studio");
        JsonArray messages = new JsonArray();
        messages.add(message("system", instruction));
        messages.add(message("user", prompt));
        request.add("messages", messages);

        HttpURLConnection connection = openConnection();
        inFlight = connection;
        try {
            byte[] body = gson.toJson(request).getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(300000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("KIPITZ returned HTTP " + status + ". Check endpoint, proxy, credentials and model.");
            }
            String response;
            try (InputStream input = connection.getInputStream()) {
                response = readText(input, 2_000_000);
            }
            JsonObject root = new JsonParser().parse(response).getAsJsonObject();
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            String finishReason = choice.get("finish_reason").getAsString();
            if (!"stop".equals(finishReason)) {
                throw new IOException("The model output was not complete (finish_reason=" + finishReason + ").");
            }
            String content = choice.getAsJsonObject("message").get("content").getAsString().trim();
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IOException("The model returned no JSON object.");
            }
            JsonObject parsed = new JsonParser().parse(content.substring(start, end + 1)).getAsJsonObject();
            Files.createDirectories(cache.getParent());
            Files.write(cache, gson.toJson(parsed).getBytes(StandardCharsets.UTF_8));
            return parsed;
        } catch (RuntimeException malformed) {
            throw new IOException("Could not parse the KIPITZ model response.", malformed);
        } finally {
            connection.disconnect();
            inFlight = null;
        }
    }

    /** Disconnect a pending request when the user cancels the composition. */
    public void cancel() {
        HttpURLConnection connection = inFlight;
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** Open through the same PAC-aware proxy route and Windows trust store used by 3d4j. */
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
