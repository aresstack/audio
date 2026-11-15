package com.aresstack.audio.studio.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * "Load the demo" for the folder of the open piece. The demo is a written arrangement; a piece
 * with a model is rendered from its model, so the demo has to become the model, or the next
 * rendering would still play the piece that was there (PO finding). Loading it is the user's
 * word to replace the piece: the model takes the demo over as it takes a newly composed
 * arrangement, as one step that can be taken back. A model that says more than a written
 * arrangement can is not replaced; the user is told to choose another folder, and nothing is
 * written.
 */
public final class DemoIntoPiece {
    /** What loading the demo into a folder would do. */
    public enum Effect {
        /** Nothing is composed there yet. */
        FIRST_PIECE,
        /** The piece there is replaced; with a model, as a step that can be taken back. */
        REPLACES_PIECE,
        /** The model of the piece holds more than the demo file can replace: nothing is loaded. */
        REFUSED
    }

    private DemoIntoPiece() {
    }

    public static Effect effect(Path folder, PieceModel model) {
        Optional<ProjectSession> piece = model.of(folder);
        if (piece.isPresent()) {
            return piece.get().withinTsv() ? Effect.REPLACES_PIECE : Effect.REFUSED;
        }
        return Files.isRegularFile(folder.resolve("arrangement.tsv")) ? Effect.REPLACES_PIECE : Effect.FIRST_PIECE;
    }

    /**
     * Writes the demo as the arrangement of the folder and lets the model of the open piece take
     * it over.
     *
     * @throws IOException if the model would lose something, or did not take the demo; in the
     *                     first case nothing was written
     */
    public static Path load(InputStream demo, Path folder, PieceModel model) throws IOException {
        if (effect(folder, model) == Effect.REFUSED) {
            throw new IOException("Dieses Werk enthält mehr, als die Demo ersetzen kann (Tempo- oder Taktwechsel,"
                    + " mehrere Clips oder Instrumente je Bahn). Die Demo bitte in einen neuen Ordner laden.");
        }
        Path arrangement = folder.resolve("arrangement.tsv");
        Files.createDirectories(folder);
        Files.copy(demo, arrangement, StandardCopyOption.REPLACE_EXISTING);
        model.synchronize();
        Optional<ProjectSession> piece = model.of(folder);
        if (piece.isPresent() && piece.get().externalChanges()) {
            throw new IOException("Die Demo ließ sich nicht ins Werkmodell übernehmen; gerendert würde weiter das"
                    + " bisherige Stück.");
        }
        return arrangement;
    }
}
