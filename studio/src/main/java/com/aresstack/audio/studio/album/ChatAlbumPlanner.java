package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.progress.Progress;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans an album with a chat model: one request for the whole list, so that the model sees the
 * album as a whole. What the model says is checked and brought into range; a title it leaves out
 * is filled in plainly, so that a plan never fails on a detail.
 */
public final class ChatAlbumPlanner implements AlbumPlanner {
    /** The standing instruction of the call. */
    public static final String INSTRUCTION = "You are the producer of an instrumental music album. "
            + "Return valid JSON only, no Markdown.";
    /** The file the answer is kept in, in the folder of the album. */
    public static final String ANSWER_FILE = "album-plan.json";
    private static final String DEFAULT_FORM = "intro, main theme, variation, contrasting middle part, reprise, outro";

    private final ChatModel model;
    private final int maxTokens;

    /** Is given the titles planned so far while the answer is still being written; may stay {@code null}. */
    private java.util.function.Consumer<List<AlbumTrack>> watcher;

    /**
     * Lets the planned titles be seen while the model writes them: the kept titles and the new ones
     * as far as they have arrived, a few times a second at most, on the thread that plans.
     */
    public ChatAlbumPlanner watching(java.util.function.Consumer<List<AlbumTrack>> seen) {
        this.watcher = seen;
        return this;
    }

    public ChatAlbumPlanner(ChatModel model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public Album plan(Album album, Path cache, Progress progress) throws IOException {
        AlbumConcept concept = album.concept();
        List<AlbumTrack> kept = new ArrayList<AlbumTrack>();
        for (AlbumTrack track : album.tracks()) {
            if (track.hasPiece()) {
                kept.add(track);
            }
        }
        int wanted = Math.max(0, concept.trackCount() - kept.size());
        if (wanted == 0) {
            return album.withTracks(kept);
        }
        progress.update("KI plant " + wanted + " Titel für das Album „" + album.title() + "“ ...");
        Path answer = cache == null ? null : cache.resolve(ANSWER_FILE);
        if (answer != null) {
            // A plan is asked for anew each time: the answer kept from before would only come again.
            Files.deleteIfExists(answer);
        }
        java.util.function.Consumer<String> heard = null;
        if (watcher != null) {
            String[] shown = {""};
            heard = text -> {
                List<AlbumTrack> seen = seenTracks(text, kept, wanted, concept);
                StringBuilder key = new StringBuilder();
                for (AlbumTrack track : seen) {
                    key.append(track).append('\n');
                }
                if (!key.toString().equals(shown[0])) {
                    shown[0] = key.toString();
                    watcher.accept(seen);
                }
            };
        }
        JsonObject said = model.generate(INSTRUCTION, prompt(album, kept, wanted), maxTokens, answer, heard);
        JsonArray planned = said != null && said.has("tracks") && said.get("tracks").isJsonArray()
                ? said.getAsJsonArray("tracks") : new JsonArray();

        // Kept titles stay at their places; the planned ones fill the places between and after them.
        List<AlbumTrack> fresh = new ArrayList<AlbumTrack>();
        List<String> ids = new ArrayList<String>();
        for (AlbumTrack track : kept) {
            ids.add(track.id());
        }
        for (int i = 0; i < wanted; i++) {
            JsonObject entry = i < planned.size() && planned.get(i).isJsonObject()
                    ? planned.get(i).getAsJsonObject() : new JsonObject();
            String id = freshId(ids);
            ids.add(id);
            fresh.add(track(id, entry, kept.size() + i + 1, concept));
        }
        List<AlbumTrack> tracks = new ArrayList<AlbumTrack>();
        int next = 0;
        for (int place = 0; place < album.tracks().size() && tracks.size() < concept.trackCount(); place++) {
            AlbumTrack before = album.tracks().get(place);
            if (before.hasPiece()) {
                tracks.add(before);
            } else if (next < fresh.size()) {
                tracks.add(fresh.get(next++));
            }
        }
        for (AlbumTrack track : kept) {
            if (!tracks.contains(track)) {
                tracks.add(track);
            }
        }
        while (next < fresh.size()) {
            tracks.add(fresh.get(next++));
        }
        progress.update("Trackliste bereit: " + tracks.size() + " Titel.");
        return album.withTracks(tracks);
    }

    /**
     * The titles of a plan that is still being written: the kept ones, then every new one whose
     * title has arrived, with what has arrived of the rest.
     */
    static List<AlbumTrack> seenTracks(String text, List<AlbumTrack> kept, int wanted, AlbumConcept concept) {
        List<AlbumTrack> seen = new ArrayList<AlbumTrack>(kept);
        try {
            JsonObject partial = com.aresstack.audio.studio.ai.PartialJson.read(text);
            if (partial == null || !partial.has("tracks") || !partial.get("tracks").isJsonArray()) {
                return seen;
            }
            List<String> ids = new ArrayList<String>();
            for (AlbumTrack track : kept) {
                ids.add(track.id());
            }
            JsonArray tracks = partial.getAsJsonArray("tracks");
            for (int i = 0; i < Math.min(wanted, tracks.size()); i++) {
                if (!tracks.get(i).isJsonObject() || AlbumFiles.text(tracks.get(i).getAsJsonObject(), "title").isBlank()) {
                    continue;
                }
                String id = freshId(ids);
                ids.add(id);
                seen.add(track(id, tracks.get(i).getAsJsonObject(), kept.size() + i + 1, concept));
            }
        } catch (RuntimeException unreadable) {
            // a look at the plan on its way
        }
        return seen;
    }

    static String prompt(Album album, List<AlbumTrack> kept, int wanted) {
        AlbumConcept concept = album.concept();
        StringBuilder prompt = new StringBuilder();
        prompt.append(concept.original() ? "Plan an instrumental album that rebuilds an existing work.\n"
                : "Plan an ORIGINAL instrumental album.\n");
        prompt.append("{\"album\":\"").append(escape(album.title())).append("\",\"wish\":\"")
                .append(escape(concept.description())).append("\",\"style\":\"").append(escape(concept.style()))
                .append("\",\"tracksWanted\":").append(wanted);
        if (!concept.original()) {
            // A work that is rebuilt has the lengths of its movements: no range of the album is named to it.
            prompt.append(",\"secondsPerTrack\":").append(concept.secondsPerTrack()).append(",\"secondsMin\":")
                    .append(concept.minSeconds()).append(",\"secondsMax\":").append(concept.maxSeconds());
        }
        prompt.append("}\n");
        if (!kept.isEmpty()) {
            prompt.append("These tracks of the album exist already; do not repeat them, plan tracks that go with them:\n");
            for (AlbumTrack track : kept) {
                prompt.append("- \"").append(escape(track.title())).append("\": ").append(track.mood()).append(", ")
                        .append(track.bpm()).append(" BPM, ").append(track.key()).append('\n');
            }
        }
        if (concept.original()) {
            prompt.append(com.aresstack.audio.studio.application.OriginalWork.ALBUM_GUIDANCE).append('\n');
        } else {
            prompt.append("Plan the album as a whole, with a dramaturgy from the first track to the last: an opening, "
                    + "calm and lively tracks in turn, a finale. The tracks must differ in mood, tempo, key, meter and "
                    + "players, and still belong together.\n");
        }
        prompt.append("Return one JSON object with an array 'tracks' of exactly ").append(wanted)
                .append(" objects in the order of the album. Each has: title (")
                .append(concept.original() ? "the real title of the movement" : "original, no existing work")
                .append("), "
                        + "mood (a few words), tempoBpm (40-200), key (like \"D major\" or \"B minor\"), "
                        + "meter (like \"4/4\", \"3/4\", \"6/8\"), instrumentation (the players in words), "
                        + "form (the sections in order, enough to fill the length without repeating one short idea, "
                        + "e.g. \"").append(DEFAULT_FORM).append("\"), targetSeconds (")
                .append(concept.original() ? "the length of a usual performance of the movement"
                        : concept.minSeconds() == concept.maxSeconds() ? "about " + concept.secondsPerTrack()
                        : "between " + concept.minSeconds() + " and " + concept.maxSeconds()
                        + "; let the lengths differ from track to track, as the music needs").append(").");
        return prompt.toString();
    }

    private static AlbumTrack track(String id, JsonObject entry, int number, AlbumConcept concept) {
        String title = AlbumFiles.text(entry, "title");
        String form = AlbumFiles.text(entry, "form");
        int seconds = AlbumFiles.number(entry, "targetSeconds", concept.secondsPerTrack());
        // The user said how long a piece may be: a length far off is replaced by the middle, one a
        // little off is brought into the range.
        if (concept.original()) {
            // The original decides how long a movement is, not the range of the album.
            seconds = Math.max(10, Math.min(AlbumConcept.MAX_SECONDS, seconds));
        } else {
            if (seconds < concept.minSeconds() / 2 || seconds > concept.maxSeconds() * 2) {
                seconds = concept.secondsPerTrack();
            }
            seconds = Math.max(concept.minSeconds(), Math.min(concept.maxSeconds(), seconds));
        }
        int bpm = AlbumFiles.number(entry, "tempoBpm", 100);
        return new AlbumTrack(id, title.isBlank() ? "Titel " + number : title, AlbumFiles.text(entry, "mood"),
                bpm < 40 || bpm > 200 ? 100 : bpm, AlbumFiles.text(entry, "key"), AlbumFiles.text(entry, "meter"),
                text(entry.get("instrumentation")), form.isBlank() ? DEFAULT_FORM : form, seconds, "");
    }

    /** The players as words, also if the model gave a list. */
    private static String text(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonArray()) {
            StringBuilder words = new StringBuilder();
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    words.append(words.length() == 0 ? "" : ", ").append(element.getAsString());
                }
            }
            return words.toString();
        }
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String freshId(List<String> taken) {
        for (int number = 1; ; number++) {
            if (!taken.contains("t" + number)) {
                return "t" + number;
            }
        }
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
