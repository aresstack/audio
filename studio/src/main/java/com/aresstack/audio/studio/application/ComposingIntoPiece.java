package com.aresstack.audio.studio.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Composing into the folder of the open piece, in the one order that keeps the model whole:
 * first everything the old way writes (the composer's arrangement and plan, then the words a
 * first plan follows), and only then the model takes all of it over at once. Taking it over
 * earlier would leave the last file out of the model, and the piece would look changed from
 * outside when it is opened again.
 */
public final class ComposingIntoPiece {
    private ComposingIntoPiece() {
    }

    /**
     * @param composer writes the arrangement into the folder and returns it; if it fails, nothing
     *                 is taken into the model
     * @param prompt   the words the piece is composed from
     */
    public static Path run(Callable<Path> composer, Path folder, String prompt, SongLibrary songs, PieceModel model)
            throws Exception {
        boolean planned = Files.isRegularFile(folder.resolve("composition-plan.json"));
        Path arrangement = composer.call();
        if (!planned) {
            songs.planned(folder, prompt); // the words this plan follows
        }
        model.synchronize();
        return arrangement;
    }
}
