package com.aresstack.audio.studio.album;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * What the assistant of the track list may do with an album: read it, put in titles, change,
 * remove and move one, and set the main theme. Each gives the changed album; a title that has
 * its piece already is never changed or removed here. Titles put in by the assistant count as
 * given by the user: planning keeps them and only fills in what they lack.
 */
public final class AlbumTools {
    private AlbumTools() {
    }

    /** The album as the assistant reads it: concept, titles with their numbers, main theme. */
    public static String read(Album album) {
        JsonObject json = AlbumFiles.write(album);
        json.remove("format");
        JsonArray tracks = json.getAsJsonArray("tracks");
        for (int i = 0; i < tracks.size(); i++) {
            JsonObject track = tracks.get(i).getAsJsonObject();
            track.remove("id");
            track.addProperty("number", i + 1);
            track.addProperty("hasPiece", album.tracks().get(i).hasPiece());
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(json);
    }

    /**
     * Puts titles in: {@code {"titles":[{title, mood, tempoBpm, key, meter, instrumentation, form,
     * targetSeconds}], "replace": true|false}}. With {@code replace} the titles without a piece go
     * first; the number of pieces follows the list.
     */
    public static Album addTracks(Album album, JsonObject arguments) {
        JsonArray titles = arguments.has("titles") && arguments.get("titles").isJsonArray()
                ? arguments.getAsJsonArray("titles") : new JsonArray();
        if (titles.size() == 0) {
            throw new IllegalArgumentException("Keine Titel angegeben.");
        }
        boolean replace = arguments.has("replace") && arguments.get("replace").getAsBoolean();
        List<AlbumTrack> tracks = new ArrayList<AlbumTrack>();
        for (AlbumTrack track : album.tracks()) {
            if (!replace || track.hasPiece()) {
                tracks.add(track);
            }
        }
        Album growing = album.withTracks(tracks);
        for (JsonElement element : titles) {
            if (tracks.size() >= AlbumConcept.MAX_TRACKS) {
                break;
            }
            JsonObject entry = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
            AlbumTrack fresh = apply(AlbumTrack.given(growing.freshTrackId(), AlbumFiles.text(entry, "title"), "")
                    .withSeconds(album.concept().secondsPerTrack()), entry);
            tracks.add(fresh);
            growing = album.withTracks(tracks);
        }
        return counted(growing);
    }

    /** Changes the fields named of the title with this {@code number} (from 1). */
    public static Album changeTrack(Album album, JsonObject arguments) {
        AlbumTrack track = numbered(album, arguments, "number");
        return album.with(apply(track, arguments).withGiven(true));
    }

    /** Takes the title with this {@code number} out of the list. */
    public static Album removeTrack(Album album, JsonObject arguments) {
        AlbumTrack track = numbered(album, arguments, "number");
        List<AlbumTrack> tracks = new ArrayList<AlbumTrack>(album.tracks());
        tracks.remove(track);
        return album.withTracks(tracks);
    }

    /** Moves the title with this {@code number} to the place {@code to} (both from 1). */
    public static Album moveTrack(Album album, JsonObject arguments) {
        AlbumTrack track = album.tracks().get(index(album, arguments, "number"));
        int to = Math.max(1, Math.min(album.tracks().size(), AlbumFiles.number(arguments, "to", 1))) - 1;
        List<AlbumTrack> tracks = new ArrayList<AlbumTrack>(album.tracks());
        tracks.remove(track);
        tracks.add(to, track);
        return album.withTracks(tracks);
    }

    /** Sets the main theme: {@code {name, description, key, notes:[{beat, duration, pitch}]}}. */
    public static Album setTheme(Album album, JsonObject arguments) {
        AlbumTheme theme = AlbumFiles.readTheme(arguments);
        if (theme.isEmpty()) {
            throw new IllegalArgumentException("Das Hauptthema braucht Noten.");
        }
        return album.withTheme(theme);
    }

    private static AlbumTrack apply(AlbumTrack track, JsonObject entry) {
        AlbumTrack changed = track;
        if (entry.has("title") && !AlbumFiles.text(entry, "title").isBlank()) {
            changed = changed.withTitle(AlbumFiles.text(entry, "title"));
        }
        if (entry.has("mood")) {
            changed = changed.withMood(AlbumFiles.text(entry, "mood"));
        }
        if (entry.has("tempoBpm")) {
            changed = changed.withBpm(AlbumFiles.number(entry, "tempoBpm", changed.bpm()));
        }
        if (entry.has("key")) {
            changed = changed.withKey(AlbumFiles.text(entry, "key"));
        }
        if (entry.has("meter")) {
            changed = changed.withMeter(AlbumFiles.text(entry, "meter"));
        }
        if (entry.has("instrumentation")) {
            changed = changed.withInstrumentation(AlbumFiles.text(entry, "instrumentation"));
        }
        if (entry.has("form")) {
            changed = changed.withForm(AlbumFiles.text(entry, "form"));
        }
        if (entry.has("targetSeconds")) {
            changed = changed.withSeconds(AlbumFiles.number(entry, "targetSeconds", changed.targetSeconds()));
        }
        if (entry.has("theme")) {
            changed = changed.withTheme(AlbumFiles.text(entry, "theme"));
        }
        return changed;
    }

    private static AlbumTrack numbered(Album album, JsonObject arguments, String name) {
        AlbumTrack track = album.tracks().get(index(album, arguments, name));
        if (track.hasPiece()) {
            throw new IllegalArgumentException("Titel " + (album.tracks().indexOf(track) + 1)
                    + " hat schon ein Stück; er bleibt, wie er ist.");
        }
        return track;
    }

    private static int index(Album album, JsonObject arguments, String name) {
        int number = AlbumFiles.number(arguments, name, 0);
        if (number < 1 || number > album.tracks().size()) {
            throw new IllegalArgumentException("Es gibt keinen Titel Nummer " + number + "; die Liste hat "
                    + album.tracks().size() + ".");
        }
        return number - 1;
    }

    /** The number of pieces follows the list when it is longer. */
    private static Album counted(Album album) {
        AlbumConcept concept = album.concept();
        int count = Math.max(1, album.tracks().size());
        if (count == concept.trackCount()) {
            return album;
        }
        return album.withConcept(new AlbumConcept(concept.description(), concept.style(), count,
                concept.minSeconds(), concept.maxSeconds(), concept.original()));
    }

    /** The tools' argument schemas, for the chat API. */
    public static final String TRACK_FIELDS = "\"title\":{\"type\":\"string\"},\"mood\":{\"type\":\"string\","
            + "\"description\":\"mood and musical direction in a few words\"},\"tempoBpm\":{\"type\":\"integer\"},"
            + "\"key\":{\"type\":\"string\",\"description\":\"like D major or B minor\"},\"meter\":{\"type\":\"string\","
            + "\"description\":\"like 4/4 or 6/8\"},\"instrumentation\":{\"type\":\"string\"},\"form\":{\"type\":"
            + "\"string\"},\"targetSeconds\":{\"type\":\"integer\"},\"theme\":{\"type\":\"string\",\"description\":"
            + "\"how the main theme appears in this track, empty if not\"}";
}
