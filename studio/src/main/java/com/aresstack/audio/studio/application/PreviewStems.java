package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.project.ProjectFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The rendered stems of a piece go along into the preview of a proposal, and back when it is
 * taken: what sounds the same is rendered once. Stems are named by their content, so a stem that
 * is there already is the same one.
 */
public final class PreviewStems {
    private PreviewStems() {
    }

    /** Copies the stems one folder has to another that lacks them. */
    public static void carry(Path from, Path to) throws IOException {
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
