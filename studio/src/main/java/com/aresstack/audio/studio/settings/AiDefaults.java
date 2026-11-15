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

    /** The bars of one request for notes: 2 to 64, 8 when the setting is missing or malformed. */
    public static int sectionBars(Properties settings) {
        try {
            return Math.max(2, Math.min(64, Integer.parseInt(settings.getProperty("sectionBars", "8").trim())));
        } catch (NumberFormatException invalid) {
            return 8;
        }
    }

    /** The bars of a piece and of each step that makes it longer, unless the user says otherwise. */
    public static final int PIECE_BARS = 40;

    /**
     * The bars a new piece has, an extension adds and a long piece grows by per step: 4 to 256,
     * {@link #PIECE_BARS} when missing or malformed. Kept as {@code pieceBars}; the older
     * {@code growBars} (16 by default, saved with every setting) is no longer read, so that the
     * new default reaches everyone.
     */
    public static int growBars(Properties settings) {
        try {
            return Math.max(4, Math.min(256,
                    Integer.parseInt(settings.getProperty("pieceBars", Integer.toString(PIECE_BARS)).trim())));
        } catch (NumberFormatException invalid) {
            return PIECE_BARS;
        }
    }

    /** How many titles of an album are made at the same time ({@code albumPiecesAtOnce}, 1 to 16; 2 by default). */
    public static int albumPiecesAtOnce(Properties settings) {
        return number(settings, "albumPiecesAtOnce", 2, 1, 16);
    }

    /** How often an answer cut off at its limit is asked for again ({@code longerAnswers}, 0 to 6). */
    public static int longerAnswers(Properties settings) {
        return number(settings, "longerAnswers", com.aresstack.audio.studio.ai.KipitzChatClient.LONGER_ANSWERS, 0, 6);
    }

    /** By how much the room grows each time it is asked again ({@code longerAnswerFactor}, 2 to 4). */
    public static int longerAnswerFactor(Properties settings) {
        return number(settings, "longerAnswerFactor", com.aresstack.audio.studio.ai.KipitzChatClient.LONGER_FACTOR,
                2, 4);
    }

    private static int number(Properties settings, String key, int fallback, int least, int most) {
        try {
            return Math.max(least, Math.min(most,
                    Integer.parseInt(settings.getProperty(key, Integer.toString(fallback)).trim())));
        } catch (NumberFormatException invalid) {
            return fallback;
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
