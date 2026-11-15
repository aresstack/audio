package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.studio.application.PieceEditing;
import com.aresstack.audio.studio.application.ProjectSession;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.UIManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Marking a passage (C2) beside the note editor (B1), driven by mouse events (acceptance finding
 * of the PO): Shift in the lanes of a piece that can be edited is the editor's and adds to its
 * selection; a passage is marked with Shift and drag in the ruler. Neither does the other's work.
 */
public final class PassageMarkBesideNoteEditorTest {
    private static final int LANE = 120;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private ProjectSession session;
    private ArrangementView view;
    private final List<ArrangementView.Range> marks = new ArrayList<ArrangementView.Range>();
    private int seeks;
    private int edits;

    @Before
    public void aPieceInTheLanes() throws Exception {
        UIManager.put("defaultFont", new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
        Path folder = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        session = ProjectSession.open(folder).orElseThrow();
        view = new ArrangementView(new ArrangementView.Listener() {
            @Override
            public void seek(double second) {
                seeks++;
            }

            @Override
            public void marked(ArrangementView.Range range) {
                marks.add(range);
            }

            @Override
            public void edited(String what, boolean changed) {
                edits += changed ? 1 : 0;
            }
        });
        view.setSize(1600, ArrangementView.RULER + 4 * LANE + ArrangementView.MIX);
        view.setLaneHeight(LANE);
    }

    private List<String> order() {
        List<String> order = new ArrayList<String>();
        for (com.aresstack.audio.composition.Track track : session.composition().tracks()) {
            order.add(track.id());
        }
        return order;
    }

    private void editable() {
        view.setEditing(new PieceEditing(() -> Optional.of(session)));
        view.setArrangement(ArrangementView.Arrangement.of(session.composition()), order(), "");
    }

    private void mouse(int id, int x, int y, int modifiers) {
        view.dispatchEvent(new MouseEvent(view, id, System.currentTimeMillis(), modifiers | InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON1));
    }

    private void drag(int fromX, int fromY, int toX, int toY, int modifiers) {
        mouse(MouseEvent.MOUSE_PRESSED, fromX, fromY, modifiers);
        mouse(MouseEvent.MOUSE_DRAGGED, (fromX + toX) / 2, (fromY + toY) / 2, modifiers);
        mouse(MouseEvent.MOUSE_DRAGGED, toX, toY, modifiers);
        mouse(MouseEvent.MOUSE_RELEASED, toX, toY, modifiers);
    }

    /** A place at the top of a lane below its clip band (B2): a press in the band grabs the clip instead. */
    private int belowBand(int index) {
        return laneTop(index) + ArrangementView.CLIP_BAND + 4;
    }

    private int laneTop(int index) {
        return ArrangementView.RULER + index * LANE;
    }

    @Test
    public void shiftAndDragInEditableLanesSelectsNotesAndMarksNoPassage() {
        editable();
        Composition before = session.composition();
        // A frame over the first lane without Shift selects the notes in it ...
        drag(2, belowBand(0), 400, laneTop(0) + LANE - 2, 0);
        int chosen = view.editor().selection().size();
        assertTrue("the frame of the editor selects notes", chosen > 0);
        // ... and one with Shift over the second lane adds to them: the editor's multiple selection.
        drag(2, belowBand(1), 400, laneTop(1) + LANE - 2, InputEvent.SHIFT_DOWN_MASK);
        assertTrue("Shift adds to the selection of the editor", view.editor().selection().size() > chosen);
        assertNull("and marks no passage", view.marked());
        assertTrue(marks.isEmpty());
        assertEquals("selecting changes nothing in the piece", before, session.composition());
        assertEquals(0, edits);
        assertFalse(view.marksAt(laneTop(1) + 5));
        assertTrue(view.marksAt(ArrangementView.RULER - 5));
    }

    @Test
    public void shiftAndDragInTheRulerMarksBarsAcrossAllTracksAndLeavesTheSelectionAlone() {
        editable();
        drag(2, belowBand(0), 400, laneTop(0) + LANE - 2, 0);
        List<?> chosen = new ArrayList<Object>(view.editor().selection());
        assertFalse(chosen.isEmpty());
        int bars = view.bars();
        int seeksBefore = seeks; // a press into an empty lane moves the playback, as the editor has it
        float third = view.xOfBar(2) + 3;
        float fourth = view.xOfBar(3) + 3;
        // The drag ends below the ruler, in a lane: the mark began in the ruler and stays a mark.
        drag((int) third, 10, (int) fourth, laneTop(2) + 30, InputEvent.SHIFT_DOWN_MASK);
        ArrangementView.Range marked = view.marked();
        assertNotNull("Shift and drag in the ruler marks a passage", marked);
        assertEquals(3, marked.firstBar());
        assertEquals(4, marked.lastBar());
        assertEquals("from the ruler the mark covers every track", order(), marked.trackIds());
        assertEquals("the selection of the editor is as it was", chosen, view.editor().selection());
        assertEquals("marking does not move the playback", seeksBefore, seeks);
        assertEquals(0, edits);
        assertTrue(bars >= 4);
        // A click into a lane works on notes and leaves the mark; a click into the ruler takes it away.
        mouse(MouseEvent.MOUSE_PRESSED, 900, laneTop(3) + 60, 0);
        mouse(MouseEvent.MOUSE_RELEASED, 900, laneTop(3) + 60, 0);
        assertEquals(marked, view.marked());
        mouse(MouseEvent.MOUSE_PRESSED, 900, 10, 0);
        assertNull(view.marked());
    }

    @Test
    public void whereNotesCannotBeEditedShiftAndDragInTheLanesStillMarksTracksAndBars() {
        // The lanes of the arrangement file: notes without ids, no editor at work.
        view.setEditing(new PieceEditing(() -> Optional.of(session)));
        view.setArrangement(ArrangementView.Arrangement.read(session.folder().resolve("arrangement.tsv")), order(), "");
        assertTrue(view.marksAt(laneTop(1) + 5));
        drag((int) view.xOfBar(0) + 3, laneTop(1) + 5, (int) view.xOfBar(1) + 3, laneTop(2) + 5,
                InputEvent.SHIFT_DOWN_MASK);
        ArrangementView.Range marked = view.marked();
        assertNotNull(marked);
        assertEquals(order().subList(1, 3), marked.trackIds());
        assertEquals(1, marked.firstBar());
        assertEquals(2, marked.lastBar());
        assertTrue(view.editor().selection().isEmpty());
    }
}
