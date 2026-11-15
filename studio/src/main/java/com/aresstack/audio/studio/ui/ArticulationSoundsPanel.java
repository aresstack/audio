package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Articulation;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.studio.render.ArticulationSounds;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;

/**
 * Which samples of a sound bank play which articulation: for the normal sound and for each
 * articulation a keyswitch or a program of the bank, or nothing (then the note sounds through its
 * length and velocity alone, as on a General MIDI bank). Saved into the file beside the bank
 * ({@link ArticulationSounds#fileOf}), which the rendering reads; no dialog, the status says what
 * happened.
 */
public final class ArticulationSoundsPanel extends JPanel {
    private static final String NONE = "—";
    private static final String KEYSWITCH = "Keyswitch";
    private static final String PROGRAM = "Programm";

    private final Consumer<String> log;
    private final Rows rows = new Rows();
    private final JTable table = new JTable(rows);
    private final JSpinner velocity = new JSpinner(new SpinnerNumberModel(ArticulationSounds.DEFAULT_KEYSWITCH_VELOCITY,
            1, 127, 1));
    private final JSpinner lead = new JSpinner(new SpinnerNumberModel(ArticulationSounds.DEFAULT_KEYSWITCH_LEAD, 0,
            TimeMap.PPQ, 1));
    private final JButton save = new JButton("Speichern");
    private final JButton clear = new JButton("Zurücksetzen");
    private final JLabel title = new JLabel();
    private final JLabel status = new JLabel(" ");
    private Path bank;

    public ArticulationSoundsPanel(Consumer<String> log) {
        super(new BorderLayout(8, 8));
        this.log = log;
        setOpaque(false);
        table.setRowHeight(26);
        table.setShowVerticalLines(false);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setFont(AudioStudioTheme.lettering(10.5f));
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(
                new JComboBox<>(new String[]{NONE, KEYSWITCH, PROGRAM})));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        scroll.setPreferredSize(new java.awt.Dimension(420, 7 * 26 + 32));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(title, BorderLayout.NORTH);
        head.add(new JLabel("<html>Eine General-MIDI-Soundbank hat keine eigenen Samples für Staccato & Co.; "
                + "dort klingen Artikulationen über Länge und Anschlag. Hat die Soundbank sie (Keyswitches einer "
                + "SFZ-Bibliothek, ein Staccato-Preset einer SF2), hier eintragen, wie sie erreicht werden.</html>"),
                BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        settings.setOpaque(false);
        settings.add(new JLabel("Keyswitch-Anschlag"));
        settings.add(velocity);
        settings.add(new JLabel("Vorlauf (Ticks)"));
        settings.add(lead);
        south.add(settings, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        clear.addActionListener(event -> fill(ArticulationSounds.NONE));
        save.putClientProperty("JButton.buttonType", "default");
        save.addActionListener(event -> save());
        buttons.add(clear);
        buttons.add(save);
        south.add(buttons, BorderLayout.EAST);
        south.add(status, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
        display(null, null);
    }

    /** Shows the articulations of a sound bank; without an installed file nothing can be set. */
    public void display(InstrumentLibrary library, Path soundbank) {
        bank = soundbank;
        boolean usable = soundbank != null;
        title.setText(library == null ? "Keine Soundbank gewählt"
                : library.name() + (usable ? "" : " · erst installieren, dann lassen sich Artikulationen setzen"));
        table.setEnabled(usable);
        velocity.setEnabled(usable);
        lead.setEnabled(usable);
        save.setEnabled(usable);
        clear.setEnabled(usable);
        status.setText(" ");
        ArticulationSounds sounds = ArticulationSounds.NONE;
        if (usable) {
            try {
                sounds = ArticulationSounds.beside(soundbank);
            } catch (java.io.IOException unreadable) {
                status.setText("Die Datei der Artikulationen lässt sich nicht lesen: " + unreadable.getMessage());
            }
        }
        fill(sounds);
    }

    private void fill(ArticulationSounds sounds) {
        rows.fill(sounds);
        velocity.setValue(sounds.velocity());
        lead.setValue(sounds.leadTicks());
    }

    private void save() {
        if (bank == null) {
            return;
        }
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        try {
            ArticulationSounds sounds = new ArticulationSounds(rows.sounds(), rows.normal(),
                    ((Number) velocity.getValue()).intValue(), ((Number) lead.getValue()).intValue());
            sounds.saveBeside(bank);
            String said = sounds.isEmpty() ? "Keine Artikulations-Samples gesetzt; die Soundbank spielt wie bisher."
                    : "Gespeichert in " + ArticulationSounds.fileOf(bank).getFileName()
                    + "; beim nächsten Rendern wählen die Noten diese Samples.";
            status.setText(said);
            log.accept(said);
        } catch (Exception failed) {
            status.setText("Nicht gespeichert: " + failed.getMessage());
            log.accept("WARN Artikulationen nicht gespeichert: " + failed.getMessage());
        }
    }

    /** One row for the normal sound, then one per articulation: what reaches its samples. */
    private static final class Rows extends AbstractTableModel {
        private final List<Articulation> order = new ArrayList<>(List.of(Articulation.values()));
        private final String[] kinds = new String[order.size() + 1];
        private final Integer[] values = new Integer[order.size() + 1];

        void fill(ArticulationSounds sounds) {
            set(0, sounds.normal());
            for (int i = 0; i < order.size(); i++) {
                set(i + 1, sounds.of(order.get(i)));
            }
            fireTableDataChanged();
        }

        private void set(int row, ArticulationSounds.Sound sound) {
            kinds[row] = sound == null ? NONE : sound.kind() == ArticulationSounds.Kind.KEYSWITCH ? KEYSWITCH : PROGRAM;
            values[row] = sound == null ? null : sound.value();
        }

        ArticulationSounds.Sound normal() {
            return sound(0);
        }

        Map<Articulation, ArticulationSounds.Sound> sounds() {
            Map<Articulation, ArticulationSounds.Sound> sounds = new EnumMap<>(Articulation.class);
            for (int i = 0; i < order.size(); i++) {
                ArticulationSounds.Sound sound = sound(i + 1);
                if (sound != null) {
                    sounds.put(order.get(i), sound);
                }
            }
            return sounds;
        }

        private ArticulationSounds.Sound sound(int row) {
            if (NONE.equals(kinds[row]) || values[row] == null) {
                return null;
            }
            return new ArticulationSounds.Sound(KEYSWITCH.equals(kinds[row]) ? ArticulationSounds.Kind.KEYSWITCH
                    : ArticulationSounds.Kind.PROGRAM, values[row]);
        }

        @Override
        public int getRowCount() {
            return order.size() + 1;
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Spielweise";
                case 1 -> "Erreicht über";
                default -> "Taste / Programm (0–127)";
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 2 ? Integer.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column > 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return switch (column) {
                case 0 -> row == 0 ? "Normal (ohne Artikulation)" : order.get(row - 1).germanName();
                case 1 -> kinds[row];
                default -> values[row];
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 1) {
                kinds[row] = String.valueOf(value);
            } else if (column == 2) {
                values[row] = value == null ? null : Math.max(0, Math.min(127, ((Number) value).intValue()));
            }
            fireTableRowsUpdated(row, row);
        }
    }
}
