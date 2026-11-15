package com.aresstack.audio.studio.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Keeps the model of the piece that is open in the studio in step with what the studio still
 * writes the old way. The window calls it when a piece is opened, after composing and after a
 * track was renamed; it never stops the studio: whatever goes wrong is said as a warning, and
 * the piece goes on working from its old files as before.
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
    public void open(Path opened) {
        folder = opened;
        session = null;
        if (opened == null || !Files.isDirectory(opened)) {
            return;
        }
        boolean known = Files.isRegularFile(opened.resolve(com.aresstack.audio.studio.project.CompositionJson.FILE_NAME));
        try {
            Optional<ProjectSession> found = ProjectSession.open(opened);
            if (!found.isPresent()) {
                return;
            }
            session = found.get();
            if (!known) {
                log.accept("Stück ins Werkmodell übernommen (" + session.composition().tracks().size()
                        + " Spuren); die bisherigen Dateien liegen unverändert im Ordner „legacy“.");
                for (String warning : session.warnings()) {
                    log.accept("WARN: Werkmodell: " + warning);
                }
            }
        } catch (IOException | RuntimeException failed) {
            log.accept("WARN: Das Werkmodell des Stücks ließ sich nicht öffnen; das Stück wird wie bisher aus "
                    + "seinen Dateien gelesen: " + said(failed));
        }
    }

    /**
     * Takes into the model what was just written the old way: a composed arrangement or a renamed
     * track. A piece that was composed for the first time gets its model here.
     */
    public void synchronize() {
        if (session == null) {
            if (folder != null) {
                open(folder);
            }
            return;
        }
        try {
            session.synchronize();
        } catch (IOException | RuntimeException failed) {
            log.accept("WARN: Das Werkmodell ließ sich nicht nachführen: " + said(failed));
        }
    }

    /** The model of the open piece, if it has one. */
    public Optional<ProjectSession> session() {
        return Optional.ofNullable(session);
    }

    private static String said(Exception failed) {
        return failed.getMessage() == null ? failed.toString() : failed.getMessage();
    }
}
