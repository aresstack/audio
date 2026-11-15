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
    /** What the plan is told about the main theme of an album that has none yet. */
    static final String THEME_GUIDANCE = "Give the album ONE memorable main theme (leitmotif) that ties the tracks "
            + "together: a melody of 4 to 8 bars, written out as notes. Let it come back in several tracks, each "
            + "time in a variant that fits the track (e.g. hopeful at first, mysterious or dramatic on the way, "
            + "triumphant at the end); calm or very different tracks may leave it out.";
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

    /** How many movements of a work that is rebuilt are planned at once (setting "Parallel"). */
    private int atOnce = 1;

    public ChatAlbumPlanner parallel(int requests) {
        this.atOnce = Math.max(1, requests);
        return this;
    }

    public ChatAlbumPlanner(ChatModel model, int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public Album plan(Album album, Path cache, Progress progress) throws IOException {
        AlbumConcept concept = album.concept();
        if (concept.original()) {
            return rebuild(album, cache, progress);
        }
        List<AlbumTrack> kept = new ArrayList<AlbumTrack>();
        List<AlbumTrack> given = new ArrayList<AlbumTrack>();
        for (AlbumTrack track : album.tracks()) {
            if (track.hasPiece()) {
                kept.add(track);
            } else if (track.given()) {
                given.add(track);
            }
        }
        List<String> ids = new ArrayList<String>();
        for (AlbumTrack track : album.tracks()) {
            ids.add(track.id());
        }
        // Without titles given in the list, a list of titles in the wish is taken: they replace
        // the titles planned before that have no piece yet.
        boolean listed = false;
        if (given.isEmpty()) {
            List<String> free = new ArrayList<String>();
            for (AlbumTrack track : kept) {
                free.add(track.id());
            }
            for (TitleList.Entry entry : TitleList.in(concept.description())) {
                String id = freshId(free);
                free.add(id);
                given.add(AlbumTrack.given(id, entry.title(), entry.direction()));
            }
            listed = !given.isEmpty();
            if (listed) {
                ids = free;
                progress.update("Die Beschreibung nennt " + given.size() + " Titel; sie werden übernommen.");
            }
        }
        int fresh = Math.max(0, concept.trackCount() - kept.size() - given.size());
        int wanted = given.size() + fresh;
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
            List<AlbumTrack> before = given;
            heard = text -> {
                List<AlbumTrack> seen = seenTracks(text, kept, before, wanted, concept);
                StringBuilder key = new StringBuilder();
                for (AlbumTrack track : seen) {
                    key.append(track).append('\n');
                }
                if (!key.toString().equals(shown[0])) {
                    shown[0] = key.toString();
                    watcher.accept(seen);
                }
            };
            watcher.accept(seenTracks("", kept, given, wanted, concept));
        }
        JsonObject said = model.generate(INSTRUCTION, prompt(album, kept, given, wanted), maxTokens, answer, heard);
        JsonArray planned = said != null && said.has("tracks") && said.get("tracks").isJsonArray()
                ? said.getAsJsonArray("tracks") : new JsonArray();
        AlbumTheme theme = album.theme();
        if (theme.isEmpty() && said != null && said.has("mainTheme") && said.get("mainTheme").isJsonObject()) {
            theme = AlbumFiles.readTheme(said.getAsJsonObject("mainTheme"));
        }

        // Kept titles stay at their places, given ones too; the planned ones fill the places between and after them.
        List<AlbumTrack> givenPlanned = new ArrayList<AlbumTrack>();
        List<AlbumTrack> freshPlanned = new ArrayList<AlbumTrack>();
        for (int i = 0; i < wanted; i++) {
            JsonObject entry = i < planned.size() && planned.get(i).isJsonObject()
                    ? planned.get(i).getAsJsonObject() : new JsonObject();
            if (i < given.size()) {
                givenPlanned.add(filled(given.get(i), entry, kept.size() + i + 1, concept));
            } else {
                String id = freshId(ids);
                ids.add(id);
                freshPlanned.add(track(id, entry, kept.size() + i + 1, concept));
            }
        }
        List<AlbumTrack> queue = new ArrayList<AlbumTrack>();
        if (listed) {
            queue.addAll(givenPlanned);
        }
        queue.addAll(freshPlanned);
        int total = kept.size() + wanted;
        List<AlbumTrack> tracks = new ArrayList<AlbumTrack>();
        int next = 0;
        for (int place = 0; place < album.tracks().size() && tracks.size() < total; place++) {
            AlbumTrack before = album.tracks().get(place);
            if (before.hasPiece()) {
                tracks.add(before);
            } else if (!listed && before.given()) {
                tracks.add(givenPlanned.get(given.indexOf(before)));
            } else if (next < queue.size()) {
                tracks.add(queue.get(next++));
            }
        }
        for (AlbumTrack track : kept) {
            if (!tracks.contains(track)) {
                tracks.add(track);
            }
        }
        if (!listed) {
            for (AlbumTrack track : givenPlanned) {
                if (!tracks.contains(track)) {
                    tracks.add(track);
                }
            }
        }
        while (next < queue.size()) {
            tracks.add(queue.get(next++));
        }
        progress.update("Trackliste bereit: " + tracks.size() + " Titel"
                + (theme.isEmpty() ? "." : ", mit dem Hauptthema „" + theme.name() + "“."));
        Album result = album.withTracks(tracks).withTheme(theme);
        if (tracks.size() > concept.trackCount() && tracks.size() <= AlbumConcept.MAX_TRACKS) {
            result = result.withConcept(new AlbumConcept(concept.description(), concept.style(), tracks.size(),
                    concept.minSeconds(), concept.maxSeconds(), concept.original()));
        }
        return result;
    }

    /** A given title with what the plan says about the rest: its name stays, and its mood if the user said one. */
    private static AlbumTrack filled(AlbumTrack given, JsonObject entry, int number, AlbumConcept concept) {
        AlbumTrack planned = track(given.id(), entry, number, concept);
        return new AlbumTrack(given.id(), given.title(), given.mood().isEmpty() ? planned.mood() : given.mood(),
                planned.bpm(), planned.key(), planned.meter(), planned.instrumentation(), planned.form(),
                planned.targetSeconds(), "", true, planned.theme());
    }

    /**
     * "Original nachbilden": first the movements of the work are found, as many as the work has
     * (not as many as the album asked for), then every movement is planned in a request of its
     * own, with its real title, key, meter, tempo, length and form. Each planned movement is shown
     * as soon as it is there. A title that has a piece already stays, at the place of the
     * movement of the same name, or after the movements.
     */
    private Album rebuild(Album album, Path cache, Progress progress) throws IOException {
        AlbumConcept concept = album.concept();
        progress.update("KI ermittelt die Sätze des Werks für „" + album.title() + "“ ...");
        Path listed = cache == null ? null : cache.resolve("album-movements.json");
        if (listed != null) {
            Files.deleteIfExists(listed);
        }
        JsonObject said = model.generate(INSTRUCTION, movementsPrompt(album), maxTokens, listed);
        List<String> movements = new ArrayList<String>();
        JsonArray named = said != null && said.has("movements") && said.get("movements").isJsonArray()
                ? said.getAsJsonArray("movements") : new JsonArray();
        for (JsonElement element : named) {
            String title = element.isJsonObject() ? AlbumFiles.text(element.getAsJsonObject(), "title")
                    : element.isJsonPrimitive() ? element.getAsString().trim() : "";
            if (!title.isBlank() && movements.size() < AlbumConcept.MAX_TRACKS) {
                movements.add(title);
            }
        }
        if (movements.isEmpty()) {
            throw new IOException("Die KI hat keine Sätze des Werks genannt; bitte das Werk im Text genauer nennen.");
        }
        progress.update("Das Werk hat " + movements.size() + " Sätze; jeder wird einzeln geplant.");
        List<AlbumTrack> kept = new ArrayList<AlbumTrack>();
        for (AlbumTrack track : album.tracks()) {
            if (track.hasPiece()) {
                kept.add(track);
            }
        }
        List<String> ids = new ArrayList<String>();
        for (AlbumTrack track : kept) {
            ids.add(track.id());
        }
        // Every movement has its row at once, in the order of the work; the rows are filled as their
        // answers come, several at a time.
        AlbumTrack[] rows = new AlbumTrack[movements.size()];
        List<Integer> open = new ArrayList<Integer>();
        for (int i = 0; i < movements.size(); i++) {
            AlbumTrack same = null;
            for (AlbumTrack track : kept) {
                if (track.title().equalsIgnoreCase(movements.get(i))) {
                    same = track;
                }
            }
            if (same != null) {
                rows[i] = same;
                kept.remove(same);
            } else {
                String id = freshId(ids);
                ids.add(id);
                JsonObject placeholder = new JsonObject();
                placeholder.addProperty("title", movements.get(i));
                rows[i] = track(id, placeholder, i + 1, concept);
                open.add(i);
            }
        }
        show(rows, kept);
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(atOnce, Math.max(1, open.size())), task -> {
                    Thread thread = new Thread(task, "album-movement");
                    thread.setDaemon(true);
                    return thread;
                });
        List<java.util.concurrent.Future<?>> asked = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i : open) {
            asked.add(workers.submit(() -> {
                Path answer = cache == null ? null : cache.resolve("album-movement-" + (i + 1) + ".json");
                if (answer != null) {
                    Files.deleteIfExists(answer);
                }
                progress.update("KI plant Satz " + (i + 1) + " von " + movements.size() + ": " + movements.get(i)
                        + " ...");
                JsonObject entry = model.generate(INSTRUCTION, movementPrompt(album, movements, i), maxTokens, answer);
                entry = entry == null ? new JsonObject() : entry;
                if (AlbumFiles.text(entry, "title").isBlank()) {
                    entry.addProperty("title", movements.get(i));
                }
                synchronized (rows) {
                    rows[i] = track(rows[i].id(), entry, i + 1, concept);
                    show(rows, kept);
                }
                return null;
            }));
        }
        workers.shutdown();
        try {
            for (java.util.concurrent.Future<?> each : asked) {
                each.get();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Planen abgebrochen.");
        } catch (java.util.concurrent.ExecutionException failed) {
            workers.shutdownNow();
            Throwable cause = failed.getCause();
            throw cause instanceof IOException io ? io : new IOException(cause.getMessage(), cause);
        }
        List<AlbumTrack> tracks = new ArrayList<AlbumTrack>(java.util.Arrays.asList(rows));
        tracks.addAll(kept);
        progress.update("Trackliste bereit: " + tracks.size() + " Sätze des Originals.");
        AlbumConcept counted = new AlbumConcept(concept.description(), concept.style(), Math.max(1, tracks.size()),
                concept.minSeconds(), concept.maxSeconds(), true);
        return album.withConcept(counted).withTracks(tracks);
    }

    private void show(AlbumTrack[] rows, List<AlbumTrack> kept) {
        if (watcher != null) {
            List<AlbumTrack> seen = new ArrayList<AlbumTrack>(java.util.Arrays.asList(rows));
            seen.addAll(kept);
            watcher.accept(seen);
        }
    }

    static String movementsPrompt(Album album) {
        AlbumConcept concept = album.concept();
        return "List the movements of the existing work this album rebuilds, in their original order.\n"
                + "{\"album\":\"" + escape(album.title()) + "\",\"wish\":\"" + escape(concept.description())
                + "\",\"style\":\"" + escape(concept.style()) + "\"}\n"
                + "Name every movement (or every piece, for a collection) the work has, as many as it has, with its "
                + "real title, e.g. \"Spring – I. Allegro\", in the language of the wish. Invent nothing.\n"
                + "Return one JSON object: {\"movements\": [{\"title\": \"...\"}, ...]}.";
    }

    static String movementPrompt(Album album, List<String> movements, int index) {
        AlbumConcept concept = album.concept();
        StringBuilder prompt = new StringBuilder();
        prompt.append("Plan ONE track of an album that rebuilds an existing work: movement ").append(index + 1)
                .append(" of ").append(movements.size()).append(", \"").append(escape(movements.get(index)))
                .append("\".\n");
        prompt.append("{\"album\":\"").append(escape(album.title())).append("\",\"wish\":\"")
                .append(escape(concept.description())).append("\",\"style\":\"").append(escape(concept.style()))
                .append("\"}\n");
        prompt.append("All movements of the work: ");
        for (int i = 0; i < movements.size(); i++) {
            prompt.append(i == 0 ? "" : "; ").append(i + 1).append(". ").append(escape(movements.get(i)));
        }
        prompt.append("\nLook up this movement as the original has it and invent nothing: ")
                .append(com.aresstack.audio.studio.application.OriginalWork.GUIDANCE).append('\n');
        prompt.append("Return one JSON object with: title (the real title of the movement), mood (a few words), "
                + "tempoBpm (the original tempo, 40-200), key (the original key, like \"E major\" or \"C# minor\"), "
                + "meter (the original meter, like \"3/4\" or \"12/8\"), instrumentation (the original players), "
                + "form (the original sections and themes in order), targetSeconds (the length of a usual "
                + "performance of this movement).");
        return prompt.toString();
    }

    /**
     * The titles of a plan that is still being written: the kept ones, then every new one whose
     * title has arrived, with what has arrived of the rest.
     */
    static List<AlbumTrack> seenTracks(String text, List<AlbumTrack> kept, int wanted, AlbumConcept concept) {
        return seenTracks(text, kept, List.of(), wanted, concept);
    }

    /** As above, with the given titles first, each shown at once and filled as the plan arrives. */
    static List<AlbumTrack> seenTracks(String text, List<AlbumTrack> kept, List<AlbumTrack> given, int wanted,
                                       AlbumConcept concept) {
        List<AlbumTrack> seen = new ArrayList<AlbumTrack>(kept);
        JsonArray tracks = new JsonArray();
        try {
            JsonObject partial = text == null || text.isEmpty() ? null
                    : com.aresstack.audio.studio.ai.PartialJson.read(text);
            if (partial != null && partial.has("tracks") && partial.get("tracks").isJsonArray()) {
                tracks = partial.getAsJsonArray("tracks");
            }
        } catch (RuntimeException unreadable) {
            // a look at the plan on its way
        }
        List<String> ids = new ArrayList<String>();
        for (AlbumTrack track : kept) {
            ids.add(track.id());
        }
        for (AlbumTrack track : given) {
            ids.add(track.id());
        }
        for (int i = 0; i < given.size(); i++) {
            try {
                seen.add(i < tracks.size() && tracks.get(i).isJsonObject()
                        ? filled(given.get(i), tracks.get(i).getAsJsonObject(), kept.size() + i + 1, concept)
                        : given.get(i));
            } catch (RuntimeException odd) {
                seen.add(given.get(i));
            }
        }
        for (int i = given.size(); i < Math.min(wanted, tracks.size()); i++) {
            try {
                if (!tracks.get(i).isJsonObject()
                        || AlbumFiles.text(tracks.get(i).getAsJsonObject(), "title").isBlank()) {
                    continue;
                }
                String id = freshId(ids);
                ids.add(id);
                seen.add(track(id, tracks.get(i).getAsJsonObject(), kept.size() + i + 1, concept));
            } catch (RuntimeException odd) {
                // a look at the plan on its way
            }
        }
        return seen;
    }

    static String prompt(Album album, List<AlbumTrack> kept, int wanted) {
        return prompt(album, kept, List.of(), wanted);
    }

    static String prompt(Album album, List<AlbumTrack> kept, List<AlbumTrack> given, int wanted) {
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
        if (!given.isEmpty()) {
            prompt.append("The user gives the first ").append(given.size()).append(" tracks, in this order. Keep each "
                    + "title exactly as given and follow what the user says about it; plan the rest of each:\n");
            for (int i = 0; i < given.size(); i++) {
                AlbumTrack track = given.get(i);
                prompt.append(i + 1).append(". \"").append(escape(track.title())).append('"');
                if (!track.mood().isEmpty()) {
                    prompt.append(": ").append(escape(track.mood()));
                }
                prompt.append('\n');
            }
        }
        if (!concept.original()) {
            if (album.theme().isEmpty()) {
                prompt.append(THEME_GUIDANCE).append('\n');
            } else {
                prompt.append("The album has this main theme (leitmotif), \"").append(escape(album.theme().name()))
                        .append("\": ").append(escape(album.theme().description())).append(". Notes: ")
                        .append(album.theme().notesText()).append(". Say for each track how it appears there.\n");
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
                        + "; let the lengths differ from track to track, as the music needs").append(")");
        if (concept.original()) {
            prompt.append('.');
        } else {
            prompt.append(", theme (how the main theme appears in this track, e.g. \"hopeful, on solo horn\" or "
                    + "\"dark variant in minor, low strings\"; empty if it does not appear).");
            if (album.theme().isEmpty()) {
                prompt.append(" Also return 'mainTheme': {name, description, key, notes: [{beat, duration, pitch}, "
                        + "...]}, beats in quarters from the start of the theme, MIDI pitches 55-84.");
            }
        }
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
                text(entry.get("instrumentation")), form.isBlank() ? DEFAULT_FORM : form, seconds, "", false,
                concept.original() ? "" : AlbumFiles.text(entry, "theme"));
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
