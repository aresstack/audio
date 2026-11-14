package com.aresstack.audio.studio.settings;

import java.net.URI;
import java.util.Properties;

/**
 * What the studio assumes about the AI service until the user says otherwise. The
 * values are the studio's own: the model and the answer length are the same as
 * 3d4j's defaults, written down here, and no file of another program is read.
 * There is no default for the address or the credentials; those the user enters.
 */
public final class AiDefaults {
    public static final String MODEL = "google/gemma-4-31B-it";
    public static final int MAX_TOKENS = 16384;

    private AiDefaults() {
    }

    /** The studio's settings with the defaults filled in where nothing is set. */
    public static Properties withDefaults(Properties audio) {
        Properties merged = new Properties();
        merged.putAll(audio);
        if (missing(merged, "model")) {
            merged.setProperty("model", MODEL);
        }
        if (missing(merged, "maxTokens")) {
            merged.setProperty("maxTokens", Integer.toString(MAX_TOKENS));
        }
        return merged;
    }

    /** At least 256 tokens; the default when the setting is missing or malformed. */
    public static int maxTokens(Properties settings) {
        try {
            return Math.max(256, Integer.parseInt(settings.getProperty("maxTokens", Integer.toString(MAX_TOKENS))));
        } catch (NumberFormatException invalid) {
            return MAX_TOKENS;
        }
    }

    /** The chat completions address for a base address, or {@code null} if it is no HTTP(S) address. */
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
}
