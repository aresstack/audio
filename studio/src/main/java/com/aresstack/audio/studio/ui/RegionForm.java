package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.ai.RegionRequest;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;

/**
 * Asks how the AI shall write a marked passage anew: replace it or vary it, in which tracks and
 * bars, in which direction, and what the user wants in words. The passage marked in the lanes
 * fills the form; the piece is not touched here, the answer is a request.
 */
final class RegionForm extends JPanel {
    private static final String[] MODES = {"Variieren: die Passage bleibt erkennbar", "Ersetzen: neues Material"};
    private static final String[] PRESETS = {"ohne Vorgabe", "ruhiger", "lebhafter", "einfacher", "reicher"};

    private final Composition piece;
    private final JComboBox<String> mode = new JComboBox<String>(MODES);
    private final JList<String> tracks;
    private final JSpinner first;
    private final JSpinner last;
    private final JComboBox<String> preset = new JComboBox<String>(PRESETS);
    private final JTextArea instruction = new JTextArea(3, 30);
    private final JLabel problem = new JLabel(" ");

    /**
     * @param bars   the number of bars of the piece
     * @param marked the passage marked in the lanes, or {@code null}: then the first track over the whole piece
     */
    RegionForm(Composition piece, int bars, ArrangementView.Range marked) {
        super(new GridBagLayout());
        this.piece = piece;
        int most = Math.max(1, bars);
        String[] names = new String[piece.parts().size()];
        List<Integer> chosen = new ArrayList<Integer>();
        for (int i = 0; i < names.length; i++) {
            Part part = piece.parts().get(i);
            names[i] = part.name() == null || part.name().isBlank() ? part.id() : part.name();
            if (marked != null && marked.trackIds().contains(part.id())) {
                chosen.add(i);
            }
        }
        tracks = new JList<String>(names);
        tracks.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tracks.setVisibleRowCount(Math.max(2, Math.min(6, names.length)));
        tracks.setToolTipText("Mehrere mit Strg oder Umschalt wählen.");
        int[] rows = new int[chosen.size()];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = chosen.get(i);
        }
        tracks.setSelectedIndices(rows.length == 0 && names.length > 0 ? new int[]{0} : rows);
        int from = marked == null ? 1 : Math.max(1, Math.min(most, marked.firstBar()));
        int to = marked == null ? most : Math.max(from, Math.min(most, marked.lastBar()));
        first = new JSpinner(new SpinnerNumberModel(from, 1, most, 1));
        last = new JSpinner(new SpinnerNumberModel(to, 1, most, 1));
        mode.addActionListener(event -> preset.setEnabled(mode.getSelectedIndex() == 0));
        instruction.setLineWrap(true);
        instruction.setWrapStyleWord(true);
        problem.setForeground(AudioStudioTheme.ACCENT);

        JPanel barsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        barsRow.add(first);
        barsRow.add(new JLabel("bis"));
        barsRow.add(last);
        barsRow.add(new JLabel("von " + most));
        JScrollPane words = new JScrollPane(instruction);
        words.setPreferredSize(new Dimension(360, 70));
        int row = 0;
        row = line(row, "Art", mode);
        row = line(row, "Spuren", new JScrollPane(tracks));
        row = line(row, "Takte", barsRow);
        row = line(row, "Richtung", preset);
        row = line(row, "Anweisung", words);
        line(row, "", problem);
    }

    private int line(int row, String label, JComponent field) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.NORTHWEST;
        left.insets = new Insets(4, 0, 4, 12);
        add(new JLabel(label), left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(4, 0, 4, 0);
        add(field, right);
        return row + 1;
    }

    /** The parts chosen, in the order of the piece. */
    List<String> partIds() {
        List<String> ids = new ArrayList<String>();
        for (int index : tracks.getSelectedIndices()) {
            ids.add(piece.parts().get(index).id());
        }
        return ids;
    }

    int firstBar() {
        return (Integer) first.getValue();
    }

    int lastBar() {
        return (Integer) last.getValue();
    }

    /** What the fields ask for; refused in words the user can act on if the passage is none. */
    RegionRequest read() {
        List<String> ids = partIds();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Mindestens eine Spur wählen.");
        }
        if (lastBar() < firstBar()) {
            throw new IllegalArgumentException("Der letzte Takt liegt vor dem ersten.");
        }
        Selection selection = Selection.bars(piece, ids, firstBar(), lastBar());
        if (mode.getSelectedIndex() == 1) {
            return RegionRequest.replace(selection, instruction.getText());
        }
        return RegionRequest.variate(selection, RegionRequest.Preset.values()[preset.getSelectedIndex()],
                instruction.getText());
    }

    /** Fills the fields as a user would; for tests. */
    void choose(boolean replace, int[] trackRows, int firstBar, int lastBar, int presetIndex, String words) {
        mode.setSelectedIndex(replace ? 1 : 0);
        tracks.setSelectedIndices(trackRows);
        first.setValue(firstBar);
        last.setValue(lastBar);
        preset.setSelectedIndex(presetIndex);
        instruction.setText(words);
    }

    boolean presetOffered() {
        return preset.isEnabled();
    }

    /** What was asked for in the dialog: the request and the passage it is about. */
    record Asked(RegionRequest request, List<String> partIds, int firstBar, int lastBar) {
    }

    /** Shows the form in a dialog and waits; {@code null} if the user gave up. */
    static Asked ask(Window owner, Composition piece, int bars, ArrangementView.Range marked) {
        final JDialog dialog = new JDialog(owner, "Passage ersetzen oder variieren",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        final RegionForm form = new RegionForm(piece, bars, marked);
        final Asked[] asked = new Asked[1];
        JButton compose = new JButton("Schreiben lassen");
        JButton cancel = new JButton("Abbrechen");
        compose.addActionListener(event -> {
            try {
                asked[0] = new Asked(form.read(), form.partIds(), form.firstBar(), form.lastBar());
                dialog.dispose();
            } catch (RuntimeException invalid) {
                form.problem.setText(invalid.getMessage());
            }
        });
        cancel.addActionListener(event -> dialog.dispose());
        JPanel keys = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        keys.add(compose);
        keys.add(cancel);
        JLabel words = new JLabel("<html>Die KI schreibt nur die gewählten Spuren in den gewählten Takten neu; alles andere<br>"
                + "bleibt Note für Note stehen. Das Stück ändert sich erst, wenn der Vorschlag übernommen ist.<br>"
                + "Tipp: Eine Passage lässt sich im Arrangement mit Umschalt und Ziehen markieren.</html>");
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 14, 16));
        content.add(words, BorderLayout.NORTH);
        content.add(form, BorderLayout.CENTER);
        content.add(keys, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(compose);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return asked[0];
    }
}
