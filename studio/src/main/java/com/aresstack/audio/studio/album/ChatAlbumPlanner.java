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
        JsonObject said = model.generate(INSTRUCTION, prompt(album, kept, wanted), maxTokens, answer);
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

    static String prompt(Album album, List<AlbumTrack> kept, int wanted) {
        AlbumConcept concept = album.concept();
        StringBuilder prompt = new StringBuilder();
        prompt.append("Plan an ORIGINAL instrumental album.\n");
        prompt.append("{\"album\":\"").append(escape(album.title())).append("\",\"wish\":\"")
                .append(escape(concept.description())).append("\",\"style\":\"").append(escape(concept.style()))
                .append("\",\"tracksWanted\":").append(wanted).append(",\"secondsPerTrack\":")
                .append(concept.secondsPerTrack()).append("}\n");
        if (!kept.isEmpty()) {
            prompt.append("These tracks of the album exist already; do not repeat them, plan tracks that go with them:\n");
            for (AlbumTrack track : kept) {
                prompt.append("- \"").append(escape(track.title())).append("\": ").append(track.mood()).append(", ")
                        .append(track.bpm()).append(" BPM, ").append(track.key()).append('\n');
            }
        }
        prompt.append("Plan the album as a whole, with a dramaturgy from the first track to the last: an opening, "
                + "calm and lively tracks in turn, a finale. The tracks must differ in mood, tempo, key, meter and "
                + "players, and still belong together.\n");
        prompt.append("Return one JSON object with an array 'tracks' of exactly ").append(wanted)
                .append(" objects in the order of the album. Each has: title (original, no existing work), "
                        + "mood (a few words), tempoBpm (40-200), key (like \"D major\" or \"B minor\"), "
                        + "meter (like \"4/4\", \"3/4\", \"6/8\"), instrumentation (the players in words), "
                        + "form (the sections in order, enough to fill the length without repeating one short idea, "
                        + "e.g. \"").append(DEFAULT_FORM).append("\"), targetSeconds (about ")
                .append(concept.secondsPerTrack()).append(").");
        return prompt.toString();
    }

    private static AlbumTrack track(String id, JsonObject entry, int number, AlbumConcept concept) {
        String title = AlbumFiles.text(entry, "title");
        String form = AlbumFiles.text(entry, "form");
        int seconds = AlbumFiles.number(entry, "targetSeconds", concept.secondsPerTrack());
        // The user said how long a piece is; the model may differ a little, not by half.
        if (seconds < concept.secondsPerTrack() / 2 || seconds > concept.secondsPerTrack() * 2) {
            seconds = concept.secondsPerTrack();
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
