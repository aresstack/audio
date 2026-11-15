package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.ai.ChatModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Proposals from a chat model that is asked as someone who knows music: styles are genres, eras
 * and line-ups that exist, not words that merely sound alike. One short request per field; what
 * the model says is cleaned and shortened, and a broken answer gives no proposals instead of an
 * error.
 */
public final class ChatAlbumSuggester implements AlbumSuggester {
    /** The standing instruction of the call. */
    public static final String INSTRUCTION = "You are a musicologist and producer who helps to describe an "
            + "instrumental music album. You know genres, subgenres, eras, forms and ensembles. "
            + "Return valid JSON only, no Markdown.";
    /** How many proposals one request asks for. */
    public static final int COUNT = 8;
    /** No proposal is longer than this. */
    public static final int MAX_LENGTH = 240;
    private static final int KNOWN_SHOWN = 30;

    private final ChatModel model;
    private final int maxTokens;

    public ChatAlbumSuggester(ChatModel model, int maxTokens) {
        this.model = model;
        // Room for a model that thinks before it answers: its thinking counts as tokens of the answer,
        // and an answer cut off at the limit is no answer.
        this.maxTokens = Math.max(256, Math.min(maxTokens, 4096));
    }

    @Override
    public List<String> suggest(Field field, String title, String description, String style, List<String> known)
            throws IOException {
        return suggest(field, title, description, style, known, null);
    }

    @Override
    public List<String> suggest(Field field, String title, String description, String style, List<String> known,
                                java.util.function.Consumer<List<String>> seen) throws IOException {
        java.util.function.Consumer<String> heard = null;
        if (seen != null) {
            List<List<String>> shown = new ArrayList<List<String>>(List.of(List.of()));
            heard = text -> {
                List<String> now;
                try {
                    now = clean(com.aresstack.audio.studio.ai.PartialJson.read(text), known);
                } catch (RuntimeException unreadable) {
                    return; // a look at the answer on its way
                }
                if (!now.equals(shown.get(0))) {
                    shown.set(0, now);
                    seen.accept(now);
                }
            };
        }
        JsonObject said = model.generate(INSTRUCTION, prompt(field, title, description, style, known), maxTokens,
                null, heard);
        return clean(said, known);
    }

    /** The proposals of an answer, whole or still being written: cleaned, shortened, none twice. */
    static List<String> clean(JsonObject said, List<String> known) {
        List<String> proposals = new ArrayList<String>();
        if (said == null || !said.has("suggestions") || !said.get("suggestions").isJsonArray()) {
            return proposals;
        }
        JsonArray array = said.getAsJsonArray("suggestions");
        for (JsonElement entry : array) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                continue;
            }
            String text = entry.getAsString().replaceAll("\\s+", " ").trim();
            if (text.length() > MAX_LENGTH) {
                text = text.substring(0, MAX_LENGTH).trim();
            }
            if (!text.isEmpty() && !AlbumSuggestions.contains(proposals, text)
                    && !AlbumSuggestions.contains(known, text)) {
                proposals.add(text);
            }
        }
        return proposals;
    }

    static String prompt(Field field, String title, String description, String style, List<String> known) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("{\"album\":\"").append(escape(title)).append("\",\"wish\":\"").append(escape(description))
                .append("\",\"style\":\"").append(escape(style)).append("\"}\n");
        switch (field) {
            case STYLE -> {
                if (style == null || style.isBlank()) {
                    prompt.append("Propose ").append(COUNT).append(" different musical directions this album "
                            + "could take: a rough choice across what really fits its title and wish. Each names "
                            + "a genre or tradition and its typical players, e.g. for Christmas carols: a cappella "
                            + "choir, organ music, solo violin with piano, brass ensemble, baroque chamber "
                            + "orchestra. Keep each to a few words.\n");
                } else {
                    prompt.append("The user is typing the style and has written \"").append(escape(style))
                            .append("\". Propose ").append(COUNT).append(" more precise styles that refine exactly "
                                    + "this: real subgenres, eras, schools or line-ups of it, each with what makes "
                                    + "it recognisable (players, character, tempo feel), e.g. for \"classical\": "
                                    + "\"Viennese classical, string quartet, light and clear\", \"Baroque, "
                                    + "harpsichord and strings, festive\", \"late Romantic, large orchestra, "
                                    + "sweeping\". Stay within what was typed and what fits the album.\n");
                }
                prompt.append("Only styles that exist in music and can be played by instruments; no artists, no "
                        + "existing works.\n");
            }
            case TITLE -> prompt.append("Propose ").append(COUNT).append(" other titles for this album: original, "
                    + "no existing album or work, fitting its wish and style, different in tone from one another "
                    + "(plain, poetic, playful).\n");
            default -> prompt.append("Propose ").append(COUNT).append(" different short descriptions of what this "
                    + "album could be about, as its maker would tell a producer: occasion or theme, mood, players, "
                    + "and how it develops from the first piece to the last. One or two sentences each, at most "
                    + "200 characters. The album is instrumental. They are ideas to start from and should differ "
                    + "clearly.\n");
        }
        if (known != null && !known.isEmpty()) {
            prompt.append("Already proposed, do not repeat these:");
            for (int i = 0; i < Math.min(KNOWN_SHOWN, known.size()); i++) {
                prompt.append(i == 0 ? " \"" : ", \"").append(escape(known.get(i))).append('"');
            }
            prompt.append('\n');
        }
        prompt.append("Write in the language of the album title and wish. Return one JSON object with an array "
                + "'suggestions' of ").append(COUNT).append(" strings, the best first.");
        return prompt.toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").trim();
    }
}
