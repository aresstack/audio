package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.application.ScoreTemplates;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The notes given as templates for the titles of an album: one file per title, kept in the
 * folder of the album under {@code titles/<title id>/score-template}. When the piece of a title
 * is produced, it gets a copy of the template of its title and is made from it instead of being
 * composed freely; a title without one is composed as before.
 */
public final class AlbumTemplates {
    public static final String FOLDER = "titles";

    private final AlbumFiles albums;

    public AlbumTemplates(AlbumFiles albums) {
        this.albums = albums;
    }

    /** Where what belongs to one title is kept; only a plain name is ever a title's folder. */
    public Path titleFolder(String albumId, String trackId) {
        if (!AlbumTrack.isFolderName(trackId)) {
            throw new IllegalArgumentException("Keine Kennung eines Titels: " + trackId);
        }
        return albums.folder(albumId).resolve(FOLDER).resolve(trackId);
    }

    public Optional<Path> of(String albumId, String trackId) {
        return ScoreTemplates.of(titleFolder(albumId, trackId));
    }

    public Path put(String albumId, String trackId, Path source) throws IOException {
        return ScoreTemplates.put(titleFolder(albumId, trackId), source);
    }

    public void remove(String albumId, String trackId) throws IOException {
        ScoreTemplates.remove(titleFolder(albumId, trackId));
    }

    /** Gives the piece of a title the template of the title, if it has one; see {@link ScoreTemplates#follow}. */
    public boolean intoPiece(String albumId, String trackId, Path piece) throws IOException {
        return ScoreTemplates.follow(titleFolder(albumId, trackId), piece);
    }
}
