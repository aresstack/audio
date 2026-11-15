package com.aresstack.audio.studio.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The kept answers of the AI, each with its origin in a file beside it
 * ({@code composition-plan.json} and {@code composition-plan.origin.json}).
 *
 * <p>A kept answer is given again when its origin has the identity asked for. An answer with
 * another origin, for example from another model, is put aside into {@code earlier/} (as the
 * studio does when composing anew) and the model is asked anew, so that switching the model is
 * heard. An answer kept before origins were written down has no origin file; it counts as the
 * answer of the studio's former single model (the legacy model), so pieces composed before keep
 * their answers as long as that model is asked, and only then.
 */
public final class AnswerCache {
    /** Where put-aside answers go, the same folder the studio uses when it composes anew. */
    public static final String EARLIER = "earlier";
    private static final String ORIGIN_SUFFIX = ".origin.json";

    private AnswerCache() {
    }

    /** The origin file of a kept answer. */
    public static Path originOf(Path cache) {
        String name = cache.getFileName().toString();
        String stem = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        return cache.resolveSibling(stem + ORIGIN_SUFFIX);
    }

    /** Whether a name is an origin file, not an answer. */
    public static boolean isOriginFile(Path file) {
        return file.getFileName().toString().endsWith(ORIGIN_SUFFIX);
    }

    /**
     * Whether the kept answer would be given again for this identity.
     *
     * @param legacyModel the model an answer without origin is taken to come from; {@code null}
     *                    takes any such answer, as before origins were written down
     */
    public static boolean reusable(Path cache, AnswerOrigin wanted, String legacyModel) {
        if (cache == null || !Files.isRegularFile(cache)) {
            return false;
        }
        Path origin = originOf(cache);
        if (Files.isRegularFile(origin)) {
            try {
                JsonObject kept = JsonParser.parseString(Files.readString(origin, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                return kept.has("key") && wanted.key().equals(kept.get("key").getAsString());
            } catch (IOException | RuntimeException unreadable) {
                return false;
            }
        }
        return legacyModel == null || legacyModel.trim().equals(wanted.model());
    }

    /**
     * The kept answer for this identity, or {@code null} when the model has to be asked. A kept
     * answer of another identity is put aside on the way.
     */
    public static JsonObject reuse(Path cache, AnswerOrigin wanted, String legacyModel) throws IOException {
        if (cache == null || !Files.isRegularFile(cache)) {
            return null;
        }
        if (!reusable(cache, wanted, legacyModel)) {
            putAside(cache);
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException invalidCache) {
            throw new IOException("Cached JSON is invalid: " + cache, invalidCache);
        }
    }

    /** Keeps a new answer and writes down where it came from. */
    public static void store(Path cache, AnswerOrigin origin, String json) throws IOException {
        if (cache == null) {
            return; // an answer that is not kept (e.g. proposals for the fields of an album)
        }
        Files.createDirectories(cache.toAbsolutePath().getParent());
        Files.writeString(cache, json, StandardCharsets.UTF_8);
        Files.writeString(originOf(cache), new GsonBuilder().setPrettyPrinting().create().toJson(origin.toJson()),
                StandardCharsets.UTF_8);
    }

    /** Moves an answer and its origin into {@code earlier/}, so that nothing is lost. */
    static void putAside(Path cache) throws IOException {
        Path aside = Files.createDirectories(cache.toAbsolutePath().getParent().resolve(EARLIER));
        String stamp = Long.toString(System.currentTimeMillis());
        Files.move(cache, aside.resolve(stamp + "-" + cache.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Path origin = originOf(cache);
        if (Files.isRegularFile(origin)) {
            Files.move(origin, aside.resolve(stamp + "-" + origin.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
