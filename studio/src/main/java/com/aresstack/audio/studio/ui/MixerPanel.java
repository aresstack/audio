package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.application.MusicTrack;
import com.aresstack.audio.studio.application.TrackArrangement;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mixer: one channel strip per track, side by side. A strip holds what a row
 * of the former table held (name, role, sound, General MIDI program, sound bank),
 * a key to listen to the track alone, and a level meter that follows the playback.
 */
public final class MixerPanel extends JPanel {
    private static final String BUILT_IN = "Java-Synthese (integriert)";

    /** Asked to play one track alone. */
    public interface Solo {
        void listen(String trackId);
    }

    private final InstrumentRegistry registry;
    private final Runnable onChanged;
    private final Solo solo;
    private final List<Strip> strips = new ArrayList<Strip>();
    private final JPanel row = new JPanel();
    private final AddStrip addStrip = new AddStrip();
    private Map<String, DeckPlayer.Envelope> envelopes = new HashMap<String, DeckPlayer.Envelope>();
    private boolean changing;
    private boolean editable = true;

    public MixerPanel(InstrumentRegistry registry, Runnable onChanged, Solo solo) {
        super(new BorderLayout());
        this.registry = registry;
        this.onChanged = onChanged;
        this.solo = solo;
        setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBackground(AudioStudioTheme.MODULE);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        JScrollPane scroll = new JScrollPane(row, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(AudioStudioTheme.MODULE);
        scroll.getHorizontalScrollBar().setUnitIncrement(40);
        add(scroll, BorderLayout.CENTER);
        fill(MusicTrack.defaults());
    }

    /** The sound banks may have changed: every strip offers what is installed now. */
    public void refreshLibraries() {
        for (Strip strip : strips) {
            strip.offerBanks();
        }
    }

    /** The arrangement as it stands, after taking over whatever is still being typed. */
    public TrackArrangement arrangement() {
        for (Strip strip : strips) {
            if (!strip.commit(false)) {
                throw new IllegalArgumentException("Bitte die Eingabe in Kanal " + (strips.indexOf(strip) + 1)
                        + " berichtigen.");
            }
        }
        return currentArrangement();
    }

    /** The arrangement as last taken over, without touching what is being typed. */
    public TrackArrangement currentArrangement() {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (Strip strip : strips) {
            tracks.add(strip.track);
        }
        return new TrackArrangement(tracks);
    }

    /** The tracks of the strips as they stand, also when they do not make a valid arrangement (none at all, say). */
    List<MusicTrack> tracks() {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (Strip strip : strips) {
            tracks.add(strip.track);
        }
        return tracks;
    }

    public void setEditingEnabled(boolean enabled) {
        editable = enabled;
        for (Strip strip : strips) {
            strip.setEditable(enabled);
        }
        addStrip.setEnabled(enabled && strips.size() < TrackArrangement.MAX_TRACKS);
    }

    public void setArrangement(TrackArrangement arrangement) {
        changing = true;
        try {
            fill(arrangement.tracks());
        } finally {
            changing = false;
        }
        changed();
    }

    /** The loudness of each track's rendered file, by track id: what the strips' meters show while playing. */
    void setEnvelopes(Map<String, DeckPlayer.Envelope> measured) {
        envelopes = measured == null ? new HashMap<String, DeckPlayer.Envelope>() : measured;
        for (Strip strip : strips) {
            strip.repaint();
        }
    }

    /**
     * Moves the meters with the playback.
     *
     * @param second where the playback is; negative when nothing plays
     * @param only   the track that plays alone, or {@code null} when the whole mix plays
     */
    void follow(double second, String only) {
        for (Strip strip : strips) {
            DeckPlayer.Envelope envelope = envelopes.get(strip.track.id());
            boolean sounding = second >= 0 && envelope != null && (only == null || only.equals(strip.track.id()));
            strip.left.setLevel(sounding ? envelope.at(second, false) : 0f);
            strip.right.setLevel(sounding ? envelope.at(second, true) : 0f);
            strip.setSolo(second >= 0 && only != null && only.equals(strip.track.id()));
        }
    }

    /** How a track is getting on while the studio composes or renders: what its strip shows in its cap. */
    enum Work { WAITING, WORKING, DONE, FAILED }

    /**
     * Shows on a track's strip how the track is getting on.
     *
     * @param label a short word for the cap, e.g. "KI" or "RENDERN"
     */
    void setWork(String trackId, Work work, String label, String detail) {
        for (Strip strip : strips) {
            if (strip.track.id().equals(trackId)) {
                strip.work = work;
                strip.workLabel = label;
                strip.setToolTipText(detail == null || detail.isEmpty() ? null : detail);
                strip.repaint();
            }
        }
    }

    /** The work is over: the strips show their roles again. */
    void clearWork() {
        for (Strip strip : strips) {
            strip.work = null;
            strip.setToolTipText(null);
            strip.repaint();
        }
    }

    /** Lets the strips that are being worked on blink; called by the desk's clock. */
    void pulse() {
        phase = (phase + 0.045f) % 1f;
        for (Strip strip : strips) {
            if (strip.work == Work.WORKING) {
                strip.repaint();
            }
        }
    }

    private float phase;

    private void fill(List<MusicTrack> tracks) {
        strips.clear();
        row.removeAll();
        int index = 0;
        for (MusicTrack track : tracks) {
            Strip strip = new Strip(track, index++);
            strip.setEditable(editable);
            strips.add(strip);
            row.add(strip);
            row.add(Box.createHorizontalStrut(10));
        }
        row.add(addStrip);
        row.add(Box.createHorizontalGlue());
        addStrip.setEnabled(editable && strips.size() < TrackArrangement.MAX_TRACKS);
        row.revalidate();
        row.repaint();
    }

    private void addTrack() {
        if (strips.size() >= TrackArrangement.MAX_TRACKS) {
            JOptionPane.showMessageDialog(this,
                    "Höchstens 16 MIDI-Kanäle (15 melodische Kanäle und ein Schlagzeugkanal).");
            return;
        }
        int sequence = 1;
        while (containsId("voice_" + sequence, null)) {
            sequence++;
        }
        List<MusicTrack> tracks = new ArrayList<MusicTrack>(currentArrangement().tracks());
        tracks.add(new MusicTrack("voice_" + sequence, MusicTrack.TEXTURE, "Flute", 73, "builtin"));
        fill(tracks);
        Strip added = strips.get(strips.size() - 1);
        added.instrument.requestFocusInWindow();
        added.instrument.selectAll();
        row.scrollRectToVisible(addStrip.getBounds());
        changed();
    }

    private void removeTrack(Strip strip) {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (Strip other : strips) {
            if (other != strip) {
                tracks.add(other.track);
            }
        }
        fill(tracks);
        changed();
    }

    private boolean containsId(String id, Strip except) {
        for (Strip strip : strips) {
            if (strip != except && strip.track.id().equals(id)) {
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

    private static JComponent labelled(String name, JComponent field) {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        panel.setOpaque(false);
        panel.add(DeckWidgets.lettering(name), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    /** One channel of the mixer. */
    private final class Strip extends JPanel {
        private MusicTrack track;
        private final int index;
        private final JTextField id = new JTextField();
        private final JComboBox<String> role = new JComboBox<String>(MusicTrack.ROLES.toArray(new String[0]));
        private final JTextField instrument = new JTextField();
        private final JSpinner program = new JSpinner(new SpinnerNumberModel(0, 0, 127, 1));
        private final JComboBox<String> bank = new JComboBox<String>();
        private final DeckDisplays.Meter left = new DeckDisplays.Meter(true);
        private final DeckDisplays.Meter right = new DeckDisplays.Meter(true);
        private final JButton listen = new JButton("Solo");
        private boolean removable = true;
        private boolean overRemove;
        private boolean filling;
        private boolean playsAlone;
        private Work work;
        private String workLabel = "";

        Strip(MusicTrack shown, int index) {
            super(new GridBagLayout());
            this.track = shown;
            this.index = index;
            setOpaque(false);
            Dimension size = new Dimension(184, 284);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(new Dimension(184, Integer.MAX_VALUE));
            setBorder(BorderFactory.createEmptyBorder(42, 12, 12, 12));

            id.setHorizontalAlignment(JTextField.CENTER);
            id.setFont(id.getFont().deriveFont(java.awt.Font.BOLD, 13.5f));
            id.setToolTipText("Name der Spur: Kleinbuchstaben, Ziffern, - und _");
            instrument.setToolTipText("Instrument oder Klangfarbe, wie die KI sie verstehen soll");
            program.setToolTipText("General-MIDI-Programm 0–127");
            listen.setToolTipText("Diese Spur allein anhören");
            listen.setMargin(new Insets(4, 6, 4, 6));
            // The cross in the cap takes the track out of the arrangement.
            java.awt.event.MouseAdapter cap = new java.awt.event.MouseAdapter() {
                @Override
                public void mouseMoved(java.awt.event.MouseEvent event) {
                    boolean over = removable && crossArea().contains(event.getPoint());
                    if (over != overRemove) {
                        overRemove = over;
                        setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        setToolTipText(over ? "Spur aus dem Arrangement nehmen" : null);
                        repaint();
                    }
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent event) {
                    if (overRemove) {
                        overRemove = false;
                        setCursor(Cursor.getDefaultCursor());
                        repaint();
                    }
                }

                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (removable && crossArea().contains(event.getPoint())) {
                        removeTrack(Strip.this);
                    }
                }
            };
            addMouseListener(cap);
            addMouseMotionListener(cap);

            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridwidth = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            c.insets = new Insets(0, 0, 8, 0);
            c.gridy = 0;
            add(id, c);
            // Role and General MIDI program share a row: a strip is narrow, but need not be tall.
            JPanel roleAndProgram = new JPanel(new BorderLayout(6, 0));
            roleAndProgram.setOpaque(false);
            roleAndProgram.add(labelled("Rolle", role), BorderLayout.CENTER);
            JComponent gm = labelled("GM", program);
            gm.setPreferredSize(new Dimension(58, 10));
            roleAndProgram.add(gm, BorderLayout.EAST);
            c.gridy = 1;
            add(roleAndProgram, c);
            c.gridy = 2;
            add(labelled("Klang", instrument), c);
            c.gridy = 4;
            add(labelled("Soundbank", bank), c);

            JPanel meters = new JPanel(new java.awt.GridLayout(1, 2, 3, 0));
            meters.setOpaque(false);
            meters.add(left);
            meters.add(right);
            meters.setPreferredSize(new Dimension(30, 44));
            meters.setMinimumSize(new Dimension(30, 44));
            JPanel keys = new JPanel(new BorderLayout());
            keys.setOpaque(false);
            keys.add(listen, BorderLayout.SOUTH);
            c.gridy = 5;
            c.gridwidth = 1;
            c.weightx = 0;
            c.weighty = 1;
            c.fill = GridBagConstraints.BOTH;
            c.ipadx = 14;
            c.anchor = GridBagConstraints.SOUTHWEST;
            c.insets = new Insets(4, 2, 0, 10);
            add(meters, c);
            c.gridx = 1;
            c.ipadx = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.SOUTH;
            c.insets = new Insets(4, 0, 0, 0);
            add(keys, c);

            offerBanks();
            show(shown);
            FocusAdapter takeOver = new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent event) {
                    if (!event.isTemporary()) {
                        commit(true);
                    }
                }
            };
            id.addFocusListener(takeOver);
            instrument.addFocusListener(takeOver);
            id.addActionListener(event -> commit(true));
            instrument.addActionListener(event -> commit(true));
            role.addActionListener(event -> commit(true));
            bank.addActionListener(event -> commit(true));
            program.addChangeListener(event -> commit(true));
            listen.addActionListener(event -> {
                if (commit(true)) {
                    solo.listen(track.id());
                }
            });
        }

        private java.awt.Rectangle crossArea() {
            return new java.awt.Rectangle(getWidth() - 30, 5, 24, 24);
        }

        void setEditable(boolean enabled) {
            id.setEnabled(enabled);
            role.setEnabled(enabled);
            instrument.setEnabled(enabled);
            program.setEnabled(enabled);
            bank.setEnabled(enabled);
            removable = enabled;
            repaint();
        }

        void setSolo(boolean alone) {
            if (playsAlone != alone) {
                playsAlone = alone;
                repaint();
            }
        }

        void offerBanks() {
            filling = true;
            try {
                bank.removeAllItems();
                bank.addItem(BUILT_IN);
                boolean found = "builtin".equals(track.libraryId());
                for (InstrumentLibrary library : registry.installedInstruments()) {
                    bank.addItem(library.name());
                    found |= library.id().equals(track.libraryId());
                }
                if (!found) {
                    bank.addItem("Nicht installiert: " + track.libraryId());
                }
                bank.setSelectedItem(bankName(track.libraryId()));
                bank.setToolTipText(String.valueOf(bank.getSelectedItem()));
            } finally {
                filling = false;
            }
        }

        private void show(MusicTrack shown) {
            filling = true;
            try {
                id.setText(shown.id());
                role.setSelectedItem(shown.role());
                instrument.setText(shown.instrument());
                instrument.setCaretPosition(0);
                program.setValue(Integer.valueOf(shown.program()));
                bank.setSelectedItem(bankName(shown.libraryId()));
            } finally {
                filling = false;
            }
        }

        private String bankName(String libraryId) {
            if ("builtin".equals(libraryId)) {
                return BUILT_IN;
            }
            for (InstrumentLibrary library : registry.installedInstruments()) {
                if (library.id().equals(libraryId)) {
                    return library.name();
                }
            }
            return "Nicht installiert: " + libraryId;
        }

        private String bankId(String name) {
            if (name == null || BUILT_IN.equals(name)) {
                return "builtin";
            }
            for (InstrumentLibrary library : registry.installedInstruments()) {
                if (library.name().equals(name)) {
                    return library.id();
                }
            }
            return track.libraryId(); // the bank that is not installed stays named
        }

        /**
         * Takes over what the fields say, if it makes a valid track with a name of its own.
         *
         * @param explain whether to say what is wrong and put the old values back
         * @return whether the strip now holds what its fields say
         */
        boolean commit(boolean explain) {
            if (filling || !strips.contains(this)) {
                return true; // a strip that was replaced still hears its fields lose the focus
            }
            try {
                MusicTrack typed = new MusicTrack(id.getText(), String.valueOf(role.getSelectedItem()),
                        instrument.getText(), ((Number) program.getValue()).intValue(),
                        bankId((String) bank.getSelectedItem()));
                if (containsId(typed.id(), this)) {
                    throw new IllegalArgumentException("Den Spurnamen gibt es schon: " + typed.id());
                }
                boolean same = typed.id().equals(track.id()) && typed.role().equals(track.role())
                        && typed.instrument().equals(track.instrument()) && typed.program() == track.program()
                        && typed.libraryId().equals(track.libraryId());
                track = typed;
                bank.setToolTipText(String.valueOf(bank.getSelectedItem()));
                if (!same) {
                    show(typed); // the name as it was cleaned up
                    repaint();
                    changed();
                }
                return true;
            } catch (IllegalArgumentException invalid) {
                if (explain) {
                    show(track);
                    JOptionPane.showMessageDialog(MixerPanel.this, invalid.getMessage(), "Spur",
                            JOptionPane.WARNING_MESSAGE);
                }
                return false;
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = DeckWidgets.smooth(graphics);
            Color color = AudioStudioTheme.channel(index);
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 16f, 16f);
            g.setColor(new Color(0x23252E));
            g.fill(shape);
            // The cap in the channel's colour, with the number of the channel.
            Graphics2D cap = (Graphics2D) g.create();
            cap.clip(shape);
            cap.setPaint(new GradientPaint(0, 0, AudioStudioTheme.withAlpha(color, 235), 0, 32,
                    AudioStudioTheme.withAlpha(color, 170)));
            cap.fillRect(0, 0, getWidth(), 32);
            cap.dispose();
            float glow = (float) Math.abs(Math.sin(phase * Math.PI * 2));
            if (work == Work.WORKING) {
                g.setColor(AudioStudioTheme.withAlpha(Color.WHITE, 90 + Math.round(150 * glow)));
                g.setStroke(new BasicStroke(2f));
            } else if (work == Work.FAILED) {
                g.setColor(AudioStudioTheme.RED);
                g.setStroke(new BasicStroke(2f));
            } else {
                g.setColor(playsAlone ? Color.WHITE : AudioStudioTheme.withAlpha(color, 120));
                g.setStroke(new BasicStroke(playsAlone ? 2f : 1f));
            }
            g.draw(shape);
            g.setFont(AudioStudioTheme.lettering(11f));
            g.setColor(new Color(0x15161B));
            FontMetrics metrics = g.getFontMetrics();
            // A lamp: lit when the track has been rendered and can be heard alone. While the studio
            // works on the track it blinks, and the cap says what is being done instead of the role.
            boolean lit = work == null ? envelopes.get(track.id()) != null
                    : work == Work.WORKING ? glow > 0.45f : work != Work.WAITING;
            DeckWidgets.paintLamp(g, 16f, 16f, 4f, work == Work.FAILED ? new Color(0x7A1020) : Color.WHITE, lit);
            String number = String.format(java.util.Locale.ROOT, "CH %02d", index + 1);
            g.drawString(number, 28, 21);
            String kind = work == null ? track.role().toUpperCase(java.util.Locale.ROOT)
                    : work == Work.WAITING ? "WARTET" : work == Work.WORKING ? workLabel + " …"
                    : work == Work.DONE ? workLabel + " ✓" : "FEHLER";
            g.drawString(kind, getWidth() - 36 - metrics.stringWidth(kind), 21);
            if (removable) {
                float x = getWidth() - 18f;
                if (overRemove) {
                    g.setColor(new Color(0, 0, 0, 70));
                    g.fill(new java.awt.geom.Ellipse2D.Float(x - 10f, 6f, 20f, 20f));
                }
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(overRemove ? Color.WHITE : new Color(0x15161B));
                g.draw(new java.awt.geom.Line2D.Float(x - 4f, 12f, x + 4f, 20f));
                g.draw(new java.awt.geom.Line2D.Float(x + 4f, 12f, x - 4f, 20f));
            }
            g.dispose();
        }
    }

    /** The empty place at the end of the row: a key that adds a track. */
    private final class AddStrip extends JButton {
        AddStrip() {
            super("+ Spur");
            Dimension size = new Dimension(112, 284);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(new Dimension(112, Integer.MAX_VALUE));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFont(AudioStudioTheme.lettering(11.5f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Eine weitere Spur hinzufügen (höchstens 16)");
            addActionListener(event -> addTrack());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = DeckWidgets.smooth(graphics);
            boolean over = isEnabled() && getModel().isRollover();
            RoundRectangle2D shape = new RoundRectangle2D.Float(1f, 1f, getWidth() - 2f, getHeight() - 2f, 16f, 16f);
            if (over) {
                g.setColor(AudioStudioTheme.withAlpha(AudioStudioTheme.ACCENT, 26));
                g.fill(shape);
            }
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 6f}, 0f));
            g.setColor(!isEnabled() ? AudioStudioTheme.STROKE : over ? AudioStudioTheme.ACCENT : AudioStudioTheme.TEXT_FAINT);
            g.draw(shape);
            g.setFont(getFont());
            g.setColor(!isEnabled() ? AudioStudioTheme.TEXT_FAINT : over ? AudioStudioTheme.ACCENT_SOFT : AudioStudioTheme.TEXT_DIM);
            FontMetrics metrics = g.getFontMetrics();
            String text = getText().toUpperCase(java.util.Locale.ROOT);
            g.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2f, getHeight() / 2f + 4);
            g.dispose();
        }
    }
}
