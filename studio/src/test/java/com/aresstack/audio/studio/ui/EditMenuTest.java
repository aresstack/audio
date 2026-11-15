package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.studio.application.PieceModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** What the edit entries of the menu and the list of revisions show. */
public final class EditMenuTest {
    @Test
    public void theEntriesNameTheChangeTheyWouldTakeBackOrDoAgain() {
        EditMenu.Labels nothing = EditMenu.labels(false, null, false, null);
        assertEquals("Rückgängig", nothing.undo());
        assertEquals("Wiederholen", nothing.redo());
        assertFalse(nothing.canUndo() || nothing.canRedo());

        EditMenu.Labels some = EditMenu.labels(true, "Spur bass neu komponiert", true, "Revision 1 wiederhergestellt");
        assertEquals("Rückgängig: Spur bass neu komponiert", some.undo());
        assertEquals("Wiederholen: Revision 1 wiederhergestellt", some.redo());
        assertTrue(some.canUndo() && some.canRedo());

        String longWords = "x".repeat(200);
        assertTrue(EditMenu.labels(true, longWords, false, null).undo().length() < 80);
        assertEquals("a description that may not be used is not shown", "Rückgängig",
                EditMenu.labels(false, "old words", false, null).undo());
    }

    @Test
    public void theListOfRevisionsShowsTheNewestFirstWithNumberAuthorAndWords() throws Exception {
        Path folder = Files.createTempDirectory("revisions-");
        Files.write(folder.resolve("arrangement.tsv"), Arrays.asList("tempo\t108",
                "track\tmelody\t10\t0\tmelody", "note\tmelody\t0\t480\t72\t80"), StandardCharsets.UTF_8);
        PieceModel model = new PieceModel(line -> { });
        model.open(folder);
        model.session().get().markRevision("Vor dem Umbau");
        List<Revision> revisions = model.session().get().revisions();
        Composition piece = model.session().get().composition();
        RevisionsDialog.Rows rows = new RevisionsDialog.Rows();

        rows.show(revisions);

        assertEquals(2, rows.getRowCount());
        assertEquals(2, rows.getValueAt(0, 0));
        assertEquals("Du", rows.getValueAt(0, 1));
        assertEquals("Vor dem Umbau", rows.getValueAt(0, 3));
        assertEquals(1, rows.getValueAt(1, 0));
        assertEquals("Original", rows.getValueAt(1, 1));
        assertEquals(piece, rows.at(1).composition());
        assertEquals(null, rows.at(5));
        assertEquals("KI", RevisionsDialog.Rows.author(Revision.Author.AI));
    }
}
