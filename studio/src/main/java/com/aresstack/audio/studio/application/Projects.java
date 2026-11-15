package com.aresstack.audio.studio.application;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The projects of the studio. A project is a library of pieces with its albums: one folder. The
 * first project is the library the studio always had ("Mein Projekt"); every further one is a
 * folder in {@code projects}. Which project is open is kept beside them, so that the studio opens
 * it again at the next start.
 */
public final class Projects {
    /** The file in a project's folder that keeps its name and the piece last open in it. */
    public static final String FILE_NAME = ".project.properties";
    public static final String STANDARD_NAME = "Mein Projekt";

    private final Path home;

    /** @param home the folder of the studio's own files, with {@code compositions} and {@code projects} in it */
    public Projects(Path home) {
        this.home = home;
    }

    public static Projects standard() {
        return new Projects(Paths.get(System.getProperty("user.home"), ".aresstack", "audio"));
    }

    /** A project: its folder and what it is called. */
    public record Project(Path root, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** The library the studio always had: the first project. */
    public Path standardRoot() {
        return home.resolve("compositions");
    }

    private Path folder() {
        return home.resolve("projects");
    }

    /** All projects, the first one first, then the others by name. */
    public List<Project> all() {
        List<Project> found = new ArrayList<Project>();
        found.add(new Project(standardRoot(), name(standardRoot())));
        List<Project> others = new ArrayList<Project>();
        if (Files.isDirectory(folder())) {
            try (DirectoryStream<Path> folders = Files.newDirectoryStream(folder())) {
                for (Path each : folders) {
                    if (Files.isDirectory(each) && !each.getFileName().toString().startsWith(".")) {
                        others.add(new Project(each, name(each)));
                    }
                }
            } catch (IOException unreadable) {
                // then only the first project is offered
            }
        }
        others.sort((first, second) -> first.name().compareToIgnoreCase(second.name()));
        found.addAll(others);
        return found;
    }

    /** The project that is open: the one chosen last, the first one if that is gone. */
    public Path current() {
        String chosen = read(home.resolve("project.properties")).getProperty("current", "");
        if (!chosen.isEmpty()) {
            Path root = Paths.get(chosen);
            if (Files.isDirectory(root)) {
                return root;
            }
        }
        return standardRoot();
    }

    /** Makes this project the open one, from the next start of the window on. */
    public void choose(Path root) throws IOException {
        Properties properties = read(home.resolve("project.properties"));
        properties.setProperty("current", root.toAbsolutePath().normalize().toString());
        write(home.resolve("project.properties"), properties);
    }

    /** What a new project is called when it is given no name. */
    public static final String NEW_NAME = "Neues Projekt";

    /**
     * Makes a new, empty project and returns its folder. If another project has that name
     * already, the new one gets a number behind it, so that no two are called the same.
     */
    public Path create(String name) throws IOException {
        String clean = uniqueName(name == null || name.isBlank() ? NEW_NAME : name.trim());
        String slug = clean.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("ß", "ss").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "projekt";
        }
        Path root = folder().resolve(slug);
        for (int number = 2; Files.exists(root); number++) {
            root = folder().resolve(slug + "-" + number);
        }
        Files.createDirectories(root);
        rename(root, clean);
        return root;
    }

    /** This name, or with " (2)", " (3)" … behind it while another project is called so already. */
    public String uniqueName(String name) {
        String wanted = name == null || name.isBlank() ? NEW_NAME : name.trim();
        java.util.Set<String> taken = new java.util.HashSet<String>();
        for (Project project : all()) {
            taken.add(project.name().toLowerCase(Locale.ROOT));
        }
        String free = wanted;
        for (int number = 2; taken.contains(free.toLowerCase(Locale.ROOT)); number++) {
            free = wanted + " (" + number + ")";
        }
        return free;
    }

    /** What a project is called: the name it was given, the first one "Mein Projekt", else its folder. */
    public String name(Path root) {
        String given = read(root.resolve(FILE_NAME)).getProperty("name", "").trim();
        if (!given.isEmpty()) {
            return given;
        }
        if (root.toAbsolutePath().normalize().equals(standardRoot().toAbsolutePath().normalize())) {
            return STANDARD_NAME;
        }
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }

    /** Gives a project another name; its folder stays. */
    public void rename(Path root, String name) throws IOException {
        if (name == null || name.isBlank()) {
            return;
        }
        Properties properties = read(root.resolve(FILE_NAME));
        properties.setProperty("name", name.trim());
        write(root.resolve(FILE_NAME), properties);
    }

    /** The piece last open in a project, or {@code null}. */
    public Path lastPiece(Path root) {
        String kept = read(root.resolve(FILE_NAME)).getProperty("lastPiece", "");
        if (kept.isEmpty()) {
            return null;
        }
        Path piece = root.resolve(kept);
        return Files.isDirectory(piece) ? piece : null;
    }

    /** Notes the piece open in its project; a folder outside the project is not noted. */
    public void lastPiece(Path root, Path piece) throws IOException {
        if (piece == null || piece.getFileName() == null
                || !root.toAbsolutePath().normalize().equals(piece.toAbsolutePath().normalize().getParent())) {
            return;
        }
        Properties properties = read(root.resolve(FILE_NAME));
        if (piece.getFileName().toString().equals(properties.getProperty("lastPiece"))) {
            return;
        }
        properties.setProperty("lastPiece", piece.getFileName().toString());
        write(root.resolve(FILE_NAME), properties);
    }

    private static Properties read(Path file) {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException unreadable) {
                // as if there were nothing kept
            }
        }
        return properties;
    }

    private static void write(Path file, Properties properties) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "Audio Studio project");
        }
    }
}
