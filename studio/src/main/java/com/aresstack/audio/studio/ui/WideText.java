package com.aresstack.audio.studio.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * A one-line field that opens in place into a large text box with several lines, for a long
 * text such as the description of a whole album. Both show the same text: the box writes into
 * the document of the field, so whoever reads the field reads everything that was written.
 */
final class WideText extends JPanel {
    /** Lines the open box shows before it scrolls. */
    static final int OPEN_ROWS = 10;
    private final CardLayout cards = new CardLayout();
    /** Only the card that is shown counts for the size: the closed field stays one line high. */
    private final JPanel shown = new JPanel(cards) {
        @Override
        public java.awt.Dimension getPreferredSize() {
            for (Component card : getComponents()) {
                if (card.isVisible()) {
                    return card.getPreferredSize();
                }
            }
            return super.getPreferredSize();
        }

        @Override
        public java.awt.Dimension getMinimumSize() {
            return getPreferredSize();
        }
    };
    private final JTextArea area;
    private final DeckWidgets.IconKey toggle = new DeckWidgets.IconKey(DeckWidgets.IconKey.Sign.EXPAND, OPEN_HINT);
    private boolean open;
    private static final String OPEN_HINT = "Öffnet das Feld als große Textbox für einen langen Text";

    /** The key that opens and folds the field; the form puts it beside the field, in line with the others. */
    JButton toggleKey() {
        return toggle;
    }

    WideText(JTextField field, Component line) {
        this(field, line, OPEN_ROWS);
    }

    /** @param rows the lines the open box shows before it scrolls */
    WideText(JTextField field, Component line, int rows) {
        super(new BorderLayout(6, 0));
        setOpaque(false);
        shown.setOpaque(false);
        area = new JTextArea(field.getDocument(), null, rows, 20);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 6));
        shown.add(line, "line");
        JScrollPane box = new JScrollPane(area);
        box.setBorder(javax.swing.BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        shown.add(box, "box");
        add(shown, BorderLayout.CENTER);
        toggle.addActionListener(event -> open(!open));
    }

    /** Opens the large box, or folds it back into one line. */
    void open(boolean wanted) {
        open = wanted;
        cards.show(shown, wanted ? "box" : "line");
        toggle.setSign(wanted ? DeckWidgets.IconKey.Sign.COLLAPSE : DeckWidgets.IconKey.Sign.EXPAND);
        toggle.setToolTipText(wanted ? "Zeigt das Feld wieder in einer Zeile" : OPEN_HINT);
        revalidate();
        repaint();
        (wanted ? area : shown.getComponent(0)).requestFocusInWindow();
    }

    /**
     * A long text must not make the form wider: the field asks for little room and takes what the
     * form gives it, so that the names of the fields beside it stay readable.
     */
    @Override
    public java.awt.Dimension getPreferredSize() {
        java.awt.Dimension size = super.getPreferredSize();
        return new java.awt.Dimension(Math.min(size.width, NARROW), size.height);
    }

    @Override
    public java.awt.Dimension getMinimumSize() {
        return new java.awt.Dimension(NARROW / 2, super.getPreferredSize().height);
    }

    /** The width the field asks for, whatever text it holds. */
    static final int NARROW = 240;

    boolean isOpen() {
        return open;
    }

    /** The large box; it has the focus while the user writes into it. */
    JTextArea area() {
        return area;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        shown.getComponent(0).setEnabled(enabled);
        area.setEnabled(enabled);
        toggle.setEnabled(true); // looking at a long text is possible while the album is at work
    }
}
