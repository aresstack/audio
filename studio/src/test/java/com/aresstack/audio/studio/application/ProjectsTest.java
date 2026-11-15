package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Projects: the library the studio always had first, new ones beside it, the open one kept. */
public final class ProjectsTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void aNewProjectIsListedChosenRenamedAndRemembersItsPiece() throws Exception {
        Path home = temporary.newFolder("audio").toPath();
        Projects projects = new Projects(home);
        assertEquals(home.resolve("compositions"), projects.current());
        assertEquals(1, projects.all().size());
        assertEquals(Projects.STANDARD_NAME, projects.all().get(0).name());

        Path made = projects.create("Filmmusik");
        assertEquals(2, projects.all().size());
        assertEquals("Filmmusik", projects.all().get(1).name());
        projects.choose(made);
        assertEquals(made.toAbsolutePath().normalize(), new Projects(home).current());

        projects.rename(made, "Soundtrack");
        assertEquals("Soundtrack", projects.name(made));
        assertNull(projects.lastPiece(made));
        Path piece = new SongLibrary(made).create("Titel");
        projects.lastPiece(made, piece);
        assertEquals(piece, projects.lastPiece(made));
        projects.lastPiece(made, temporary.newFolder("elsewhere").toPath());
        assertEquals("a folder outside is not noted", piece, projects.lastPiece(made));

        Files.delete(made.resolve(Projects.FILE_NAME));
        assertEquals(made.getFileName().toString(), projects.name(made));
    }
}
