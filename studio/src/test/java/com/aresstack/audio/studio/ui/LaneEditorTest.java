package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.studio.application.PieceEditing;
import com.aresstack.audio.studio.application.ProjectSession;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The note editor in the lanes (B1), driven by mouse events as the user would: a drag is one
 * patch, a double-click inserts one note, Delete and undo work on the selection.
 */
public final class LaneEditorTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private ProjectSession session;
    private ArrangementView view;
    private final List<String> said = new ArrayList<String>();
    private int seeks;

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
            public void edited(String what, boolean changed) {
                said.add(what);
                if (changed) {
                    show();
                }
            }
        });
        view.setEditing(new PieceEditing(() -> Optional.of(session)));
        view.setSize(1600, ArrangementView.RULER + 4 * 120 + ArrangementView.MIX);
        view.setLaneHeight(120);
        show();
    }

    private void show() {
        List<String> order = new ArrayList<String>();
        for (com.aresstack.audio.composition.Track track : session.composition().tracks()) {
            order.add(track.id());
        }
        view.setArrangement(ArrangementView.Arrangement.of(session.composition()), order, "");
    }

    private MidiClip melody() {
        return (MidiClip) session.composition().track("melody").clips().get(0);
    }

    private int lane(String id) {
        return view.order().indexOf(id);
    }

    private RoundRectangle2D.Float boundsOf(Note note) {
        return view.noteBounds(lane("melody"), note.tick(), note.length(), note.pitch());
    }

    private void mouse(int id, int x, int y, int modifiers, int clicks) {
        view.dispatchEvent(new MouseEvent(view, id, System.currentTimeMillis(), modifiers | InputEvent.BUTTON1_DOWN_MASK,
                x, y, clicks, false, MouseEvent.BUTTON1));
    }

    private void drag(int fromX, int fromY, int toX, int toY) {
        mouse(MouseEvent.MOUSE_PRESSED, fromX, fromY, 0, 1);
        mouse(MouseEvent.MOUSE_DRAGGED, (fromX + toX) / 2, (fromY + toY) / 2, 0, 1);
        mouse(MouseEvent.MOUSE_DRAGGED, toX, toY, 0, 1);
        mouse(MouseEvent.MOUSE_RELEASED, toX, toY, 0, 1);
    }

    private void key(int code, int modifiers) {
        Object name = view.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(code, modifiers));
        view.getActionMap().get(name).actionPerformed(new java.awt.event.ActionEvent(view, 0, ""));
    }

    @Test
    public void draggingANoteMovesItOnTheGridAsOneUndoableStep() throws Exception {
        Note first = melody().notes().get(2); // tick 480, pitch 67
        RoundRectangle2D.Float bounds = boundsOf(first);
        int x = (int) (bounds.x + 3);
        int y = (int) (bounds.y + bounds.height / 2);
        double pixelsPerTick = 1 / view.ticksPerPixel();
        float semitone = view.semitone(lane("melody"));
        Composition before = session.composition();
        drag(x, y, x + (int) Math.round(250 * pixelsPerTick), y - Math.round(2 * semitone));
        Note moved = melody().notes().get(2);
        assertEquals("a move lands on the 1/16 grid", 480 + 240, moved.tick());
        assertEquals(69, moved.pitch());
        assertTrue(session.canUndo());
        assertEquals(1, said.size());
        session.undo();
        assertEquals(before, session.composition());
        assertEquals("a click into a note does not move the playback", 0, seeks);
    }

    @Test
    public void draggingTheEndOfANoteChangesItsLength() {
        Note note = melody().notes().get(4); // tick 960, length 480
        RoundRectangle2D.Float bounds = boundsOf(note);
        int x = (int) (bounds.x + bounds.width - 2);
        int y = (int) (bounds.y + bounds.height / 2);
        drag(x, y, x + (int) Math.round(480 / view.ticksPerPixel()), y);
        assertEquals(960, melody().notes().get(4).length());
    }

    @Test
    public void aDoubleClickInsertsAndDeleteTakesItAway() {
        int before = melody().notes().size();
        int index = lane("melody");
        float x = view.xOf(3 * 1920 + 7 * 120);
        int y = Math.round(view.yOf(index, 64) + view.noteThickness(index) / 2);
        mouse(MouseEvent.MOUSE_PRESSED, (int) x + 2, y, 0, 2);
        mouse(MouseEvent.MOUSE_RELEASED, (int) x + 2, y, 0, 2);
        assertEquals(before + 1, melody().notes().size());
        Note added = null;
        for (Note note : melody().notes()) {
            if (note.tick() == 3 * 1920 + 7 * 120 && note.length() == 120) {
                added = note;
            }
        }
        assertTrue("inserted on the grid, one grid step long", added != null);
        assertEquals(64, added.pitch());
        key(KeyEvent.VK_DELETE, 0);
        assertEquals(before, melody().notes().size());
    }

    @Test
    public void aFrameOverEmptyLaneSelectsAndArrowsMoveTheSelection() {
        int index = lane("melody");
        int top = view.laneTop(index);
        drag((int) view.xOf(0) + 1, top + 1, (int) view.xOf(1920) - 2, top + view.laneHeight() - 2);
        Note first = melody().notes().get(0);
        key(KeyEvent.VK_RIGHT, 0);
        assertEquals(first.tick() + 120, melody().notes().get(0).tick());
        key(KeyEvent.VK_UP, InputEvent.SHIFT_DOWN_MASK);
        assertEquals(first.pitch() + 12, melody().notes().get(0).pitch());
        assertEquals("notes outside the frame stay", 1920, melody().notes().get(6).tick());
        assertTrue("an empty press moves the playback as before", seeks > 0);
    }

    @Test
    public void theLanesPaintWithSelectionAndFrame() {
        Note first = melody().notes().get(0);
        RoundRectangle2D.Float bounds = boundsOf(first);
        mouse(MouseEvent.MOUSE_PRESSED, (int) bounds.x + 2, (int) (bounds.y + 2), 0, 1);
        BufferedImage image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
        view.paint(image.getGraphics());
        int rgb = image.getRGB((int) bounds.x + 4, (int) (bounds.y + bounds.height / 2));
        java.awt.Color drawn = new java.awt.Color(rgb);
        assertTrue("a selected note is drawn white: " + drawn,
                drawn.getRed() > 235 && drawn.getGreen() > 235 && drawn.getBlue() > 235);
        mouse(MouseEvent.MOUSE_RELEASED, (int) bounds.x + 2, (int) (bounds.y + 2), 0, 1);
    }
}
