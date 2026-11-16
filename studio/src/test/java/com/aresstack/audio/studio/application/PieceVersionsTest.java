package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A piece that is made anew is never written over: all it is becomes an earlier version, and a
 * version that is brought back is the piece again with everything it had (the user nearly lost a
 * favourite piece to an album production that wrote into it).
 */
public final class PieceVersionsTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 11, 16, 12, 0, 0);

    private SongLibrary songs;
    private Path piece;

    @Before
    public void aPieceWithNotes() throws IOException {
        songs = new SongLibrary(temporary.newFolder("compositions").toPath());
        piece = songs.create("Pastel Bloom City");
        songs.save(piece, null, "a cozy city tune");
        songs.planned(piece, "a cozy city tune");
        write(piece.resolve("arrangement.tsv"), "first notes");
        write(piece.resolve("tracks.json"), "first tracks");
        write(piece.resolve("composition.json"), "first model");
        write(piece.resolve("revisions").resolve("1.json"), "first revision");
        write(piece.resolve("render").resolve("stems").resolve("lead.wav"), "first sound");
        write(piece.resolve(StageMixService.MIX_FILE), "first mix");
        write(piece.resolve(ScoreTemplates.FOLDER).resolve("given.mid"), "given notes");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static TreeSet<String> names(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(entry -> entry.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    public void aPieceSetAsideIsWholeInItsVersionAndItsFolderIsFreeForANewOne() throws Exception {
        assertTrue(PieceVersions.hasNotes(piece));
        assertTrue(PieceVersions.list(piece).isEmpty());

        PieceVersions.Version kept = PieceVersions.setAside(piece, NOON);

        assertEquals("20261116-120000", kept.id());
        assertEquals("16.11.2026 12:00", kept.label());
        assertEquals("everything the piece was lies in the version",
                new TreeSet<String>(List.of("arrangement.tsv", "tracks.json", "composition.json", "revisions", "render",
                        StageMixService.MIX_FILE, ScoreTemplates.FOLDER, SongLibrary.FILE_NAME)), names(kept.folder()));
        assertEquals("first notes", read(kept.folder().resolve("arrangement.tsv")));
        assertEquals("first sound", read(kept.folder().resolve("render").resolve("stems").resolve("lead.wav")));
        assertEquals(kept.folder().resolve(StageMixService.MIX_FILE), kept.mix());
        assertEquals("the folder keeps its name, its words, the notes it follows and its versions; nothing else",
                new TreeSet<String>(List.of(PieceVersions.FOLDER, ScoreTemplates.FOLDER, SongLibrary.FILE_NAME)),
                names(piece));
        assertFalse(PieceVersions.hasNotes(piece));
        assertEquals("Pastel Bloom City", songs.name(piece));
        assertEquals("a cozy city tune", songs.description(piece));
        assertNull("the new piece is planned from its own words", songs.plannedFrom(piece));
        assertEquals("given notes", read(piece.resolve(ScoreTemplates.FOLDER).resolve("given.mid")));
        assertEquals(List.of(kept), PieceVersions.list(piece));
        assertEquals("nothing is left beside the piece", List.of(piece.getFileName().toString()),
                names(piece.getParent()).stream().toList());
    }

    @Test
    public void aVersionBroughtBackIsThePieceAgainAndThePieceUntilThenBecomesAVersion() throws Exception {
        PieceVersions.Version first = PieceVersions.setAside(piece, NOON);
        write(piece.resolve("arrangement.tsv"), "second notes");
        write(piece.resolve("tracks.json"), "second tracks");
        songs.save(piece, null, "made anew by the album");

        PieceVersions.Version second = PieceVersions.restore(piece, first.id(), NOON.plusHours(1));

        assertNotNull(second);
        assertEquals("first notes", read(piece.resolve("arrangement.tsv")));
        assertEquals("first tracks", read(piece.resolve("tracks.json")));
        assertEquals("first model", read(piece.resolve("composition.json")));
        assertEquals("first revision", read(piece.resolve("revisions").resolve("1.json")));
        assertEquals("first mix", read(piece.resolve(StageMixService.MIX_FILE)));
        assertEquals("a cozy city tune", songs.description(piece));
        assertEquals("a cozy city tune", songs.plannedFrom(piece));
        assertEquals("only the piece made since is a version now", List.of(second), PieceVersions.list(piece));
        assertEquals("second notes", read(second.folder().resolve("arrangement.tsv")));
        assertEquals("made anew by the album", new SongLibrary(piece).description(second.folder()));

        // And back again: choosing never loses anything.
        PieceVersions.Version third = PieceVersions.restore(piece, second.id(), NOON.plusHours(2));
        assertEquals("second notes", read(piece.resolve("arrangement.tsv")));
        assertEquals("first notes", read(third.folder().resolve("arrangement.tsv")));
    }

    @Test
    public void aVersionComesBackIntoAPieceWithoutNotesAndVersionsOfOneSecondStayApart() throws Exception {
        PieceVersions.Version first = PieceVersions.setAside(piece, NOON);
        // The new piece failed before it had a note: nothing of it is worth a version.
        assertNull(PieceVersions.restore(piece, first.id(), NOON));
        assertEquals("first notes", read(piece.resolve("arrangement.tsv")));
        assertTrue(PieceVersions.list(piece).isEmpty());

        PieceVersions.Version again = PieceVersions.setAside(piece, NOON);
        write(piece.resolve("arrangement.tsv"), "second notes");
        PieceVersions.Version sameSecond = PieceVersions.setAside(piece, NOON);
        assertEquals("20261116-120000", again.id());
        assertEquals("20261116-120000-2", sameSecond.id());
        assertEquals("16.11.2026 12:00 (2)", sameSecond.label());
        assertEquals("the newest first", List.of(sameSecond.id(), again.id()),
                PieceVersions.list(piece).stream().map(PieceVersions.Version::id).toList());
    }

    @Test(expected = IOException.class)
    public void aVersionThatIsNotThereIsRefusedBeforeAnythingMoves() throws Exception {
        try {
            PieceVersions.restore(piece, "../other", NOON);
        } finally {
            assertEquals("first notes", read(piece.resolve("arrangement.tsv")));
            assertTrue(PieceVersions.list(piece).isEmpty());
        }
    }
}
