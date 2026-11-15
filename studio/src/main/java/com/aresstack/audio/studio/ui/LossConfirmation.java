package com.aresstack.audio.studio.ui;

import java.awt.BorderLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * What a proposal takes from the original and cannot give back (notes an insertion has to
 * shorten), listed line by line, with the tick by which the user says they saw every one. A
 * proposal with such losses is only taken after that tick (acceptance criterion of the PO).
 */
final class LossConfirmation extends JPanel {
    private final List<String> lines;
    private final JCheckBox seen;

    /**
     * @param losses  what is lost, one line each; at least one
     * @param changed called whenever the tick is set or taken away
     */
    LossConfirmation(List<String> losses, Runnable changed) {
        super(new BorderLayout(0, 8));
        lines = List.copyOf(losses);
        StringBuilder lost = new StringBuilder("<html><b>").append(lines.size() == 1
                ? "Diese Note des Originals lässt sich nicht in voller Länge erhalten:"
                : "Diese " + lines.size() + " Noten des Originals lassen sich nicht in voller Länge erhalten:")
                .append("</b>");
        for (String line : lines) {
            lost.append("<br>· ").append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
        }
        JLabel list = new JLabel(lost.append("</html>").toString());
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        if (lines.size() > 8) {
            scroll.setPreferredSize(new java.awt.Dimension(list.getPreferredSize().width + 24, 190));
        }
        seen = new JCheckBox(lines.size() == 1 ? "Ich habe die gekürzte Note gesehen und bin einverstanden"
                : "Ich habe die " + lines.size() + " gekürzten Noten gesehen und bin einverstanden");
        seen.addActionListener(event -> changed.run());
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(AudioStudioTheme.ACCENT, 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        add(scroll, BorderLayout.CENTER);
        add(seen, BorderLayout.SOUTH);
    }

    /** Whether the user ticked that they saw every line. */
    boolean confirmed() {
        return seen.isSelected();
    }

    /** The lines shown, every loss once. */
    List<String> lines() {
        return lines;
    }

    String question() {
        return seen.getText();
    }

    /** Sets or takes away the tick as a user would; for tests. */
    void tick(boolean ticked) {
        if (seen.isSelected() != ticked) {
            seen.doClick();
        }
    }
}
