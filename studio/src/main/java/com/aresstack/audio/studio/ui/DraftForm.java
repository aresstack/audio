package com.aresstack.audio.studio.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Container;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;

/**
 * How the draft of a piece is laid out: at the left the words for the AI, over the whole height of
 * the page; at the right a column of named groups with everything else that is said about the
 * piece. Every group has its name lettered over it, its AI help beside the name and its lines
 * under it, and a fine line parts it from the group above.
 */
final class DraftForm {
    /** How wide the column of groups is; the words take the rest. */
    static final int SIDE_WIDTH = 470;
    /** How high a head is, with or without a key, so that the heads of both sides stand level. */
    private static final int HEAD_HEIGHT = 26;

    private DraftForm() {
    }

    /**
     * The name over a group and, at its right end, the key of its AI help.
     *
     * @param meaning what the group is for, shown as the tooltip of the name
     * @param key     the key at the right end, or {@code null}
     */
    static JPanel head(String name, String meaning, Component key) {
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        JLabel lettered = DeckWidgets.lettering(name);
        lettered.setToolTipText(meaning);
        head.add(lettered, BorderLayout.WEST);
        if (key != null) {
            head.add(key, BorderLayout.EAST);
        }
        head.setPreferredSize(new Dimension(10, HEAD_HEIGHT));
        return head;
    }

    /** The words of the piece: their name over them, and the field in all the room there is. */
    static JPanel words(String name, String meaning, Component key, Component field) {
        JPanel words = new JPanel(new BorderLayout(0, 6));
        words.setOpaque(false);
        words.add(head(name, meaning, key), BorderLayout.NORTH);
        words.add(field, BorderLayout.CENTER);
        return words;
    }

    /**
     * A group of the column: its head and its lines under one another, each as wide as the column.
     *
     * @param head  the head of the group ({@link #head}), or {@code null} if the first line is one
     * @param lines what the group holds, from top to bottom
     */
    static JPanel group(Component head, Component... lines) {
        JPanel group = new JPanel(new Stack(6));
        group.setOpaque(false);
        if (head != null) {
            group.add(head);
        }
        for (Component line : lines) {
            group.add(line);
        }
        return group;
    }

    /**
     * The column of groups beside the words: one under the other, parted by fine lines, and with a
     * scroll bar of its own when the window is too low for all of them.
     */
    static JComponent side(JPanel... groups) {
        Column column = new Column();
        for (int i = 0; i < groups.length; i++) {
            groups[i].setBorder(i == 0 ? BorderFactory.createEmptyBorder(0, 0, 12, 0)
                    : BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(1, 0, 0, 0, AudioStudioTheme.STROKE),
                            BorderFactory.createEmptyBorder(10, 0, 12, 0)));
            column.add(groups[i]);
        }
        column.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 8));
        JScrollPane scrolled = new JScrollPane(column, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrolled.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, AudioStudioTheme.STROKE));
        scrolled.setOpaque(false);
        scrolled.getViewport().setOpaque(false);
        scrolled.getVerticalScrollBar().setUnitIncrement(24);
        scrolled.setPreferredSize(new Dimension(SIDE_WIDTH, 10));
        return scrolled;
    }

    /**
     * Lays parts under one another: each as wide as the container and as high as it wants to be.
     * Unlike a grid it never squeezes a part because another one is too wide for the column.
     */
    private static final class Stack implements LayoutManager {
        private final int gap;

        Stack(int gap) {
            this.gap = gap;
        }

        @Override
        public void addLayoutComponent(String name, Component part) {
        }

        @Override
        public void removeLayoutComponent(Component part) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int width = 0;
            int height = 0;
            for (Component part : parent.getComponents()) {
                if (part.isVisible()) {
                    Dimension wanted = part.getPreferredSize();
                    width = Math.max(width, wanted.width);
                    height += (height == 0 ? 0 : gap) + wanted.height;
                }
            }
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(0, preferredLayoutSize(parent).height);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int width = parent.getWidth() - insets.left - insets.right;
            int y = insets.top;
            for (Component part : parent.getComponents()) {
                if (part.isVisible()) {
                    int height = part.getPreferredSize().height;
                    part.setBounds(insets.left, y, width, height);
                    y += height + gap;
                }
            }
        }
    }

    /** As wide as the room it is given, as high as its groups need. */
    private static final class Column extends JPanel implements Scrollable {
        Column() {
            super(new Stack(0));
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 24);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
