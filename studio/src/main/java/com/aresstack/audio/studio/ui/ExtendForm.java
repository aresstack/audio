package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.studio.ai.ContinuationEnding;
import com.aresstack.audio.studio.ai.ContinuationPreset;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.IntensityCurve;
import com.aresstack.audio.studio.ai.TransitionMode;
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
 * Asks how the AI shall make the piece longer, or (work package C4) which bars it shall put into
 * it: how many bars, of which kind, what they shall be in the user's words, how the piece ends,
 * whether the bars before may be revised for the transition, which voices go on and how many new
 * instruments may come in. The piece is not touched here; the answer is a request.
 */
final class ExtendForm extends JPanel {
    /** The intensity curves offered, in the order of the list; the first leaves it to the AI. */
    private static final String[] CURVE_NAMES = {"der KI überlassen", "ansteigend", "abfallend",
            "Bogen: aufbauen und zurücknehmen", "gleichbleibend ruhig", "gleichbleibend kräftig"};

    private final boolean inserting;
    private final List<Part> parts;
    private final JSpinner before;
    private final JSpinner bars = new JSpinner(new SpinnerNumberModel(
            com.aresstack.audio.studio.settings.AiDefaults.PIECE_BARS, 1, ContinuationRequest.MAX_BARS, 1));
    private final JComboBox<String> preset = new JComboBox<String>();
    private final JTextArea instruction = new JTextArea(3, 30);
    private final JComboBox<String> ending = new JComboBox<String>();
    private final JComboBox<String> transition = new JComboBox<String>();
    private final JSpinner tailBars = new JSpinner(new SpinnerNumberModel(ContinuationRequest.DEFAULT_TAIL_BARS, 1,
            ContinuationRequest.MAX_TAIL_BARS, 1));
    private final JList<String> voices;
    private final JSpinner newParts = new JSpinner(new SpinnerNumberModel(0, 0, ContinuationRequest.MAX_NEW_PARTS, 1));
    private final JComboBox<String> curve = new JComboBox<String>(CURVE_NAMES);
    private final JLabel problem = new JLabel(" ");
    /** "um … Takte" (one step) or "auf insgesamt … Minuten" (as many steps as that takes). */
    private final javax.swing.JRadioButton byBars = new javax.swing.JRadioButton("um", true);
    private final javax.swing.JRadioButton toTotal = new javax.swing.JRadioButton("auf insgesamt");
    private final JSpinner totalMinutes = new JSpinner(new javax.swing.SpinnerNumberModel(30.0, 0.5,
            com.aresstack.audio.studio.album.AlbumConcept.MAX_SECONDS / 60.0, 0.5));
    private final Composition piece;
    /** What the user asked for once the form is closed with the key; {@code null} if they gave up. */
    private Extension asked;
    /** Whether the number of new instruments still follows the preset; a number the user set stays. */
    private boolean newPartsFollow = true;
    private boolean settingNewParts;

    /**
     * @param pieceBars how many bars the piece has now
     * @param insertAt  the bar before which new bars are inserted, from 1; 0 to make the piece longer
     */
    ExtendForm(Composition piece, int pieceBars, int insertAt) {
        super(new GridBagLayout());
        inserting = insertAt > 0;
        this.piece = piece;
        parts = piece.parts();
        int last = Math.max(1, pieceBars);
        before = new JSpinner(new SpinnerNumberModel(Math.max(1, Math.min(last, insertAt)), 1, last, 1));
        for (ContinuationPreset kind : ContinuationPreset.values()) {
            preset.addItem(kind.label());
        }
        preset.setSelectedIndex(ContinuationPreset.STANDALONE.ordinal());
        for (ContinuationEnding end : ContinuationEnding.values()) {
            ending.addItem(end.label());
        }
        // Open unless the user says otherwise: an ending is chosen on purpose, never by leaving the form as it is.
        ending.setSelectedIndex(ContinuationEnding.OPEN.ordinal());
        for (TransitionMode mode : TransitionMode.values()) {
            transition.addItem(mode.label());
        }
        String[] names = new String[parts.size()];
        for (int i = 0; i < names.length; i++) {
            Part part = parts.get(i);
            names[i] = part.name() == null || part.name().isBlank() ? part.id() : part.name();
        }
        voices = new JList<String>(names);
        voices.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        voices.setVisibleRowCount(Math.max(2, Math.min(5, names.length)));
        voices.setToolTipText("Ohne Auswahl spielen alle Stimmen weiter. Mehrere mit Strg oder Umschalt wählen.");
        if (inserting) {
            bars.setValue(4);
        }
        tailBars.setEnabled(false);
        transition.addActionListener(event -> tailBars.setEnabled(transition.getSelectedIndex()
                == TransitionMode.REVISE_TAIL.ordinal()));
        transition.setToolTipText(inserting
                ? "Ob die Takte vor der Einfügestelle für den Übergang verändert werden dürfen"
                : "Ob die letzten Takte des Stücks für den Übergang verändert werden dürfen");
        preset.addActionListener(event -> followPreset());
        newParts.addChangeListener(event -> {
            if (!settingNewParts) {
                newPartsFollow = false;
            }
        });
        followPreset();
        instruction.setLineWrap(true);
        instruction.setWrapStyleWord(true);
        problem.setForeground(AudioStudioTheme.ACCENT);

        JPanel tail = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tail.setOpaque(false);
        tail.add(transition);
        tail.add(new JLabel("Takte:"));
        tail.add(tailBars);
        int row = 0;
        if (inserting) {
            row = line(this, row, "Vor Takt", before);
        }
        if (inserting) {
            row = line(this, row, "Neue Takte", bars);
        } else {
            // One step of the given bars, or as many steps as it takes to a whole length, like the
            // first composition of a long piece.
            javax.swing.ButtonGroup how = new javax.swing.ButtonGroup();
            how.add(byBars);
            how.add(toTotal);
            double now = piece.timeMap().seconds(piece.endTick()) / 60.0;
            totalMinutes.setValue(Math.max(30.0, Math.ceil(now * 2 + 1) / 2));
            toTotal.setToolTipText("Verlängert in so vielen Schritten (je „Takte je Schritt“ aus den Einstellungen),"
                    + " bis das Stück so lang ist; nur der letzte Schritt bekommt das gewählte Ende");
            JPanel length = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            length.setOpaque(false);
            length.add(byBars);
            length.add(bars);
            length.add(new JLabel("Takte   oder"));
            length.add(toTotal);
            length.add(totalMinutes);
            length.add(new JLabel("Minuten"));
            row = line(this, row, "Verlängern", length);
        }
        row = line(this, row, "Art", preset);
        JScrollPane words = new JScrollPane(instruction);
        words.setPreferredSize(new Dimension(360, 70));
        row = line(this, row, "Anweisung", words);
        if (!inserting) {
            row = line(this, row, "Ende", ending);
        }
        row = line(this, row, "Übergang", tail);
        row = line(this, row, "Stimmen", new JScrollPane(voices));
        row = line(this, row, "Neue Instrumente", newParts);
        row = line(this, row, "Intensität", curve);
        line(this, row, "", problem);
    }

    private void followPreset() {
        if (newPartsFollow) {
            settingNewParts = true;
            newParts.setValue(ContinuationPreset.values()[preset.getSelectedIndex()].newPartsByDefault() ? 2 : 0);
            settingNewParts = false;
        }
    }

    /** The intensity curve of a row of the list; {@code null} for the first, which leaves it to the AI. */
    static IntensityCurve curveOf(int row) {
        switch (row) {
            case 1:
                return IntensityCurve.ramp(0.3, 0.9);
            case 2:
                return IntensityCurve.ramp(0.8, 0.3);
            case 3:
                return IntensityCurve.arch(0.3, 0.9, 0.4);
            case 4:
                return IntensityCurve.flat(0.3);
            case 5:
                return IntensityCurve.flat(0.8);
            default:
                return null;
        }
    }

    static String[] curveNames() {
        return CURVE_NAMES.clone();
    }

    /** Shows the form for a longer piece in a dialog and waits; the request, or {@code null} if the user gave up. */
    static Extension ask(Window owner, Composition piece, int pieceBars, int newBars) {
        ExtendForm form = new ExtendForm(piece, pieceBars, 0);
        form.bars.setValue(Math.max(1, Math.min(ContinuationRequest.MAX_BARS, newBars)));
        ask(owner, form, "Stück verlängern",
                "<html>Die KI plant die neuen Takte (Abschnitte, Akkorde, Besetzung) und schreibt sie dann. Die neuen"
                        + " Takte<br>kommen direkt ans Ende des Stücks; „Rückgängig“ nimmt sie wieder weg.</html>",
                "Verlängern lassen");
        return form.asked;
    }

    /** Shows the form for new bars within the piece (C4); the request, or {@code null} if the user gave up. */
    static ContinuationRequest askInsert(Window owner, Composition piece, int pieceBars, int beforeBar) {
        ExtendForm form = new ExtendForm(piece, pieceBars, Math.max(1, beforeBar));
        ask(owner, form, "Takte einfügen",
                "<html>Die KI schreibt neue Takte vor dem gewählten Takt; alles ab dort rückt unverändert nach"
                        + " hinten.<br>Die Takte kommen direkt ins Stück; „Rückgängig“ nimmt sie wieder heraus.</html>",
                "Einfügen lassen");
        return form.asked == null ? null : form.asked.request();
    }

    private static void ask(Window owner, final ExtendForm form, String title, String said, String go) {
        final JDialog dialog = new JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JButton compose = new JButton(go);
        JButton cancel = new JButton("Abbrechen");
        compose.addActionListener(event -> {
            try {
                ContinuationRequest request = form.read();
                form.asked = new Extension(request, form.totalBars());
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
        content.add(new JLabel(said), BorderLayout.NORTH);
        content.add(form, BorderLayout.CENTER);
        content.add(keys, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(compose);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * What the user asked for: one step ({@code totalBars} 0), or steps until the piece has
     * {@code totalBars} bars, each like {@code request} but for its bars.
     */
    record Extension(ContinuationRequest request, int totalBars) {
    }

    /** The bars the piece shall have in the end with "auf insgesamt"; 0 for one step of the bars given. */
    int totalBars() {
        if (inserting || !toTotal.isSelected()) {
            return 0;
        }
        double seconds = ((Number) totalMinutes.getValue()).doubleValue() * 60;
        int total = com.aresstack.audio.studio.application.PieceGrowth.barsLasting(piece, seconds);
        if (total <= piece.timeMap().barsCovering(piece.endTick())) {
            throw new IllegalArgumentException("Das Stück ist schon so lang; eine längere Gesamtlänge wählen.");
        }
        return total;
    }

    /** Chooses "auf insgesamt … Minuten"; for tests. */
    void chooseTotal(double minutes) {
        toTotal.setSelected(true);
        totalMinutes.setValue(minutes);
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

    /** What the fields ask for; refused with the words of {@link ContinuationRequest} if something is missing. */
    ContinuationRequest read() {
        List<String> chosen = new ArrayList<String>();
        for (int index : voices.getSelectedIndices()) {
            chosen.add(parts.get(index).id());
        }
        if (chosen.size() == parts.size()) {
            chosen.clear(); // every voice is the whole piece
        }
        boolean revise = transition.getSelectedIndex() == TransitionMode.REVISE_TAIL.ordinal();
        return new ContinuationRequest((Integer) bars.getValue(),
                ContinuationPreset.values()[preset.getSelectedIndex()], instruction.getText(),
                inserting ? ContinuationEnding.OPEN : ContinuationEnding.values()[ending.getSelectedIndex()],
                revise ? TransitionMode.REVISE_TAIL : TransitionMode.PROTECT_ORIGINAL,
                revise ? (Integer) tailBars.getValue() : 0, chosen, (Integer) newParts.getValue(),
                inserting ? (Integer) before.getValue() : 0, curveOf(curve.getSelectedIndex()));
    }

    /** Fills the fields as a user would; for tests. A negative number of new instruments leaves the field alone. */
    void choose(int barCount, ContinuationPreset kind, String words, ContinuationEnding end, int revisedBars,
                int[] voiceRows, int instruments, int curveRow) {
        bars.setValue(barCount);
        preset.setSelectedIndex(kind.ordinal());
        instruction.setText(words);
        ending.setSelectedIndex(end.ordinal());
        transition.setSelectedIndex(revisedBars > 0 ? TransitionMode.REVISE_TAIL.ordinal()
                : TransitionMode.PROTECT_ORIGINAL.ordinal());
        if (revisedBars > 0) {
            tailBars.setValue(revisedBars);
        }
        voices.setSelectedIndices(voiceRows);
        if (instruments >= 0) {
            newParts.setValue(instruments);
        }
        curve.setSelectedIndex(curveRow);
    }

    void insertBefore(int bar) {
        before.setValue(bar);
    }

    int newPartsShown() {
        return (Integer) newParts.getValue();
    }

    boolean tailBarsEnabled() {
        return tailBars.isEnabled();
    }
}
