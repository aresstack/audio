package com.aresstack.audio.studio.ui;

import com.aresstack.audio.core.mix.ExportNormalization;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The choice beside the file dialog of an export: whether the written file is normalized, and to
 * what. Only a piece with the fixed level structure can be normalized; a piece in the
 * compatibility mode is brought to its peak as before, and the choice says so.
 */
final class ExportLevelChoice extends JPanel {
    /** The usual targets: streaming loudness, and a peak just below full level. */
    static final double USUAL_LOUDNESS = -16;
    static final double USUAL_PEAK = -1;

    private static final String NONE = "Keine";
    private static final String PEAK = "Spitze (dBFS)";
    private static final String LOUDNESS = "Lautheit (LUFS)";

    private final JComboBox<String> kind = new JComboBox<String>(new String[]{NONE, PEAK, LOUDNESS});
    private final SpinnerNumberModel peak = new SpinnerNumberModel(USUAL_PEAK, -60.0, 0.0, 0.5);
    private final SpinnerNumberModel loudness = new SpinnerNumberModel(USUAL_LOUDNESS, -60.0, 0.0, 0.5);
    private final JSpinner target = new JSpinner(peak);
    private final JLabel unit = new JLabel(" ");
    private final JLabel hint = new JLabel(" ");

    /**
     * @param fixedLevel whether the piece has the fixed level structure
     * @param last       what was chosen for the last export, or {@code null}
     */
    ExportLevelChoice(boolean fixedLevel, ExportNormalization last) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 4, 0);
        add(new JLabel("Normalisierung"), c);
        c.gridy = 1;
        add(kind, c);
        c.gridy = 2;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        add(target, c);
        c.gridx = 1;
        c.insets = new Insets(0, 6, 4, 0);
        add(unit, c);
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.insets = new Insets(4, 0, 0, 0);
        c.weighty = 1;
        c.anchor = GridBagConstraints.NORTHWEST;
        add(hint, c);

        ((JSpinner.DefaultEditor) target.getEditor()).getTextField().setColumns(5);
        kind.setToolTipText("Was mit der fertigen Datei am Ende geschieht; Balance und Dynamik bleiben, wie sie sind");
        if (last != null && last.kind() == ExportNormalization.Kind.PEAK) {
            peak.setValue(last.target());
            kind.setSelectedItem(PEAK);
        } else if (last != null && last.kind() == ExportNormalization.Kind.LOUDNESS) {
            loudness.setValue(last.target());
            kind.setSelectedItem(LOUDNESS);
        }
        kind.addActionListener(event -> refresh(fixedLevel));
        if (!fixedLevel) {
            kind.setSelectedItem(NONE);
            kind.setEnabled(false);
        }
        refresh(fixedLevel);
    }

    private void refresh(boolean fixedLevel) {
        Object chosen = kind.getSelectedItem();
        boolean aims = fixedLevel && !NONE.equals(chosen);
        target.setModel(LOUDNESS.equals(chosen) ? loudness : peak);
        ((JSpinner.DefaultEditor) target.getEditor()).getTextField().setColumns(5);
        target.setEnabled(aims);
        unit.setText(!aims ? " " : LOUDNESS.equals(chosen) ? "LUFS" : "dBFS");
        if (!fixedLevel) {
            hint.setText("<html>Dieses Stück wird wie bisher<br>voll ausgesteuert. Wählbar,<br>sobald es auf der Seite Bühne<br>"
                    + "auf festen Pegel umgestellt ist.</html>");
        } else if (LOUDNESS.equals(chosen)) {
            hint.setText("<html>Lautheit nach ITU-R BS.1770;<br>die Spitzen bleiben bei<br>höchstens −1 dBFS.</html>");
        } else if (PEAK.equals(chosen)) {
            hint.setText("<html>Der höchste Ausschlag der<br>Datei liegt genau hier.</html>");
        } else {
            hint.setText("<html>Die Datei wird so geschrieben,<br>wie sie aus dem Limiter kommt.</html>");
        }
    }

    /** The normalization that was chosen; none for a piece in the compatibility mode. */
    ExportNormalization chosen() {
        Object chosen = kind.getSelectedItem();
        if (!kind.isEnabled() || NONE.equals(chosen)) {
            return ExportNormalization.NONE;
        }
        return LOUDNESS.equals(chosen) ? ExportNormalization.loudness(loudness.getNumber().doubleValue())
                : ExportNormalization.peak(peak.getNumber().doubleValue());
    }

    /** Chooses as a user would; for tests. */
    void choose(ExportNormalization.Kind wanted, double value) {
        if (wanted == ExportNormalization.Kind.LOUDNESS) {
            kind.setSelectedItem(LOUDNESS);
            loudness.setValue(value);
        } else if (wanted == ExportNormalization.Kind.PEAK) {
            kind.setSelectedItem(PEAK);
            peak.setValue(value);
        } else {
            kind.setSelectedItem(NONE);
        }
    }
}
