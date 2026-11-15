package com.aresstack.audio.studio.album;

import java.io.IOException;

/**
 * Works through the titles of an album: in order, several at once, each failure for itself, and
 * from where it stood after an interruption. It lets pieces come into being through
 * {@link PieceProduction} only and knows neither a renderer nor a prompt.
 */
public interface AlbumProductionCoordinator {
    /**
     * Starts the production of an album or goes on with it, and returns at once.
     *
     * @throws IOException           if the album cannot be read or its state cannot be written
     * @throws IllegalStateException if this album is being produced already
     */
    Production start(String albumId, Listener listener) throws IOException;

    /** Told about a production, from the threads that work on it. */
    interface Listener {
        /** The state of a title changed; the album is as it is kept now (a title may have got its piece). */
        void changed(Album album, AlbumProductionState state);

        /** What the piece of a title is doing right now. */
        default void progress(AlbumTrack track, String message) {
        }

        /**
         * The progress the piece of a title is made with: {@code plain} tells {@link #progress};
         * a listener that wants to see the piece while it is written (its notes, its instruments,
         * its form) gives one that shows that as well.
         */
        default com.aresstack.audio.studio.progress.Progress watching(AlbumTrack track, java.nio.file.Path piece,
                                                                     com.aresstack.audio.studio.progress.Progress plain) {
            return plain;
        }
    }

    /** A production that runs. */
    interface Production {
        /** Stops taking new titles and what is running; what was not finished waits for the next start. */
        void cancel();

        boolean running();

        /** Waits until nothing runs any more and says how the album stands then. */
        AlbumProductionState await() throws InterruptedException;
    }
}
