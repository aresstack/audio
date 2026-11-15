package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
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
 * still write {@code arrangement.tsv} themselves. {@link #synchronize()} takes such a change into
 * the model, keeping the ids of everything that stayed; afterwards model and view agree again.
 */
public final class ProjectSession {
    private final Path folder;
    private Composition composition;
    private MixScene mix;
    private List<String> warnings;

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
            Composition composition = CompositionJson.load(folder);
            MixScene mix = MixJson.load(folder);
            ProjectSession session = new ProjectSession(folder, composition, mix,
                    project == null ? List.of() : project.warnings());
            session.synchronize();
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
                ProjectFolder.source(folder).equals(folder) ? "." : ProjectFolder.LEGACY, result.warnings()).save(folder);
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
     * Takes into the model what the old pipeline has written since the model was saved: a new
     * {@code arrangement.tsv} from the composer or a renamed track, and the stage in
     * {@code spatial-scene.json}. Objects that stayed keep their ids. Returns whether the model
     * changed.
     */
    public boolean synchronize() throws IOException {
        Path arrangement = folder.resolve(ProjectFolder.ARRANGEMENT);
        if (!Files.isRegularFile(arrangement)) {
            return false;
        }
        Optional<LegacyImport.Result> current = LegacyImport.read(folder, folder, composition, mix);
        if (current.isEmpty()) {
            return false;
        }
        LegacyImport.Result result = current.get();
        boolean changed = !result.composition().equals(composition) || !java.util.Objects.equals(result.mix(), mix);
        if (!changed) {
            return false;
        }
        composition = result.composition();
        mix = result.mix();
        // Stems taken over stay valid only for parts that still sound as they did.
        List<StemManifest.Entry> kept = StemManifest.current(StemManifest.load(folder), composition);
        StemManifest.save(folder, kept);
        writeModel();
        return true;
    }

    /**
     * Saves the model and writes {@code arrangement.tsv} from it where the file does not already
     * say the same (a hand-written file with comments is left alone as long as it plays the same).
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
        if (!Files.isRegularFile(arrangement)
                || !TsvExport.canonical(ArrangementFile.read(arrangement)).equals(lines)) {
            Path temporary = Files.createTempFile(folder, "arrangement-", ".tmp");
            try {
                Files.write(temporary, lines, StandardCharsets.UTF_8);
                Files.move(temporary, arrangement, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Writes the piece as a Standard MIDI File. */
    public void exportMidi(Path target) throws IOException {
        MidiExport.write(composition, target);
    }

    private void writeModel() throws IOException {
        CompositionJson.save(folder, composition);
        if (mix != null) {
            MixJson.save(folder, mix);
        }
        if (ProjectFile.load(folder) == null) {
            new ProjectFile(ProjectFile.FORMAT_VERSION, null, null, new ArrayList<>()).save(folder);
        }
    }
}
