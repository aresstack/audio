package com.aresstack.audio.studio.settings;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Suggest the existing 3d4j/Enterprise AI configuration without copying secrets. */
public final class ExistingAiSettings {
    private ExistingAiSettings() {
    }

    public static Properties withDefaults(Properties audio) {
        Properties merged = new Properties();
        merged.putAll(audio);
        Properties game = read(Paths.get(System.getProperty("user.home"),
                ".3d4j", "generator.properties"));
        String appData = System.getenv("APPDATA");
        Properties enterprise = read(Paths.get(
                appData == null || appData.trim().isEmpty() ? System.getProperty("user.home") : appData,
                ".enterprise-ai-client", "enterprise-ai-client.properties"));

        String base = first(game.getProperty("chat.baseUrl"), enterprise.getProperty("chat.baseUrl"));
        if (missing(merged, "endpoint") && base != null) {
            String endpoint = completionsEndpoint(base);
            if (endpoint != null) {
                merged.setProperty("endpoint", endpoint);
            }
        }
        if (missing(merged, "model")) {
            String value = first(game.getProperty("chat.model"), enterprise.getProperty("chat.model"));
            if (value != null) {
                merged.setProperty("model", value);
            }
        }
        if (missing(merged, "maxTokens")) {
            String configured = first(game.getProperty("chat.maxTokens"),
                    enterprise.getProperty("chat.maxTokens"));
            if (configured != null) {
                merged.setProperty("maxTokens", configured);
            }
        }
        if (missing(merged, "keepassEntry")) {
            String reference = first(game.getProperty("chat.apiKeyRef"), enterprise.getProperty("chat.apiKeyRef"));
            if (reference != null) {
                merged.setProperty("keepassEntry", reference.startsWith("keepass:")
                        ? reference.substring("keepass:".length()) : reference);
            }
        }
        return merged;
    }

    /** Match 3d4j: use at least 256 tokens or default to 16384 when malformed. */
    public static int maxTokens(Properties settings) {
        try {
            return Math.max(256, Integer.parseInt(settings.getProperty("maxTokens", "16384")));
        } catch (NumberFormatException invalid) {
            return 16384;
        }
    }

    public static String completionsEndpoint(String base) {
        String normalized = base.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(normalized);
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) {
                return null;
            }
            return normalized.endsWith("/chat/completions")
                    ? normalized : normalized + "/chat/completions";
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean missing(Properties settings, String key) {
        String value = settings.getProperty(key);
        return value == null || value.trim().isEmpty();
    }

    private static String first(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static Properties read(Path path) {
        Properties settings = new Properties();
        if (!Files.isRegularFile(path)) {
            return settings;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            settings.load(reader);
        } catch (IOException ignored) {
            // Preserve the Audio Studio's own configuration when a foreign file cannot be read.
        }
        return settings;
    }
}
