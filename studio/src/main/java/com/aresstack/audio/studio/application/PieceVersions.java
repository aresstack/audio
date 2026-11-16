package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.ProjectFolder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * The earlier versions of a piece ("Fassungen"). Before a piece that has notes is made anew, all
 * it is goes into a folder of its own inside {@value #FOLDER} of the piece: the notes, the model
 * with its revisions, the tracks, the stage, the answers of the AI and what was rendered. Nothing
 * is copied and nothing is written over: the folder of the piece is moved as a whole, so either
 * all of it is set aside or nothing changed. A version comes back the same way, and the piece
 * as it was until then becomes a version itself; so nothing is ever lost by choosing.
 */
public final class PieceVersions {
    /** The folder inside a piece that holds its earlier versions, one folder each. */
    public static final String FOLDER = "fassungen";

    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * One earlier version of a piece.
     *
     * @param id     its name in {@value #FOLDER}: when it was set aside
     * @param folder where all of it lies
     */
    public record Version(String id, Path folder) {
        /** When it was set aside, as the user reads it; its id if that is no time. */
        public String label() {
            try {
                String time = id.length() > 15 ? id.substring(0, 15) : id;
                String shown = LocalDateTime.parse(time, ID).format(SHOWN);
                return id.length() > 15 ? shown + " (" + id.substring(16) + ")" : shown;
            } catch (RuntimeException other) {
                return id;
            }
        }

        /** The mix of this version, if it was rendered: it can be heard without bringing it back. */
        public Path mix() {
            Path mix = folder.resolve(StageMixService.MIX_FILE);
            return Files.isRegularFile(mix) ? mix : null;
        }
    }

    private PieceVersions() {
    }

    /** Whether a folder holds a piece with notes: something that would be lost by making it anew. */
    public static boolean hasNotes(Path piece) {
        return piece != null && (Files.isRegularFile(piece.resolve(ProjectFolder.ARRANGEMENT))
                || Files.isRegularFile(piece.resolve(CompositionJson.FILE_NAME)));
    }

    /** The earlier versions of a piece, the newest first. */
    public static List<Version> list(Path piece) {
        List<Version> versions = new ArrayList<Version>();
        Path kept = piece == null ? null : piece.resolve(FOLDER);
        if (kept != null && Files.isDirectory(kept)) {
            try (DirectoryStream<Path> folders = Files.newDirectoryStream(kept)) {
                for (Path folder : folders) {
                    if (Files.isDirectory(folder)) {
                        versions.add(new Version(folder.getFileName().toString(), folder));
                    }
                }
            } catch (IOException unreadable) {
                // then the piece shows no versions; they stay where they are
            }
        }
        versions.sort((first, second) -> second.id().compareTo(first.id()));
        return Collections.unmodifiableList(versions);
    }

    /**
     * Sets the piece as it is aside as a version and leaves its folder empty for a new one: only
     * its name, its description, the notes it follows ({@link ScoreTemplates}) and its earlier
     * versions stay. Either all of the piece is set aside or, if a file of it is in use, nothing
     * is changed and this throws.
     *
     * @return the version the piece became
     */
    public static Version setAside(Path piece) throws IOException {
        return setAside(piece, LocalDateTime.now());
    }

    static Version setAside(Path piece, LocalDateTime now) throws IOException {
        Path root = piece.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || root.getParent() == null) {
            throw new IOException("Kein Ordner eines Stücks: " + piece);
        }
        String id = freshId(root, now);
        // A folder that begins with a dot is no piece: nobody lists it while it lies beside the others.
        Path aside = root.resolveSibling("." + root.getFileName() + ".fassung-" + id);
        try {
            Files.move(root, aside, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException inUse) {
            throw new IOException("Die bisherige Fassung ließ sich nicht beiseitelegen (wird eine Datei des Stücks"
                    + " gerade benutzt oder abgespielt?). Nichts wurde geändert: " + said(inUse), inUse);
        }
        Path versions = root.resolve(FOLDER);
        Path target = versions.resolve(id);
        boolean movedVersions = false;
        try {
            Files.createDirectories(root);
            if (Files.isDirectory(aside.resolve(FOLDER))) {
                Files.move(aside.resolve(FOLDER), versions);
                movedVersions = true;
            } else {
                Files.createDirectories(versions);
            }
            Files.move(aside, target);
        } catch (IOException failed) {
            // Back to where it was: the piece must not be left half moved.
            try {
                if (movedVersions) {
                    Files.move(versions, aside.resolve(FOLDER));
                } else {
                    Files.deleteIfExists(versions);
                }
                Files.deleteIfExists(root);
                Files.move(aside, root);
            } catch (IOException stuck) {
                failed.addSuppressed(stuck);
                throw new IOException("Die bisherige Fassung liegt vollständig in " + aside
                        + ", ließ sich aber nicht einordnen: " + said(failed), failed);
            }
            throw new IOException("Die bisherige Fassung ließ sich nicht beiseitelegen. Nichts wurde geändert: "
                    + said(failed), failed);
        }
        carryOver(target, root);
        return new Version(id, target);
    }

    /**
     * Brings a version back: it is the piece again, with everything it had. The piece as it was
     * until now becomes a version itself, if it has notes; without notes it had nothing to keep.
     *
     * @return the version the piece of until now became, or {@code null} if it had no notes
     */
    public static Version restore(Path piece, String id) throws IOException {
        return restore(piece, id, LocalDateTime.now());
    }

    static Version restore(Path piece, String id, LocalDateTime now) throws IOException {
        Path root = piece.toAbsolutePath().normalize();
        Path chosen = root.resolve(FOLDER).resolve(id).normalize();
        if (id == null || id.isEmpty() || !root.resolve(FOLDER).equals(chosen.getParent())
                || !Files.isDirectory(chosen)) {
            throw new IOException("Diese Fassung gibt es nicht: " + id);
        }
        Version before = null;
        if (hasNotes(root)) {
            before = setAside(root, now);
        } else {
            // Nothing worth keeping: only what a piece without notes has (its name) lies here.
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path entry : entries) {
                    if (!entry.getFileName().toString().equals(FOLDER)) {
                        deleteTree(entry);
                    }
                }
            }
        }
        // What setAside carried over is of the piece until now; the version brings its own.
        for (String name : new String[]{SongLibrary.FILE_NAME, ScoreTemplates.FOLDER}) {
            if (Files.exists(chosen.resolve(name))) {
                deleteTree(root.resolve(name));
            }
        }
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(chosen)) {
            for (Path entry : found) {
                entries.add(entry);
            }
        }
        for (Path entry : entries) {
            Files.move(entry, root.resolve(entry.getFileName().toString()));
        }
        Files.delete(chosen);
        return before;
    }

    /** The piece keeps its name and description, and the notes it was told to follow. */
    private static void carryOver(Path version, Path piece) {
        try {
            Path song = version.resolve(SongLibrary.FILE_NAME);
            if (Files.isRegularFile(song)) {
                Properties all = new Properties();
                try (Reader reader = Files.newBufferedReader(song, StandardCharsets.UTF_8)) {
                    all.load(reader);
                }
                Properties kept = new Properties();
                for (String key : new String[]{"name", "description"}) {
                    if (all.getProperty(key) != null) {
                        kept.setProperty(key, all.getProperty(key));
                    }
                }
                if (!kept.isEmpty()) {
                    try (Writer writer = Files.newBufferedWriter(piece.resolve(SongLibrary.FILE_NAME),
                            StandardCharsets.UTF_8)) {
                        kept.store(writer, "Audio Studio piece");
                    }
                }
            }
            Path template = version.resolve(ScoreTemplates.FOLDER);
            if (Files.isDirectory(template)) {
                Path target = Files.createDirectories(piece.resolve(ScoreTemplates.FOLDER));
                try (DirectoryStream<Path> files = Files.newDirectoryStream(template)) {
                    for (Path file : files) {
                        if (Files.isRegularFile(file)) {
                            Files.copy(file, target.resolve(file.getFileName().toString()),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException unimportant) {
            // The version is safe; the new piece then begins without its name or template.
        }
    }

    private static String freshId(Path root, LocalDateTime now) {
        String stamp = now.format(ID);
        String id = stamp;
        for (int number = 2; Files.exists(root.resolve(FOLDER).resolve(id)); number++) {
            id = stamp + "-" + number;
        }
        return id;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }

    private static String said(Exception failed) {
        return failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
    }
}
