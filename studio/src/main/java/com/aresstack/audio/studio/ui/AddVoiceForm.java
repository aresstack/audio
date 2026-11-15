package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.studio.ai.AddVoiceRequest;
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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

/**
 * Asks what voice the AI shall compose into the piece: its name, instrument and role, the
 * sections it plays in, what it shall do, whether it takes up the motifs of another voice, and
 * whether the AI plans first. The piece is not touched here; the answer is a request.
 * {@link #ask} shows the form in a dialog.
 */
final class AddVoiceForm extends JPanel {
    private static final String[] ROLE_NAMES = {"Melodie", "Harmonie", "Bass", "Schlagzeug", "Klangfläche"};
    private static final String[] MOTIF_USES = {"frei erfinden", "Motiv aufgreifen (zitieren)", "Motiv variieren"};

    private final List<Section> sections;
    private final List<Part> parts;
    private final JTextField name = new JTextField(18);
    private final JComboBox<String> instrument = new JComboBox<String>(GeneralMidi.entries());
    private final JComboBox<String> role = new JComboBox<String>(ROLE_NAMES);
    private final JList<String> where;
    private final JTextArea instruction = new JTextArea(3, 30);
    private final JComboBox<String> motifSource = new JComboBox<String>();
    private final JComboBox<String> motifUse = new JComboBox<String>(MOTIF_USES);
    private final JCheckBox plan = new JCheckBox("Die KI plant zuerst, wo und wie die Stimme spielt", true);
    private final JLabel problem = new JLabel(" ");
    /** Whether the name still follows the instrument; a name typed by the user stays. */
    private boolean nameFollows = true;

    AddVoiceForm(Composition piece) {
        super(new GridBagLayout());
        sections = piece.sections();
        parts = piece.parts();
        String[] names = new String[sections.size()];
        for (int i = 0; i < names.length; i++) {
            Section section = sections.get(i);
            names[i] = section.name() == null || section.name().isBlank() ? "Abschnitt " + (i + 1) : section.name();
        }
        where = new JList<String>(names);
        where.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        where.setVisibleRowCount(Math.max(2, Math.min(5, names.length)));
        where.setToolTipText("Ohne Auswahl spielt die Stimme im ganzen Stück. Mehrere mit Strg oder Umschalt wählen.");
        motifSource.addItem("keine");
        for (Part part : parts) {
            motifSource.addItem(part.name() == null || part.name().isBlank() ? part.id() : part.name());
        }
        instrument.setSelectedIndex(56); // a trumpet: a voice that is easy to hear in a first try
        instrument.setMaximumRowCount(14);
        name.setText(GeneralMidi.name(56));
        instrument.addActionListener(event -> {
            if (nameFollows) {
                name.setText(GeneralMidi.name(instrument.getSelectedIndex()));
                nameFollows = true;
            }
        });
        name.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                typed();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                typed();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                typed();
            }

            private void typed() {
                nameFollows = name.getText().equals(GeneralMidi.name(instrument.getSelectedIndex()));
            }
        });
        motifSource.addActionListener(event -> motifUse.setEnabled(motifSource.getSelectedIndex() > 0));
        motifUse.setEnabled(false);
        motifUse.setSelectedIndex(2);
        instruction.setLineWrap(true);
        instruction.setWrapStyleWord(true);
        problem.setForeground(AudioStudioTheme.ACCENT);

        JPanel form = this;
        int row = 0;
        row = line(form, row, "Instrument", instrument);
        row = line(form, row, "Name", name);
        row = line(form, row, "Rolle", role);
        row = line(form, row, "Abschnitte", new JScrollPane(where));
        JScrollPane words = new JScrollPane(instruction);
        words.setPreferredSize(new Dimension(360, 70));
        row = line(form, row, "Anweisung", words);
        row = line(form, row, "Motiv von", motifSource);
        row = line(form, row, "Umgang", motifUse);
        row = line(form, row, "", plan);
        line(form, row, "", problem);
    }

    /** Shows the form in a dialog and waits; the request, or {@code null} if the user gave up. */
    static AddVoiceRequest ask(Window owner, Composition piece) {
        final JDialog dialog = new JDialog(owner, "Stimme hinzukomponieren", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        final AddVoiceForm form = new AddVoiceForm(piece);
        final AddVoiceRequest[] request = new AddVoiceRequest[1];
        JButton compose = new JButton("Komponieren lassen");
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
        JLabel words = new JLabel("<html>Die KI schreibt eine neue Stimme zu dem, was die anderen spielen. Sie kommt<br>"
                + "direkt ins Stück; „Rückgängig“ nimmt sie wieder heraus.</html>");
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

    /** What the fields ask for; refused with the words of {@link AddVoiceRequest} if something is missing. */
    AddVoiceRequest read() {
        List<String> chosen = new ArrayList<String>();
        for (int index : where.getSelectedIndices()) {
            chosen.add(sections.get(index).id());
        }
        if (chosen.size() == sections.size()) {
            chosen.clear(); // every section is the whole piece
        }
        int source = motifSource.getSelectedIndex();
        AddVoiceRequest.MotifUse use = source <= 0 ? AddVoiceRequest.MotifUse.FREE
                : AddVoiceRequest.MotifUse.values()[motifUse.getSelectedIndex()];
        return new AddVoiceRequest(name.getText().trim(), GeneralMidi.name(instrument.getSelectedIndex()),
                instrument.getSelectedIndex(), Role.ALL.get(role.getSelectedIndex()), null, chosen,
                instruction.getText(), use == AddVoiceRequest.MotifUse.FREE ? null : parts.get(source - 1).id(), use,
                plan.isSelected());
    }

    /** Fills the fields as a user would; for tests. */
    void choose(String voiceName, int program, int roleIndex, int[] sectionRows, String words, int motifPart,
                int use, boolean planFirst) {
        instrument.setSelectedIndex(program);
        if (voiceName != null) {
            name.setText(voiceName);
        }
        role.setSelectedIndex(roleIndex);
        where.setSelectedIndices(sectionRows);
        instruction.setText(words);
        motifSource.setSelectedIndex(motifPart + 1);
        motifUse.setSelectedIndex(use);
        plan.setSelected(planFirst);
    }

    String nameShown() {
        return name.getText();
    }
}
