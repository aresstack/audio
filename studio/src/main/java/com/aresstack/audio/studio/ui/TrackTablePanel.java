package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.application.MusicTrack;
import com.aresstack.audio.studio.application.TrackArrangement;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;

/** Edit track identity, musical role, GM program and installed sample bank in one table. */
public final class TrackTablePanel extends JPanel {
    private final InstrumentRegistry registry;
    private final Runnable onChanged;
    private final TrackTableModel model = new TrackTableModel();
    private final JTable table = new JTable(model);
    private final JButton addButton = new JButton("+ Spur hinzufügen");
    private boolean changing;

    public TrackTablePanel(InstrumentRegistry registry, Runnable onChanged) {
        super(new BorderLayout(4, 6));
        this.registry = registry;
        this.onChanged = onChanged;
        setBorder(BorderFactory.createTitledBorder("Instrumentierung / Arrangement"));
        table.setRowHeight(30);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(1).setCellEditor(new javax.swing.DefaultCellEditor(
                new JComboBox<String>(MusicTrack.ROLES.toArray(new String[0]))));
        table.getColumnModel().getColumn(5).setCellRenderer(new DeleteCellRenderer());
        table.getColumnModel().getColumn(5).setMaxWidth(52);
        table.getColumnModel().getColumn(3).setPreferredWidth(75);
        table.getColumnModel().getColumn(0).setPreferredWidth(95);
        table.getColumnModel().getColumn(2).setPreferredWidth(180);
        table.getColumnModel().getColumn(4).setPreferredWidth(220);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int column = table.columnAtPoint(event.getPoint());
                int row = table.rowAtPoint(event.getPoint());
                if (column == 5 && row >= 0) {
                    removeTrack(row);
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        addButton.addActionListener(event -> add());
        bottom.add(addButton);
        bottom.add(new JLabel("Rolle und Instrument bearbeiten · GM-Programm 0–127 · ✕ entfernt die Spur"));
        add(bottom, BorderLayout.SOUTH);
        refreshLibraries();
    }

    public void refreshLibraries() {
        JComboBox<String> libraries = new JComboBox<String>();
        libraries.addItem("Java-Synthese (integriert)");
        for (InstrumentLibrary library : registry.installedInstruments()) {
            libraries.addItem(library.name());
        }
        table.getColumnModel().getColumn(4).setCellEditor(
                new javax.swing.DefaultCellEditor(libraries));
        model.fireTableDataChanged();
    }

    public TrackArrangement arrangement() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            throw new IllegalArgumentException("Finish editing the current table cell.");
        }
        return currentArrangement();
    }

    /** Inspect committed edits without recursively ending the active JTable editor. */
    public TrackArrangement currentArrangement() {
        return new TrackArrangement(new ArrayList<MusicTrack>(model.tracks));
    }

    public void setEditingEnabled(boolean enabled) {
        table.setEnabled(enabled);
        addButton.setEnabled(enabled && model.tracks.size() < TrackArrangement.MAX_TRACKS);
    }

    public void setArrangement(TrackArrangement arrangement) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        changing = true;
        try {
            model.tracks.clear();
            model.tracks.addAll(arrangement.tracks());
            model.fireTableDataChanged();
            addButton.setEnabled(model.tracks.size() < TrackArrangement.MAX_TRACKS);
        } finally {
            changing = false;
        }
        changed();
    }

    private void add() {
        if (model.tracks.size() >= TrackArrangement.MAX_TRACKS) {
            JOptionPane.showMessageDialog(this,
                    "Maximum 16 MIDI channels (15 melodic channels and one drum channel).");
            return;
        }
        int sequence = 1;
        while (containsId("voice_" + sequence)) {
            sequence++;
        }
        MusicTrack track = new MusicTrack("voice_" + sequence, MusicTrack.TEXTURE,
                "Flute", 73, "builtin");
        model.tracks.add(track);
        int row = model.tracks.size() - 1;
        model.fireTableRowsInserted(row, row);
        addButton.setEnabled(model.tracks.size() < TrackArrangement.MAX_TRACKS);
        table.editCellAt(row, 2);
        changed();
    }

    private void removeTrack(int row) {
        if (row >= model.tracks.size()) {
            return;
        }
        model.tracks.remove(row);
        model.fireTableDataChanged();
        addButton.setEnabled(true);
        changed();
    }

    private boolean containsId(String id) {
        for (MusicTrack track : model.tracks) {
            if (track.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void changed() {
        if (!changing) {
            onChanged.run();
        }
    }

    private String displayName(String libraryId) {
        if ("builtin".equals(libraryId)) {
            return "Java-Synthese (integriert)";
        }
        for (InstrumentLibrary library : registry.installedInstruments()) {
            if (library.id().equals(libraryId)) {
                return library.name();
            }
        }
        return "Nicht installiert: " + libraryId;
    }

    private String findLibraryId(String display) {
        if ("Java-Synthese (integriert)".equals(display)) {
            return "builtin";
        }
        for (InstrumentLibrary library : registry.installedInstruments()) {
            if (library.name().equals(display)) {
                return library.id();
            }
        }
        throw new IllegalArgumentException("Unknown sample library: " + display);
    }

    private final class TrackTableModel extends AbstractTableModel {
        private final String[] columns = {
                "Spur-ID", "Rolle", "Instrument / Klangfarbe", "GM-Programm", "Soundbank", "✕"
        };
        private final List<MusicTrack> tracks = new ArrayList<MusicTrack>(MusicTrack.defaults());

        @Override public int getRowCount() { return tracks.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int index) { return columns[index]; }

        @Override public Class<?> getColumnClass(int column) {
            return column == 3 ? Integer.class : String.class;
        }

        @Override public boolean isCellEditable(int row, int column) {
            return column >= 0 && column <= 4;
        }

        @Override public Object getValueAt(int row, int column) {
            MusicTrack track = tracks.get(row);
            switch (column) {
                case 0: return track.id();
                case 1: return track.role();
                case 2: return track.instrument();
                case 3: return track.program();
                case 4: return displayName(track.libraryId());
                default: return "✕";
            }
        }

        @Override public void setValueAt(Object value, int row, int column) {
            MusicTrack old = tracks.get(row);
            try {
                String id = column == 0 ? String.valueOf(value) : old.id();
                String role = column == 1 ? String.valueOf(value) : old.role();
                String instrument = column == 2 ? String.valueOf(value) : old.instrument();
                int program = column == 3
                        ? Integer.parseInt(String.valueOf(value)) : old.program();
                String library = column == 4 ? findLibraryId(String.valueOf(value))
                        : old.libraryId();
                MusicTrack replacement = new MusicTrack(id, role, instrument, program, library);
                for (int i = 0; i < tracks.size(); i++) {
                    if (i != row && tracks.get(i).id().equals(replacement.id())) {
                        throw new IllegalArgumentException("Duplicate track ID: " + replacement.id());
                    }
                }
                tracks.set(row, replacement);
                fireTableRowsUpdated(row, row);
                changed();
            } catch (IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(TrackTablePanel.this, invalid.getMessage(),
                        "Invalid track", JOptionPane.ERROR_MESSAGE);
                fireTableRowsUpdated(row, row);
            }
        }
    }

    private static final class DeleteCellRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused,
                int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            setHorizontalAlignment(CENTER);
            setForeground(new java.awt.Color(0xA23341));
            return this;
        }
    }
}
