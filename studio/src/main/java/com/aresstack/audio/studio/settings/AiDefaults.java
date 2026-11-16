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
    /** Tokens of one answer at most, as in the setup that is known to work; a cut-off answer is asked again with more. */
    public static final int MAX_TOKENS = 16384;
    /** Requests at the same time, as in the setup that is known to work. */
    public static final int PARALLEL = 8;

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

    /**
     * The lines of a template written as text (ABC and the like) the AI writes over into notes in
     * one request: {@code TextTemplate.MIN_LINES} to {@code MAX_LINES}, its default when missing or malformed.
     */
    public static int templateLines(Properties settings) {
        int fallback = com.aresstack.audio.studio.template.TextTemplate.DEFAULT_LINES;
        try {
            int lines = Integer.parseInt(settings.getProperty("templateLines", Integer.toString(fallback)).trim());
            return Math.max(com.aresstack.audio.studio.template.TextTemplate.MIN_LINES,
                    Math.min(com.aresstack.audio.studio.template.TextTemplate.MAX_LINES, lines));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /** The bars of a piece and of each step that makes it longer, unless the user says otherwise. */
    public static final int PIECE_BARS = 280;

    /**
     * The bars a new piece has, an extension adds and a long piece grows by per step: 4 to 512,
     * {@link #PIECE_BARS} when missing or malformed. Kept as {@code portionBars}; the older
     * {@code pieceBars} (40 by default, saved with every setting) and {@code growBars} are no
     * longer read, so that the new default reaches everyone.
     */
    public static int growBars(Properties settings) {
        try {
            return Math.max(4, Math.min(512,
                    Integer.parseInt(settings.getProperty("portionBars", Integer.toString(PIECE_BARS)).trim())));
        } catch (NumberFormatException invalid) {
            return PIECE_BARS;
        }
    }


    /** How many bars back a long piece is told to the AI that continues it ({@code lookBackBars}; 0: the whole piece). */
    public static int lookBackBars(Properties settings) {
        return number(settings, "lookBackBars", com.aresstack.audio.studio.ai.ContextTiers.NEAR_BARS, 0, 100000);
    }

    /** The room for the answer of one section of a continuation in tokens per bar ({@code answerTokensPerBar}; 0: always the most). */
    public static int answerTokensPerBar(Properties settings) {
        return number(settings, "answerTokensPerBar", com.aresstack.audio.studio.ai.ChatSectionGenerator.TOKENS_PER_BAR,
                0, 8192);
    }

    /** The least room for the answer of one section of a continuation, in tokens ({@code answerTokensLeast}). */
    public static int answerTokensLeast(Properties settings) {
        return number(settings, "answerTokensLeast", com.aresstack.audio.studio.ai.ChatSectionGenerator.LEAST_TOKENS,
                256, 65536);
    }

    /** Down to this room an answer is asked for again when the service finds the request too large ({@code answerTokensSmallest}). */
    public static int answerTokensSmallest(Properties settings) {
        return number(settings, "answerTokensSmallest", com.aresstack.audio.studio.ai.KipitzChatClient.SMALLEST_ANSWER,
                128, 65536);
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

    /** The first pause before an AI request is tried again, in seconds ({@code retryFirstSeconds}, 1 to 600). */
    public static int retryFirstSeconds(Properties settings) {
        return number(settings, "retryFirstSeconds", com.aresstack.audio.studio.ai.Backoff.FIRST_SECONDS, 1, 600);
    }

    /** How long the first text of an AI answer may take, in seconds ({@code firstTextSeconds}, 10 to 3600). */
    public static int firstTextSeconds(Properties settings) {
        return number(settings, "firstTextSeconds", com.aresstack.audio.studio.ai.KipitzChatClient.FIRST_TEXT_SECONDS,
                10, 3600);
    }

    /** How long an AI answer may stay silent once its text flows, in seconds ({@code silenceSeconds}, 10 to 3600). */
    public static int silenceSeconds(Properties settings) {
        return number(settings, "silenceSeconds", com.aresstack.audio.studio.ai.KipitzChatClient.SILENCE_SECONDS,
                10, 3600);
    }

    /** The longest pause before an AI request is tried again, in seconds ({@code retryMostSeconds}, 1 to 3600). */
    public static int retryMostSeconds(Properties settings) {
        return number(settings, "retryMostSeconds", com.aresstack.audio.studio.ai.Backoff.MOST_SECONDS, 1, 3600);
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
