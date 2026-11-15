package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.Articulation;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;

/**
 * How the piece is played, to be set by hand ("Vortrag"): the tempo with jumps and glides
 * (accelerando, ritardando), the dynamic marks (ppp to fff) and hairpins, the velocity each
 * level stands for and how each articulation sounds, and the articulation of the notes of a
 * part in some bars. Bars and beats are counted from 1, as a musician counts them; a beat is a
 * quarter note. Nothing changes until the user confirms; then it is one undoable step.
 */
final class PerformanceDialog extends JDialog {
    /** What the user set: the tempos and the dynamics of the piece. */
    record Result(List<TimeMap.Tempo> tempos, Dynamics dynamics) {
    }

    /** Sets an articulation at once, while the dialog stays open. */
    interface Articulator {
        void articulate(String partId, int firstBar, int lastBar, Articulation articulation) throws Exception;
    }

    private static final String ALL = "alle";
    private static final String NONE = "keine";

    private final Composition piece;
    private final TimeMap time;
    private final DefaultTableModel tempoRows = model("Takt", "Schlag", "BPM", "gleitet zum nächsten");
    private final DefaultTableModel markRows = model("Takt", "Schlag", "Zeichen", "Stimme");
    private final DefaultTableModel pinRows = model("von Takt", "Schlag", "bis Takt", "Schlag", "endet auf", "Stimme");
    private final DefaultTableModel scaleRows = model("Stufe", "Anschlagstärke (1–127)");
    private final DefaultTableModel articulationRows = model("Artikulation", "Länge (% des Notenwerts)",
            "Anschlag (%)");
    private final JComboBox<String> reference = new JComboBox<>(symbols());
    private final List<JTable> tables = new ArrayList<>();
    private Result result;

    private PerformanceDialog(Component owner, Composition piece, Articulator articulator) {
        super(javax.swing.SwingUtilities.getWindowAncestor(owner), "Vortrag: Tempo, Dynamik, Artikulation",
                ModalityType.APPLICATION_MODAL);
        this.piece = piece;
        this.time = piece.timeMap();
        fill();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Tempo", page("Ab einem Takt gilt ein Tempo. „Gleitet“ heißt: von hier an wird es gleichmäßig "
                + "schneller oder langsamer bis zum Tempo der nächsten Zeile (accelerando, ritardando); sonst springt es.",
                table(tempoRows, null), tempoRows, true));
        tabs.addTab("Dynamik", page("Dynamikzeichen gelten ab ihrem Schlag bis zum nächsten Zeichen; ohne Stimme für alle.",
                table(markRows, Map.of(2, combo(symbols()), 3, combo(parts()))), markRows, false));
        tabs.addTab("Gabeln", page("Crescendo oder Decrescendo: die Lautstärke ändert sich gleichmäßig bis zum Ende der "
                + "Gabel und endet auf der Stufe „endet auf“.",
                table(pinRows, Map.of(4, combo(symbols()), 5, combo(parts()))), pinRows, false));
        tabs.addTab("Stufen", levels());
        tabs.addTab("Noten artikulieren", articulating(articulator));

        JButton ok = new JButton("Übernehmen");
        JButton cancel = new JButton("Abbrechen");
        ok.addActionListener(event -> confirm());
        cancel.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(tabs, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        setSize(new Dimension(760, 520));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the dialog for a piece and gives what the user set, or {@code null} if nothing is to change.
     *
     * @param articulator sets an articulation at once (tab "Noten artikulieren")
     */
    static Result show(Component owner, Composition piece, Articulator articulator) {
        PerformanceDialog dialog = new PerformanceDialog(owner, piece, articulator);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void fill() {
        for (TimeMap.Tempo tempo : time.tempos()) {
            double[] at = barAndBeat(tempo.tick());
            tempoRows.addRow(new Object[]{(int) at[0], at[1], tempo.bpm(), tempo.glide()});
        }
        Dynamics dynamics = piece.dynamics();
        for (Dynamics.Mark mark : dynamics.marks()) {
            double[] at = barAndBeat(mark.tick());
            markRows.addRow(new Object[]{(int) at[0], at[1], mark.level().symbol(), partOrAll(mark.partId())});
        }
        for (Dynamics.Hairpin pin : dynamics.hairpins()) {
            double[] from = barAndBeat(pin.startTick());
            double[] to = barAndBeat(pin.endTick());
            pinRows.addRow(new Object[]{(int) from[0], from[1], (int) to[0], to[1], pin.target().symbol(),
                    partOrAll(pin.partId())});
        }
        for (Dynamics.Level level : Dynamics.Level.values()) {
            scaleRows.addRow(new Object[]{level.symbol(), dynamics.scale().velocity(level)});
        }
        reference.setSelectedItem(dynamics.scale().reference().symbol());
        for (Articulation articulation : Articulation.values()) {
            articulationRows.addRow(new Object[]{articulation.germanName(),
                    percent(dynamics.articulations().length(articulation)),
                    percent(dynamics.articulations().velocity(articulation))});
        }
    }

    private JPanel levels() {
        JPanel scale = new JPanel(new BorderLayout(0, 6));
        scale.add(new JLabel("<html>Welche Anschlagstärke jede Stufe bedeutet. Eine Note klingt mit ihrer "
                + "geschriebenen Stärke mal Stufe ÷ Bezugsstufe; ohne Dynamikzeichen klingt sie wie geschrieben.</html>"),
                BorderLayout.NORTH);
        scale.add(new JScrollPane(table(scaleRows, null)), BorderLayout.CENTER);
        JPanel referenceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        referenceRow.add(new JLabel("Bezugsstufe (so sind die Noten geschrieben):"));
        referenceRow.add(reference);
        scale.add(referenceRow, BorderLayout.SOUTH);
        JPanel articulations = new JPanel(new BorderLayout(0, 6));
        articulations.add(new JLabel("<html>Wie Artikulationen klingen: Länge der Note und Anschlag, in Prozent "
                + "des Geschriebenen.</html>"), BorderLayout.NORTH);
        articulations.add(new JScrollPane(table(articulationRows, null)), BorderLayout.CENTER);
        JPanel both = new JPanel(new GridLayout(1, 2, 12, 0));
        both.add(scale);
        both.add(articulations);
        return both;
    }

    private JPanel articulating(Articulator articulator) {
        JComboBox<String> part = new JComboBox<>(partIds());
        int bars = Math.max(1, time.barsCovering(piece.endTick()));
        JSpinner first = new JSpinner(new SpinnerNumberModel(1, 1, bars, 1));
        JSpinner last = new JSpinner(new SpinnerNumberModel(bars, 1, bars, 1));
        String[] choices = new String[Articulation.values().length + 1];
        choices[0] = NONE;
        for (Articulation articulation : Articulation.values()) {
            choices[articulation.ordinal() + 1] = articulation.germanName();
        }
        JComboBox<String> which = new JComboBox<>(choices);
        JButton apply = new JButton("Auf die Noten anwenden");
        JLabel said = new JLabel(" ");
        apply.addActionListener(event -> {
            int from = (Integer) first.getValue();
            int to = (Integer) last.getValue();
            int index = which.getSelectedIndex();
            Articulation chosen = index == 0 ? null : Articulation.values()[index - 1];
            try {
                articulator.articulate((String) part.getSelectedItem(), from, Math.max(from, to), chosen);
                said.setText("Gesetzt: " + which.getSelectedItem() + " für " + part.getSelectedItem() + ", Takt "
                        + from + " bis " + Math.max(from, to) + " (unter „Rückgängig“ zurückzunehmen).");
            } catch (Exception failed) {
                said.setText("Nicht gesetzt: " + failed.getMessage());
            }
        });
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Stimme"));
        form.add(part);
        form.add(new JLabel("von Takt"));
        form.add(first);
        form.add(new JLabel("bis Takt"));
        form.add(last);
        form.add(new JLabel("Artikulation"));
        form.add(which);
        JPanel page = new JPanel(new BorderLayout(0, 10));
        page.add(new JLabel("<html>Gibt allen Noten einer Stimme, die in diesen Takten beginnen, eine Artikulation "
                + "(oder nimmt sie weg). Das wirkt sofort und ist ein eigener Schritt.</html>"), BorderLayout.NORTH);
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(form, BorderLayout.NORTH);
        page.add(holder, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(apply, BorderLayout.WEST);
        bottom.add(said, BorderLayout.SOUTH);
        page.add(bottom, BorderLayout.SOUTH);
        return page;
    }

    private void confirm() {
        try {
            result = new Result(tempos(), dynamics());
            dispose();
        } catch (RuntimeException invalid) {
            JOptionPane.showMessageDialog(this, invalid.getMessage(), "Vortrag", JOptionPane.WARNING_MESSAGE);
        }
    }

    private List<TimeMap.Tempo> tempos() {
        stopEditing();
        java.util.TreeMap<Long, TimeMap.Tempo> byTick = new java.util.TreeMap<>();
        for (int row = 0; row < tempoRows.getRowCount(); row++) {
            long tick = tick(tempoRows, row, 0, 1, "Tempo");
            double bpm = number(tempoRows.getValueAt(row, 2), "BPM in Zeile " + (row + 1));
            if (bpm < com.aresstack.audio.studio.project.PlanShape.MIN_BPM
                    || bpm > com.aresstack.audio.studio.project.PlanShape.MAX_BPM) {
                throw new IllegalArgumentException("Das Tempo in Zeile " + (row + 1) + " liegt nicht zwischen "
                        + com.aresstack.audio.studio.project.PlanShape.MIN_BPM + " und "
                        + com.aresstack.audio.studio.project.PlanShape.MAX_BPM + " BPM.");
            }
            byTick.put(tick, new TimeMap.Tempo(tick, bpm, Boolean.TRUE.equals(tempoRows.getValueAt(row, 3))));
        }
        if (!byTick.containsKey(0L)) {
            throw new IllegalArgumentException("Takt 1, Schlag 1 braucht ein Tempo.");
        }
        List<TimeMap.Tempo> tempos = new ArrayList<>(byTick.values());
        TimeMap.Tempo last = tempos.get(tempos.size() - 1);
        if (last.glide()) {
            throw new IllegalArgumentException("Das letzte Tempo kann nicht gleiten: es fehlt ein Tempo, zu dem es geht.");
        }
        return tempos;
    }

    private Dynamics dynamics() {
        List<Dynamics.Mark> marks = new ArrayList<>();
        for (int row = 0; row < markRows.getRowCount(); row++) {
            marks.add(new Dynamics.Mark(tick(markRows, row, 0, 1, "Dynamik"),
                    Dynamics.Level.parse(String.valueOf(markRows.getValueAt(row, 2))), part(markRows.getValueAt(row, 3))));
        }
        List<Dynamics.Hairpin> pins = new ArrayList<>();
        for (int row = 0; row < pinRows.getRowCount(); row++) {
            long from = tick(pinRows, row, 0, 1, "Gabel");
            long to = tick(pinRows, row, 2, 3, "Gabel");
            if (to <= from) {
                throw new IllegalArgumentException("Die Gabel in Zeile " + (row + 1) + " endet nicht nach ihrem Anfang.");
            }
            pins.add(new Dynamics.Hairpin(from, to, Dynamics.Level.parse(String.valueOf(pinRows.getValueAt(row, 4))),
                    part(pinRows.getValueAt(row, 5))));
        }
        for (int i = 0; i < pins.size(); i++) {
            for (int j = i + 1; j < pins.size(); j++) {
                Dynamics.Hairpin a = pins.get(i);
                Dynamics.Hairpin b = pins.get(j);
                if (java.util.Objects.equals(a.partId(), b.partId()) && a.startTick() < b.endTick()
                        && b.startTick() < a.endTick()) {
                    throw new IllegalArgumentException("Zwei Gabeln derselben Stimme überschneiden sich.");
                }
            }
        }
        EnumMap<Dynamics.Level, Integer> velocities = new EnumMap<>(Dynamics.Level.class);
        for (int row = 0; row < scaleRows.getRowCount(); row++) {
            velocities.put(Dynamics.Level.parse(String.valueOf(scaleRows.getValueAt(row, 0))),
                    (int) Math.round(number(scaleRows.getValueAt(row, 1), "Anschlagstärke von "
                            + scaleRows.getValueAt(row, 0))));
        }
        Dynamics.Scale scale = new Dynamics.Scale(velocities,
                Dynamics.Level.parse(String.valueOf(reference.getSelectedItem())));
        Dynamics.Articulations articulations = piece.dynamics().articulations();
        for (Articulation articulation : Articulation.values()) {
            int row = articulation.ordinal();
            articulations = articulations
                    .withLength(articulation, number(articulationRows.getValueAt(row, 1),
                            "Länge von " + articulation.germanName()) / 100.0)
                    .withVelocity(articulation, number(articulationRows.getValueAt(row, 2),
                            "Anschlag von " + articulation.germanName()) / 100.0);
        }
        return new Dynamics(marks, pins, scale, articulations);
    }

    /** A tick from the bar and beat columns of a row; bars and beats count from 1. */
    private long tick(DefaultTableModel rows, int row, int barColumn, int beatColumn, String what) {
        int bar = (int) Math.round(number(rows.getValueAt(row, barColumn), what + ": Takt in Zeile " + (row + 1)));
        double beat = number(rows.getValueAt(row, beatColumn), what + ": Schlag in Zeile " + (row + 1));
        if (bar < 1 || beat < 1) {
            throw new IllegalArgumentException(what + ": Takt und Schlag zählen ab 1 (Zeile " + (row + 1) + ").");
        }
        long start = time.tickOfBar(bar - 1);
        long into = Math.round((beat - 1) * TimeMap.PPQ);
        if (into >= time.meterAt(start).barTicks()) {
            throw new IllegalArgumentException(what + ": Schlag " + format(beat) + " liegt nicht in Takt " + bar + ".");
        }
        return start + into;
    }

    private double[] barAndBeat(long tick) {
        int bar = time.barOf(tick);
        return new double[]{bar + 1, 1 + (tick - time.tickOfBar(bar)) / (double) TimeMap.PPQ};
    }

    /** Takes what is still being typed into a cell, in every table. */
    private void stopEditing() {
        for (JTable table : tables) {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }
    }

    private JPanel page(String explanation, JTable table, DefaultTableModel rows, boolean tempo) {
        JPanel page = new JPanel(new BorderLayout(0, 8));
        page.add(new JLabel("<html>" + explanation + "</html>"), BorderLayout.NORTH);
        page.add(new JScrollPane(table), BorderLayout.CENTER);
        JButton add = new JButton("Zeile hinzufügen");
        JButton remove = new JButton("Zeile entfernen");
        add.addActionListener(event -> {
            int bar = Math.max(1, time.barsCovering(piece.endTick()));
            if (tempo) {
                rows.addRow(new Object[]{bar, 1.0, time.tempoAt(time.tickOfBar(bar - 1)), false});
            } else if (rows == markRows) {
                rows.addRow(new Object[]{1, 1.0, Dynamics.Level.MF.symbol(), ALL});
            } else {
                rows.addRow(new Object[]{1, 1.0, 2, 1.0, Dynamics.Level.F.symbol(), ALL});
            }
        });
        remove.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (table.isEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
            if (row >= 0) {
                rows.removeRow(table.convertRowIndexToModel(row));
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(add);
        buttons.add(remove);
        page.add(buttons, BorderLayout.SOUTH);
        return page;
    }

    private JTable table(DefaultTableModel rows, Map<Integer, JComboBox<String>> combos) {
        JTable table = new JTable(rows) {
            @Override
            public Class<?> getColumnClass(int column) {
                Object first = getRowCount() == 0 ? null : getValueAt(0, column);
                return first instanceof Boolean ? Boolean.class : Object.class;
            }
        };
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setRowHeight(24);
        tables.add(table);
        if (combos != null) {
            combos.forEach((column, combo) -> table.getColumnModel().getColumn(column)
                    .setCellEditor(new DefaultCellEditor(combo)));
        }
        return table;
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0);
    }

    private static JComboBox<String> combo(String[] items) {
        return new JComboBox<>(items);
    }

    private static String[] symbols() {
        String[] symbols = new String[Dynamics.Level.values().length];
        for (Dynamics.Level level : Dynamics.Level.values()) {
            symbols[level.ordinal()] = level.symbol();
        }
        return symbols;
    }

    private String[] parts() {
        List<String> ids = new ArrayList<>();
        ids.add(ALL);
        for (Part part : piece.parts()) {
            ids.add(part.id());
        }
        return ids.toArray(new String[0]);
    }

    private String[] partIds() {
        List<String> ids = new ArrayList<>();
        for (Part part : piece.parts()) {
            ids.add(part.id());
        }
        return ids.toArray(new String[0]);
    }

    private static String partOrAll(String partId) {
        return partId == null ? ALL : partId;
    }

    private static String part(Object value) {
        String text = value == null ? ALL : String.valueOf(value).trim();
        return text.isEmpty() || ALL.equals(text) ? null : text;
    }

    private static double number(Object value, String what) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim().replace(',', '.'));
        } catch (RuntimeException odd) {
            throw new IllegalArgumentException(what + ": keine Zahl (" + value + ").");
        }
    }

    private static long percent(double factor) {
        return Math.round(factor * 100);
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }
}
