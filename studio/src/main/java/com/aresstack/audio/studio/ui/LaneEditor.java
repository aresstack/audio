package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.application.NoteEdits;
import com.aresstack.audio.studio.application.PieceEditing;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * The note editor in the lanes of the arrangement (work package B1). It only reads the mouse and
 * the keys and says which notes and by how much; what that does to the piece is
 * {@link PieceEditing}'s, which hands every action as one patch to the edit core.
 *
 * <ul>
 *   <li>Click a note: select it; with Shift or Ctrl add it or take it out. Drag over empty lane:
 *       select all notes in the frame. A click on empty lane also moves the playback and the place
 *       where pasted notes go.</li>
 *   <li>Drag a note: move the selected notes in time (on the grid; Alt for free) and pitch. Drag the
 *       right end of a note: change the length of the selected notes.</li>
 *   <li>Double-click empty lane: a new note of one grid step.</li>
 *   <li>Keys: Delete, Ctrl+C/X/V, Ctrl+A, Q (to the grid), arrows (left/right one grid step, with
 *       Shift one bar; up/down one semitone, with Shift one octave), Ctrl+Z, Ctrl+Y or Ctrl+Shift+Z,
 *       Escape.</li>
 *   <li>Right click: the same as a menu, and the choice of the grid.</li>
 *   <li>Clips (work package B2), grabbed at the band on top of a lane: click selects, drag moves in
 *       time and to another lane (Ctrl while letting go copies), double-click on an empty band
 *       starts a clip, S cuts the selected clip at the cursor, Ctrl+D duplicates it, Delete takes it
 *       away when no note is selected. The menu of a clip chooses which part plays it, or gives it
 *       a new instrument: a new player with a place of its own on the stage.</li>
 * </ul>
 */
final class LaneEditor extends MouseAdapter {
    /** A choice of grid: its name and its distance in ticks, 0 for none; a bar comes from the piece. */
    record Grid(String name, int ticks) {
        static final int BAR = -1;
    }

    static final List<Grid> GRIDS = List.of(new Grid("Takt", Grid.BAR), new Grid("1/4", 480), new Grid("1/8", 240),
            new Grid("1/16", 120), new Grid("1/32", 60), new Grid("1/8 Triole", 160), new Grid("1/16 Triole", 80),
            new Grid("Aus", 0));
    private static final int EDGE = 5;

    private enum Drag { NONE, MOVE, RESIZE, BAND, CLIP }

    private final ArrangementView view;
    private final PieceEditing editing;
    private final ArrangementView.Listener listener;
    private final Set<NoteEdits.NoteRef> selected = new LinkedHashSet<NoteEdits.NoteRef>();
    private Grid grid = GRIDS.get(3);
    private Drag drag = Drag.NONE;
    private int pressX;
    private int pressY;
    private int dragLane = -1;
    private ArrangementView.Note anchor;
    private long byTicks;
    private int bySemitones;
    private Rectangle band;
    private Set<NoteEdits.NoteRef> before = new LinkedHashSet<NoteEdits.NoteRef>();
    /** The clip selected at its band, or {@code null}. */
    private String selectedClip;
    private ArrangementView.ClipBox draggedClip;
    private int clipLane = -1;
    /** Where pasted notes go: a lane and a tick. */
    private int cursorLane = -1;
    private long cursorTick;

    LaneEditor(ArrangementView view, PieceEditing editing, ArrangementView.Listener listener) {
        this.view = view;
        this.editing = editing;
        this.listener = listener;
        view.addMouseListener(this);
        view.addMouseMotionListener(this);
        view.setFocusable(true);
        bindKeys();
    }

    /** Whether a press at this height is the editor's: in the lanes of a piece that can be edited. */
    boolean owns(int y) {
        ArrangementView.Arrangement shown = view.arrangement();
        return shown != null && shown.editable && editing.canEdit() && view.laneAt(y) >= 0;
    }

    boolean selectedClip(String clipId) {
        return clipId.equals(selectedClip) && drag != Drag.CLIP;
    }

    boolean selected(ArrangementView.Note note) {
        NoteEdits.NoteRef ref = note.ref();
        return ref != null && selected.contains(ref) && drag != Drag.MOVE && drag != Drag.RESIZE;
    }

    /** The lanes show another state of the piece: notes that are gone are no longer selected. */
    void shown(ArrangementView.Arrangement shown) {
        Set<NoteEdits.NoteRef> there = new LinkedHashSet<NoteEdits.NoteRef>();
        if (shown != null) {
            for (List<ArrangementView.Note> lane : shown.lanes.values()) {
                for (ArrangementView.Note note : lane) {
                    if (note.ref() != null) {
                        there.add(note.ref());
                    }
                }
            }
        }
        selected.retainAll(there);
        if (selectedClip != null && (shown == null || shown.clips.values().stream()
                .noneMatch(boxes -> boxes.stream().anyMatch(box -> box.id.equals(selectedClip))))) {
            selectedClip = null;
        }
    }

    /** The selected notes, in the order they were picked. */
    List<NoteEdits.NoteRef> selection() {
        return new ArrayList<NoteEdits.NoteRef>(selected);
    }

    /** The distance of the grid lines in ticks; 0 when the grid is off. */
    long gridTicks() {
        if (grid.ticks() == Grid.BAR) {
            ArrangementView.Arrangement shown = view.arrangement();
            return shown == null ? 1920 : shown.barTicks;
        }
        return grid.ticks();
    }

    void setGrid(Grid chosen) {
        grid = chosen;
        view.repaint();
    }

    Grid grid() {
        return grid;
    }

    // ---------------------------------------------------------------------------------------
    // Mouse

    @Override
    public void mousePressed(MouseEvent event) {
        if (!owns(event.getY())) {
            return;
        }
        view.requestFocusInWindow();
        int pressedLane = view.laneAt(event.getY());
        boolean inBand = event.getY() < view.laneTop(pressedLane) + view.clipBand() + 2;
        ArrangementView.ClipBox box = view.clipAt(pressedLane, view.tickAt(event.getX()));
        if (SwingUtilities.isRightMouseButton(event) || event.isPopupTrigger()) {
            if (box != null && (inBand || noteAt(event.getX(), event.getY()) == null)) {
                selectedClip = box.id;
                selected.clear();
            }
            ArrangementView.Note hit = noteAt(event.getX(), event.getY());
            if (hit != null && !selected.contains(hit.ref())) {
                selected.clear();
                selected.add(hit.ref());
            }
            placeCursor(event);
            menu().show(view, event.getX(), event.getY());
            view.repaint();
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(event)) {
            return;
        }
        pressX = event.getX();
        pressY = event.getY();
        dragLane = view.laneAt(event.getY());
        byTicks = 0;
        bySemitones = 0;
        boolean adding = event.isShiftDown() || event.isControlDown() || event.isMetaDown();
        if (inBand) {
            pressInBand(event, box);
            return;
        }
        selectedClip = null;
        ArrangementView.Note hit = noteAt(event.getX(), event.getY());
        if (hit == null) {
            if (event.getClickCount() == 2) {
                insertAt(event);
                return;
            }
            before = adding ? new LinkedHashSet<NoteEdits.NoteRef>(selected) : new LinkedHashSet<NoteEdits.NoteRef>();
            if (!adding) {
                selected.clear();
            }
            drag = Drag.BAND;
            band = new Rectangle(pressX, pressY, 0, 0);
            placeCursor(event);
            view.seekTo(event.getX());
            view.repaint();
            return;
        }
        NoteEdits.NoteRef ref = hit.ref();
        if (adding) {
            if (!selected.remove(ref)) {
                selected.add(ref);
            }
            drag = Drag.NONE;
            view.repaint();
            return;
        }
        if (!selected.contains(ref)) {
            selected.clear();
            selected.add(ref);
        }
        anchor = hit;
        RoundRectangle2D.Float bounds = view.noteBounds(dragLane, hit);
        drag = bounds.width > 3 * EDGE && event.getX() >= bounds.x + bounds.width - EDGE ? Drag.RESIZE : Drag.MOVE;
        view.freezeWindows(true);
        view.repaint();
    }

    /** A press at the band of a lane: a clip is grabbed, or on an empty band a double-click starts one. */
    private void pressInBand(MouseEvent event, ArrangementView.ClipBox box) {
        placeCursor(event);
        if (box == null) {
            selectedClip = null;
            if (event.getClickCount() == 2) {
                long bar = bar();
                long start = Math.floorDiv(view.tickAt(event.getX()), bar) * bar;
                tell(editing.createClip(view.order().get(dragLane), start, 4 * bar));
            }
            view.repaint();
            return;
        }
        selected.clear();
        selectedClip = box.id;
        draggedClip = box;
        clipLane = dragLane;
        drag = Drag.CLIP;
        view.repaint();
    }

    @Override
    public void mouseDragged(MouseEvent event) {
        switch (drag) {
            case CLIP -> {
                long wanted = Math.round((event.getX() - pressX) * view.ticksPerPixel());
                long step = gridTicks() <= 0 ? 1 : gridTicks();
                byTicks = event.isAltDown() ? wanted : NoteEdits.snap(draggedClip.start + wanted, step) - draggedClip.start;
                byTicks = Math.max(-draggedClip.start, byTicks);
                int over = view.laneAt(event.getY());
                clipLane = draggedClip.mirrored || over < 0 ? dragLane : over;
                view.repaint();
            }
            case MOVE -> {
                long wanted = Math.round((event.getX() - pressX) * view.ticksPerPixel());
                byTicks = event.isAltDown() ? wanted : snapped(anchor.start, wanted);
                bySemitones = Math.round((pressY - event.getY()) / Math.max(1f, view.semitone(dragLane)));
                view.repaint();
            }
            case RESIZE -> {
                long wanted = Math.round((event.getX() - pressX) * view.ticksPerPixel());
                byTicks = event.isAltDown() ? wanted : snapped(anchor.start + anchor.length, wanted);
                view.repaint();
            }
            case BAND -> {
                band = new Rectangle(Math.min(pressX, event.getX()), Math.min(pressY, event.getY()),
                        Math.abs(event.getX() - pressX), Math.abs(event.getY() - pressY));
                selected.clear();
                selected.addAll(before);
                selected.addAll(notesIn(band));
                view.repaint();
            }
            default -> {
            }
        }
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        Drag ended = drag;
        drag = Drag.NONE;
        band = null;
        view.freezeWindows(false);
        if (ended == Drag.CLIP && draggedClip != null) {
            String target = clipLane == dragLane || clipLane < 0 ? null : trackOf(view.order().get(clipLane));
            boolean copying = event.isControlDown() || event.isMetaDown();
            if (clipLane != dragLane && clipLane >= 0 && target == null) {
                listener.edited("Diese Zeile ist ein Instrument ohne eigene Spur; Clips liegen auf Spuren.", false);
            } else if (copying) {
                tell(editing.copyClip(draggedClip.id, draggedClip.start + byTicks,
                        target != null ? target : draggedClip.trackId));
            } else if (byTicks != 0 || target != null) {
                tell(editing.moveClip(draggedClip.id, draggedClip.start + byTicks, target));
            }
            draggedClip = null;
        } else if (ended == Drag.MOVE && (byTicks != 0 || bySemitones != 0)) {
            tell(editing.move(selected, byTicks, bySemitones));
        } else if (ended == Drag.RESIZE && byTicks != 0) {
            tell(editing.resize(selected, byTicks, Math.max(1, Math.min(gridTicks(), 60))));
        }
        byTicks = 0;
        bySemitones = 0;
        view.repaint();
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        if (!owns(event.getY())) {
            view.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            return;
        }
        ArrangementView.Note hit = noteAt(event.getX(), event.getY());
        int cursor = Cursor.CROSSHAIR_CURSOR;
        if (hit != null) {
            RoundRectangle2D.Float bounds = view.noteBounds(view.laneAt(event.getY()), hit);
            cursor = bounds.width > 3 * EDGE && event.getX() >= bounds.x + bounds.width - EDGE
                    ? Cursor.E_RESIZE_CURSOR : Cursor.MOVE_CURSOR;
        }
        view.setCursor(Cursor.getPredefinedCursor(cursor));
    }

    /** A move by this many ticks, corrected so that a tick lands on the grid. */
    private long snapped(long from, long wanted) {
        long step = gridTicks();
        return step <= 0 ? wanted : NoteEdits.snap(from + wanted, step) - from;
    }

    private void placeCursor(MouseEvent event) {
        cursorLane = view.laneAt(event.getY());
        long step = gridTicks();
        long tick = view.tickAt(event.getX());
        cursorTick = step <= 0 ? tick : Math.floorDiv(tick, step) * step;
    }

    private void insertAt(MouseEvent event) {
        int index = view.laneAt(event.getY());
        if (index < 0) {
            return;
        }
        long step = gridTicks();
        long tick = view.tickAt(event.getX());
        long at = step <= 0 ? tick : Math.floorDiv(tick, step) * step;
        long length = step <= 0 ? 240 : step;
        PieceEditing.Outcome outcome = editing.insert(view.order().get(index), at, view.pitchAt(index, event.getY()),
                length);
        if (!outcome.changed() && outcome.said().isEmpty()) {
            listener.edited("Hier liegt kein Clip, in den eine Note passt.", false);
            return;
        }
        tell(outcome);
    }

    private ArrangementView.Note noteAt(int x, int y) {
        int index = view.laneAt(y);
        ArrangementView.Arrangement shown = view.arrangement();
        if (index < 0 || shown == null) {
            return null;
        }
        List<ArrangementView.Note> notes = shown.lanes.get(view.order().get(index));
        if (notes == null) {
            return null;
        }
        ArrangementView.Note found = null;
        for (ArrangementView.Note note : notes) {
            RoundRectangle2D.Float bounds = view.noteBounds(index, note);
            if (note.ref() != null && x >= bounds.x - 1 && x <= bounds.x + bounds.width + 1
                    && y >= bounds.y - 2 && y <= bounds.y + bounds.height + 2) {
                found = note; // the last one drawn lies on top
            }
        }
        return found;
    }

    private List<NoteEdits.NoteRef> notesIn(Rectangle frame) {
        List<NoteEdits.NoteRef> found = new ArrayList<NoteEdits.NoteRef>();
        ArrangementView.Arrangement shown = view.arrangement();
        if (shown == null) {
            return found;
        }
        for (int index = 0; index < view.order().size(); index++) {
            List<ArrangementView.Note> notes = shown.lanes.get(view.order().get(index));
            if (notes == null) {
                continue;
            }
            for (ArrangementView.Note note : notes) {
                if (note.ref() != null && view.noteBounds(index, note).intersects(frame)) {
                    found.add(note.ref());
                }
            }
        }
        return found;
    }

    // ---------------------------------------------------------------------------------------
    // Painting

    void paintOver(Graphics2D g) {
        ArrangementView.Arrangement shown = view.arrangement();
        if (shown == null || !shown.editable) {
            return;
        }
        if ((drag == Drag.MOVE || drag == Drag.RESIZE) && dragLane >= 0) {
            g.setColor(AudioStudioTheme.withAlpha(Color.WHITE, 235));
            for (int index = 0; index < view.order().size(); index++) {
                List<ArrangementView.Note> notes = shown.lanes.get(view.order().get(index));
                if (notes == null) {
                    continue;
                }
                for (ArrangementView.Note note : notes) {
                    if (note.ref() == null || !selected.contains(note.ref())) {
                        continue;
                    }
                    if (drag == Drag.MOVE) {
                        g.fill(view.noteBounds(index, note.start + byTicks, note.length,
                                Math.max(0, Math.min(127, note.pitch + bySemitones))));
                    } else {
                        g.fill(view.noteBounds(index, note.start, Math.max(1, note.length + byTicks), note.pitch));
                    }
                }
            }
        }
        if (drag == Drag.CLIP && draggedClip != null && clipLane >= 0) {
            float x = view.xOf(draggedClip.start + byTicks);
            float w = Math.max(3f, view.xOf(draggedClip.end + byTicks) - x - 1f);
            int top = view.laneTop(clipLane);
            g.setColor(AudioStudioTheme.withAlpha(Color.WHITE, 30));
            g.fill(new RoundRectangle2D.Float(x, top + 2f, w, view.laneHeight() - 4f, 5f, 5f));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.4f));
            g.draw(new RoundRectangle2D.Float(x, top + 2f, w, view.laneHeight() - 4f, 5f, 5f));
        }
        if (band != null) {
            g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 40));
            g.fill(band);
            g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 180));
            g.setStroke(new BasicStroke(1f));
            g.draw(band);
        }
        if (cursorLane >= 0 && cursorLane < view.order().size() && view.isFocusOwner()) {
            float x = view.xOf(cursorTick);
            int top = view.laneTop(cursorLane);
            g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT_SOFT, 200));
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3f, 3f}, 0f));
            g.draw(new Line2D.Float(x, top + 2, x, top + view.laneHeight() - 3));
        }
        g.setFont(AudioStudioTheme.lettering(9f));
        g.setColor(AudioStudioTheme.TEXT_FAINT);
        String label = "RASTER " + grid.name().toUpperCase(java.util.Locale.ROOT)
                + (selected.isEmpty() ? selectedClip != null ? " · CLIP GEWÄHLT" : ""
                : " · " + selected.size() + " GEWÄHLT");
        g.drawString(label, view.getWidth() - 14 - g.getFontMetrics().stringWidth(label), ArrangementView.RULER - 7);
    }

    // ---------------------------------------------------------------------------------------
    // Keys and menu

    private void bindKeys() {
        int menuKey = menuKey();
        bind("note-delete", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), this::delete);
        bind("note-delete-back", KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), this::delete);
        bind("note-copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, menuKey), this::copy);
        bind("note-cut", KeyStroke.getKeyStroke(KeyEvent.VK_X, menuKey), this::cut);
        bind("note-paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, menuKey), this::paste);
        bind("note-all", KeyStroke.getKeyStroke(KeyEvent.VK_A, menuKey), this::selectAll);
        bind("note-quantize", KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0), this::quantize);
        bind("note-undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuKey), () -> tell(editing.undo()));
        bind("note-redo", KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuKey), () -> tell(editing.redo()));
        bind("note-redo-shift", KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuKey | InputEvent.SHIFT_DOWN_MASK),
                () -> tell(editing.redo()));
        bind("clip-split", KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), this::splitClip);
        bind("clip-duplicate", KeyStroke.getKeyStroke(KeyEvent.VK_D, menuKey), this::duplicateClip);
        bind("note-none", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> {
            selectedClip = null;
            selected.clear();
            view.repaint();
        });
        bind("note-left", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), () -> nudge(-step(), 0));
        bind("note-right", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), () -> nudge(step(), 0));
        bind("note-left-bar", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK), () -> nudge(-bar(), 0));
        bind("note-right-bar", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK), () -> nudge(bar(), 0));
        bind("note-up", KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), () -> nudge(0, 1));
        bind("note-down", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), () -> nudge(0, -1));
        bind("note-up-octave", KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.SHIFT_DOWN_MASK), () -> nudge(0, 12));
        bind("note-down-octave", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.SHIFT_DOWN_MASK), () -> nudge(0, -12));
    }

    /** Ctrl, or Cmd on a Mac; Ctrl where there is no screen to ask. */
    private static int menuKey() {
        try {
            return java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException noScreen) {
            return InputEvent.CTRL_DOWN_MASK;
        }
    }

    private void bind(String name, KeyStroke key, Runnable action) {
        InputMap keys = view.getInputMap(JComponent.WHEN_FOCUSED);
        keys.put(key, name);
        view.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (editable()) {
                    action.run();
                }
            }
        });
    }

    private boolean editable() {
        ArrangementView.Arrangement shown = view.arrangement();
        return shown != null && shown.editable && editing.canEdit();
    }

    private long step() {
        long ticks = gridTicks();
        return ticks <= 0 ? 10 : ticks;
    }

    private long bar() {
        ArrangementView.Arrangement shown = view.arrangement();
        return shown == null ? 1920 : shown.barTicks;
    }

    private void nudge(long ticks, int semitones) {
        if (!selected.isEmpty()) {
            tell(editing.move(selected, ticks, semitones));
        }
    }

    void delete() {
        if (!selected.isEmpty()) {
            tell(editing.delete(selected));
        } else if (selectedClip != null) {
            tell(editing.deleteClip(selectedClip));
        }
    }

    /** Cuts the selected clip, or the one under the cursor, at the cursor. */
    void splitClip() {
        String clip = selectedClip;
        if (clip == null && cursorLane >= 0) {
            ArrangementView.ClipBox box = view.clipAt(cursorLane, cursorTick);
            clip = box == null ? null : box.id;
        }
        if (clip == null) {
            listener.edited("Erst einen Clip wählen oder in einen Clip klicken: dort wird geteilt.", false);
            return;
        }
        tell(editing.splitClip(clip, cursorTick));
    }

    /** A copy of the selected clip right after it, on the same lane. */
    void duplicateClip() {
        ArrangementView.ClipBox box = box(selectedClip);
        if (box != null) {
            tell(editing.copyClip(box.id, box.end, box.trackId));
        }
    }

    private ArrangementView.ClipBox box(String clipId) {
        ArrangementView.Arrangement shown = view.arrangement();
        if (clipId == null || shown == null) {
            return null;
        }
        for (List<ArrangementView.ClipBox> boxes : shown.clips.values()) {
            for (ArrangementView.ClipBox box : boxes) {
                if (box.id.equals(clipId) && !box.mirrored) {
                    return box;
                }
            }
        }
        return null;
    }

    /** The lane id as a track of the piece, or {@code null} if the row is a part without a lane. */
    private String trackOf(String laneId) {
        return editing.composition().map(piece -> piece.track(laneId) == null ? null : laneId).orElse(null);
    }

    void copy() {
        int copied = editing.copy(selected);
        if (copied > 0) {
            listener.edited(copied == 1 ? "Note kopiert" : copied + " Noten kopiert", false);
        }
    }

    void cut() {
        if (!selected.isEmpty()) {
            tell(editing.cut(selected));
        }
    }

    void paste() {
        if (!editing.canPaste()) {
            return;
        }
        int lane = cursorLane >= 0 && cursorLane < view.order().size() ? cursorLane : laneOfSelection();
        if (lane < 0) {
            listener.edited("Erst in eine Spur klicken: dort werden die Noten eingefügt.", false);
            return;
        }
        PieceEditing.Outcome outcome = editing.paste(view.order().get(lane), cursorTick);
        if (!outcome.changed() && outcome.said().isEmpty()) {
            listener.edited("Hier liegt kein Clip, in den die Noten passen.", false);
            return;
        }
        tell(outcome);
    }

    void quantize() {
        if (!selected.isEmpty() && gridTicks() > 0) {
            tell(editing.quantize(selected, gridTicks()));
        }
    }

    void selectAll() {
        int lane = cursorLane >= 0 ? cursorLane : laneOfSelection();
        ArrangementView.Arrangement shown = view.arrangement();
        if (shown == null) {
            return;
        }
        selected.clear();
        for (int index = 0; index < view.order().size(); index++) {
            if (lane >= 0 && index != lane) {
                continue;
            }
            List<ArrangementView.Note> notes = shown.lanes.get(view.order().get(index));
            if (notes != null) {
                for (ArrangementView.Note note : notes) {
                    if (note.ref() != null) {
                        selected.add(note.ref());
                    }
                }
            }
        }
        view.repaint();
    }

    private int laneOfSelection() {
        ArrangementView.Arrangement shown = view.arrangement();
        if (shown == null || selected.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < view.order().size(); index++) {
            List<ArrangementView.Note> notes = shown.lanes.get(view.order().get(index));
            if (notes != null) {
                for (ArrangementView.Note note : notes) {
                    if (note.ref() != null && selected.contains(note.ref())) {
                        return index;
                    }
                }
            }
        }
        return -1;
    }

    private JPopupMenu menu() {
        JPopupMenu menu = new JPopupMenu();
        boolean any = !selected.isEmpty();
        ArrangementView.ClipBox clip = box(selectedClip);
        if (clip != null) {
            menu.add(item("Clip am Cursor teilen (S)", cursorTick > clip.start && cursorTick < clip.end, this::splitClip));
            menu.add(item("Clip duplizieren (Strg+D)", true, this::duplicateClip));
            menu.add(item("Clip löschen", true, () -> tell(editing.deleteClip(clip.id))));
            menu.add(playedBy(clip));
            menu.addSeparator();
        } else if (cursorLane >= 0 && trackOf(view.order().get(cursorLane)) != null
                && view.clipAt(cursorLane, cursorTick) == null) {
            menu.add(item("Neuer Clip hier (4 Takte)", true, () -> {
                long bar = bar();
                tell(editing.createClip(view.order().get(cursorLane), Math.floorDiv(cursorTick, bar) * bar, 4 * bar));
            }));
            menu.addSeparator();
        }
        menu.add(item("Note hier einfügen", true, () -> {
            if (cursorLane >= 0) {
                long length = gridTicks() <= 0 ? 240 : gridTicks();
                int pitch = view.pitchAt(cursorLane, pressY);
                PieceEditing.Outcome outcome = editing.insert(view.order().get(cursorLane), cursorTick, pitch, length);
                if (!outcome.changed() && outcome.said().isEmpty()) {
                    listener.edited("Hier liegt kein Clip, in den eine Note passt.", false);
                } else {
                    tell(outcome);
                }
            }
        }));
        menu.addSeparator();
        menu.add(item("Kopieren", any, this::copy));
        menu.add(item("Ausschneiden", any, this::cut));
        menu.add(item("Einfügen", editing.canPaste(), this::paste));
        menu.add(item("Löschen", any, this::delete));
        menu.add(item("Ins Raster setzen (Q)", any && gridTicks() > 0, this::quantize));
        menu.add(item("Alle Noten der Spur wählen", true, this::selectAll));
        menu.addSeparator();
        JMenu grids = new JMenu("Raster");
        ButtonGroup group = new ButtonGroup();
        for (Grid choice : GRIDS) {
            JRadioButtonMenuItem entry = new JRadioButtonMenuItem(choice.name(), choice.equals(grid));
            entry.addActionListener(event -> setGrid(choice));
            group.add(entry);
            grids.add(entry);
        }
        menu.add(grids);
        menu.addSeparator();
        menu.add(item("Rückgängig", editing.canUndo(), () -> tell(editing.undo())));
        menu.add(item("Wiederholen", editing.canRedo(), () -> tell(editing.redo())));
        return menu;
    }

    /** Which part plays a clip: the parts of the piece to choose from, or a new instrument. */
    private JMenu playedBy(ArrangementView.ClipBox clip) {
        JMenu menu = new JMenu("Gespielt von");
        ButtonGroup group = new ButtonGroup();
        editing.composition().ifPresent(piece -> {
            for (com.aresstack.audio.composition.Part part : piece.parts()) {
                String label = part.name() + (part.instrument() != null && !part.instrument().equals(part.name())
                        ? " · " + part.instrument() : "");
                JRadioButtonMenuItem entry = new JRadioButtonMenuItem(label, part.id().equals(clip.partId));
                entry.addActionListener(event -> tell(editing.playClipBy(clip.id, part.id())));
                group.add(entry);
                menu.add(entry);
            }
        });
        menu.addSeparator();
        menu.add(item("Neues Instrument …", true, () -> chooseInstrument(clip)));
        return menu;
    }

    /** Asks for a General MIDI instrument and lets a new part with it play the clip. */
    private void chooseInstrument(ArrangementView.ClipBox clip) {
        javax.swing.JComboBox<String> programs = new javax.swing.JComboBox<String>(GeneralMidi.entries());
        programs.setSelectedIndex(73);
        int answer = javax.swing.JOptionPane.showConfirmDialog(view, programs, "Instrument für diesen Clip",
                javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (answer != javax.swing.JOptionPane.OK_OPTION) {
            return;
        }
        int program = programs.getSelectedIndex();
        String role = editing.composition().map(piece -> {
            com.aresstack.audio.composition.Part part = piece.part(clip.partId);
            return part == null ? null : part.role();
        }).orElse(null);
        tell(editing.playClipByNew(clip.id,
                new com.aresstack.audio.studio.application.ClipEdits.Instrument(GeneralMidi.name(program), program,
                        "builtin", role)));
    }

    private static JMenuItem item(String name, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(name);
        item.setEnabled(enabled);
        item.addActionListener(event -> action.run());
        return item;
    }

    private void tell(PieceEditing.Outcome outcome) {
        if (outcome.changed() && !outcome.selected().isEmpty()) {
            selected.clear();
            selected.addAll(outcome.selected());
        }
        if (outcome.changed() && outcome.selectedClip() != null) {
            selected.clear();
            selectedClip = outcome.selectedClip();
        }
        if (outcome.changed() || !outcome.said().isEmpty()) {
            listener.edited(outcome.said(), outcome.changed());
        }
        view.repaint();
    }
}
