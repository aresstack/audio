package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.OrchestrationPreset;
import com.aresstack.audio.studio.ai.ReorchestrationRequest;
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
 * Asks how the AI shall orchestrate a passage anew (work package C4): which bars, which voices
 * give and take material, in which direction, in the user's words, how many new instruments may
 * come in and after which intensity. The piece is not touched here; the answer is a request.
 */
final class ReorchestrateForm extends JPanel {
    private final List<Part> parts;
    private final JSpinner first;
    private final JSpinner last;
    private final JList<String> voices;
    private final JComboBox<String> preset = new JComboBox<String>();
    private final JTextArea instruction = new JTextArea(3, 30);
    private final JSpinner newParts = new JSpinner(new SpinnerNumberModel(0, 0, ContinuationRequest.MAX_NEW_PARTS, 1));
    private final JComboBox<String> curve = new JComboBox<String>(ExtendForm.curveNames());
    private final JLabel problem = new JLabel(" ");

    /**
     * @param pieceBars how many bars the piece has
     * @param marked    the passage marked in the lanes, or {@code null}: then the whole piece
     */
    ReorchestrateForm(Composition piece, int pieceBars, ArrangementView.Range marked) {
        super(new GridBagLayout());
        parts = piece.parts();
        int bars = Math.max(1, pieceBars);
        int from = marked == null ? 1 : Math.max(1, Math.min(bars, marked.firstBar()));
        int to = marked == null ? bars : Math.max(from, Math.min(bars, marked.lastBar()));
        first = new JSpinner(new SpinnerNumberModel(from, 1, bars, 1));
        last = new JSpinner(new SpinnerNumberModel(to, 1, bars, 1));
        String[] names = new String[parts.size()];
        List<Integer> chosen = new ArrayList<Integer>();
        for (int i = 0; i < names.length; i++) {
            Part part = parts.get(i);
            names[i] = part.name() == null || part.name().isBlank() ? part.id() : part.name();
            if (marked != null && marked.trackIds().contains(part.id())) {
                chosen.add(i);
            }
        }
        voices = new JList<String>(names);
        voices.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        voices.setVisibleRowCount(Math.max(2, Math.min(5, names.length)));
        voices.setToolTipText("Ohne Auswahl wird das Material aller Stimmen neu verteilt. Mehrere mit Strg oder"
                + " Umschalt wählen.");
        if (chosen.size() < parts.size()) {
            voices.setSelectedIndices(chosen.stream().mapToInt(Integer::intValue).toArray());
        }
        for (OrchestrationPreset kind : OrchestrationPreset.values()) {
            preset.addItem(kind.label());
        }
        curve.setToolTipText("Mit einem Verlauf richtet sich die Besetzung nach der Intensität: leise Takte dünner,"
                + " kräftige voller");
        instruction.setLineWrap(true);
        instruction.setWrapStyleWord(true);
        problem.setForeground(AudioStudioTheme.ACCENT);

        JPanel range = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        range.setOpaque(false);
        range.add(first);
        range.add(new JLabel("bis"));
        range.add(last);
        int row = 0;
        row = line(this, row, "Takte", range);
        row = line(this, row, "Stimmen", new JScrollPane(voices));
        row = line(this, row, "Art", preset);
        JScrollPane words = new JScrollPane(instruction);
        words.setPreferredSize(new Dimension(360, 70));
        row = line(this, row, "Anweisung", words);
        row = line(this, row, "Neue Instrumente", newParts);
        row = line(this, row, "Intensität", curve);
        line(this, row, "", problem);
    }

    /** Shows the form in a dialog and waits; the request, or {@code null} if the user gave up. */
    static ReorchestrationRequest ask(Window owner, Composition piece, int pieceBars, ArrangementView.Range marked) {
        final JDialog dialog = new JDialog(owner, "Umorchestrieren", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        final ReorchestrateForm form = new ReorchestrateForm(piece, pieceBars, marked);
        final ReorchestrationRequest[] request = new ReorchestrationRequest[1];
        JButton compose = new JButton("Umorchestrieren lassen");
        JButton cancel = new JButton("Abbrechen");
        compose.addActionListener(event -> {
            try {
                request[0] = form.read();
                dialog.dispose();
            } catch (RuntimeException invalid) {
                form.problem.setText(invalid.getMessage());
            }
        });
        cancel.addActionListener(event -> dialog.dispose());
        JPanel keys = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        keys.add(compose);
        keys.add(cancel);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 14, 16));
        content.add(new JLabel("<html>Die KI verteilt das Material der gewählten Takte neu auf die Stimmen: wer"
                + " übernimmt was.<br>Das Stück bleibt unverändert, bis der Vorschlag angehört und übernommen"
                + " ist.</html>"), BorderLayout.NORTH);
        content.add(form, BorderLayout.CENTER);
        content.add(keys, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(compose);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return request[0];
    }

    private static int line(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.NORTHWEST;
        left.insets = new Insets(4, 0, 4, 12);
        form.add(new JLabel(label), left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(4, 0, 4, 0);
        form.add(field, right);
        return row + 1;
    }

    /** What the fields ask for; refused with the words of {@link ReorchestrationRequest} if something is wrong. */
    ReorchestrationRequest read() {
        List<String> chosen = new ArrayList<String>();
        for (int index : voices.getSelectedIndices()) {
            chosen.add(parts.get(index).id());
        }
        if (chosen.size() == parts.size()) {
            chosen.clear(); // every voice is all of them
        }
        return new ReorchestrationRequest((Integer) first.getValue(), (Integer) last.getValue(), chosen,
                OrchestrationPreset.values()[preset.getSelectedIndex()], instruction.getText(),
                (Integer) newParts.getValue(), ExtendForm.curveOf(curve.getSelectedIndex()));
    }

    /** Fills the fields as a user would; for tests. */
    void choose(int firstBar, int lastBar, int[] voiceRows, OrchestrationPreset kind, String words, int instruments,
                int curveRow) {
        first.setValue(firstBar);
        last.setValue(lastBar);
        voices.setSelectedIndices(voiceRows);
        preset.setSelectedIndex(kind.ordinal());
        instruction.setText(words);
        newParts.setValue(instruments);
        curve.setSelectedIndex(curveRow);
    }
}
