package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.studio.ai.SeamSmoothing;
import com.aresstack.audio.studio.project.RevisionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Smooths afterwards the loudness jumps at the seams of a piece that was lengthened before the
 * new bars learned to grow into their level: the seams are known from the revisions (a piece
 * lengthened by the AI and its state before), and only the dynamics change, no note is asked
 * for again. It is an undoable step; the next playing or rendering sounds smooth.
 */
final class SeamRepair {
    /** In the piece's folder: how many revisions were looked at last time, so that each is looked at once. */
    static final String CHECKED_FILE = "seams.checked";
    static final String DESCRIPTION = "Lautstärke an Nahtstellen geglättet";
    /** How the description of a lengthening by the AI begins and what it says. */
    private static final String LENGTHENED_START = "Stück um ";
    private static final String LENGTHENED = " Takte verlängert";

    private SeamRepair() {
    }

    /** Smooths the seams of the open piece if there is anything new to look at; never fails the opening. */
    static void repair(ProjectSession session, Path folder, Consumer<String> log) {
        try {
            long count = revisionFiles(folder);
            Path checked = folder.resolve(CHECKED_FILE);
            if (count == 0 || (Files.isRegularFile(checked) && String.valueOf(count).equals(
                    Files.readString(checked, StandardCharsets.UTF_8).trim()))) {
                return;
            }
            List<String> said = new ArrayList<>();
            Composition piece = session.composition();
            Dynamics smoothed = smoothed(piece, RevisionStore.list(folder), said);
            if (!smoothed.equals(piece.dynamics())) {
                session.apply(Patch.of(DESCRIPTION, new EditOp.SetDynamics(smoothed)), PerformanceEdit.scope());
                for (String line : said) {
                    log.accept(line);
                }
                log.accept(DESCRIPTION + " (" + said.size() + "×, ohne neue Noten; rückgängig zu machen). "
                        + "Die nächste Wiedergabe bzw. das nächste Rendern klingt ohne Sprung.");
            }
            Files.writeString(checked, String.valueOf(count), StandardCharsets.UTF_8);
        } catch (IOException | EditRefusedException | RuntimeException failed) {
            log.accept("WARN: Die Nahtstellen des Stücks ließen sich nicht glätten: " + failed.getMessage());
        }
    }

    /**
     * The dynamics of the piece with the jump at every seam of a lengthening smoothed. A seam is
     * the first bar after the end the piece had before; only marks the lengthening itself set
     * there glide, a mark set later by hand stays as it is.
     */
    static Dynamics smoothed(Composition piece, List<Revision> revisions, List<String> said) {
        Map<String, Revision> byId = new HashMap<>();
        for (Revision revision : revisions) {
            byId.put(revision.id(), revision);
        }
        Dynamics dynamics = piece.dynamics();
        for (Revision revision : revisions) {
            if (revision.author() != Revision.Author.AI || !revision.description().startsWith(LENGTHENED_START)
                    || !revision.description().contains(LENGTHENED)) {
                continue;
            }
            Revision before = revision.parentId() == null ? null : byId.get(revision.parentId());
            if (before == null) {
                continue;
            }
            TimeMap time = revision.composition().timeMap();
            long seam = time.tickOfBar(time.barsCovering(before.composition().endTick()));
            if (seam <= 0 || seam >= piece.endTick()) {
                continue;
            }
            List<Dynamics.Mark> lengthening = revision.composition().dynamics().marks();
            dynamics = SeamSmoothing.smooth(piece, dynamics, seam, piece.endTick(), lengthening::contains, said);
        }
        return dynamics;
    }

    private static long revisionFiles(Path folder) throws IOException {
        Path revisions = RevisionStore.folder(folder);
        if (!Files.isDirectory(revisions)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(revisions)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json")).count();
        }
    }
}
