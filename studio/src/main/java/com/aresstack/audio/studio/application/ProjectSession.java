package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Meta;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.EditHistory;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.composition.edit.TimeShift;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.LegacyImport;
import com.aresstack.audio.studio.project.MidiExport;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.project.PreviewFolder;
import com.aresstack.audio.studio.project.ProjectFile;
import com.aresstack.audio.studio.project.ProjectFolder;
import com.aresstack.audio.studio.project.RevisionStore;
import com.aresstack.audio.studio.project.StemManifest;
import com.aresstack.audio.studio.project.TsvExport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One open piece as a model (phase M1 of the architecture review): {@code composition.json} is
 * the truth, {@code arrangement.tsv} a view written from it, so that renderer, score and mixer
 * go on reading what they read.
 *
 * <p>A piece from before the model is imported when it is first opened, without asking: its
 * old files are copied to {@code legacy/} once and never touched again, and the model is made
 * from them. Rendered stems are taken over, so nothing is rendered again.
 *
 * <p>Until the composer and the renaming of a track go through the edit core below, they
 * still write {@code arrangement.tsv} and {@code spatial-scene.json} themselves. Opening a piece
 * never takes such a change: a file that is older than the model, or merely different, cannot
 * overwrite it. The caller that has just written the file calls {@link #synchronize()}, which
 * takes over what changed and keeps what the model holds beyond the old files; a model that says
 * more than {@code arrangement.tsv} can is never replaced from that file.
 *
 * <p><b>Edit core (work package F3).</b> Every change of the model goes through
 * {@link com.aresstack.audio.composition.edit.CompositionEditor}: {@link #apply} for a change
 * by hand, {@link #propose} for a change that is first previewed (every change by the AI).
 * A proposal is written to {@code preview/<id>/} for listening ({@link #preview}) and changes
 * nothing of the piece until it is {@linkplain #accept accepted}; {@link #discard} only deletes
 * the preview. Accepted changes and changes by hand can be undone and redone within the session;
 * revisions in {@code revisions/} are made before and after every accepted AI change, when the
 * piece is saved and on request, the first being the original. After every change only the stems
 * of parts that sound different are dropped from {@code render/manifest.json}.
 */
public final class ProjectSession {
    /** The files that describe the piece in {@code arrangement.tsv} and beside it. */
    private static final List<String> PIECE_FILES = List.of(ProjectFolder.ARRANGEMENT, ProjectFolder.TRACKS,
            ProjectFolder.PLAN, ProjectFolder.SONG);
    /** The file of the stage the studio still writes. */
    private static final List<String> STAGE_FILES = List.of(ProjectFolder.SCENE);

    /** A state of the piece for undo and redo: the model and its mix, which a shift moves along. */
    private record State(Composition composition, MixScene mix) {
    }

    private final Path folder;
    private Composition composition;
    private MixScene mix;
    private final List<String> warnings;
    private final EditHistory<State> history;
    private final Ids ids = Ids.random();
    private Proposal pending;
    private Revision lastRevision;

    private ProjectSession(Path folder, Composition composition, MixScene mix, List<String> warnings) {
        this.folder = folder;
        this.composition = composition;
        this.mix = mix;
        this.warnings = List.copyOf(warnings);
        this.history = new EditHistory<>(new State(composition, mix));
    }

    /**
     * Opens the piece in a folder: loads its model, or imports it from the old files if it has
     * none yet, and takes over what the old pipeline has written since. Empty if the folder holds
     * no piece yet (nothing composed).
     */
    public static Optional<ProjectSession> open(Path folder) throws IOException {
        if (Files.isRegularFile(folder.resolve(CompositionJson.FILE_NAME))) {
            ProjectFile project = ProjectFile.load(folder); // refuses a newer format before anything is read
            // The model is the truth: opening never takes anything from the old files. Whether they
            // changed since the model last took them over says externalChanges().
            ProjectSession session = new ProjectSession(folder, CompositionJson.load(folder), MixJson.load(folder),
                    project == null ? List.of() : project.warnings());
            session.keepOriginal();
            return Optional.of(session);
        }
        ProjectFolder.keepLegacy(folder);
        Optional<LegacyImport.Result> imported = LegacyImport.read(folder);
        if (imported.isEmpty()) {
            return Optional.empty();
        }
        LegacyImport.Result result = imported.get();
        ProjectSession session = new ProjectSession(folder, result.composition(), result.mix(), result.warnings());
        new ProjectFile(ProjectFile.FORMAT_VERSION, null,
                ProjectFolder.source(folder).equals(folder) ? "." : ProjectFolder.LEGACY, result.warnings(),
                sources(folder)).save(folder);
        StemManifest.save(folder, result.stems());
        session.writeModel();
        session.keepOriginal();
        return Optional.of(session);
    }

    /**
     * Brings back a piece as it was before the model: the files of the model go, the old files
     * come back from {@code legacy/}. Returns false if the piece has no such copy.
     */
    public static boolean revertToLegacy(Path folder) throws IOException {
        return ProjectFolder.restoreLegacy(folder);
    }

    public Path folder() {
        return folder;
    }

    public Composition composition() {
        return composition;
    }

    /** The mix, or {@code null} if the piece has none. */
    public MixScene mix() {
        return mix;
    }

    /** What the import of the old files could not take over as it was written. */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Whether the old pipeline has written {@code arrangement.tsv}, {@code tracks.json}, the plan,
     * the name or the stage since the model last took them over. Opening a piece never takes such
     * a change; {@link #synchronize()} does, when the caller decides to.
     */
    public boolean externalChanges() throws IOException {
        if (!Files.isRegularFile(folder.resolve(ProjectFolder.ARRANGEMENT))) {
            return false;
        }
        ProjectFile project = ProjectFile.load(folder);
        Map<String, String> now = sources(folder);
        Map<String, String> before = project == null ? Map.of() : project.sources();
        return changed(before, now, PIECE_FILES) || changed(before, now, STAGE_FILES);
    }

    /**
     * Whether the model says only what {@code arrangement.tsv} can say: one tempo in four
     * quarters, one clip per track over its default part. Only then is the file a full view of
     * the model, and only then may a new version of it be taken over without losing anything.
     */
    public boolean withinTsv() {
        try {
            TsvExport.lines(composition);
        } catch (TsvExport.NotExpressible beyond) {
            return false;
        }
        for (Track track : composition.tracks()) {
            if (track.kind() != Track.Kind.MIDI || track.clips().size() > 1) {
                return false;
            }
            for (com.aresstack.audio.composition.Clip clip : track.clips()) {
                if (!(clip instanceof com.aresstack.audio.composition.MidiClip midi) || clip.startTick() != 0
                        || (midi.partId() != null && !midi.partId().equals(track.defaultPartId()))) {
                    return false;
                }
            }
        }
        return composition.assets().isEmpty() && composition.parts().size() == composition.tracks().size();
    }

    /**
     * Takes into the model, on purpose, what the old pipeline has written since the model last took it over:
     * a new {@code arrangement.tsv} from the composer or a renamed track, a changed
     * {@code tracks.json}, plan or name, and the stage in {@code spatial-scene.json}. Nothing
     * happens if none of these files changed. Parts whose notes stayed the same are kept as the
     * model has them, and so are the fields of the model the old files do not know; changed
     * objects keep their ids where they stayed. Returns whether the model changed.
     */
    public boolean synchronize() throws IOException {
        if (!Files.isRegularFile(folder.resolve(ProjectFolder.ARRANGEMENT))) {
            return false;
        }
        ProjectFile project = ProjectFile.load(folder);
        Map<String, String> now = sources(folder);
        Map<String, String> before = project == null ? Map.of() : project.sources();
        boolean piece = changed(before, now, PIECE_FILES);
        boolean stage = changed(before, now, STAGE_FILES);
        if (!piece && !stage) {
            return false;
        }
        if (piece && !withinTsv()) {
            throw new IOException("Das Werk enthält mehr, als arrangement.tsv ausdrücken kann (Tempo- oder"
                    + " Taktwechsel, mehrere Clips oder Instrumente je Bahn); die geänderte Datei wird nicht"
                    + " übernommen, damit nichts verloren geht: " + folder);
        }
        Optional<LegacyImport.Result> current = LegacyImport.read(folder, folder, composition, mix);
        if (current.isEmpty()) {
            return false;
        }
        LegacyImport.Result result = current.get();
        Composition merged = piece ? keepingModel(result.composition(), composition) : composition;
        MixScene mixed = keepingMix(result.mix(), mix, merged, stage);
        boolean changed = !merged.equals(composition) || !Objects.equals(mixed, mix);
        if (changed) {
            // First the files, then the session: if writing fails, the session keeps the model on
            // disk, and the next call finds the same change again and takes it over then.
            StemManifest.save(folder, StemManifest.current(StemManifest.load(folder), merged));
            writeModel(merged, mixed);
            composition = merged;
            mix = mixed;
            history.push(new State(composition, mix), "Übernommen aus den Dateien des Komponisten");
        }
        ProjectFile kept = project != null ? project
                : new ProjectFile(ProjectFile.FORMAT_VERSION, null, null, new ArrayList<>(), null);
        kept.withSources(now).save(folder);
        return changed;
    }

    /**
     * Saves the model and writes {@code arrangement.tsv} from it, unless the file already says
     * exactly that: a hand-written file with comments is left alone as long as it plays the same
     * and every line of it can be read.
     */
    public void save() throws IOException {
        persist();
        if (lastRevision == null || !lastRevision.composition().equals(composition)) {
            record(Revision.Author.USER, "Gespeichert", null);
        }
    }

    /**
     * Writes the model and {@code arrangement.tsv} from it, without a revision. A piece that says
     * more than the TSV can (tempo or meter changes, another meter than 4/4) gets
     * {@code arrangement.mid}, written from the model, as its exchange file. A TSV lying there is
     * left as it is (work package F1): {@link #synchronize()} never takes it over such a model.
     */
    private void persist() throws IOException {
        writeModel();
        Path arrangement = folder.resolve(ProjectFolder.ARRANGEMENT);
        List<String> lines;
        try {
            lines = TsvExport.lines(composition);
        } catch (TsvExport.NotExpressible beyond) {
            MidiExport.write(composition, folder.resolve(ProjectFolder.MIDI));
            ProjectFile project = ProjectFile.load(folder);
            if (project != null) {
                project.withSources(sources(folder)).save(folder);
            }
            return;
        }
        boolean same = false;
        if (Files.isRegularFile(arrangement)) {
            ArrangementFile written = ArrangementFile.read(arrangement);
            same = written.problems().isEmpty() && TsvExport.canonical(written).equals(lines);
        }
        if (!same) {
            Path temporary = Files.createTempFile(folder, "arrangement-", ".tmp");
            try {
                Files.write(temporary, lines, StandardCharsets.UTF_8);
                Files.move(temporary, arrangement, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        ProjectFile project = ProjectFile.load(folder);
        if (project != null) {
            project.withSources(sources(folder)).save(folder);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Edit core (work package F3)

    /**
     * Applies a change by hand at once, without preview: an undoable step, no revision.
     *
     * @return which parts sound different now
     * @throws EditRefusedException if the edit core refused the patch; nothing changed then
     */
    public ChangeSet apply(Patch patch, EditScope scope) throws EditRefusedException, IOException {
        Proposal.Outcome outcome = Proposal.make(ids.next(), composition, patch, scope, Revision.Author.USER, null);
        if (!outcome.made()) {
            throw new EditRefusedException(outcome.result().violations());
        }
        return commit(outcome.proposal(), outcome.proposal().patch().description());
    }

    /**
     * Makes a proposal: the patch is applied and checked, but the piece stays as it is until the
     * proposal is accepted. One proposal at a time; a new one replaces one still open, whose
     * preview is deleted.
     *
     * @param runId for a proposal of the AI the run it came from, else {@code null}
     * @throws EditRefusedException if the edit core refused the patch
     */
    public Proposal propose(Patch patch, EditScope scope, Revision.Author author, String runId)
            throws EditRefusedException, IOException {
        Proposal.Outcome outcome = Proposal.make(ids.next(), composition, patch, scope, author, runId);
        if (!outcome.made()) {
            throw new EditRefusedException(outcome.result().violations());
        }
        if (pending != null) {
            PreviewFolder.delete(folder, pending.id());
        }
        pending = outcome.proposal();
        return pending;
    }

    /**
     * Proposes to compose one part anew from a candidate of the whole piece, as a generator wrote
     * it (see {@link TrackRecomposition}): only the notes of that part are taken from it.
     */
    public Proposal proposeRecomposition(String partId, Composition candidate, String runId)
            throws EditRefusedException, IOException {
        return propose(TrackRecomposition.patch(composition, candidate, partId), TrackRecomposition.scope(partId),
                Revision.Author.AI, runId);
    }

    /**
     * Proposes to compose one part anew from the old files a generator wrote into another folder
     * ({@code arrangement.tsv} and beside it), without touching the files of the piece.
     */
    public Proposal proposeRecomposition(String partId, Path composedFolder, String runId)
            throws EditRefusedException, IOException {
        Optional<LegacyImport.Result> read = LegacyImport.read(composedFolder, composedFolder, composition, mix);
        if (read.isEmpty()) {
            throw new IOException("Im Ordner liegt keine arrangement.tsv: " + composedFolder);
        }
        return proposeRecomposition(partId, read.get().composition(), runId);
    }

    /** The proposal waiting to be accepted or discarded, if any. */
    public Optional<Proposal> pending() {
        return Optional.ofNullable(pending);
    }

    /**
     * Writes the preview of the open proposal to {@code preview/<id>/} and gives that folder:
     * the proposed piece as model, {@code arrangement.tsv} and MIDI, for the renderer to render
     * there. Nothing of the piece itself is written.
     */
    public Path preview(Proposal proposal) throws IOException {
        requirePending(proposal);
        return PreviewFolder.write(folder, proposal.id(), proposal.result());
    }

    /**
     * Takes over the open proposal: it becomes the piece, an undoable step and, for a change of
     * the AI, a revision (with one of the state before it if that was none yet).
     *
     * @return which parts sound different now, so that only their stems are rendered again
     * @throws IllegalStateException if the piece changed since the proposal was made
     */
    public ChangeSet accept(Proposal proposal) throws IOException {
        requirePending(proposal);
        if (!proposal.base().equals(composition)) {
            throw new IllegalStateException("Der Vorschlag beruht auf einem älteren Stand des Stücks.");
        }
        boolean ai = proposal.author() == Revision.Author.AI;
        if (ai && (lastRevision == null || !lastRevision.composition().equals(composition))) {
            record(Revision.Author.USER, "Stand vor: " + proposal.patch().description(), null);
        }
        ChangeSet changes = commit(proposal, proposal.patch().description());
        pending = null;
        PreviewFolder.delete(folder, proposal.id());
        if (ai) {
            record(Revision.Author.AI, proposal.patch().description(), proposal.runId());
        }
        return changes;
    }

    /** Throws the open proposal away with its preview; the piece is as it was. */
    public void discard(Proposal proposal) throws IOException {
        requirePending(proposal);
        pending = null;
        PreviewFolder.delete(folder, proposal.id());
    }

    public boolean canUndo() {
        return history.canUndo();
    }

    public boolean canRedo() {
        return history.canRedo();
    }

    /** What an undo would take back, or {@code null}. */
    public String undoDescription() {
        return history.undoDescription();
    }

    /** What a redo would do again, or {@code null}. */
    public String redoDescription() {
        return history.redoDescription();
    }

    /** Takes back the last change of the session. */
    public ChangeSet undo() throws IOException {
        dropPending();
        return become(history.undo());
    }

    /** Does the last change taken back again. */
    public ChangeSet redo() throws IOException {
        dropPending();
        return become(history.redo());
    }

    /** The revisions of the piece, the original first. */
    public List<Revision> revisions() throws IOException {
        return RevisionStore.list(folder);
    }

    /** Makes a revision of the piece as it is now, on the user's request. */
    public Revision markRevision(String description) throws IOException {
        persist();
        return record(Revision.Author.USER, description, null);
    }

    /**
     * Brings back the piece as a revision holds it: an undoable step, and a revision of its own
     * so that the history goes on from there.
     */
    public ChangeSet restoreRevision(String revisionId) throws IOException {
        Revision revision = RevisionStore.load(folder, revisionId)
                .orElseThrow(() -> new IOException("Keine Revision " + revisionId + " in " + folder));
        dropPending();
        String description = "Revision " + revision.sequence() + " wiederhergestellt";
        history.push(new State(revision.composition(), revision.mix() != null ? revision.mix() : mix), description);
        ChangeSet changes = become(history.current());
        record(Revision.Author.USER, description, null);
        return changes;
    }

    private ChangeSet commit(Proposal proposal, String description) throws IOException {
        MixScene shifted = mix;
        if (shifted != null) {
            for (EditOp.ShiftTime shift : proposal.patch().shifts()) {
                shifted = TimeShift.shift(shifted, shift.fromTick(), shift.ticks());
            }
        }
        history.push(new State(proposal.result(), shifted), description);
        return become(history.current());
    }

    /** Makes a state the piece: writes it and drops the stems of the parts that sound different. */
    private ChangeSet become(State state) throws IOException {
        ChangeSet changes = ChangeSet.between(composition, state.composition());
        composition = state.composition();
        mix = state.mix();
        Set<String> stale = changes.staleStems();
        if (!stale.isEmpty()) {
            List<StemManifest.Entry> kept = new ArrayList<>();
            for (StemManifest.Entry entry : StemManifest.load(folder)) {
                if (!stale.contains(entry.partId())) {
                    kept.add(entry);
                }
            }
            StemManifest.save(folder, kept);
        }
        persist();
        return changes;
    }

    private void requirePending(Proposal proposal) {
        if (proposal == null || pending == null || !pending.id().equals(proposal.id())) {
            throw new IllegalStateException("Dieser Vorschlag ist nicht mehr offen.");
        }
    }

    private void dropPending() throws IOException {
        if (pending != null) {
            PreviewFolder.delete(folder, pending.id());
            pending = null;
        }
    }

    /** Makes sure the piece has its original as revision 1, and finds the last revision. */
    private void keepOriginal() throws IOException {
        List<Revision> revisions = RevisionStore.list(folder);
        if (revisions.isEmpty()) {
            record(Revision.Author.ORIGINAL, "Originalfassung", null);
        } else {
            lastRevision = revisions.get(revisions.size() - 1);
        }
    }

    private Revision record(Revision.Author author, String description, String runId) throws IOException {
        Revision revision = new Revision(ids.next(), lastRevision == null ? 1 : lastRevision.sequence() + 1,
                lastRevision == null ? null : lastRevision.id(), author, System.currentTimeMillis(), description, runId,
                composition, mix);
        RevisionStore.save(folder, revision);
        lastRevision = revision;
        ProjectFile project = ProjectFile.load(folder);
        if (project != null) {
            project.withCurrentRevision(revision.id()).save(folder);
        }
        return revision;
    }

    /** Writes the piece as a Standard MIDI File. */
    public void exportMidi(Path target) throws IOException {
        MidiExport.write(composition, target);
    }

    /**
     * The model after a new import of the old files: a part (with its track) whose notes sound
     * as before stays as the model has it; meta fields the old files do not say keep their
     * value; the sections (the form of the piece) stay, and only music beyond their end gets a
     * section of its own; the time map stays where the file names the same tempo.
     */
    private static Composition keepingModel(Composition imported, Composition model) {
        List<Part> parts = new ArrayList<>();
        List<Track> tracks = new ArrayList<>();
        for (Part part : imported.parts()) {
            Part old = model.part(part.id());
            boolean same = old != null && model.track(part.id()) != null
                    && TsvExport.linesOf(imported, part.id()).equals(TsvExport.linesOf(model, part.id()));
            // tracks.json is still where the old studio keeps instrument and sound bank: they come
            // from there even for a part whose notes stayed the same, each one that it names.
            // A part tracks.json does not name imports with no instrument and the built-in sound.
            boolean listed = part.instrument() != null || !"builtin".equals(part.libraryId());
            parts.add(!same ? part : !listed ? old
                    : new Part(old.id(), old.name(), old.role(), or(part.instrument(), old.instrument()),
                            old.program(), part.libraryId(), old.staffLayout(), old.voices(), old.legacyChannel()));
            tracks.add(same ? model.track(part.id()) : imported.track(part.id()));
        }
        Meta a = imported.meta();
        Meta b = model.meta();
        Meta meta = new Meta(or(a.title(), b.title()), or(a.genre(), b.genre()), or(a.mood(), b.mood()),
                or(a.brief(), b.brief()), or(a.plannedFrom(), b.plannedFrom()), or(a.remarks(), b.remarks()));
        List<Section> sections = keepingSections(imported, model);
        TimeMap time = imported.timeMap().tempoAt(0) == model.timeMap().tempoAt(0) ? model.timeMap() : imported.timeMap();
        return new Composition(meta, time, sections, parts, tracks, model.assets());
    }

    /**
     * The mix after a new import: the channels of parts that stayed keep what the mix says of
     * them, a new part gets the seat the import gives it, a part that went loses its channel and
     * its automation. Only a changed {@code spatial-scene.json} sets places, levels, room and
     * level curves anew; how the level is set ({@code levelMode}) is never said by the old files
     * and stays as the mix has it.
     */
    private static MixScene keepingMix(MixScene imported, MixScene model, Composition composition, boolean stage) {
        if (model == null) {
            return imported;
        }
        if (stage) {
            // The stage file says the places and levels anew, for the parts it knows; a part of the
            // model it does not know keeps its channel and its curve.
            List<MixChannel> channels = new ArrayList<>();
            List<String> fromStage = new ArrayList<>();
            for (Part part : composition.parts()) {
                MixChannel.Owner owner = MixChannel.Owner.part(part.id());
                MixChannel fresh = imported.channelOf(owner);
                MixChannel channel = fresh != null ? fresh : model.channelOf(owner);
                if (channel != null) {
                    channels.add(channel);
                    if (fresh != null) {
                        fromStage.add(channel.id());
                    }
                }
            }
            List<AutomationLane> automation = new ArrayList<>();
            for (AutomationLane lane : imported.automation()) {
                if (fromStage.contains(lane.channelId())) {
                    automation.add(lane);
                }
            }
            for (AutomationLane lane : model.automation()) {
                boolean stays = channels.stream().anyMatch(c -> c.id().equals(lane.channelId()))
                        && !fromStage.contains(lane.channelId());
                if (lane.channelId() == null || stays) {
                    automation.add(lane);
                }
            }
            return new MixScene(imported.room(), imported.masterDb(), model.levelMode(), channels, automation);
        }
        List<MixChannel> channels = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (Part part : composition.parts()) {
            MixChannel.Owner owner = MixChannel.Owner.part(part.id());
            MixChannel old = model.channelOf(owner);
            MixChannel channel = old != null ? old : imported.channelOf(owner);
            if (channel != null) {
                channels.add(channel);
                kept.add(channel.id());
            }
        }
        List<AutomationLane> automation = new ArrayList<>();
        for (AutomationLane lane : model.automation()) {
            if (lane.channelId() == null || kept.contains(lane.channelId())) {
                automation.add(lane);
            }
        }
        for (AutomationLane lane : imported.automation()) {
            boolean newChannel = lane.channelId() != null && kept.contains(lane.channelId())
                    && model.channels().stream().noneMatch(c -> c.id().equals(lane.channelId()));
            if (newChannel) {
                automation.add(lane);
            }
        }
        return new MixScene(model.room(), model.masterDb(), model.levelMode(), channels, automation);
    }

    /**
     * The form of the piece after a new import: the old files know only one section over the
     * whole piece, so the sections of the model stay as they are. Music that now goes beyond them
     * gets one more section from their end to its end; a piece that got shorter keeps its form.
     */
    private static List<Section> keepingSections(Composition imported, Composition model) {
        if (model.sections().isEmpty()) {
            return imported.sections();
        }
        List<Section> sections = new ArrayList<>(model.sections());
        long end = sections.get(sections.size() - 1).endTick();
        long music = imported.endTick();
        if (music > end) {
            java.util.Set<String> taken = new java.util.HashSet<>();
            sections.forEach(section -> taken.add(section.id()));
            com.aresstack.audio.composition.Ids ids = com.aresstack.audio.composition.Ids.random();
            String id = ids.next();
            while (taken.contains(id)) {
                id = ids.next();
            }
            sections.add(new Section(id, "Neu", null, end, music - end, null, List.of(), null));
        }
        return sections;
    }

    private static String or(String first, String second) {
        return first != null ? first : second;
    }

    private static List<String> ids(Composition composition) {
        return composition.parts().stream().map(Part::id).toList();
    }

    private static boolean changed(Map<String, String> before, Map<String, String> now, List<String> names) {
        for (String name : names) {
            if (!Objects.equals(before.get(name), now.get(name))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> sources(Path folder) throws IOException {
        List<String> names = new ArrayList<>(PIECE_FILES);
        names.addAll(STAGE_FILES);
        return ProjectFile.fingerprint(folder, names);
    }

    private void writeModel() throws IOException {
        writeModel(composition, mix);
    }

    private void writeModel(Composition composition, MixScene mix) throws IOException {
        CompositionJson.save(folder, composition);
        if (mix != null) {
            MixJson.save(folder, mix);
        }
        if (ProjectFile.load(folder) == null) {
            new ProjectFile(ProjectFile.FORMAT_VERSION, null, null, new ArrayList<>(), null).save(folder);
        }
    }
}
