package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.studio.ai.RegionComposer;
import com.aresstack.audio.studio.ai.RegionRequest;
import com.aresstack.audio.studio.ai.ContinuationPerformance;
import com.aresstack.audio.studio.ai.RewrittenRegion;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.project.MidiExport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The use case "write this passage anew" or "vary this passage" in the folder of a piece (work
 * package C2, acceptance A5 and A6): the AI writes the selected parts in the selected bars anew,
 * it becomes a proposal of the piece's {@link ProjectSession}, which can be listened to with the
 * piece and with the selected parts alone ({@link #preview}), written again ({@link #regenerate}),
 * accepted or discarded. The piece itself changes only on acceptance, and then nowhere but in the
 * selection.
 *
 * <p>The answers of the model are kept in {@value #CACHE} within the piece; every attempt has its
 * own answers. What the normalizer and the patch had to cut or leave out is in
 * {@link #corrections()}.
 */
public final class RegionRework {
    /** Where the answers of the model are kept, relative to the folder of the piece. */
    public static final String CACHE = "ai-cache/region";
    /** The MIDI file of the selected parts alone, within the preview folder. */
    public static final String PARTS = "parts.mid";

    private final ProjectSession session;
    private final RegionComposer composer;
    private final RegionRequest request;
    private final Supplier<String> ids;
    /** The form the piece gets with the passage (a gap becomes a transition), or {@code null} to keep it. */
    private final List<Section> form;
    private int attempt;
    private RewrittenRegion region;
    private List<String> corrections = List.of();
    private Proposal proposal;
    private boolean accepted;

    private RegionRework(ProjectSession session, RegionComposer composer, RegionRequest request, Supplier<String> ids,
                         List<Section> form) {
        this.session = session;
        this.composer = composer;
        this.request = request;
        this.ids = ids;
        this.form = form == null ? null : List.copyOf(form);
    }

    /**
     * Writes the passage anew and proposes it.
     *
     * @throws EditRefusedException if the edit core refused the patch (it never should)
     * @throws IOException          if the model failed or answered for another part
     */
    public static RegionRework start(ProjectSession session, RegionComposer composer, RegionRequest request,
                                     Progress progress) throws IOException, EditRefusedException {
        return start(session, composer, request, Ids.random(), progress);
    }

    /** As {@link #start(ProjectSession, RegionComposer, RegionRequest, Progress)}, with a source of ids. */
    public static RegionRework start(ProjectSession session, RegionComposer composer, RegionRequest request,
                                     Supplier<String> ids, Progress progress) throws IOException, EditRefusedException {
        return start(session, composer, request, null, ids, progress);
    }

    /**
     * As {@link #start(ProjectSession, RegionComposer, RegionRequest, Progress)}, and the form of the
     * piece becomes {@code form} in the same step: a gap the AI fills becomes a section of its own
     * (see {@link SectionEdits#filledForm}). Nothing else of the form may differ.
     *
     * @param form the sections of the piece with the passage, or {@code null} to keep them
     */
    public static RegionRework start(ProjectSession session, RegionComposer composer, RegionRequest request,
                                     List<Section> form, Supplier<String> ids, Progress progress)
            throws IOException, EditRefusedException {
        RegionRework rework = new RegionRework(session, composer, request, ids, form);
        rework.generate(progress);
        return rework;
    }

    /** Writes the passage anew, differently: a new attempt. An open proposal is replaced. */
    public Proposal regenerate(Progress progress) throws IOException, EditRefusedException {
        if (accepted) {
            throw new IllegalStateException("Die Passage ist schon übernommen.");
        }
        return generate(progress);
    }

    /**
     * Writes the preview: the piece with the new passage in {@code preview/<id>/} as
     * {@link ProjectSession#preview} does, and the selected parts alone as {@value #PARTS} beside it.
     *
     * @return the preview folder
     */
    public Path preview() throws IOException {
        requireOpen();
        Path folder = session.preview(proposal);
        Composition piece = proposal.result();
        Set<String> parts = new HashSet<>(region.passages().keySet());
        List<Part> selected = piece.parts().stream().filter(p -> parts.contains(p.id())).toList();
        List<Track> tracks = new ArrayList<>();
        for (Track track : piece.tracks()) {
            List<Clip> clips = track.clips().stream()
                    .filter(c -> c instanceof MidiClip midi && parts.contains(track.partOf(midi))).toList();
            if (!clips.isEmpty()) {
                String home = parts.contains(track.defaultPartId()) ? track.defaultPartId() : null;
                List<Clip> owned = new ArrayList<>();
                for (Clip clip : clips) {
                    MidiClip midi = (MidiClip) clip;
                    owned.add(home != null ? midi : new MidiClip(midi.id(), midi.startTick(), midi.lengthTicks(),
                            track.partOf(midi), midi.notes()));
                }
                tracks.add(new Track(track.id(), track.name(), Track.Kind.MIDI, home, owned));
            }
        }
        MidiExport.write(new Composition(piece.meta(), piece.timeMap(), piece.sections(), selected, tracks, List.of(),
                        piece.dynamics()),
                folder.resolve(PARTS));
        return folder;
    }

    /** Takes the new passage into the piece: a revision of the AI, undoable. */
    public ChangeSet accept() throws IOException {
        requireOpen();
        ChangeSet changes = session.accept(proposal);
        proposal = null;
        accepted = true;
        return changes;
    }

    /** Throws the new passage away; the piece is as it was. */
    public void discard() throws IOException {
        requireOpen();
        session.discard(proposal);
        proposal = null;
    }

    /** The open proposal, {@code null} once accepted or discarded. */
    public Proposal proposal() {
        return proposal;
    }

    /** The passage of the last attempt, with its context and the repairs of the normalizer. */
    public RewrittenRegion region() {
        return region;
    }

    /** Every repair of the last attempt: those of the normalizer, then what the patch had to cut. */
    public List<String> corrections() {
        return corrections;
    }

    public int attempt() {
        return attempt;
    }

    public RegionRequest request() {
        return request;
    }

    private Proposal generate(Progress progress) throws IOException, EditRefusedException {
        Composition base = session.composition();
        int next = attempt + 1;
        RewrittenRegion written = composer.compose(base, request, next, session.folder().resolve(CACHE), progress);
        List<String> log = new ArrayList<>(written.corrections());
        Patch patch = RegionEdit.patch(base, written, ids, log);
        EditScope scope = RegionEdit.scope(request.selection());
        Composition expected = base;
        if (form != null) {
            List<EditOp> operations = new ArrayList<>(patch.operations());
            operations.add(new EditOp.SetSections(form));
            patch = new Patch(patch.description(), operations);
            scope = scope.allowing(EditOp.Kind.SET_SECTIONS);
            expected = new Composition(base.meta(), base.timeMap(), form, base.parts(), base.tracks(), base.assets(),
                    base.dynamics());
        }
        if (!written.performance().isEmpty()) {
            // how the passage is played, as the AI planned it; before and after it the piece stays as it was
            List<String> said = new ArrayList<>();
            ContinuationPerformance.Applied applied = written.performance().applied(base,
                    request.selection().fromTick(), request.selection().toTick(), said);
            List<EditOp> operations = new ArrayList<>(patch.operations());
            if (applied.timeMap() != null) {
                operations.add(new EditOp.SetTimeMap(applied.timeMap()));
                expected = expected.withTimeMap(applied.timeMap());
            }
            if (applied.dynamics() != null) {
                operations.add(new EditOp.SetDynamics(applied.dynamics()));
                expected = expected.withDynamics(applied.dynamics());
            }
            log.addAll(said);
            patch = new Patch(patch.description(), operations);
            scope = scope.allowing(EditOp.Kind.SET_TIME_MAP).allowing(EditOp.Kind.SET_DYNAMICS);
        }
        Proposal made = session.propose(patch, scope, Revision.Author.AI, written.runId());
        List<String> differences = RegionEdit.outsideChanges(expected, made.result(), request.selection());
        if (!differences.isEmpty()) {
            session.discard(made);
            throw new IllegalStateException("Die Passage hätte außerhalb der Auswahl etwas verändert: " + differences);
        }
        attempt = next;
        region = written;
        corrections = List.copyOf(log);
        proposal = made;
        return made;
    }

    private void requireOpen() {
        if (proposal == null || session.pending().map(p -> !p.id().equals(proposal.id())).orElse(true)) {
            throw new IllegalStateException("Kein offener Vorschlag für die Passage.");
        }
    }
}
