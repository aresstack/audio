package com.aresstack.audio.studio.album;

import java.util.ArrayList;
import java.util.List;

/**
 * An album: several pieces that belong together. It is an aggregate of ordinary pieces of the
 * library, not a kind of piece: it holds what the album is about and the list of its titles, and
 * names the folder of each piece. Immutable; every change gives a new album.
 *
 * @param id      the name of the folder of the album, fixed when it is created
 * @param title   what the album is called
 * @param concept what the user said about the whole album
 * @param tracks  its titles in order
 * @param theme   its main theme, which comes back in several pieces; {@link AlbumTheme#NONE} if it has none
 */
public record Album(String id, String title, AlbumConcept concept, List<AlbumTrack> tracks, AlbumTheme theme) {
    public Album {
        theme = theme == null ? AlbumTheme.NONE : theme;
        if (!isId(id)) {
            throw new IllegalArgumentException("Keine Kennung eines Albums: " + id);
        }
        title = title == null || title.isBlank() ? "Album" : title.trim();
        if (concept == null) {
            throw new IllegalArgumentException("Ein Album braucht ein Konzept.");
        }
        tracks = List.copyOf(tracks);
        List<String> seen = new ArrayList<String>();
        for (AlbumTrack track : tracks) {
            if (seen.contains(track.id())) {
                throw new IllegalArgumentException("Zwei Titel haben dieselbe Kennung: " + track.id());
            }
            seen.add(track.id());
        }
    }

    /**
     * Whether this is the id of an album: lower-case letters, digits and hyphens, starting with a
     * letter or digit. An id is the name of the album's folder, so nothing else may ever be one:
     * no dot, no separator, nothing that leads out of the folder of the albums.
     */
    public static boolean isId(String id) {
        return id != null && id.length() <= 80 && id.matches("[a-z0-9][a-z0-9-]*");
    }

    /** An album without a main theme. */
    public Album(String id, String title, AlbumConcept concept, List<AlbumTrack> tracks) {
        this(id, title, concept, tracks, AlbumTheme.NONE);
    }

    public Album withTracks(List<AlbumTrack> changed) {
        return new Album(id, title, concept, changed, theme);
    }

    public Album withTitle(String changed) {
        return new Album(id, changed, concept, tracks, theme);
    }

    public Album withConcept(AlbumConcept changed) {
        return new Album(id, title, changed, tracks, theme);
    }

    public Album withTheme(AlbumTheme changed) {
        return new Album(id, title, concept, tracks, changed);
    }

    /** The title with this id, or {@code null}. */
    public AlbumTrack track(String trackId) {
        for (AlbumTrack track : tracks) {
            if (track.id().equals(trackId)) {
                return track;
            }
        }
        return null;
    }

    /** The album with one title exchanged for another of the same id. */
    public Album with(AlbumTrack changed) {
        List<AlbumTrack> next = new ArrayList<AlbumTrack>();
        for (AlbumTrack track : tracks) {
            next.add(track.id().equals(changed.id()) ? changed : track);
        }
        return withTracks(next);
    }

    /** How long the album is meant to be: the sum of what its titles ask for, in seconds. */
    public int targetSeconds() {
        int sum = 0;
        for (AlbumTrack track : tracks) {
            sum += track.targetSeconds();
        }
        return sum;
    }

    /** An id no title of this album has yet: t1, t2, ... */
    public String freshTrackId() {
        for (int number = tracks.size() + 1; ; number++) {
            if (track("t" + number) == null) {
                return "t" + number;
            }
        }
    }

    /**
     * What a piece is told about its album when it is composed: the album in a few lines, its own
     * place in it, and one line for each other title, so that the pieces belong together without
     * the whole plan being made again. This is part of the music wish of the piece, in words.
     */
    public String brief(AlbumTrack own) {
        StringBuilder text = new StringBuilder();
        text.append("This piece is track ").append(tracks.indexOf(own) + 1).append(" of ").append(tracks.size())
                .append(" of the album \"").append(title).append("\": ").append(concept.description());
        if (!concept.style().isEmpty()) {
            text.append(" Style of the album: ").append(concept.style()).append('.');
        }
        text.append("\nThis track: \"").append(own.title()).append('"');
        append(text, ", mood: ", own.mood());
        append(text, ", played by: ", own.instrumentation());
        append(text, ", form: ", own.form());
        if (concept.original()) {
            text.append(".\n").append(com.aresstack.audio.studio.application.OriginalWork.GUIDANCE)
                    .append(" This track is that movement of the original.");
        } else {
            text.append(". Fill its whole length with this form; develop and vary the ideas instead of repeating a short one.");
        }
        if (!theme.isEmpty() && !own.theme().isEmpty()) {
            text.append("\n").append(themeBrief(own));
        }
        if (tracks.size() > 1) {
            text.append("\nThe other tracks of the album, so that this one fits in and differs from them:");
            for (AlbumTrack other : tracks) {
                if (!other.id().equals(own.id())) {
                    text.append("\n- ").append(tracks.indexOf(other) + 1).append(". \"").append(other.title()).append('"');
                    append(text, ": ", other.mood());
                    text.append(" (").append(other.bpm()).append(" BPM, ").append(other.key()).append(')');
                }
            }
        }
        return text.toString();
    }

    /**
     * What a piece is told about the main theme of the album: the theme as notes and how it
     * appears in this piece. Only for a piece the theme appears in.
     */
    String themeBrief(AlbumTrack own) {
        StringBuilder text = new StringBuilder("The main theme of the album");
        if (!theme.name().isEmpty()) {
            text.append(", \"").append(theme.name()).append('"');
        }
        if (!theme.description().isEmpty()) {
            text.append(": ").append(theme.description());
        }
        text.append(". Its notes");
        if (!theme.key().isEmpty()) {
            text.append(" (in ").append(theme.key()).append(')');
        }
        text.append(", beats in quarters from its start, MIDI pitches: ").append(theme.notesText()).append('.')
                .append("\nIn this track the theme appears so: ").append(own.theme())
                .append(". Quote it recognisably, with its rhythm and intervals, transposed to the key of this track "
                        + "and varied as said, at least once in the melody; develop it further in the piece.");
        return text.toString();
    }

    /**
     * A description without what a piece was told about its album: earlier versions kept the whole
     * {@link #brief} in the description of the piece, which made it long and hard to read.
     */
    public static String withoutBrief(String description) {
        if (description == null) {
            return null;
        }
        int at = description.indexOf("This piece is track ");
        return at < 0 ? description : description.substring(0, at).strip();
    }

    private static void append(StringBuilder text, String label, String value) {
        if (!value.isEmpty()) {
            text.append(label).append(value);
        }
    }
}
