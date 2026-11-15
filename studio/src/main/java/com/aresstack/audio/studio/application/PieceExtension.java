package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.studio.ai.ComposedContinuation;
import com.aresstack.audio.studio.ai.ContinuationComposer;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The use case "continue the piece" in the folder of a piece (work package C3, report 5): the AI
 * plans and writes the continuation, it becomes a proposal of the piece's {@link ProjectSession},
 * which can be listened to ({@link #preview}), composed again ({@link #regenerate}), accepted or
 * discarded. The piece changes only on acceptance; the state before stays as a revision, so the
 * original is never lost, not even after a revised transition.
 *
 * <p>The answers of the model are kept in {@value #CACHE} within the piece, as the record of where
 * the continuation came from; every attempt has its own answers.
 */
public final class PieceExtension {
    /** Where the answers of the model are kept, relative to the folder of the piece. */
    public static final String CACHE = "ai-cache/extend";

    private final ProjectSession session;
    private final ContinuationComposer composer;
    private final ContinuationRequest request;
    private final Supplier<String> ids;
    private int attempt;
    private ComposedContinuation continuation;
    private Proposal proposal;
    private boolean accepted;

    private PieceExtension(ProjectSession session, ContinuationComposer composer, ContinuationRequest request,
                           Supplier<String> ids) {
        this.session = session;
        this.composer = composer;
        this.request = request;
        this.ids = ids;
    }

    /**
     * Composes the continuation and proposes it.
     *
     * @throws EditRefusedException if the edit core refused the patch (it never should)
     * @throws IOException          if the model failed or answered for another part
     */
    public static PieceExtension start(ProjectSession session, ContinuationComposer composer,
                                       ContinuationRequest request, Progress progress)
            throws IOException, EditRefusedException {
        return start(session, composer, request, Ids.random(), progress);
    }

    /** As {@link #start(ProjectSession, ContinuationComposer, ContinuationRequest, Progress)}, with a source of ids. */
    public static PieceExtension start(ProjectSession session, ContinuationComposer composer,
                                       ContinuationRequest request, Supplier<String> idSource, Progress progress)
            throws IOException, EditRefusedException {
        PieceExtension extension = new PieceExtension(session, composer, request, idSource);
        extension.generate(progress);
        return extension;
    }

    /**
     * Composes the continuation anew, differently: a new attempt with a plan of its own. An open
     * proposal is replaced; after a discard it can be composed again as well, after acceptance no
     * longer.
     */
    public Proposal regenerate(Progress progress) throws IOException, EditRefusedException {
        if (accepted) {
            throw new IllegalStateException("Die Fortsetzung ist schon übernommen.");
        }
        return generate(progress);
    }

    /** Writes the preview of the longer piece to {@code preview/<id>/}, as {@link ProjectSession#preview} does. */
    public Path preview() throws IOException {
        requireOpen();
        return session.preview(proposal);
    }

    /** Takes the continuation into the piece: a revision of the AI, undoable, after one of the state before. */
    public ChangeSet accept() throws IOException {
        requireOpen();
        ChangeSet changes = session.accept(proposal);
        proposal = null;
        accepted = true;
        return changes;
    }

    /** Throws the continuation away; the piece is as it was. */
    public void discard() throws IOException {
        requireOpen();
        session.discard(proposal);
        proposal = null;
    }

    /** The open proposal, {@code null} once accepted or discarded. */
    public Proposal proposal() {
        return proposal;
    }

    /** The continuation of the last attempt, with its plan, context and repairs. */
    public ComposedContinuation continuation() {
        return continuation;
    }

    public int attempt() {
        return attempt;
    }

    public ContinuationRequest request() {
        return request;
    }

    private Proposal generate(Progress progress) throws IOException, EditRefusedException {
        Composition base = session.composition();
        int next = attempt + 1;
        ComposedContinuation composed = composer.compose(base, request, ids, next, session.folder().resolve(CACHE),
                progress);
        Proposal made = session.propose(AppendContinuation.patch(base, composed), AppendContinuation.scope(composed),
                Revision.Author.AI, composed.runId());
        Set<String> goOn = new HashSet<>();
        composed.task().parts().forEach(part -> goOn.add(part.id()));
        Set<String> created = new HashSet<>();
        composed.newParts().stream().map(Part::id).forEach(created::add);
        List<String> differences = AppendContinuation.originalKept(base, made.result(), composed.fromTick(), goOn,
                created);
        if (!differences.isEmpty()) {
            session.discard(made);
            throw new IllegalStateException("Die Fortsetzung hätte das Original verändert: " + differences);
        }
        attempt = next;
        continuation = composed;
        proposal = made;
        return made;
    }

    private void requireOpen() {
        if (proposal == null || session.pending().map(p -> !p.id().equals(proposal.id())).orElse(true)) {
            throw new IllegalStateException("Kein offener Vorschlag für die Fortsetzung.");
        }
    }
}
