package com.aresstack.audio.studio.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Keeps the model of the piece that is open in the studio in step with what the studio still
 * writes the old way. The window calls it when a piece is opened, after composing and after a
 * track was renamed, from the window's thread or from the one that composes; it never stops the studio: whatever goes wrong is said as a warning, and
 * the piece goes on working from its old files as before.
 *
 * <p>Opening a piece that has a model never changes that model: old files that differ from it
 * are only named in a warning. The model takes over old files solely in {@link #synchronize()},
 * which the window calls right after the studio itself has written them.
 */
public final class PieceModel {
    private final Consumer<String> log;
    private Path folder;
    private ProjectSession session;

    /** @param log receives what is worth telling the user, one line at a time */
    public PieceModel(Consumer<String> log) {
        this.log = log;
    }

    /**
     * The piece in this folder is the open one now. A piece from before the model is taken into
     * it here; a folder without a piece is no piece yet, and nothing is written into it.
     */
    public synchronized void open(Path opened) {
        load(opened);
    }

    /** Returns whether the piece was taken into the model just now, from the files as they are. */
    private boolean load(Path opened) {
        folder = opened;
        session = null;
        if (opened == null || !Files.isDirectory(opened)) {
            return false;
        }
        boolean known = Files.isRegularFile(opened.resolve(com.aresstack.audio.studio.project.CompositionJson.FILE_NAME));
        try {
            Optional<ProjectSession> found = ProjectSession.open(opened);
            if (!found.isPresent()) {
                return false;
            }
            session = found.get();
            if (known) {
                if (session.externalChanges()) {
                    log.accept("WARN: Die Dateien des Stücks (arrangement.tsv, tracks.json, Plan, Name oder Bühne)"
                            + " wurden außerhalb des Werkmodells geändert. Das gespeicherte Werkmodell bleibt"
                            + " unverändert; die Änderung wird erst nach dem nächsten Komponieren oder"
                            + " Umbenennen übernommen.");
                }
            } else {
                log.accept("Stück ins Werkmodell übernommen (" + session.composition().tracks().size()
                        + " Spuren); die bisherigen Dateien liegen unverändert im Ordner „legacy“.");
                for (String warning : session.warnings()) {
                    log.accept("WARN: Werkmodell: " + warning);
                }
                return true;
            }
        } catch (IOException | RuntimeException failed) {
            log.accept("WARN: Das Werkmodell des Stücks ließ sich nicht öffnen; das Stück wird wie bisher aus "
                    + "seinen Dateien gelesen: " + said(failed));
        }
        return false;
    }

    /**
     * Takes into the model what was just written the old way: a composed arrangement or a renamed
     * track. Only these two call it. Rendering, playing, mixing and proposals of the AI never do:
     * they must not change the saved piece, and a file changed behind the model's back stays out.
     * A piece that was composed for the first time gets its model here. A model that says more
     * than {@code arrangement.tsv} can is not replaced; that is a warning.
     */
    public synchronized void synchronize() {
        if (session == null) {
            // Nothing in memory: the piece is new, or the last attempt failed. A piece without a
            // model is imported from the files as they are now, which already is the new state.
            if (folder == null || load(folder) || session == null) {
                return;
            }
        }
        try {
            session.synchronize();
        } catch (IOException | RuntimeException failed) {
            // What is in memory may be ahead of what was written; the next attempt starts from the
            // saved model again, so that a write that failed is tried once more.
            session = null;
            log.accept("WARN: Das Werkmodell ließ sich nicht nachführen: " + said(failed));
        }
    }

    /**
     * How the instruments of the open piece move on its stage (work package D3), as its mix
     * document says; nothing for a piece without a model.
     */
    public synchronized StageMovements movements() {
        return session == null ? StageMovements.NONE
                : StageMovements.of(session.mix(), session.composition().timeMap());
    }

    /**
     * The user drew how a track moves: the curve of one parameter is kept in the mix document of
     * the piece. Only {@code mix.json} is written; the notes and the rendered sound stay.
     *
     * @param movements the movements as the stage page shows them now
     * @return whether the curve is kept; if not, the reason was said as a warning
     */
    public synchronized boolean move(StageMovements movements, String trackId, String parameter) {
        if (!StageMovements.PARAMETERS.contains(parameter)) {
            // The level curve has one source: the stage (dynamics), which the model takes over as its level lane.
            log.accept("WARN: Der Verlauf lässt sich nicht speichern: \"" + parameter
                    + "\" wird nicht hier gezeichnet (der Pegelverlauf gehört zur Bühne).");
            return false;
        }
        if (session == null) {
            log.accept("WARN: Der Verlauf lässt sich nicht speichern: das Stück hat noch kein Werkmodell.");
            return false;
        }
        try {
            session.automate(trackId, parameter, movements.ticks(trackId, parameter));
            return true;
        } catch (IOException | RuntimeException failed) {
            log.accept("WARN: Der Verlauf lässt sich nicht speichern: " + said(failed));
            return false;
        }
    }

    /**
     * The user took the movements the AI proposed: each proposed curve is kept in the mix document
     * as if drawn by hand, replacing the curve of that parameter; curves the AI did not propose stay.
     * A track the user protected in the meantime is left as it is. Only {@code mix.json} is written.
     *
     * @param stage the stage as it stands after the mix proposal was taken
     * @return how many curves were kept; a curve that could not be kept was said as a warning
     */
    public synchronized int takeMovements(MovementDirector.Proposal proposal, com.aresstack.audio.studio.stage.SpatialScene stage) {
        if (proposal == null || proposal.empty()) {
            return 0;
        }
        StageMovements movements = movements();
        int kept = 0;
        for (Map.Entry<String, Map<String, List<StageMovements.Point>>> track : proposal.curves().entrySet()) {
            com.aresstack.audio.studio.stage.SoundSourcePlacement placement =
                    stage == null ? null : stage.placement(track.getKey());
            if (placement != null && placement.locked()) {
                continue;
            }
            for (Map.Entry<String, List<StageMovements.Point>> curve : track.getValue().entrySet()) {
                movements = movements.with(track.getKey(), curve.getKey(), curve.getValue());
                if (movements.editable(track.getKey()) && move(movements, track.getKey(), curve.getKey())) {
                    kept++;
                }
            }
        }
        return kept;
    }

    /**
     * The user switched how the level of the mix is made. The model notes exactly that in
     * {@code mix.json} and takes nothing else from the files. A piece without a model yet gets
     * the mode from its stage file when it is taken into the model.
     */
    public synchronized void levelFixed(boolean fixed) {
        if (session == null) {
            return;
        }
        try {
            session.setLevelMode(fixed ? com.aresstack.audio.composition.mix.LevelMode.FIXED
                    : com.aresstack.audio.composition.mix.LevelMode.LEGACY_PEAK);
        } catch (IOException | RuntimeException failed) {
            session = null;
            log.accept("WARN: Die Umstellung des Pegels ließ sich nicht ins Werkmodell schreiben: " + said(failed));
        }
    }

    /** The model of the open piece, if it has one. */
    public synchronized Optional<ProjectSession> session() {
        return Optional.ofNullable(session);
    }

    private static String said(Exception failed) {
        return failed.getMessage() == null ? failed.toString() : failed.getMessage();
    }
}
