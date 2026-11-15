package com.aresstack.audio.studio.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.ToolTipManager;

/**
 * The form of the piece above the lanes: a field per section with its name and function, its
 * intensity as a level, and the chords bar by bar where they fit. It lies over the same bars as
 * the lanes below it and is not there for a piece without sections.
 *
 * <p>It is edited like the column heads of a table: a click marks a section (its bars across every
 * MIDI lane), dragging or Shift and a click mark several, a marked section is dragged to another
 * bar, the right mouse button opens what can be done with the marked bars, and Entf deletes them.
 * A gap (bars without music) is drawn hollow; the AI can fill it.
 */
final class SectionBar extends JComponent {
    static final int HEIGHT = 38;

    /** Who is told when the user sets the intensity of a section by hand. */
    interface Listener {
        /** @param intensity 0 to 1 */
        void intensity(SectionMark section, double intensity);
    }

    /** Who changes the form when the user edits it here; bars are counted from 0, ends exclusive. */
    interface Editing {
        /** Deletes the bars with their music; {@code close} moves what follows forward, else a gap remains. */
        void deleteBars(int firstBar, int endBar, boolean close);

        /** Drags a section with its music so that it begins in {@code toBar}. */
        void moveSection(SectionMark section, int toBar);

        /** Puts {@code bars} empty bars in before {@code beforeBar}. */
        void insertEmpty(int beforeBar, int bars);

        /** Has the AI write the bars: fill them if they are empty, write them anew otherwise. */
        void generate(int firstBar, int endBar);

        /** Whether no note lies in the bars. */
        boolean silent(int firstBar, int endBar);
    }

    /** How far the pointer moves, in pixels, before a press on a marked section becomes a drag. */
    private static final int DRAG_START = 4;

    private final ArrangementView lanes;
    private List<SectionMark> sections = new ArrayList<SectionMark>();
    private Listener listener;
    private Editing editing;
    /** The bars the mark began with (from 0, both included); a drag or Shift and a click extend from them. */
    private int anchorFirst = -1;
    private int anchorLast = -1;
    /** The marked section pressed on, which a drag moves; {@code null} while none is. */
    private SectionMark dragged;
    private int pressX;
    /** The bar the dragged section would begin in now, or -1 before the pointer moved far enough. */
    private int dropBar = -1;

    SectionBar(ArrangementView lanes) {
        this.lanes = lanes;
        setVisible(false);
        setFocusable(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                pressed(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                dragged(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                released(event);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        bindKey(java.awt.event.KeyEvent.VK_DELETE, 0, "delete-bars", () -> deleteMarked(false));
        bindKey(java.awt.event.KeyEvent.VK_DELETE, java.awt.event.InputEvent.SHIFT_DOWN_MASK, "delete-bars-close",
                () -> deleteMarked(true));
        bindKey(java.awt.event.KeyEvent.VK_ESCAPE, 0, "unmark", () -> lanes.mark(null));
    }

    private void bindKey(int key, int modifiers, String name, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(key, modifiers), name);
        getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                action.run();
            }
        });
    }

    /** Lets the user edit the form here: delete, drag, put bars in and have the AI write bars. */
    void setEditing(Editing told) {
        editing = told;
    }

    /** Lets the user set the intensity of a section from its menu (work package C4). */
    void setListener(Listener told) {
        listener = told;
        setCursor(java.awt.Cursor.getPredefinedCursor(told == null && editing == null ? java.awt.Cursor.DEFAULT_CURSOR
                : java.awt.Cursor.HAND_CURSOR));
    }

    /** The small menu under a section: a slider for its intensity and the key that sets it. */
    javax.swing.JPopupMenu intensityMenu(final SectionMark section) {
        final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        final javax.swing.JSlider slider = new javax.swing.JSlider(0, 100,
                (int) Math.round((section.intensity() == null ? 0.5 : section.intensity()) * 100));
        slider.setName("intensity");
        slider.setMajorTickSpacing(25);
        slider.setPaintTicks(true);
        final javax.swing.JLabel value = new javax.swing.JLabel();
        Runnable show = () -> value.setText("Intensität von „" + section.name() + "“: " + slider.getValue() + " %");
        show.run();
        slider.addChangeListener(event -> show.run());
        javax.swing.JButton set = new javax.swing.JButton("Setzen");
        set.setName("set");
        set.setToolTipText("Ändert nur die Form: keine Note ändert sich, nichts wird neu gerendert. Die KI richtet sich"
                + " beim nächsten Verlängern, Einfügen oder Umorchestrieren danach.");
        set.addActionListener(event -> {
            menu.setVisible(false);
            if (listener != null) {
                listener.intensity(section, slider.getValue() / 100.0);
            }
        });
        javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(8, 6));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));
        panel.add(value, java.awt.BorderLayout.NORTH);
        panel.add(slider, java.awt.BorderLayout.CENTER);
        panel.add(set, java.awt.BorderLayout.EAST);
        menu.add(panel);
        return menu;
    }

    // The mouse and the keys ------------------------------------------------------------------

    private void pressed(MouseEvent event) {
        dragged = null;
        dropBar = -1;
        if (sections.isEmpty()) {
            return;
        }
        int x = event.getX();
        boolean right = javax.swing.SwingUtilities.isRightMouseButton(event) || event.isPopupTrigger();
        if (editing == null) {
            SectionMark section = at(x);
            if (section != null && listener != null) {
                intensityMenu(section).show(this, x, HEIGHT);
            }
            return;
        }
        int[] span = spanAt(x);
        int[] marked = marked();
        if (right) {
            int bar = barAt(x);
            if (marked == null || bar < marked[0] || bar > marked[1]) {
                anchorFirst = span[0];
                anchorLast = span[1];
                markSpan(span[0], span[1]);
            }
            barMenu().show(this, x, HEIGHT);
            return;
        }
        if (event.isShiftDown() && marked != null) {
            int first = anchorFirst >= 0 ? anchorFirst : marked[0];
            int last = anchorLast >= 0 ? anchorLast : marked[1];
            markSpan(Math.min(first, span[0]), Math.max(last, span[1]));
            return;
        }
        SectionMark section = at(x);
        if (section != null && !section.gap() && marked != null && marked[0] == section.firstBar()
                && marked[1] == section.firstBar() + section.bars() - 1) {
            dragged = section; // a press on the marked section: a drag moves it
            pressX = x;
            return;
        }
        anchorFirst = span[0];
        anchorLast = span[1];
        markSpan(span[0], span[1]);
    }

    private void dragged(MouseEvent event) {
        if (editing == null || javax.swing.SwingUtilities.isRightMouseButton(event)) {
            return;
        }
        if (dragged != null) {
            if (dropBar < 0 && Math.abs(event.getX() - pressX) < DRAG_START) {
                return;
            }
            float width = barWidth();
            int moved = width <= 0 ? 0 : Math.round((event.getX() - pressX) / width);
            dropBar = Math.max(0, dragged.firstBar() + moved);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR));
            repaint();
            return;
        }
        if (anchorFirst >= 0) {
            int[] span = spanAt(event.getX());
            markSpan(Math.min(anchorFirst, span[0]), Math.max(anchorLast, span[1]));
        }
    }

    private void released(MouseEvent event) {
        SectionMark moving = dragged;
        int to = dropBar;
        dragged = null;
        dropBar = -1;
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        repaint();
        if (moving != null && to >= 0 && to != moving.firstBar() && editing != null) {
            editing.moveSection(moving, to);
        }
    }

    /** Deletes the marked bars (Entf; with Shift the gap closes). */
    void deleteMarked(boolean close) {
        int[] marked = marked();
        if (editing != null && marked != null) {
            editing.deleteBars(marked[0], marked[1] + 1, close);
        }
    }

    /** The marked bars from 0, both included, or {@code null}: the mark of the lanes below. */
    int[] marked() {
        ArrangementView.Range range = lanes.marked();
        return range == null ? null : new int[] {range.firstBar() - 1, range.lastBar() - 1};
    }

    /** Marks bars (from 0, both included) across every MIDI lane. */
    void markSpan(int first, int last) {
        lanes.markBars(first + 1, last + 1);
        repaint();
    }

    /** The bars a press at {@code x} takes: the section there, else the bar alone. */
    private int[] spanAt(int x) {
        SectionMark section = at(x);
        if (section != null) {
            return new int[] {section.firstBar(), section.firstBar() + section.bars() - 1};
        }
        int bar = Math.min(barAt(x), Math.max(0, lanes.bars() - 1));
        return new int[] {bar, bar};
    }

    /** How wide a bar is drawn. */
    private float barWidth() {
        int bars = Math.max(1, lanes.bars());
        return (lanes.xOfBar(bars) - lanes.xOfBar(0)) / bars;
    }

    /** The bar under a place across the bar, from 0; behind the music it counts on. */
    int barAt(int x) {
        float width = barWidth();
        return width <= 0 ? 0 : Math.max(0, (int) Math.floor((x - lanes.xOfBar(0)) / width));
    }

    /** The section that covers exactly these bars (from 0, end exclusive), or {@code null}. */
    private SectionMark exactly(int first, int end) {
        for (SectionMark section : sections) {
            if (section.firstBar() == first && section.firstBar() + section.bars() == end) {
                return section;
            }
        }
        return null;
    }

    /** What can be done with the marked bars: have the AI write them, delete them, put empty bars in. */
    javax.swing.JPopupMenu barMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        final int[] marked = marked();
        if (marked == null || editing == null) {
            return menu;
        }
        final int first = marked[0];
        final int end = marked[1] + 1;
        final int count = end - first;
        final SectionMark only = exactly(first, end);
        boolean gap = only != null && only.gap();
        String bars = count == 1 ? "Takt " + (first + 1) : "Takte " + (first + 1) + " bis " + end;
        boolean silent = gap || editing.silent(first, end);
        item(menu, "generate", gap ? "Lücke per KI füllen" : bars + (silent ? " per KI füllen" : " per KI neu generieren"),
                "Die KI hört, was davor und danach klingt, und baut einen Übergang, der an beiden Enden passt."
                        + " Wird sofort übernommen; „Rückgängig“ nimmt es zurück.",
                null, () -> editing.generate(first, end));
        menu.addSeparator();
        String what = only == null ? bars : gap ? "Lücke" : "„" + only.name() + "“";
        if (!gap) {
            item(menu, "delete", what + " löschen, Lücke bleibt",
                    "Die Noten gehen, die Takte bleiben leer; die KI kann sie später füllen.",
                    javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0),
                    () -> editing.deleteBars(first, end, false));
        }
        item(menu, "delete-close", gap ? "Lücke schließen" : what + " löschen und Lücke schließen",
                "Was danach kommt, rückt nach vorn; das Stück wird kürzer.",
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE,
                        java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                () -> editing.deleteBars(first, end, true));
        item(menu, "insert", (count == 1 ? "1 leeren Takt" : count + " leere Takte") + " vor Takt " + (first + 1)
                        + " einfügen",
                "Alles ab hier rückt nach hinten; die neuen Takte sind eine Lücke, die die KI füllen kann.", null,
                () -> editing.insertEmpty(first, count));
        if (only != null && !gap && listener != null) {
            menu.addSeparator();
            item(menu, "intensity", "Intensität setzen …", null, null,
                    () -> intensityMenu(only).show(this, Math.round(lanes.xOfBar(only.firstBar())), HEIGHT));
        }
        menu.addSeparator();
        item(menu, "unmark", "Markierung aufheben", null,
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), () -> lanes.mark(null));
        return menu;
    }

    private static void item(javax.swing.JPopupMenu menu, String name, String label, String hint,
                             javax.swing.KeyStroke key, Runnable action) {
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(label);
        item.setName(name);
        item.setToolTipText(hint);
        if (key != null) {
            item.setAccelerator(key);
        }
        item.addActionListener(event -> action.run());
        menu.add(item);
    }

    void setSections(List<SectionMark> shown) {
        sections = new ArrayList<SectionMark>(shown == null ? List.<SectionMark>of() : shown);
        setVisible(!sections.isEmpty());
        revalidate();
        repaint();
    }

    List<SectionMark> sections() {
        return new ArrayList<SectionMark>(sections);
    }

    /** The section under a place across the bar, or {@code null}. */
    SectionMark at(int x) {
        for (SectionMark section : sections) {
            if (x >= lanes.xOfBar(section.firstBar()) && x < lanes.xOfBar(section.firstBar() + section.bars())) {
                return section;
            }
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        SectionMark section = at(event.getX());
        if (section == null) {
            return null;
        }
        if (editing == null) {
            return section.told() + (listener == null ? "" : " · Rechtsklick: Intensität setzen");
        }
        return section.told() + (section.gap() ? " · Rechtsklick: per KI füllen" : "")
                + " · Klick: markieren, Ziehen oder Umschalt: mehrere, markierten Abschnitt ziehen: verschieben,"
                + " Rechtsklick: Menü, Entf: löschen";
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(lanes.getPreferredSize().width, sections.isEmpty() ? 0 : HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = DeckWidgets.smooth(graphics);
        g.setColor(AudioStudioTheme.WELL);
        g.fillRect(0, 0, getWidth(), getHeight());
        for (int index = 0; index < sections.size(); index++) {
            SectionMark section = sections.get(index);
            float x = lanes.xOfBar(section.firstBar());
            float end = lanes.xOfBar(section.firstBar() + section.bars());
            float width = Math.max(2f, end - x - 1f);
            Color color = AudioStudioTheme.channel(index);
            if (section.gap()) {
                // A gap: bars without music, hollow, waiting to be filled or dragged into.
                g.setColor(AudioStudioTheme.TEXT_FAINT);
                g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER,
                        10f, new float[] {4f, 3f}, 0f));
                g.draw(new Rectangle2D.Float(x + 0.5f, 1.5f, width - 1, HEIGHT - 3));
                g.setStroke(new java.awt.BasicStroke(1f));
                Shape clip = g.getClip();
                g.clip(new Rectangle2D.Float(x, 0, width, HEIGHT));
                g.setFont(AudioStudioTheme.lettering(11f));
                g.drawString(section.name(), x + 7, 3 + g.getFontMetrics().getAscent());
                g.setClip(clip);
                continue;
            }
            g.setColor(AudioStudioTheme.withAlpha(color, 30));
            g.fill(new Rectangle2D.Float(x, 1, width, HEIGHT - 2));
            if (section.intensity() != null) {
                // The intensity as a level from the bottom: a strong section stands high.
                float level = (HEIGHT - 2) * (float) Math.max(0, Math.min(1, section.intensity()));
                g.setColor(AudioStudioTheme.withAlpha(color, 70));
                g.fill(new Rectangle2D.Float(x, HEIGHT - 1 - level, width, level));
            }
            g.setColor(color);
            g.fill(new Rectangle2D.Float(x, 1, 2, HEIGHT - 2));
            Shape clip = g.getClip();
            g.clip(new Rectangle2D.Float(x, 0, width, HEIGHT));
            g.setFont(AudioStudioTheme.lettering(11f));
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(new Color(0xF2F0F6));
            String title = section.title() + (section.intensity() == null ? ""
                    : "  " + Math.round(section.intensity() * 100) + " %");
            g.drawString(title, x + 7, 3 + metrics.getAscent());
            g.setFont(AudioStudioTheme.lettering(10f));
            metrics = g.getFontMetrics();
            g.setColor(new Color(0xB9B6C6));
            float barWidth = (end - x) / section.bars();
            String before = null;
            for (int bar = 0; bar < section.chords().size() && bar < section.bars(); bar++) {
                String chord = section.chords().get(bar);
                if (chord == null || chord.isBlank() || chord.equals(before)) {
                    continue; // a chord that goes on is written once
                }
                before = chord;
                if (barWidth >= 14 || bar == 0) {
                    g.drawString(chord, x + 7 + bar * barWidth, HEIGHT - 6);
                }
            }
            g.setClip(clip);
        }
        int[] marked = marked();
        if (marked != null) {
            float from = lanes.xOfBar(marked[0]);
            float to = lanes.xOfBar(marked[1] + 1);
            Rectangle2D area = new Rectangle2D.Float(from, 1, Math.max(2f, to - from), HEIGHT - 2);
            g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 46));
            g.fill(area);
            g.setColor(AudioStudioTheme.ACCENT);
            g.setStroke(new java.awt.BasicStroke(1.4f));
            g.draw(area);
            g.setStroke(new java.awt.BasicStroke(1f));
        }
        if (dragged != null && dropBar >= 0) {
            // Where the dragged section would land.
            float width = barWidth();
            float from = lanes.xOfBar(0) + dropBar * width;
            Rectangle2D ghost = new Rectangle2D.Float(from, 2, Math.max(2f, dragged.bars() * width - 1), HEIGHT - 4);
            g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT_SOFT, 70));
            g.fill(ghost);
            g.setColor(AudioStudioTheme.ACCENT_SOFT);
            g.draw(ghost);
            g.setFont(AudioStudioTheme.lettering(11f));
            g.setColor(AudioStudioTheme.TEXT);
            g.drawString(dragged.name() + " → Takt " + (dropBar + 1), from + 7, 3 + g.getFontMetrics().getAscent());
        }
        g.setColor(new Color(255, 255, 255, 20));
        g.fillRect(0, HEIGHT - 1, getWidth(), 1);
    }
}
