package com.aresstack.audio.studio.album;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Where albums are kept. Reading never fails on one unreadable album; writing is whole or not at all. */
public interface AlbumRepository {
    /** All albums that can be read, by title. */
    List<Album> albums();

    Optional<Album> album(String id);

    /**
     * A new, empty album with a fresh id made from its title.
     *
     * @throws IOException if it cannot be written
     */
    Album create(String title, AlbumConcept concept) throws IOException;

    void save(Album album) throws IOException;

    /** Forgets the album. Its pieces are pieces of the library and stay. */
    void delete(String id) throws IOException;
}
