package com.aresstack.audio.studio.ai;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Isolate the authenticated OpenAI-compatible KIPITZ protocol from music composition. */
public final class KipitzChatClient {
    private final Gson gson = new Gson();
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final String proxyHost;
    private final int proxyPort;

    public KipitzChatClient(String endpoint, String apiKey, String model, String proxyHost, int proxyPort) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.proxyHost = proxyHost == null ? "" : proxyHost.trim();
        this.proxyPort = proxyPort;
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
        }
    }

    private HttpURLConnection openConnection() throws IOException {
        URL url = new URL(endpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol()) && !"http".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Only HTTP(S) endpoints are supported.");
        }
        if (proxyHost.isEmpty()) {
            return (HttpURLConnection) url.openConnection();
        }
        if (proxyPort < 1 || proxyPort > 65535) {
            throw new IOException("Invalid proxy port: " + proxyPort);
        }
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        return (HttpURLConnection) url.openConnection(proxy);
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
