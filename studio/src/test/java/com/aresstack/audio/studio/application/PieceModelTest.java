package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/** The link between the window and the model of the open piece: it follows, and it never stops the studio. */
public final class PieceModelTest {
    private static final List<String> PIECE = Arrays.asList("tempo\t108",
            "track\tmelody\t10\t0\tmelody", "note\tmelody\t0\t480\t72\t80",
            "track\tbass\t32\t1\tbass", "note\tbass\t0\t960\t48\t70");

    @Test
    public void anOldPieceIsTakenIntoTheModelWhenOpenedAndItsFilesStay() throws Exception {
        Path folder = Files.createTempDirectory("piece-model-");
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(folder.resolve("arrangement.tsv"));
        List<String> said = new ArrayList<String>();
        PieceModel model = new PieceModel(said::add);

        model.open(folder);

        assertTrue(model.session().isPresent());
        assertEquals(2, model.session().get().composition().tracks().size());
        assertTrue(Files.isRegularFile(folder.resolve("composition.json")));
        assertEquals(new String(before, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(folder.resolve("arrangement.tsv")), StandardCharsets.UTF_8));
        assertEquals(new String(before, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(folder.resolve("legacy").resolve("arrangement.tsv")), StandardCharsets.UTF_8));
        assertEquals(said.toString(), 1, said.size());
        assertFalse(said.get(0).startsWith("WARN"));

        said.clear();
        model.open(folder); // a piece that has its model is opened without a word
        assertTrue(said.toString(), said.isEmpty());
    }

    @Test
    public void aFolderWithoutAPieceStaysEmptyAndGetsItsModelOnceComposed() throws Exception {
        Path folder = Files.createTempDirectory("piece-model-");
        List<String> said = new ArrayList<String>();
        PieceModel model = new PieceModel(said::add);

        model.open(folder);
        assertFalse(model.session().isPresent());
        try (Stream<Path> files = Files.list(folder)) {
            assertEquals(0, files.count());
        }
        assertTrue(said.isEmpty());

        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8); // the composer wrote it
        model.synchronize();
        assertTrue(model.session().isPresent());
    }

    @Test
    public void aRenamedTrackIsFollowed() throws Exception {
        Path folder = Files.createTempDirectory("piece-model-");
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        int notes = model.session().get().composition().notesOf("melody").size();

        StageMixService.rename(folder, "melody", "flute");
        model.synchronize();

        // Until the edit core, a track is known by the name the old files give it: the model follows the files.
        assertEquals("flute", model.session().get().composition().tracks().get(0).id());
        assertEquals(notes, model.session().get().composition().notesOf("flute").size());
        assertTrue(model.session().get().composition().notesOf("melody").isEmpty());
        assertEquals("bass", model.session().get().composition().tracks().get(1).id());
    }

    @Test
    public void whatCannotBeReadIsAWarningAndNothingElse() throws Exception {
        Path folder = Files.createTempDirectory("piece-model-");
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        Files.write(folder.resolve("composition.json"), "{ not json".getBytes(StandardCharsets.UTF_8));
        List<String> said = new ArrayList<String>();
        PieceModel model = new PieceModel(said::add);

        model.open(folder);

        assertFalse(model.session().isPresent());
        assertEquals(said.toString(), 1, said.size());
        assertTrue(said.get(0).startsWith("WARN: "));
        model.synchronize(); // and it stays a warning, not an exception
    }

    @Test
    public void noFolderIsNoPiece() {
        PieceModel model = new PieceModel(line -> { throw new AssertionError(line); });
        model.open(null);
        model.synchronize();
        assertFalse(model.session().isPresent());
    }
}
