package com.aresstack.audio.studio.album;

import java.io.IOException;
import java.util.List;

/**
 * A new album that starts from a piece the user likes: its description says what the piece was
 * made from, its style what the piece is like (mood, tempo, key, meter and who plays). The piece
 * itself is not touched and stays in the album it is in; the new album has no titles yet, they
 * are planned on its page as for every album.
 */
public final class AlbumFromPiece {
    private AlbumFromPiece() {
    }

    /** What the new album is called. */
    public static String title(String piece) {
        return "Album zu „" + piece + "“";
    }

    /**
     * The concept of the album.
     *
     * @param track   the title of the piece in its album
     * @param words   the description the piece was composed from, or empty
     * @param players the instruments of the piece, in words; may be empty
     */
    public static AlbumConcept concept(AlbumTrack track, String words, List<String> players) {
        StringBuilder description = new StringBuilder("Ein Album, das von dem Stück „" + track.title()
                + "“ ausgeht: weitere Stücke in seiner Art.");
        if (words != null && !words.isBlank()) {
            description.append(" Das Stück wurde so beschrieben: ").append(words.trim());
        }
        StringBuilder style = new StringBuilder();
        if (!track.mood().isBlank()) {
            style.append(track.mood().trim());
        }
        style.append(style.length() == 0 ? "" : " · ").append("um ").append(track.bpm()).append(" BPM, ")
                .append(track.key()).append(", ").append(track.meter());
        String who = players == null || players.isEmpty() ? track.instrumentation() : String.join(", ", players);
        if (who != null && !who.isBlank()) {
            style.append(" · Besetzung wie ").append(who.trim());
        }
        AlbumConcept usual = AlbumConcept.standard("", "", 12);
        return new AlbumConcept(description.toString(), style.toString(), usual.trackCount(), usual.minSeconds(),
                usual.maxSeconds());
    }

    /** Makes the album and returns it. */
    public static Album create(AlbumFiles files, AlbumTrack track, String words, List<String> players)
            throws IOException {
        return files.create(title(track.title()), concept(track, words, players));
    }
}
