package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Drafts the list of titles of an album from what the user said about it. */
public interface AlbumPlanner {
    /**
     * Plans the titles of an album as a whole: different moods, tempos and players that belong
     * together, not the same piece several times.
     *
     * @param album  the album; its titles that have a piece already are kept as they are and at
     *               their places, the others are planned anew
     * @param cache  the folder the answer of the model is kept in (the record of where the plan
     *               came from), or {@code null}
     * @return the album with as many titles as its concept asks for
     */
    Album plan(Album album, Path cache, Progress progress) throws IOException;
}
