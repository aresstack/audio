package com.aresstack.audio.studio.application;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;

/**
 * Where the notes given as a template are kept: a copy of the file in the folder
 * {@value #FOLDER} of a piece (or of a title of an album). A piece whose folder holds one is not
 * composed freely: it is made from the template ({@link TemplatePiece}). The copy keeps the name
 * of the file it was made from, so the window can say which notes the piece follows; a template
 * that is given anew replaces the one before.
 */
public final class ScoreTemplates {
    public static final String FOLDER = "score-template";

    private ScoreTemplates() {
    }

    /** The template kept in this folder (of a piece, or of a title of an album), if there is one. */
    public static Optional<Path> of(Path folder) {
        Path kept = folder.resolve(FOLDER);
        if (!Files.isDirectory(kept)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(kept)) {
            Path[] found = java.util.stream.StreamSupport.stream(files.spliterator(), false)
                    .filter(Files::isRegularFile).filter(file -> !file.getFileName().toString().startsWith("."))
                    .sorted().toArray(Path[]::new);
            return found.length == 0 ? Optional.empty() : Optional.of(found[0]);
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    /** Keeps a copy of {@code source} as the template of this folder, in place of the one before. */
    public static Path put(Path folder, Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Keine Datei: " + source);
        }
        Path kept = Files.createDirectories(folder.resolve(FOLDER));
        Path target = kept.resolve(source.getFileName().toString());
        if (Files.exists(target) && Files.isSameFile(source, target)) {
            return target;
        }
        Path temporary = Files.createTempFile(kept, ".template", ".part");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            clear(kept);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    /** The folder has no template any more; what is composed in it then is free again. */
    public static void remove(Path folder) throws IOException {
        Path kept = folder.resolve(FOLDER);
        if (Files.isDirectory(kept)) {
            clear(kept);
            try (DirectoryStream<Path> rest = Files.newDirectoryStream(kept)) {
                if (!rest.iterator().hasNext()) {
                    Files.delete(kept);
                }
            }
        }
    }

    /**
     * Gives a piece the template of its title in an album: copied when the piece has none or
     * another one, else left as it is.
     *
     * @return whether the piece follows a template now
     */
    public static boolean follow(Path titleFolder, Path piece) throws IOException {
        Optional<Path> given = of(titleFolder);
        if (given.isEmpty()) {
            return of(piece).isPresent();
        }
        Optional<Path> has = of(piece);
        if (has.isEmpty() || !has.get().getFileName().equals(given.get().getFileName())
                || !Arrays.equals(Files.readAllBytes(has.get()), Files.readAllBytes(given.get()))) {
            put(piece, given.get());
        }
        return true;
    }

    private static void clear(Path kept) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(kept)) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && !file.getFileName().toString().endsWith(".part")) {
                    Files.delete(file);
                }
            }
        }
    }
}
