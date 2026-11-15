package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.studio.project.ProjectFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * "Have the AI compose this track anew" as a proposal: the composer writes into a folder of its
 * own, the result is offered as a preview that can be heard, and the piece changes only when the
 * user takes the proposal. Until then nothing of the piece is written: not its arrangement, not
 * its model, not the answers of the AI it keeps, not its stems.
 *
 * <p>The steps, in the order the studio takes them: {@link #stage} (where the composer works),
 * the composer, {@link #propose} (the proposal and its preview folder), a rendering of the
 * preview folder, then {@link #accept} or {@link #discard}.
 */
public final class RecompositionOffer {
    /** The files of the model and of the stage: the composer neither reads nor writes them. */
    private static final Set<String> NOT_FOR_THE_COMPOSER = Set.of("composition.json", "mix.json", "project.json",
            ProjectFolder.SCENE);

    private final ProjectSession session;
    private final String trackId;
    private final Path staging;
    private Proposal proposal;
    private Path preview;

    private RecompositionOffer(ProjectSession session, String trackId, Path staging) {
        this.session = session;
        this.trackId = trackId;
        this.staging = staging;
    }

    /**
     * Prepares the folder the composer works in: the plan, the track list and the kept answers of
     * the piece, but not the answers for this track, so that the composer writes exactly this
     * track anew and takes the others as they are.
     *
     * @param runId names this run of the AI; it is kept with the revision if the proposal is taken
     */
    public static RecompositionOffer stage(ProjectSession session, String trackId, String runId) throws IOException {
        if (session.composition().part(trackId) == null) {
            throw new IOException("Das Stück hat keine Spur " + trackId + ".");
        }
        Path piece = session.folder();
        Path staging = piece.resolve(ProjectFolder.PREVIEW).resolve("compose-" + runId);
        deleteTree(staging);
        Files.createDirectories(staging);
        Pattern answers = answersOf(trackId);
        try (Stream<Path> files = Files.list(piece)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                boolean wanted = name.endsWith(".json") ? !NOT_FOR_THE_COMPOSER.contains(name)
                        && !answers.matcher(name).matches() : name.equals(ProjectFolder.SONG);
                if (wanted && Files.isRegularFile(file)) {
                    Files.copy(file, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return new RecompositionOffer(session, trackId, staging);
    }

    /** The folder the composer writes into. */
    public Path staging() {
        return staging;
    }

    public String trackId() {
        return trackId;
    }

    /**
     * Makes the proposal from what the composer wrote, and its preview folder: the proposed piece
     * with a copy of the stage, and the stems the piece already has, so that rendering the preview
     * renders only the track that is new. The piece itself stays as it is.
     *
     * @return the preview folder, to be rendered and mixed there
     * @throws EditRefusedException if the edit core refused the change (it touched more than this track)
     */
    public Path propose(String runId) throws EditRefusedException, IOException {
        proposal = session.proposeRecomposition(trackId, staging, runId);
        preview = session.preview(proposal);
        carryStems(session.folder(), preview);
        return preview;
    }

    /** The proposal, once made. */
    public Proposal proposal() {
        return proposal;
    }

    /** Whether the proposed track differs from the one the piece has. */
    public boolean changesSomething() {
        return proposal != null && !proposal.result().equals(proposal.base());
    }

    /**
     * Takes the proposal: the piece becomes the proposed one (an undoable step and a revision),
     * the stem rendered for the preview becomes the stem of the piece, and the answer of the AI
     * for this track replaces the one kept so far, which goes to the folder "earlier". Both are
     * noted on the {@link AnswerShelf} with the notes they belong to, so that undo, redo and a
     * revision brought back find the answer of their state.
     *
     * @return which parts sound different now
     */
    public ChangeSet accept() throws IOException {
        requireProposed();
        Path piece = session.folder();
        carryStems(preview, piece); // before the preview goes: what was heard need not be rendered again
        // The answers this track was written from stay with its notes: undo brings both back together.
        AnswerShelf.keep(piece, proposal.base(), trackId);
        ChangeSet changes = session.accept(proposal);
        Pattern answers = answersOf(trackId);
        List<Path> fresh = new ArrayList<Path>();
        try (Stream<Path> files = Files.list(staging)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (answers.matcher(file.getFileName().toString()).matches()) {
                    fresh.add(file);
                }
            }
        }
        if (!fresh.isEmpty()) {
            // A later composing of the whole piece keeps this track as it was taken, not as it was before.
            try (Stream<Path> files = Files.list(piece)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (answers.matcher(file.getFileName().toString()).matches()) {
                        Path aside = Files.createDirectories(piece.resolve("earlier"));
                        Files.move(file, aside.resolve(System.currentTimeMillis() + "-" + file.getFileName()),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            for (Path file : fresh) {
                Files.copy(file, piece.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        AnswerShelf.keep(piece, proposal.result(), trackId);
        deleteTree(staging);
        return changes;
    }

    /** Throws the proposal away with everything made for it; the piece is as it was. */
    public void discard() throws IOException {
        if (proposal != null && session.pending().filter(open -> open.id().equals(proposal.id())).isPresent()) {
            session.discard(proposal);
        }
        deleteTree(staging);
    }

    private void requireProposed() {
        if (proposal == null) {
            throw new IllegalStateException("Es gibt noch keinen Vorschlag.");
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(file);
            }
        }
    }

    /** The kept answers of the AI for a track: {@code <track>.json} and {@code <track>-<hash>.json}. */
    private static Pattern answersOf(String trackId) {
        return Pattern.compile(Pattern.quote(trackId) + "(-[0-9a-f]{12})?\\.json");
    }

    /** Copies the stems one folder has to another that lacks them; stems are named by their content. */
    private static void carryStems(Path from, Path to) throws IOException {
        Path source = from.resolve(ProjectFolder.RENDER).resolve("stems");
        if (!Files.isDirectory(source)) {
            return;
        }
        Path target = Files.createDirectories(to.resolve(ProjectFolder.RENDER).resolve("stems"));
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path copy = target.resolve(file.getFileName());
                if (Files.isRegularFile(file) && !Files.exists(copy)) {
                    Files.copy(file, copy);
                }
            }
        }
    }
}
