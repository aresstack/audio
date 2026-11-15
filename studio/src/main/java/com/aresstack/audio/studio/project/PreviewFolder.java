package com.aresstack.audio.studio.project;

import com.aresstack.audio.composition.Composition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The folder of a proposal's preview, {@code preview/<id>/} within the piece: the proposed piece
 * as {@code composition.json}, {@code arrangement.tsv} (where it can say it) and
 * {@code arrangement.mid}, beside copies of the files the renderer reads with the arrangement
 * (instruments, stage, name). The renderer can be pointed at it like at the folder of a piece and
 * writes its stems there; nothing of the piece itself is overwritten. Discarding deletes the folder.
 */
public final class PreviewFolder {
    /** The files beside the arrangement that a render of the preview reads as the piece has them. */
    private static final List<String> BESIDE = List.of(ProjectFolder.TRACKS, ProjectFolder.SCENE, ProjectFolder.SONG);

    private PreviewFolder() {
    }

    public static Path of(Path piece, String proposalId) {
        return piece.resolve(ProjectFolder.PREVIEW).resolve(proposalId);
    }

    /** Writes the preview of a proposal and gives its folder. */
    public static Path write(Path piece, String proposalId, Composition proposed) throws IOException {
        Path folder = of(piece, proposalId);
        ProjectFolder.deleteTree(folder);
        Files.createDirectories(folder);
        CompositionJson.save(folder, proposed);
        try {
            JsonText.atomically(folder.resolve(ProjectFolder.ARRANGEMENT),
                    (String.join("\n", TsvExport.lines(proposed)) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (TsvExport.NotExpressible beyond) {
            // then the MIDI file is what a renderer reads
        }
        MidiExport.write(proposed, folder.resolve(ProjectFolder.MIDI));
        for (String name : BESIDE) {
            Path file = piece.resolve(name);
            if (Files.isRegularFile(file)) {
                Files.copy(file, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return folder;
    }

    /** Deletes the preview of a proposal, if there is one. */
    public static void delete(Path piece, String proposalId) throws IOException {
        ProjectFolder.deleteTree(of(piece, proposalId));
        Path previews = piece.resolve(ProjectFolder.PREVIEW);
        try (var left = Files.isDirectory(previews) ? Files.list(previews) : java.util.stream.Stream.<Path>empty()) {
            if (Files.isDirectory(previews) && left.findAny().isEmpty()) {
                Files.delete(previews);
            }
        }
    }
}
