package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A project into one ZIP and back: as a new project, never over one that is there, renders only if wanted. */
public final class ProjectArchiveTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private Path filledProject(Projects projects) throws IOException {
        Path root = projects.create("Filmmusik");
        write(root.resolve("titel/arrangement.tsv"), "tsv");
        write(root.resolve("titel/revisions/r1.json"), "rev");
        write(root.resolve("titel/render/stems/lead.wav"), "render");
        write(root.resolve("titel/arrangement-instruments.wav"), "mix");
        write(root.resolve("titel/assets/a1-whoosh.wav"), "own sound");
        write(root.resolve("albums/a/album.json"), "album");
        write(root.resolve(".papierkorb/alt/arrangement.tsv"), "away");
        write(root.resolve("titel/stems.wav.123.part.wav"), "half");
        projects.lastPiece(root, root.resolve("titel"));
        return root;
    }

    @Test
    public void rendersAreRenderedSoundButNotTheUsersOwnSounds() {
        assertTrue(ProjectArchive.isRender(Paths.get("titel/render/composition.json")));
        assertTrue(ProjectArchive.isRender(Paths.get("titel/preview/p1/lead.wav")));
        assertTrue(ProjectArchive.isRender(Paths.get("titel/arrangement-instruments.wav")));
        assertTrue(ProjectArchive.isRender(Paths.get("albums/a/export/01 - Titel.WAV")));
        assertFalse(ProjectArchive.isRender(Paths.get("titel/assets/a1-whoosh.wav")));
        assertFalse(ProjectArchive.isRender(Paths.get("titel/arrangement.tsv")));
        assertFalse(ProjectArchive.isRender(Paths.get("titel/revisions/r1.json")));
    }

    @Test
    public void secretsNeverGoIntoTheArchive() {
        Properties settings = new Properties();
        settings.setProperty("apiKey", "geheim");
        settings.setProperty("keepassPassword", "geheim");
        settings.setProperty("model", "gemma");
        Properties kept = ProjectArchive.withoutSecrets(settings);
        assertEquals("gemma", kept.getProperty("model"));
        assertEquals(1, kept.size());
    }

    @Test
    public void anExportedProjectComesBackAsANewProjectBesideTheOld() throws Exception {
        Path home = temporary.newFolder("audio").toPath();
        Projects projects = new Projects(home);
        Path root = filledProject(projects);
        Properties settings = new Properties();
        settings.setProperty("apiKey", "geheim");
        settings.setProperty("growBars", "16");

        Path zip = temporary.getRoot().toPath().resolve("export/Filmmusik.zip");
        ProjectArchive.Written written = ProjectArchive.export(root, "Filmmusik", settings, zip, false);
        assertTrue(Files.isRegularFile(zip));
        assertEquals("two renders left out", 2, written.skippedRenders());

        ProjectArchive.Imported made = ProjectArchive.importInto(projects, zip);
        assertNotEquals(root.toAbsolutePath().normalize(), made.root().toAbsolutePath().normalize());
        assertEquals("Filmmusik (2)", made.name());
        assertTrue(made.renamed());
        assertEquals("Filmmusik", projects.name(root));
        assertEquals("Filmmusik (2)", projects.name(made.root()));
        assertEquals(3, projects.all().size());

        assertEquals("tsv", read(made.root().resolve("titel/arrangement.tsv")));
        assertEquals("rev", read(made.root().resolve("titel/revisions/r1.json")));
        assertEquals("album", read(made.root().resolve("albums/a/album.json")));
        assertEquals("own sound", read(made.root().resolve("titel/assets/a1-whoosh.wav")));
        assertFalse(Files.exists(made.root().resolve("titel/render")));
        assertFalse(Files.exists(made.root().resolve("titel/arrangement-instruments.wav")));
        assertFalse(Files.exists(made.root().resolve(".papierkorb")));
        assertFalse(Files.exists(made.root().resolve("titel/stems.wav.123.part.wav")));
        assertEquals(made.root().resolve("titel"), projects.lastPiece(made.root()));

        Properties kept = new Properties();
        try (Reader reader = Files.newBufferedReader(made.root().resolve(ProjectArchive.KEPT_SETTINGS),
                StandardCharsets.UTF_8)) {
            kept.load(reader);
        }
        assertEquals("16", kept.getProperty("growBars"));
        assertFalse(kept.containsKey("apiKey"));

        ProjectArchive.export(root, "Filmmusik", null, zip, true);
        ProjectArchive.Imported full = ProjectArchive.importInto(projects, zip);
        assertEquals("Filmmusik (3)", full.name());
        assertEquals("render", read(full.root().resolve("titel/render/stems/lead.wav")));
        assertEquals("mix", read(full.root().resolve("titel/arrangement-instruments.wav")));
    }

    @Test
    public void theStandardProjectComesBackWithANumberBecauseEveryStudioHasOne() throws Exception {
        Path home = temporary.newFolder("audio").toPath();
        Projects projects = new Projects(home);
        write(projects.standardRoot().resolve("stueck/arrangement.tsv"), "tsv");
        Path zip = temporary.getRoot().toPath().resolve("mein.zip");
        ProjectArchive.export(projects.standardRoot(), projects.name(projects.standardRoot()), null, zip, false);

        Path other = temporary.newFolder("elsewhere").toPath();
        ProjectArchive.Imported made = ProjectArchive.importInto(new Projects(other), zip);
        assertEquals(Projects.STANDARD_NAME + " (2)", made.name());
        assertTrue(made.renamed());
        assertEquals("tsv", read(made.root().resolve("stueck/arrangement.tsv")));
    }

    @Test
    public void anEntryThatWouldLeaveTheProjectIsRefusedAndNothingStays() throws Exception {
        Path home = temporary.newFolder("audio").toPath();
        Projects projects = new Projects(home);
        Path zip = temporary.getRoot().toPath().resolve("boese.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream archive = new ZipOutputStream(out)) {
            archive.putNextEntry(new ZipEntry(ProjectArchive.MANIFEST));
            archive.write("format=1\nname=Boese\n".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
            archive.putNextEntry(new ZipEntry(ProjectArchive.CONTENT + "../../draussen.txt"));
            archive.write("x".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        try {
            ProjectArchive.importInto(projects, zip);
            fail("an entry outside the project must be refused");
        } catch (IOException expected) {
            // refused
        }
        assertEquals(1, projects.all().size());
        assertFalse(Files.exists(home.resolve("draussen.txt")));
    }

    @Test
    public void aZipThatIsNoProjectIsRefused() throws Exception {
        Path home = temporary.newFolder("audio").toPath();
        Projects projects = new Projects(home);
        Path zip = temporary.getRoot().toPath().resolve("fremd.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream archive = new ZipOutputStream(out)) {
            archive.putNextEntry(new ZipEntry("irgendwas.txt"));
            archive.closeEntry();
        }
        try {
            ProjectArchive.importInto(projects, zip);
            fail("a ZIP without the manifest must be refused");
        } catch (IOException expected) {
            // refused
        }
        assertEquals(1, projects.all().size());
    }

    @Test
    public void aNewProjectWithATakenNameGetsANumber() throws Exception {
        Projects projects = new Projects(temporary.newFolder("audio").toPath());
        Path first = projects.create(Projects.NEW_NAME);
        Path second = projects.create(Projects.NEW_NAME);
        assertEquals(Projects.NEW_NAME, projects.name(first));
        assertEquals(Projects.NEW_NAME + " (2)", projects.name(second));
        assertEquals(Projects.STANDARD_NAME + " (2)", projects.uniqueName(Projects.STANDARD_NAME));
    }
}
