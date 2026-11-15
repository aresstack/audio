package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.application.SongLibrary;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every piece of the library belongs to an album. A piece that no album names, because it was made
 * before there were albums, made without an album, or taken out of its album, lands in the
 * unnamed album ({@link #UNTITLED}). Nothing is moved on disk: the pieces stay ordinary folders of
 * the library, and the albums name them, so an old library loses nothing and a later version can
 * still read it.
 *
 * <p>The unnamed album is the first album, by id, that is called {@link #UNTITLED}. Once the user
 * gives it a title of its own it is an album like any other, and the next piece without an album
 * gets a new unnamed one.
 */
public final class AlbumMembership {
    /** What the album of the pieces without an album is called. */
    public static final String UNTITLED = "Unbenanntes Album";

    private final AlbumFiles albums;
    private final SongLibrary songs;

    public AlbumMembership(AlbumFiles albums, SongLibrary songs) {
        this.albums = albums;
        this.songs = songs;
    }

    public AlbumFiles albums() {
        return albums;
    }

    /** Whether this is the unnamed album, the one the pieces without an album land in. */
    public static boolean isUntitled(Album album) {
        return album != null && UNTITLED.equals(album.title());
    }

    /** The unnamed album, made if there is none. */
    public synchronized Album untitled() throws IOException {
        Optional<Album> found = findUntitled(albums.albums());
        return found.isPresent() ? found.get() : albums.create(UNTITLED, new AlbumConcept("", "", 12, 300));
    }

    private static Optional<Album> findUntitled(List<Album> all) {
        Album first = null;
        for (Album album : all) {
            if (isUntitled(album) && (first == null || album.id().compareTo(first.id()) < 0)) {
                first = album;
            }
        }
        return Optional.ofNullable(first);
    }

    /**
     * The album a piece belongs to, or empty for a piece outside the library or one that no album
     * names yet. A piece named by a real album and, left over, by the unnamed one belongs to the
     * real one.
     */
    public synchronized Optional<Album> albumOf(Path piece) {
        String name = folderName(piece);
        if (name == null) {
            return Optional.empty();
        }
        Album untitled = null;
        for (Album album : albums.albums()) {
            if (names(album, name)) {
                if (!isUntitled(album)) {
                    return Optional.of(album);
                }
                untitled = untitled == null ? album : untitled;
            }
        }
        return Optional.ofNullable(untitled);
    }

    /** The pieces of an album that exist in the library, in the order of the album. */
    public List<SongLibrary.Song> pieces(Album album) {
        List<SongLibrary.Song> pieces = new ArrayList<SongLibrary.Song>();
        Set<String> seen = new HashSet<String>();
        for (AlbumTrack track : album.tracks()) {
            if (track.hasPiece() && seen.add(track.piece()) && Files.isDirectory(songs.root().resolve(track.piece()))) {
                pieces.add(songs.song(songs.root().resolve(track.piece())));
            }
        }
        return pieces;
    }

    /**
     * Puts a piece of the library into an album as its last title, unless the album has it
     * already. Its title, tempo, key, meter and length come from the piece itself. The album is
     * read again first, so that nothing another part of the studio wrote meanwhile is lost.
     *
     * @return the album as it is now
     */
    public synchronized Album add(String albumId, Path piece) throws IOException {
        String name = folderName(piece);
        if (name == null) {
            throw new IOException("Das Stück liegt nicht in der Bibliothek: " + piece);
        }
        Album album = albums.album(albumId)
                .orElseThrow(() -> new IOException("Das Album \"" + albumId + "\" lässt sich nicht lesen."));
        if (names(album, name)) {
            return album;
        }
        List<AlbumTrack> tracks = new ArrayList<AlbumTrack>(album.tracks());
        tracks.add(trackOf(album.freshTrackId(), piece, album.concept().secondsPerTrack()));
        Album changed = album.withTracks(tracks);
        albums.save(changed);
        return changed;
    }

    /**
     * Puts every piece of the library that no album names into the unnamed album, and takes out
     * of the unnamed album what a real album names as well (a piece the production of an album
     * made while the library was looked at). Runs again without changing anything when all is in
     * order; the unnamed album is made only when a piece needs it.
     *
     * @return how many pieces were put into the unnamed album
     */
    public synchronized int adoptLoosePieces() throws IOException {
        List<Album> all = albums.albums();
        Set<String> inReal = new HashSet<String>();
        Set<String> named = new HashSet<String>();
        Optional<Album> untitled = findUntitled(all);
        for (Album album : all) {
            for (AlbumTrack track : album.tracks()) {
                if (track.hasPiece()) {
                    named.add(track.piece());
                    if (!untitled.isPresent() || !album.id().equals(untitled.get().id())) {
                        inReal.add(track.piece());
                    }
                }
            }
        }
        Set<String> loose = new LinkedHashSet<String>();
        // The oldest first, so that the unnamed album lists the old pieces in the order they were made.
        List<SongLibrary.Song> library = new ArrayList<SongLibrary.Song>(songs.songs());
        java.util.Collections.reverse(library);
        for (SongLibrary.Song song : library) {
            String name = folderName(song.folder());
            if (name != null && !named.contains(name)) {
                loose.add(name);
            }
        }
        boolean doubled = untitled.isPresent() && untitled.get().tracks().stream()
                .anyMatch(track -> track.hasPiece() && inReal.contains(track.piece()));
        if (loose.isEmpty() && !doubled) {
            return 0;
        }
        Album album = untitled.isPresent() ? untitled.get() : untitled();
        List<AlbumTrack> tracks = new ArrayList<AlbumTrack>();
        for (AlbumTrack track : album.tracks()) {
            if (!track.hasPiece() || !inReal.contains(track.piece())) {
                tracks.add(track);
            }
        }
        Album changed = album.withTracks(tracks);
        for (String name : loose) {
            List<AlbumTrack> more = new ArrayList<AlbumTrack>(changed.tracks());
            more.add(trackOf(changed.freshTrackId(), songs.root().resolve(name), changed.concept().secondsPerTrack()));
            changed = changed.withTracks(more);
        }
        albums.save(changed);
        return loose.size();
    }

    /** The name of the folder of a piece directly in the library, or {@code null} for any other folder. */
    String folderName(Path piece) {
        if (piece == null || piece.getFileName() == null) {
            return null;
        }
        Path root = songs.root().toAbsolutePath().normalize();
        Path folder = piece.toAbsolutePath().normalize();
        String name = folder.getFileName().toString();
        return root.equals(folder.getParent()) && AlbumTrack.isFolderName(name) ? name : null;
    }

    private static boolean names(Album album, String folderName) {
        for (AlbumTrack track : album.tracks()) {
            if (folderName.equals(track.piece())) {
                return true;
            }
        }
        return false;
    }

    /** A title for a piece that exists: what it is called, and what its plan says about it. */
    AlbumTrack trackOf(String id, Path piece, int secondsOtherwise) {
        String mood = "";
        int bpm = 100;
        String key = "C major";
        String meter = "4/4";
        int seconds = secondsOtherwise;
        Path plan = piece.resolve("composition-plan.json");
        if (Files.isRegularFile(plan)) {
            try {
                JsonObject json = JsonParser.parseString(new String(Files.readAllBytes(plan), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                mood = AlbumFiles.text(json, "mood");
                bpm = AlbumFiles.number(json, "tempoBpm", bpm);
                key = AlbumFiles.text(json, "key").isEmpty() ? key : AlbumFiles.text(json, "key");
                meter = AlbumFiles.text(json, "timeSignature").isEmpty() ? meter : AlbumFiles.text(json, "timeSignature");
                int bars = AlbumFiles.number(json, "bars", 0);
                int[] beats = AlbumTrack.meterOf(meter);
                if (bars > 0 && bpm > 0) {
                    seconds = (int) Math.round(bars * (beats[0] * 4.0 / beats[1]) * 60.0 / bpm);
                }
            } catch (IOException | RuntimeException unreadable) {
                // then the title has only its name
            }
        }
        return new AlbumTrack(id, songs.name(piece), mood, bpm, key, meter, "", "", seconds,
                piece.getFileName().toString());
    }
}
