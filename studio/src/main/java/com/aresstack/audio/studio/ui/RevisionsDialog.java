package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.edit.Revision;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

/**
 * The revisions of the piece: its original, every state an AI change was taken from and led to,
 * and the states the user asked to keep. One of them can be brought back; the piece as it is can
 * be kept as a new one.
 */
final class RevisionsDialog extends JDialog {
    /** What the dialog asks of the piece. */
    interface Piece {
        List<Revision> revisions() throws Exception;

        /** Keeps the piece as it is now as a revision with these words. */
        void mark(String description) throws Exception;
    }

    /** The rows of the list, the newest first. */
    static final class Rows extends AbstractTableModel {
        private static final String[] COLUMNS = {"Nr.", "Von", "Zeit", "Beschreibung"};
        private List<Revision> revisions = new ArrayList<Revision>();

        void show(List<Revision> oldestFirst) {
            revisions = new ArrayList<Revision>(oldestFirst);
            java.util.Collections.reverse(revisions);
            fireTableDataChanged();
        }

        Revision at(int row) {
            return row < 0 || row >= revisions.size() ? null : revisions.get(row);
        }

        @Override
        public int getRowCount() {
            return revisions.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Revision revision = revisions.get(row);
            switch (column) {
                case 0:
                    return revision.sequence();
                case 1:
                    return author(revision.author());
                case 2:
                    return new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(revision.timeMillis()));
                default:
                    return revision.description();
            }
        }

        static String author(Revision.Author author) {
            switch (author) {
                case AI:
                    return "KI";
                case ORIGINAL:
                    return "Original";
                default:
                    return "Du";
            }
        }
    }

    private final Rows rows = new Rows();
    private final JTable table = new JTable(rows);
    private final Piece piece;
    private Revision chosen;

    RevisionsDialog(Window owner, Piece piece) {
        super(owner, "Revisionen", ModalityType.APPLICATION_MODAL);
        this.piece = piece;
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setMaxWidth(80);
        table.getColumnModel().getColumn(2).setMinWidth(150);
        table.getColumnModel().getColumn(2).setMaxWidth(170);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(640, 300));
        JButton restore = new JButton("Wiederherstellen");
        restore.setToolTipText("Das Stück so zurückholen, wie es in der gewählten Revision war; "
                + "das lässt sich rückgängig machen");
        JButton mark = new JButton("Stand merken …");
        mark.setToolTipText("Das Stück, wie es jetzt ist, als Revision festhalten");
        JButton close = new JButton("Schließen");
        restore.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(
                event -> restore.setEnabled(rows.at(table.getSelectedRow()) != null));
        restore.addActionListener(event -> {
            chosen = rows.at(table.getSelectedRow());
            dispose();
        });
        mark.addActionListener(event -> {
            String words = JOptionPane.showInputDialog(this, "Wie soll dieser Stand heißen?", "Stand merken",
                    JOptionPane.PLAIN_MESSAGE);
            if (words != null && !words.trim().isEmpty()) {
                mark(words.trim());
            }
        });
        close.addActionListener(event -> dispose());
        JPanel keys = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        keys.add(mark);
        keys.add(restore);
        keys.add(close);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 12, 14));
        content.add(new JLabel("Die neueste Revision steht oben; Nr. 1 ist die Originalfassung."), BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(keys, BorderLayout.SOUTH);
        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        refresh();
        pack();
        setLocationRelativeTo(owner);
    }

    void mark(String words) {
        try {
            piece.mark(words);
            refresh();
        } catch (Exception failed) {
            JOptionPane.showMessageDialog(this, "Der Stand ließ sich nicht festhalten: " + failed.getMessage(),
                    "Revisionen", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void refresh() {
        try {
            rows.show(piece.revisions());
        } catch (Exception failed) {
            rows.show(new ArrayList<Revision>());
            JOptionPane.showMessageDialog(this, "Die Revisionen ließen sich nicht lesen: " + failed.getMessage(),
                    "Revisionen", JOptionPane.WARNING_MESSAGE);
        }
    }

    Rows rows() {
        return rows;
    }

    /** Shows the dialog and waits; returns the revision to bring back, or {@code null}. */
    Revision ask() {
        setVisible(true);
        return chosen;
    }
}
