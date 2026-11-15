package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.studio.ai.OrchestrationComposer;
import com.aresstack.audio.studio.ai.ReorchestratedRegion;
import com.aresstack.audio.studio.ai.ReorchestrationRequest;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The use case "re-orchestrate a passage" in the folder of a piece (work package C4): the AI plans
 * who takes over which material in which section of the passage and writes the parts anew; the
 * result becomes a proposal of the piece's {@link ProjectSession}, which can be listened to
 * ({@link #preview}), composed again ({@link #regenerate}), accepted or discarded. The piece
 * changes only on acceptance; the state before stays as a revision.
 *
 * <p>The answers of the model are kept in {@value #CACHE} within the piece.
 */
public final class Reorchestration {
    /** Where the answers of the model are kept, relative to the folder of the piece. */
    public static final String CACHE = "ai-cache/reorchestrate";

    private final ProjectSession session;
    private final OrchestrationComposer composer;
    private final ReorchestrationRequest request;
    private final Supplier<String> ids;
    private int attempt;
    private ReorchestratedRegion region;
    private Proposal proposal;
    private boolean accepted;

    private Reorchestration(ProjectSession session, OrchestrationComposer composer, ReorchestrationRequest request,
                            Supplier<String> ids) {
        this.session = session;
        this.composer = composer;
        this.request = request;
        this.ids = ids;
    }

    /**
     * Re-orchestrates the passage and proposes it.
     *
     * @throws EditRefusedException if the edit core refused the patch (it never should)
     * @throws IOException          if the model failed or answered for another part
     */
    public static Reorchestration start(ProjectSession session, OrchestrationComposer composer,
                                        ReorchestrationRequest request, Progress progress)
            throws IOException, EditRefusedException {
        return start(session, composer, request, Ids.random(), progress);
    }

    /** As {@link #start(ProjectSession, OrchestrationComposer, ReorchestrationRequest, Progress)}, with a source of ids. */
    public static Reorchestration start(ProjectSession session, OrchestrationComposer composer,
                                        ReorchestrationRequest request, Supplier<String> idSource, Progress progress)
            throws IOException, EditRefusedException {
        Reorchestration reorchestration = new Reorchestration(session, composer, request, idSource);
        reorchestration.generate(progress);
        return reorchestration;
    }

    /** Re-orchestrates anew, differently; an open proposal is replaced. Not after acceptance. */
    public Proposal regenerate(Progress progress) throws IOException, EditRefusedException {
        if (accepted) {
            throw new IllegalStateException("Die Umorchestrierung ist schon übernommen.");
        }
        return generate(progress);
    }

    /** Writes the preview to {@code preview/<id>/}, as {@link ProjectSession#preview} does. */
    public Path preview() throws IOException {
        requireOpen();
        return session.preview(proposal);
    }

    /** Takes the re-orchestration into the piece: a revision of the AI, undoable. */
    public ChangeSet accept() throws IOException {
        requireOpen();
        ChangeSet changes = session.accept(proposal);
        proposal = null;
        accepted = true;
        return changes;
    }

    /** Throws the re-orchestration away; the piece is as it was. */
    public void discard() throws IOException {
        requireOpen();
        session.discard(proposal);
        proposal = null;
    }

    /** The open proposal, {@code null} once accepted or discarded. */
    public Proposal proposal() {
        return proposal;
    }

    /** The passage of the last attempt, with its plan, context and repairs. */
    public ReorchestratedRegion region() {
        return region;
    }

    public int attempt() {
        return attempt;
    }

    public ReorchestrationRequest request() {
        return request;
    }

    private Proposal generate(Progress progress) throws IOException, EditRefusedException {
        Composition base = session.composition();
        int next = attempt + 1;
        ReorchestratedRegion made = composer.compose(base, request, ids, next, session.folder().resolve(CACHE),
                progress);
        Proposal proposed = session.propose(ReorchestrateRegion.patch(made), ReorchestrateRegion.scope(made),
                Revision.Author.AI, made.runId());
        Set<String> chosen = new HashSet<>();
        made.task().parts().forEach(part -> chosen.add(part.id()));
        Set<String> created = new HashSet<>();
        made.newParts().stream().map(Part::id).forEach(created::add);
        List<String> differences = ReorchestrateRegion.outsideKept(base, proposed.result(), made.task().fromTick(),
                made.task().toTick(), chosen, created);
        if (!differences.isEmpty()) {
            session.discard(proposed);
            throw new IllegalStateException("Die Umorchestrierung hätte Musik außerhalb des Bereichs verändert: "
                    + differences);
        }
        attempt = next;
        region = made;
        proposal = proposed;
        return proposed;
    }

    private void requireOpen() {
        if (proposal == null || session.pending().map(p -> !p.id().equals(proposal.id())).orElse(true)) {
            throw new IllegalStateException("Kein offener Vorschlag für die Umorchestrierung.");
        }
    }
}
