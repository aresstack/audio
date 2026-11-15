package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.project.ProjectFolder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A whole project in one ZIP file: its pieces with their revisions, its albums, what the studio
 * keeps about it, a copy of the studio's settings without secrets, and if wanted the rendered
 * sound. An imported project always becomes a new project beside the others; nothing that is
 * there already is overwritten.
 */
public final class ProjectArchive {
    /** The entry that says what the file is: format, name of the project, when it was written. */
    public static final String MANIFEST = "audio-studio-projekt.properties";
    /** Where the files of the project lie inside the archive. */
    public static final String CONTENT = "projekt/";
    /** The copy of the studio's settings inside the archive. */
    public static final String SETTINGS = "einstellungen/studio.properties";
    /** Where an imported project keeps the settings it came with; they are not applied. */
    public static final String KEPT_SETTINGS = ".studio-einstellungen.properties";
    /** The file name ending of a project archive. */
    public static final String ENDING = ".zip";
    public static final int FORMAT = 1;
    /** Pieces put away are not part of the project any more. */
    public static final String TRASH = ".papierkorb";

    /** Settings whose key names a secret are never written into an archive. */
    private static final Pattern SECRET = Pattern.compile("(?i).*(key|passw|token|secret|credential|keepass).*");
    /** Files half written by the studio. */
    private static final Pattern PARTIAL = Pattern.compile("(?i).*\\.(part|tmp)(\\..*)?$");
    private static final Set<String> SOUND = Set.of("wav", "mp3", "ogg", "flac", "aif", "aiff");

    private ProjectArchive() {
    }

    /** What an export wrote. */
    public record Written(Path file, int files, long bytes, int skippedRenders) {
    }

    /**
     * Whether a file of a project (relative to its folder) is rendered sound that the studio can
     * make again: everything below {@code render/} or {@code preview/}, and every sound file
     * except the ones placed on audio lanes ({@code assets/}), which are the user's own.
     */
    public static boolean isRender(Path relative) {
        boolean asset = false;
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(ProjectFolder.RENDER) || name.equals(ProjectFolder.PREVIEW)) {
                return true;
            }
            if (name.equals(AudioAssets.FOLDER)) {
                asset = true;
            }
        }
        String file = relative.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String ending = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
        return !asset && SOUND.contains(ending);
    }

    /** Whether a file of a project is left out of every archive: put away, or half written. */
    static boolean leftOut(Path relative) {
        for (Path part : relative) {
            if (part.toString().equals(TRASH)) {
                return true;
            }
        }
        return PARTIAL.matcher(relative.getFileName().toString()).matches();
    }

    /** The settings as they go into an archive: every key that names a secret taken out. */
    public static Properties withoutSecrets(Properties settings) {
        Properties kept = new Properties();
        if (settings != null) {
            for (String key : settings.stringPropertyNames()) {
                if (!SECRET.matcher(key).matches()) {
                    kept.setProperty(key, settings.getProperty(key));
                }
            }
        }
        return kept;
    }

    /**
     * Writes a project into a ZIP file. The file appears only when it is complete.
     *
     * @param root     the project's folder
     * @param name     what the project is called
     * @param settings the studio's settings, or {@code null}; secrets are left out
     * @param target   the ZIP file to write
     * @param renders  whether rendered sound goes along
     */
    public static Written export(Path root, String name, Properties settings, Path target, boolean renders)
            throws IOException {
        Path folder = root.toAbsolutePath().normalize();
        Path file = target.toAbsolutePath().normalize();
        List<Path> files = new ArrayList<Path>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> walk = Files.walk(folder)) {
                walk.filter(Files::isRegularFile).filter(each -> !each.equals(file)).sorted().forEach(files::add);
            }
        }
        Files.createDirectories(file.getParent());
        Path partial = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".part");
        int count = 0;
        long bytes = 0;
        int skipped = 0;
        try {
            try (OutputStream out = Files.newOutputStream(partial); ZipOutputStream zip = new ZipOutputStream(out,
                    StandardCharsets.UTF_8)) {
                Properties manifest = new Properties();
                manifest.setProperty("format", Integer.toString(FORMAT));
                manifest.setProperty("name", name == null || name.isBlank() ? Projects.NEW_NAME : name.trim());
                manifest.setProperty("exported", Instant.now().toString());
                manifest.setProperty("renders", Boolean.toString(renders));
                properties(zip, MANIFEST, manifest, "Audio Studio project archive");
                if (settings != null) {
                    properties(zip, SETTINGS, withoutSecrets(settings),
                            "Audio Studio settings at export, without secrets");
                }
                for (Path each : files) {
                    Path relative = folder.relativize(each);
                    if (leftOut(relative)) {
                        continue;
                    }
                    if (!renders && isRender(relative)) {
                        skipped++;
                        continue;
                    }
                    ZipEntry entry = new ZipEntry(CONTENT + relative.toString().replace('\\', '/'));
                    entry.setTime(Files.getLastModifiedTime(each).toMillis());
                    zip.putNextEntry(entry);
                    bytes += Files.copy(each, zip);
                    zip.closeEntry();
                    count++;
                }
            }
            try {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notHere) {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
        return new Written(file, count, bytes, skipped);
    }

    /** What an import made: the new project's folder and what it is called. */
    public record Imported(Path root, String name, int files, boolean renamed) {
    }

    /**
     * Reads a project archive into a new project. If a project of that name exists already, the
     * new one gets a number behind its name. Entries that would land outside the new folder are
     * refused; if anything fails, the half made project is removed again.
     */
    public static Imported importInto(Projects projects, Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry described = zip.getEntry(MANIFEST);
            if (described == null) {
                throw new IOException("Das ist keine Projektdatei des Audio Studio: " + archive.getFileName());
            }
            Properties manifest = new Properties();
            try (Reader reader = new java.io.InputStreamReader(zip.getInputStream(described), StandardCharsets.UTF_8)) {
                manifest.load(reader);
            }
            int format = parse(manifest.getProperty("format", "0"));
            if (format > FORMAT) {
                throw new IOException("Die Projektdatei ist von einer neueren Version des Audio Studio (Format "
                        + format + ").");
            }
            String wanted = manifest.getProperty("name", Projects.NEW_NAME).trim();
            if (wanted.isEmpty()) {
                wanted = Projects.NEW_NAME;
            }
            Path root = projects.create(wanted);
            String name = projects.name(root);
            Path folder = root.toAbsolutePath().normalize();
            int count = 0;
            try {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String path = entry.getName();
                    Path target;
                    if (path.equals(SETTINGS)) {
                        target = folder.resolve(KEPT_SETTINGS);
                    } else if (path.startsWith(CONTENT) && path.length() > CONTENT.length()) {
                        target = folder.resolve(path.substring(CONTENT.length())).normalize();
                        if (!target.startsWith(folder) || target.equals(folder)) {
                            throw new IOException("Ungültiger Eintrag in der Projektdatei: " + path);
                        }
                    } else {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (entry.getTime() > 0) {
                        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(entry.getTime()));
                    }
                    count++;
                }
                // the archive brought the old project's file along: the name here is the new one
                projects.rename(root, name);
            } catch (IOException | RuntimeException failed) {
                remove(root);
                throw failed;
            }
            return new Imported(root, name, count, !name.equalsIgnoreCase(wanted));
        }
    }

    private static int parse(String number) {
        try {
            return Integer.parseInt(number.trim());
        } catch (NumberFormatException notANumber) {
            return Integer.MAX_VALUE;
        }
    }

    private static void properties(ZipOutputStream zip, String name, Properties properties, String comment)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Writer writer = new java.io.OutputStreamWriter(zip, StandardCharsets.UTF_8);
        properties.store(writer, comment);
        writer.flush();
        zip.closeEntry();
    }

    private static void remove(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failed) throws IOException {
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // what is left is a folder the user can delete
        }
    }
}
