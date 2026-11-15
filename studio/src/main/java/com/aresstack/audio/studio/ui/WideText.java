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
    private final JButton toggle = new DeckWidgets.HeadKey("Groß");
    private boolean open;
    private final JPanel east = new JPanel(new BorderLayout());
    private final JPanel keys = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));

    /** Puts a key beside "Groß", e.g. the AI help of this field. */
    void aside(JButton key) {
        keys.add(key);
        revalidate();
    }

    WideText(JTextField field, Component line) {
        super(new BorderLayout(6, 0));
        setOpaque(false);
        shown.setOpaque(false);
        area = new JTextArea(field.getDocument(), null, OPEN_ROWS, 20);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        shown.add(line, "line");
        shown.add(new JScrollPane(area), "box");
        add(shown, BorderLayout.CENTER);
        east.setOpaque(false);
        keys.setOpaque(false);
        keys.add(toggle);
        east.add(keys, BorderLayout.NORTH);
        add(east, BorderLayout.EAST);
        toggle.setToolTipText("Öffnet das Feld als große Textbox für einen langen Text");
        toggle.addActionListener(event -> open(!open));
    }

    /** Opens the large box, or folds it back into one line. */
    void open(boolean wanted) {
        open = wanted;
        cards.show(shown, wanted ? "box" : "line");
        toggle.setText(wanted ? "Klein" : "Groß");
        toggle.setToolTipText(wanted ? "Zeigt das Feld wieder in einer Zeile"
                : "Öffnet das Feld als große Textbox für einen langen Text");
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
