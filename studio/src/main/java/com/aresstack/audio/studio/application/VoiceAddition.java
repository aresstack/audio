package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.studio.ai.AddVoiceRequest;
import com.aresstack.audio.studio.ai.ComposedVoice;
import com.aresstack.audio.studio.ai.NewVoiceIds;
import com.aresstack.audio.studio.ai.VoiceComposer;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.project.MidiExport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The use case "add a new voice" in the folder of a piece (work package C1, report 6.3): the AI
 * composes the voice, it becomes a proposal of the piece's {@link ProjectSession}, which can be
 * listened to on its own and with the piece ({@link #preview}), generated again
 * ({@link #regenerate}), accepted or discarded. The piece itself changes only on acceptance, and
 * then by nothing but the new part.
 *
 * <p>The answers of the model are kept in {@value #CACHE} within the piece, as the record of where
 * the voice came from; every attempt has its own answers.
 */
public final class VoiceAddition {
    /** Where the answers of the model are kept, relative to the folder of the piece. */
    public static final String CACHE = "ai-cache/add-voice";
    /** The MIDI file of the new voice alone, within the preview folder. */
    public static final String SOLO = "solo.mid";

    private final ProjectSession session;
    private final VoiceComposer composer;
    private final AddVoiceRequest request;
    private final NewVoiceIds ids;
    private int attempt;
    private ComposedVoice voice;
    private Proposal proposal;
    private boolean accepted;

    private VoiceAddition(ProjectSession session, VoiceComposer composer, AddVoiceRequest request, NewVoiceIds ids) {
        this.session = session;
        this.composer = composer;
        this.request = request;
        this.ids = ids;
    }

    /**
     * Composes the new voice and proposes it.
     *
     * @throws EditRefusedException if the edit core refused the patch (it never should)
     * @throws IOException          if the model failed or answered for another part
     */
    public static VoiceAddition start(ProjectSession session, VoiceComposer composer, AddVoiceRequest request,
                                      Progress progress) throws IOException, EditRefusedException {
        return start(session, composer, request, Ids.random(), progress);
    }

    /** As {@link #start(ProjectSession, VoiceComposer, AddVoiceRequest, Progress)}, with a source of ids. */
    public static VoiceAddition start(ProjectSession session, VoiceComposer composer, AddVoiceRequest request,
                                      Supplier<String> idSource, Progress progress)
            throws IOException, EditRefusedException {
        VoiceAddition addition = new VoiceAddition(session, composer, request,
                NewVoiceIds.fresh(session.composition(), idSource));
        addition.generate(progress);
        return addition;
    }

    /**
     * Composes the voice anew, differently: a new attempt. An open proposal is replaced; after a
     * discard the voice can be generated again as well, after acceptance no longer.
     */
    public Proposal regenerate(Progress progress) throws IOException, EditRefusedException {
        if (accepted) {
            throw new IllegalStateException("Die neue Stimme ist schon übernommen.");
        }
        return generate(progress);
    }

    /**
     * Writes the preview: the piece with the new voice in {@code preview/<id>/} as
     * {@link ProjectSession#preview} does, and the new voice alone as {@value #SOLO} beside it.
     *
     * @return the preview folder
     */
    public Path preview() throws IOException {
        requireOpen();
        Path folder = session.preview(proposal);
        Composition piece = proposal.result();
        Track track = piece.track(voice.track().id());
        Composition solo = new Composition(piece.meta(), piece.timeMap(), piece.sections(),
                List.of(piece.part(voice.part().id())), List.of(track), List.of(), piece.dynamics());
        MidiExport.write(solo, folder.resolve(SOLO));
        return folder;
    }

    /** Takes the new voice into the piece: a revision of the AI, undoable. */
    public ChangeSet accept() throws IOException {
        requireOpen();
        ChangeSet changes = session.accept(proposal);
        proposal = null;
        accepted = true;
        return changes;
    }

    /** Throws the new voice away; the piece is as it was. */
    public void discard() throws IOException {
        requireOpen();
        session.discard(proposal);
        proposal = null;
    }

    /** The open proposal, {@code null} once accepted or discarded. */
    public Proposal proposal() {
        return proposal;
    }

    /** The voice of the last attempt, with its plan, context and repairs. */
    public ComposedVoice voice() {
        return voice;
    }

    public int attempt() {
        return attempt;
    }

    public AddVoiceRequest request() {
        return request;
    }

    private Proposal generate(Progress progress) throws IOException, EditRefusedException {
        Composition base = session.composition();
        int next = attempt + 1;
        ComposedVoice composed = composer.compose(base, request, ids, next,
                session.folder().resolve(CACHE), progress);
        Proposal made = session.propose(AddVoice.patch(composed), AddVoice.scope(composed), Revision.Author.AI,
                composed.runId());
        List<String> differences = new ArrayList<>(AddVoice.untouched(base, made.result(), composed.part().id()));
        if (!differences.isEmpty()) {
            session.discard(made);
            throw new IllegalStateException("Die neue Stimme hätte Bestehendes verändert: " + differences);
        }
        attempt = next;
        voice = composed;
        proposal = made;
        return made;
    }

    private void requireOpen() {
        if (proposal == null || session.pending().map(p -> !p.id().equals(proposal.id())).orElse(true)) {
            throw new IllegalStateException("Kein offener Vorschlag für die neue Stimme.");
        }
    }
}
