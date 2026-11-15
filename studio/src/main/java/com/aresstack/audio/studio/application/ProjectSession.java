package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Meta;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.LegacyImport;
import com.aresstack.audio.studio.project.MidiExport;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.project.ProjectFile;
import com.aresstack.audio.studio.project.ProjectFolder;
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

/**
 * One open piece as a model (phase M1 of the architecture review): {@code composition.json} is
 * the truth, {@code arrangement.tsv} a view written from it, so that renderer, score and mixer
 * go on reading what they read.
 *
 * <p>A piece from before the model is imported when it is first opened, without asking: its
 * old files are copied to {@code legacy/} once and never touched again, and the model is made
 * from them. Rendered stems are taken over, so nothing is rendered again.
 *
 * <p>Until the edit core (work package F3) exists, the composer and the renaming of a track
 * still write {@code arrangement.tsv} and {@code spatial-scene.json} themselves. Opening a piece
 * never takes such a change: a file that is older than the model, or merely different, cannot
 * overwrite it. The caller that has just written the file calls {@link #synchronize()}, which
 * takes over what changed and keeps what the model holds beyond the old files; a model that says
 * more than {@code arrangement.tsv} can is never replaced from that file.
 */
public final class ProjectSession {
    /** The files that describe the piece in {@code arrangement.tsv} and beside it. */
    private static final List<String> PIECE_FILES = List.of(ProjectFolder.ARRANGEMENT, ProjectFolder.TRACKS,
            ProjectFolder.PLAN, ProjectFolder.SONG);
    /** The file of the stage the studio still writes. */
    private static final List<String> STAGE_FILES = List.of(ProjectFolder.SCENE);

    private final Path folder;
    private Composition composition;
    private MixScene mix;
    private final List<String> warnings;

    private ProjectSession(Path folder, Composition composition, MixScene mix, List<String> warnings) {
        this.folder = folder;
        this.composition = composition;
        this.mix = mix;
        this.warnings = List.copyOf(warnings);
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
            return Optional.of(new ProjectSession(folder, CompositionJson.load(folder), MixJson.load(folder),
                    project == null ? List.of() : project.warnings()));
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
        boolean partsChanged = !ids(merged).equals(ids(composition));
        MixScene mixed = stage || partsChanged || mix == null ? result.mix() : mix;
        boolean changed = !merged.equals(composition) || !Objects.equals(mixed, mix);
        composition = merged;
        mix = mixed;
        if (changed) {
            // Stems taken over stay valid only for parts that still sound as they did.
            StemManifest.save(folder, StemManifest.current(StemManifest.load(folder), composition));
            writeModel();
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
        writeModel();
        Path arrangement = folder.resolve(ProjectFolder.ARRANGEMENT);
        List<String> lines;
        try {
            lines = TsvExport.lines(composition);
        } catch (TsvExport.NotExpressible beyond) {
            return; // then arrangement.mid is the exchange format
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

    /** Writes the piece as a Standard MIDI File. */
    public void exportMidi(Path target) throws IOException {
        MidiExport.write(composition, target);
    }

    /**
     * The model after a new import of the old files: a part (with its track) whose notes sound
     * as before stays as the model has it; meta fields the old files do not say keep their
     * value; the sections stay where they still cover the piece; the time map stays where the
     * file names the same tempo.
     */
    private static Composition keepingModel(Composition imported, Composition model) {
        List<Part> parts = new ArrayList<>();
        List<Track> tracks = new ArrayList<>();
        for (Part part : imported.parts()) {
            Part old = model.part(part.id());
            boolean same = old != null && model.track(part.id()) != null
                    && TsvExport.linesOf(imported, part.id()).equals(TsvExport.linesOf(model, part.id()));
            parts.add(same ? old : part);
            tracks.add(same ? model.track(part.id()) : imported.track(part.id()));
        }
        Meta a = imported.meta();
        Meta b = model.meta();
        Meta meta = new Meta(or(a.title(), b.title()), or(a.genre(), b.genre()), or(a.mood(), b.mood()),
                or(a.brief(), b.brief()), or(a.plannedFrom(), b.plannedFrom()), or(a.remarks(), b.remarks()));
        List<Section> sections = model.sections().isEmpty()
                || model.sections().get(model.sections().size() - 1).endTick() != imported.endTick()
                ? imported.sections() : model.sections();
        TimeMap time = imported.timeMap().tempoAt(0) == model.timeMap().tempoAt(0) ? model.timeMap() : imported.timeMap();
        return new Composition(meta, time, sections, parts, tracks, model.assets());
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
        CompositionJson.save(folder, composition);
        if (mix != null) {
            MixJson.save(folder, mix);
        }
        if (ProjectFile.load(folder) == null) {
            new ProjectFile(ProjectFile.FORMAT_VERSION, null, null, new ArrayList<>(), null).save(folder);
        }
    }
}
