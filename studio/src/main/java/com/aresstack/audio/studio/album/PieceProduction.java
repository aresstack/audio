package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.application.CompositionSpec;
import com.aresstack.audio.studio.progress.Progress;
import java.nio.file.Path;

/**
 * The one way the production of an album lets a piece come into being. What is behind it (the
 * proposal of the players, composing, rendering, mixing) is the pipeline of a single piece; the
 * album knows none of it.
 */
public interface PieceProduction {
    /**
     * Brings a piece to its mixed file. It can be called again for the same folder after it was
     * interrupted or failed: what is there already (answers of the model, rendered tracks) is
     * used, only what is missing is made.
     */
    ProducedPiece produce(PieceOrder order, Progress progress) throws Exception;

    /** Stops what is running, as far as it can; {@link #produce} then ends with an exception. */
    default void cancel() {
    }

    /**
     * What is asked of a piece.
     *
     * @param folder      the folder of the piece in the library; it exists
     * @param name        what the piece is called
     * @param description the music wish in words, with what the piece is told about its album
     * @param spec        bars, tempo, key and meter
     */
    record PieceOrder(Path folder, String name, String description, CompositionSpec spec) {
    }

    /**
     * What came of it.
     *
     * @param mix     the mixed file of the piece
     * @param seconds how long the piece really is
     */
    record ProducedPiece(Path folder, Path mix, double seconds) {
    }
}
