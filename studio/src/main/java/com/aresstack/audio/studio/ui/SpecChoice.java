package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.application.CompositionSpec;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The advanced settings of a piece: length, tempo, key and meter, each "automatisch" unless the
 * user sets it. What the user sets is binding for the AI; the rest it chooses from the description
 * and shows here. The same fields serve a single piece (length in bars) and a title of an album
 * (length in minutes).
 */
final class SpecChoice extends JPanel {
    /** How the length is given. */
    enum Length {
        BARS, MINUTES
    }

    /** What the fields show. */
    record Values(double length, int bpm, Key key, int numerator, int denominator) {
    }

    /** The fields offered here, by the names planning knows them by. */
    static final List<String> FIELDS = List.of(AlbumTrack.LENGTH, AlbumTrack.BPM, AlbumTrack.KEY, AlbumTrack.METER);

    private final Length unit;
    private final JSpinner length;
    private final JSpinner bpm = new JSpinner(new SpinnerNumberModel(108, CompositionSpec.MIN_BPM,
            CompositionSpec.MAX_BPM, 1));
    private final JComboBox<Key> key = new JComboBox<Key>(Key.all().toArray(new Key[0]));
    private final JSpinner numerator = new JSpinner(new SpinnerNumberModel(4, 1, 32, 1));
    private final JComboBox<Integer> denominator = new JComboBox<Integer>(new Integer[]{1, 2, 4, 8, 16});
    /** The switch "automatisch" of each field, by the name planning knows it by. */
    private final Map<String, JCheckBox> automatic = new LinkedHashMap<String, JCheckBox>();
    private final Map<String, List<JComponent>> fields = new LinkedHashMap<String, List<JComponent>>();
    private final List<Runnable> listeners = new ArrayList<Runnable>();
    private boolean locked;
    private boolean quiet;

    SpecChoice(Length unit) {
        super(new GridBagLayout());
        this.unit = unit;
        setOpaque(false);
        length = unit == Length.BARS
                ? new JSpinner(new SpinnerNumberModel(8, 1, CompositionSpec.MAX_BARS, 1))
                : new JSpinner(new SpinnerNumberModel(4.0, 0.25, 60.0, 0.25));
        length.setToolTipText(unit == Length.BARS ? "Länge des Stücks, 1 bis " + CompositionSpec.MAX_BARS + " Takte"
                : "Länge des Titels in Minuten");
        bpm.setToolTipText("Viertel pro Minute, " + CompositionSpec.MIN_BPM + " bis " + CompositionSpec.MAX_BPM);
        key.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focused) {
                return super.getListCellRendererComponent(list, value instanceof Key ? ((Key) value).germanName()
                        : value, index, selected, focused);
            }
        });
        key.setMaximumRowCount(12);
        width(length, 84);
        width(bpm, 84);
        width(key, 120);
        width(numerator, 58);
        width(denominator, 62);
        JPanel meter = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        meter.setOpaque(false);
        meter.add(numerator);
        meter.add(new javax.swing.JLabel("/"));
        meter.add(denominator);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 0, 2, 10);
        c.anchor = GridBagConstraints.WEST;
        add(c, 0, 0, unit == Length.BARS ? "Takte" : "Minuten", AlbumTrack.LENGTH, length, List.of(length));
        add(c, 0, 1, "Tempo", AlbumTrack.BPM, bpm, List.of(bpm));
        add(c, 1, 0, "Tonart", AlbumTrack.KEY, key, List.of(key));
        add(c, 1, 1, "Taktart", AlbumTrack.METER, meter, List.of(numerator, denominator));
        for (JComponent field : new JComponent[]{length, bpm, numerator}) {
            ((JSpinner) field).addChangeListener(event -> changed());
        }
        key.addActionListener(event -> changed());
        denominator.addActionListener(event -> changed());
        enableFields();
    }

    private void add(GridBagConstraints c, int column, int row, String name, String field, JComponent shown,
                     List<JComponent> parts) {
        JCheckBox auto = new JCheckBox("automatisch", true);
        auto.setOpaque(false);
        auto.setForeground(AudioStudioTheme.TEXT_DIM);
        auto.setToolTipText("An: die KI wählt " + name + " aus der Beschreibung. Aus: dein Wert gilt, die KI hält sich "
                + "daran.");
        auto.addActionListener(event -> {
            enableFields();
            changed();
        });
        automatic.put(field, auto);
        fields.put(field, parts);
        c.gridy = row;
        c.gridx = column * 3;
        add(DeckWidgets.lettering(name), c);
        c.gridx = column * 3 + 1;
        add(shown, c);
        c.gridx = column * 3 + 2;
        add(auto, c);
    }

    private static void width(Component field, int width) {
        field.setPreferredSize(new Dimension(width, 30));
    }

    private void enableFields() {
        for (Map.Entry<String, JCheckBox> each : automatic.entrySet()) {
            each.getValue().setEnabled(!locked);
            for (JComponent part : fields.get(each.getKey())) {
                part.setEnabled(!locked && !each.getValue().isSelected());
            }
        }
    }

    private void changed() {
        if (!quiet) {
            for (Runnable listener : listeners) {
                listener.run();
            }
        }
    }

    /** Hears every change the user makes. */
    void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** The fields the user set, by the names planning knows them by ({@link AlbumTrack#BPM}, ...). */
    Set<String> fixed() {
        Set<String> set = new TreeSet<String>();
        for (Map.Entry<String, JCheckBox> each : automatic.entrySet()) {
            if (!each.getValue().isSelected()) {
                set.add(each.getKey());
            }
        }
        return set;
    }

    /** What the fields show, typed values counting as typed. */
    Values values() {
        try {
            length.commitEdit();
            bpm.commitEdit();
            numerator.commitEdit();
        } catch (java.text.ParseException mistyped) {
            throw new IllegalArgumentException("Länge, Tempo und Taktart sind Zahlen.");
        }
        return new Values(((Number) length.getValue()).doubleValue(), (Integer) bpm.getValue(),
                (Key) key.getSelectedItem(), (Integer) numerator.getValue(), (Integer) denominator.getSelectedItem());
    }

    /** Shows these values; the fields named in {@code fixed} as set by the user, the others as automatic. */
    void show(Values values, Set<String> fixed) {
        quiet = true;
        try {
            showValues(values);
            for (Map.Entry<String, JCheckBox> each : automatic.entrySet()) {
                each.getValue().setSelected(!fixed.contains(each.getKey()));
            }
            enableFields();
        } finally {
            quiet = false;
        }
    }

    /** Shows what the AI chose in the automatic fields; the fields the user set stay. */
    void showChosen(Values values) {
        quiet = true;
        try {
            Values now = values();
            Set<String> set = fixed();
            showValues(new Values(set.contains(AlbumTrack.LENGTH) ? now.length() : values.length(),
                    set.contains(AlbumTrack.BPM) ? now.bpm() : values.bpm(),
                    set.contains(AlbumTrack.KEY) ? now.key() : values.key(),
                    set.contains(AlbumTrack.METER) ? now.numerator() : values.numerator(),
                    set.contains(AlbumTrack.METER) ? now.denominator() : values.denominator()));
        } finally {
            quiet = false;
        }
    }

    private void showValues(Values values) {
        if (unit == Length.BARS) {
            length.setValue((int) Math.max(1, Math.min(CompositionSpec.MAX_BARS, Math.round(values.length()))));
        } else {
            length.setValue(Math.max(0.25, Math.min(60.0, Math.round(values.length() * 4) / 4.0)));
        }
        bpm.setValue(Math.max(CompositionSpec.MIN_BPM, Math.min(CompositionSpec.MAX_BPM, values.bpm())));
        key.setSelectedItem(values.key());
        numerator.setValue(values.numerator());
        denominator.setSelectedItem(values.denominator());
    }

    /** A planned piece cannot be changed here: every field is locked. */
    void lock(boolean lockedNow) {
        locked = lockedNow;
        enableFields();
    }

    /** Sets the length while it is automatic (a suggestion that does not count as the user's). */
    void suggestLength(double suggested) {
        if (!locked && automatic.get(AlbumTrack.LENGTH).isSelected()) {
            quiet = true;
            try {
                Values now = values();
                showValues(new Values(suggested, now.bpm(), now.key(), now.numerator(), now.denominator()));
            } finally {
                quiet = false;
            }
        }
    }
}
