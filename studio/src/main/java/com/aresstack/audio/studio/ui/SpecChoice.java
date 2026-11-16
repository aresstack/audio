package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.application.CompositionSpec;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;

/**
 * The settings of a piece beside its description: length, tempo, key and meter, in one row. Each
 * says "automatisch" unless the user chooses or types a value: what the user sets is binding for
 * the AI; the rest it chooses from the description, and the field then shows what it chose. The
 * same fields serve a single piece (length in bars) and a title of an album (length in minutes).
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

    /** The entry that leaves a field to the AI; once the AI has chosen, it says what. */
    private static final class Auto {
        private String chosen;

        @Override
        public String toString() {
            return chosen == null ? "automatisch" : "auto · " + chosen;
        }
    }

    private final Length unit;
    private final Map<String, JComboBox<Object>> boxes = new LinkedHashMap<String, JComboBox<Object>>();
    private final Map<String, Auto> autos = new LinkedHashMap<String, Auto>();
    private final List<Runnable> listeners = new ArrayList<Runnable>();
    /** What the fields stand for, the automatic ones included: what was shown or chosen last. */
    private Values held;
    private boolean locked;
    private boolean quiet;

    SpecChoice(Length unit) {
        super(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        this.unit = unit;
        setOpaque(false);
        held = new Values(unit == Length.BARS ? 8 : 4, 108, Key.C_MAJOR, 4, 4);
        boolean bars = unit == Length.BARS;
        field(AlbumTrack.LENGTH, bars ? "Takte" : "Minuten", true,
                bars ? new Object[]{"16", "24", "32", "40", "48", "64", "80", "96", "128"}
                        : new Object[]{"1", "2", "3", "4", "5", "6", "8", "10"},
                bars ? "Länge des Stücks in Takten, 1 bis " + CompositionSpec.MAX_BARS + ". Automatisch: die KI wählt sie "
                        + "aus der Beschreibung. Eine Zahl wählen oder eintippen: sie gilt."
                        : "Länge des Titels in Minuten. Automatisch: die KI wählt sie. Eine Zahl wählen oder eintippen: "
                        + "sie gilt.");
        field(AlbumTrack.BPM, "Tempo", true, new Object[]{"60", "72", "84", "96", "108", "120", "132", "144", "160"},
                "Viertel pro Minute, " + CompositionSpec.MIN_BPM + " bis " + CompositionSpec.MAX_BPM
                        + ". Automatisch: die KI wählt das Tempo. Eine Zahl wählen oder eintippen: sie gilt.");
        field(AlbumTrack.KEY, "Tonart", false, Key.all().toArray(),
                "Automatisch: die KI wählt die Tonart. Eine Tonart wählen: sie gilt.");
        field(AlbumTrack.METER, "Takt", true, new Object[]{"4/4", "3/4", "2/4", "6/8", "9/8", "12/8", "5/4", "7/8", "2/2"},
                "Taktart. Automatisch: die KI wählt sie. Eine Taktart wählen oder eintippen (z. B. 5/8): sie gilt.");
        boxes.get(AlbumTrack.KEY).setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focused) {
                return super.getListCellRendererComponent(list, value instanceof Key ? ((Key) value).germanName()
                        : value, index, selected, focused);
            }
        });
    }

    private void field(String field, String name, boolean typed, Object[] offered, String meaning) {
        Auto auto = new Auto();
        JComboBox<Object> box = new JComboBox<Object>();
        box.addItem(auto);
        for (Object each : offered) {
            box.addItem(each);
        }
        box.setEditable(typed);
        box.setMaximumRowCount(13);
        box.setPreferredSize(new Dimension(132, 30));
        box.setToolTipText(meaning);
        box.addActionListener(event -> changed());
        javax.swing.JLabel label = new javax.swing.JLabel(name);
        label.setForeground(AudioStudioTheme.TEXT_DIM);
        label.setToolTipText(meaning);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, boxes.isEmpty() ? 0 : 18, 0, 8));
        add(label);
        add(box);
        autos.put(field, auto);
        boxes.put(field, box);
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

    /** What a field says: the entry "automatisch", a chosen entry, or what was typed. */
    private Object said(String field) {
        JComboBox<Object> box = boxes.get(field);
        Object item = box.isEditable() ? box.getEditor().getItem() : box.getSelectedItem();
        if (item instanceof String) {
            String text = ((String) item).strip();
            if (text.isEmpty() || text.toLowerCase(java.util.Locale.ROOT).startsWith("auto")) {
                return autos.get(field);
            }
            return text;
        }
        return item == null ? autos.get(field) : item;
    }

    /** The fields the user set, by the names planning knows them by ({@link AlbumTrack#BPM}, ...). */
    Set<String> fixed() {
        Set<String> set = new TreeSet<String>();
        for (String field : FIELDS) {
            if (!(said(field) instanceof Auto)) {
                set.add(field);
            }
        }
        return set;
    }

    /** What the fields stand for; a value that is typed counts as typed, one that cannot be is refused in words. */
    Values values() {
        double length = held.length();
        int bpm = held.bpm();
        Key key = held.key();
        int numerator = held.numerator();
        int denominator = held.denominator();
        Object said = said(AlbumTrack.LENGTH);
        if (!(said instanceof Auto)) {
            double least = unit == Length.BARS ? 1 : 0.25;
            double most = unit == Length.BARS ? CompositionSpec.MAX_BARS : 60;
            length = number(said, unit == Length.BARS ? "Takte" : "Minuten", least, most);
            if (unit == Length.BARS) {
                length = Math.rint(length);
            }
        }
        said = said(AlbumTrack.BPM);
        if (!(said instanceof Auto)) {
            bpm = (int) Math.rint(number(said, "Tempo", CompositionSpec.MIN_BPM, CompositionSpec.MAX_BPM));
        }
        said = said(AlbumTrack.KEY);
        if (said instanceof Key) {
            key = (Key) said;
        }
        said = said(AlbumTrack.METER);
        if (!(said instanceof Auto)) {
            String[] parts = said.toString().split("/");
            try {
                numerator = Integer.parseInt(parts[0].strip());
                denominator = Integer.parseInt(parts[1].strip());
            } catch (RuntimeException mistyped) {
                throw new IllegalArgumentException("Takt: zwei Zahlen wie 4/4 oder 6/8.");
            }
            if (parts.length != 2 || numerator < 1 || numerator > 32
                    || !List.of(1, 2, 4, 8, 16).contains(denominator)) {
                throw new IllegalArgumentException("Takt: 1 bis 32 Schläge auf Ganze, Halbe, Viertel, Achtel oder "
                        + "Sechzehntel, z. B. 4/4 oder 6/8.");
            }
        }
        return new Values(length, bpm, key, numerator, denominator);
    }

    private static double number(Object said, String name, double least, double most) {
        double value;
        try {
            value = Double.parseDouble(said.toString().strip().replace(',', '.'));
        } catch (NumberFormatException mistyped) {
            throw new IllegalArgumentException(name + ": eine Zahl von " + plain(least) + " bis " + plain(most) + ".");
        }
        if (value < least || value > most) {
            throw new IllegalArgumentException(name + ": " + plain(least) + " bis " + plain(most) + ".");
        }
        return value;
    }

    /** A number as the fields write it: whole where it is whole, with a comma otherwise. */
    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 100) / 100.0).replace('.', ',');
    }

    /** How a value stands in its field. */
    private Object shown(String field, Values values) {
        switch (field) {
            case AlbumTrack.LENGTH:
                return plain(unit == Length.BARS ? Math.max(1, Math.rint(values.length())) : values.length());
            case AlbumTrack.BPM:
                return String.valueOf(values.bpm());
            case AlbumTrack.KEY:
                return values.key();
            default:
                return values.numerator() + "/" + values.denominator();
        }
    }

    /** Shows these values; the fields named in {@code fixed} as set by the user, the others as automatic. */
    void show(Values values, Set<String> fixed) {
        quiet = true;
        try {
            held = values;
            for (String field : FIELDS) {
                Auto auto = autos.get(field);
                auto.chosen = null;
                put(field, fixed.contains(field) ? shown(field, values) : auto);
            }
        } finally {
            quiet = false;
        }
    }

    private void put(String field, Object item) {
        JComboBox<Object> box = boxes.get(field);
        box.setSelectedItem(item);
        if (box.isEditable()) {
            box.getEditor().setItem(item); // also when the entry is the same and only its words changed
        }
        box.repaint();
    }

    /** Shows what the AI chose in the automatic fields; the fields the user set stay. */
    void showChosen(Values values) {
        quiet = true;
        try {
            Set<String> set = fixed();
            Values now;
            try {
                now = values();
            } catch (IllegalArgumentException mistyped) {
                now = held;
            }
            held = new Values(set.contains(AlbumTrack.LENGTH) ? now.length() : values.length(),
                    set.contains(AlbumTrack.BPM) ? now.bpm() : values.bpm(),
                    set.contains(AlbumTrack.KEY) ? now.key() : values.key(),
                    set.contains(AlbumTrack.METER) ? now.numerator() : values.numerator(),
                    set.contains(AlbumTrack.METER) ? now.denominator() : values.denominator());
            for (String field : FIELDS) {
                if (!set.contains(field)) {
                    Auto auto = autos.get(field);
                    Object chosen = shown(field, held);
                    auto.chosen = chosen instanceof Key ? ((Key) chosen).germanName() : chosen.toString();
                    put(field, auto);
                }
            }
        } finally {
            quiet = false;
        }
    }

    /** A planned piece cannot be changed here: every field is locked. */
    void lock(boolean lockedNow) {
        locked = lockedNow;
        for (JComboBox<Object> box : boxes.values()) {
            box.setEnabled(!locked);
        }
    }

    /** Sets the length while it is automatic (a suggestion that does not count as the user's). */
    void suggestLength(double suggested) {
        if (!locked && said(AlbumTrack.LENGTH) instanceof Auto) {
            held = new Values(suggested, held.bpm(), held.key(), held.numerator(), held.denominator());
        }
    }
}
