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
import org.junit.Test;

/** Composing writes several files the old way; the model takes them over once, after the last. */
public final class ComposingIntoPieceTest {
    private static final List<String> PIECE = Arrays.asList("tempo\t108",
            "track\tmelody\t10\t0\tmelody", "note\tmelody\t0\t480\t72\t80");

    @Test
    public void theFirstCompositionIsWholeInTheModelAndOpensAgainWithoutAWord() throws Exception {
        Path root = Files.createTempDirectory("composing-");
        SongLibrary songs = new SongLibrary(root);
        Path folder = songs.create("Morgenlied");
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        assertFalse("nothing composed yet", model.session().isPresent());

        ComposingIntoPiece.run(() -> composer(folder), folder, "Ein helles Morgenlied", songs, model);

        assertEquals("Ein helles Morgenlied", songs.plannedFrom(folder));
        assertEquals("the words the plan follows are part of the model",
                "Ein helles Morgenlied", model.session().get().composition().meta().plannedFrom());
        assertFalse("the model has taken over everything composing wrote",
                model.session().get().externalChanges());

        // The piece is closed and opened again: nothing was changed from outside, so nothing is said.
        List<String> said = new ArrayList<String>();
        PieceModel reopened = new PieceModel(said::add);
        reopened.open(folder);
        assertTrue(said.toString(), said.isEmpty());
        assertFalse(reopened.session().get().externalChanges());
        assertEquals("Ein helles Morgenlied", reopened.session().get().composition().meta().plannedFrom());
    }

    @Test
    public void aLaterCompositionKeepsTheWordsThePlanFollows() throws Exception {
        Path root = Files.createTempDirectory("composing-");
        SongLibrary songs = new SongLibrary(root);
        Path folder = songs.create("Morgenlied");
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        ComposingIntoPiece.run(() -> composer(folder), folder, "Ein helles Morgenlied", songs, model);

        ComposingIntoPiece.run(() -> composer(folder), folder, "Etwas ganz anderes", songs, model);

        assertEquals("the plan is the old one, and so are its words", "Ein helles Morgenlied",
                model.session().get().composition().meta().plannedFrom());
        assertFalse(model.session().get().externalChanges());
    }

    @Test
    public void aComposerThatFailsLeavesTheModelAlone() throws Exception {
        Path root = Files.createTempDirectory("composing-");
        SongLibrary songs = new SongLibrary(root);
        Path folder = songs.create("Morgenlied");
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        ComposingIntoPiece.run(() -> composer(folder), folder, "Ein helles Morgenlied", songs, model);
        byte[] saved = Files.readAllBytes(folder.resolve("composition.json"));

        try {
            ComposingIntoPiece.run(() -> {
                Files.write(folder.resolve("arrangement.tsv"), Arrays.asList("tempo\t60"), StandardCharsets.UTF_8);
                throw new java.io.IOException("the AI did not answer");
            }, folder, "Ein helles Morgenlied", songs, model);
            org.junit.Assert.fail();
        } catch (java.io.IOException expected) {
            assertEquals("the AI did not answer", expected.getMessage());
        }

        assertEquals(new String(saved, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(folder.resolve("composition.json")), StandardCharsets.UTF_8));
    }

    /** What the composer leaves in the folder: its plan and the arrangement. */
    private static Path composer(Path folder) throws Exception {
        Files.write(folder.resolve("composition-plan.json"), "{\"title\":\"Morgenlied\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        return folder.resolve("arrangement.tsv");
    }
}
