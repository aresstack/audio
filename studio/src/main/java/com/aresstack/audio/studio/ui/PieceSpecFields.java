package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.application.CompositionSpec;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * What a new piece is to be, beside the words for the AI: how many bars, how fast, in which key
 * and meter, and whether pitches are pulled onto the harmony. A piece that is planned already
 * shows what it was planned with and cannot be changed here: other values are a new piece.
 */
final class PieceSpecFields extends JPanel {
    private final JSpinner bars = new JSpinner(new SpinnerNumberModel(8, 1, CompositionSpec.MAX_BARS, 1));
    private final JSpinner bpm = new JSpinner(new SpinnerNumberModel(108, CompositionSpec.MIN_BPM,
            CompositionSpec.MAX_BPM, 1));
    private final JComboBox<Key> key = new JComboBox<Key>(Key.all().toArray(new Key[0]));
    private final JSpinner numerator = new JSpinner(new SpinnerNumberModel(4, 1, 32, 1));
    private final JComboBox<Integer> denominator = new JComboBox<Integer>(new Integer[]{1, 2, 4, 8, 16});
    private final JCheckBox snap = new JCheckBox("auf Harmonie ziehen", true);
    private static final String SNAP_HINT = "Töne, die nicht zur Tonart und zum Akkord des Takts passen,"
            + " werden beim Komponieren auf den nächsten passenden Ton gezogen";
    private final JLabel planned = new JLabel(" ");
    private boolean locked;

    PieceSpecFields() {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        setOpaque(false);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 0, 0, 0));
        key.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focused) {
                return super.getListCellRendererComponent(list, value instanceof Key ? ((Key) value).germanName() : value,
                        index, selected, focused);
            }
        });
        key.setMaximumRowCount(12);
        snap.setOpaque(false);
        snap.setToolTipText("An: Töne außerhalb von Tonart und Akkord des Takts werden auf die Harmonie gezogen."
                + " Aus: Sie bleiben stehen und werden nur gemeldet.");
        bars.setToolTipText("Länge des Stücks, 1 bis " + CompositionSpec.MAX_BARS + " Takte");
        bpm.setToolTipText("Viertel pro Minute, " + CompositionSpec.MIN_BPM + " bis " + CompositionSpec.MAX_BPM);
        planned.setForeground(AudioStudioTheme.TEXT_DIM);
        width(bars, 76);
        width(bpm, 76);
        width(numerator, 58);
        width(denominator, 62);
        width(key, 108);
        field("Takte", bars);
        field("Tempo", bpm);
        field("Tonart", key);
        add(DeckWidgets.lettering("Taktart"));
        add(Box.createHorizontalStrut(6));
        add(numerator);
        add(Box.createHorizontalStrut(4));
        add(new JLabel("/"));
        add(Box.createHorizontalStrut(4));
        add(denominator);
        add(Box.createHorizontalStrut(16));
        add(snap);
        add(Box.createHorizontalStrut(16));
        add(planned);
        show(null);
        choose(CompositionSpec.legacy());
    }

    private static void width(Component field, int width) {
        field.setPreferredSize(new Dimension(width, 30));
    }

    private void field(String name, Component field) {
        add(DeckWidgets.lettering(name));
        add(Box.createHorizontalStrut(6));
        add(field);
        add(Box.createHorizontalStrut(16));
    }

    /**
     * What the fields say. A value that is still being typed counts as typed; one that is out of
     * range is refused with the words of {@link CompositionSpec}.
     */
    CompositionSpec spec() {
        try {
            bars.commitEdit();
            bpm.commitEdit();
            numerator.commitEdit();
        } catch (java.text.ParseException mistyped) {
            throw new IllegalArgumentException("Takte, Tempo und Taktart sind ganze Zahlen.");
        }
        return CompositionSpec.of((Integer) bars.getValue(), (Integer) bpm.getValue(), (Key) key.getSelectedItem(),
                (Integer) numerator.getValue(), (Integer) denominator.getSelectedItem())
                .withSnapToHarmony(snap.isSelected());
    }

    /** Sets the fields to a spec. */
    void choose(CompositionSpec spec) {
        bars.setValue(spec.bars());
        bpm.setValue(spec.bpm());
        key.setSelectedItem(spec.key());
        numerator.setValue(spec.numerator());
        denominator.setSelectedItem(spec.denominator());
        snap.setSelected(spec.snapToHarmony());
    }

    /**
     * Shows the piece that is open: one that is planned shows what it was planned with and locks
     * the fields; one that is not ({@code null}) leaves them free, with what they said last.
     */
    void show(CompositionSpec ofPlannedPiece) {
        locked = ofPlannedPiece != null;
        if (locked) {
            choose(ofPlannedPiece);
        }
        for (Component field : new Component[]{bars, bpm, key, numerator, denominator, snap}) {
            field.setEnabled(!locked);
        }
        planned.setText(locked ? "Gesperrt: Das Stück ist mit diesen Vorgaben geplant" : " ");
        String why = locked ? "Dieses Stück ist mit diesen Vorgaben geplant. Für andere Vorgaben ein neues Stück beginnen"
                + " oder beim Komponieren „Neues Stück“ oder „Dieses ersetzen“ wählen." : null;
        planned.setToolTipText(why);
        setToolTipText(why);
        // A disabled field shows no tooltip of the panel behind it: each one says why it is locked.
        for (javax.swing.JComponent field : new javax.swing.JComponent[]{bars, bpm, key, numerator, denominator}) {
            field.setToolTipText(why);
        }
        snap.setToolTipText(locked ? why : SNAP_HINT);
    }

    /** Whether the fields show a planned piece and cannot be changed. */
    boolean locked() {
        return locked;
    }
}
