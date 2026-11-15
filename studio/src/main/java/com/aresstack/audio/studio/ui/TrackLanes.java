package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.application.MusicTrack;
import com.aresstack.audio.studio.application.TrackArrangement;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The tracks of the piece, one below the other: in front of each lane of notes its
 * head with the name, the role and the instrument, a fader and a mute key. All else
 * that can be set for a track (rename, role, instrument, the words for the AI, the
 * sound bank, compose anew, remove) is in the menu of the head, which also opens
 * with the right mouse button. Below the last track a new one is added.
 */
final class TrackLanes extends JPanel {
    private static final String BUILT_IN = "Java-Synthese (integriert)";
    private static final int HEAD_WIDTH = 384;
    private static final String[] FAMILIES = {"Klaviere", "Stabspiele", "Orgeln", "Gitarren", "Bässe", "Streicher",
            "Ensembles", "Blechbläser", "Rohrblatt", "Flöten", "Synth · Lead", "Synth · Flächen", "Synth · Effekte",
            "Weltmusik", "Perkussiv", "Geräusche"};

    /** Asked to hear one track alone. */
    interface Solo {
        /** Play this track alone, now. */
        void listen(String trackId);

        /** Choose what the playback plays: this track alone, or the mix for {@code null}. */
        void choose(String trackId);
    }

    /** Asked to have the AI compose one track anew, leaving the others as they are. */
    interface Recompose {
        void again(String trackId);
    }

    /** The stage of the piece, as far as a lane deals with it: the level and the muting of its track. */
    interface Stage {
        /** How the track stands on the stage, or {@code null} if it is not placed yet. */
        SoundSourcePlacement placement(String trackId);

        /**
         * @param settled {@code false} while the fader is still being moved
         */
        void change(SoundSourcePlacement placement, boolean settled);

        /** A track got another name; its place on the stage goes with it. */
        void renamed(String from, String to);
    }

    /** How a track is getting on while the studio composes or renders. */
    enum Work { WAITING, WORKING, DONE, FAILED }

    private final InstrumentRegistry registry;
    private final Runnable onChanged;
    private final Solo solo;
    private final Recompose recompose;
    private final Stage stage;
    private final ArrangementView view;
    private final List<Head> heads = new ArrayList<Head>();
    private final JPanel column = new JPanel();
    private final JLabel count = DeckWidgets.lettering("");
    private final JButton add = new JButton("+ Spur");
    private final JLabel mixName = DeckWidgets.lettering("Mix");
    private final JScrollPane scroll;
    private boolean changing;
    private boolean editable = true;
    private String alone;
    private float phase;

    TrackLanes(InstrumentRegistry registry, Runnable onChanged, Solo solo, Recompose recompose, Stage stage,
               ArrangementView view) {
        super(new BorderLayout());
        this.registry = registry;
        this.onChanged = onChanged;
        this.solo = solo;
        this.recompose = recompose;
        this.stage = stage;
        this.view = view;
        setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBackground(new Color(0x1B1D24));
        add.setToolTipText("Eine weitere Spur unten anfügen");
        add.setMargin(new Insets(3, 10, 3, 10));
        add.addActionListener(event -> addTrack());
        mixName.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        mixName.setToolTipText("Die ganze Mischung zur Wiedergabe wählen");
        mixName.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                TrackLanes.this.solo.choose(null);
            }
        });
        scroll = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setRowHeaderView(column);
        scroll.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        scroll.getViewport().setBackground(AudioStudioTheme.WELL);
        scroll.getRowHeader().setBackground(new Color(0x1B1D24));
        scroll.getVerticalScrollBar().setUnitIncrement(30);
        scroll.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                fitHeights();
            }
        });
        add(scroll, BorderLayout.CENTER);
        fill(MusicTrack.defaults());
    }

    /** The sound banks may have changed; the menus are built when they open, so nothing is to be done. */
    void refreshLibraries() {
        for (Head head : heads) {
            head.showTrack();
        }
    }

    /** The arrangement as it stands. */
    TrackArrangement arrangement() {
        return currentArrangement();
    }

    TrackArrangement currentArrangement() {
        return new TrackArrangement(tracks());
    }

    /** The tracks as they stand, also when they do not make a valid arrangement (none at all, say). */
    List<MusicTrack> tracks() {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (Head head : heads) {
            tracks.add(head.track);
        }
        return tracks;
    }

    void setEditingEnabled(boolean enabled) {
        editable = enabled;
        add.setEnabled(enabled);
    }

    void setArrangement(TrackArrangement arrangement) {
        changing = true;
        try {
            fill(arrangement.tracks());
        } finally {
            changing = false;
        }
        changed();
    }

    /** The stage was changed elsewhere: the faders and mute keys show it. */
    void refreshStage() {
        for (Head head : heads) {
            head.showStage();
        }
    }

    /** What the playback plays: one track alone, or the mix for {@code null}; its head is marked. */
    void setAlone(String trackId) {
        if (trackId == null ? alone != null : !trackId.equals(alone)) {
            alone = trackId;
            mixName.setForeground(trackId == null ? Color.WHITE : AudioStudioTheme.TEXT_DIM);
            column.repaint();
        }
    }

    /**
     * Shows in a track's head how the track is getting on.
     *
     * @param label a short word, e.g. "KI" or "Rendern"
     */
    void setWork(String trackId, Work work, String label, String detail) {
        for (Head head : heads) {
            if (head.track.id().equals(trackId)) {
                head.work = work;
                head.workLabel = label;
                head.setToolTipText(detail == null || detail.isEmpty() ? null : detail);
                head.showTrack();
            }
        }
    }

    /** The work is over: the heads show role and instrument again. */
    void clearWork() {
        for (Head head : heads) {
            if (head.work != null) {
                head.work = null;
                head.setToolTipText(null);
                head.showTrack();
            }
        }
    }

    /** Lets the heads that are being worked on blink; called by the desk's clock. */
    void pulse() {
        phase = (phase + 0.045f) % 1f;
        for (Head head : heads) {
            if (head.work == Work.WORKING) {
                head.repaint();
            }
        }
    }

    /** Puts a component above the lanes, over the same width: the form of the piece. */
    void setHeader(javax.swing.JComponent header) {
        scroll.setColumnHeaderView(header);
        scroll.getColumnHeader().setBackground(AudioStudioTheme.WELL);
    }

    private void fill(List<MusicTrack> tracks) {
        heads.clear();
        column.removeAll();
        JPanel corner = new JPanel(new BorderLayout());
        corner.setOpaque(false);
        corner.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 10));
        corner.add(count, BorderLayout.WEST);
        fix(corner, ArrangementView.RULER);
        column.add(corner);
        int index = 0;
        for (MusicTrack track : tracks) {
            Head head = new Head(track, index++);
            heads.add(head);
            column.add(head);
        }
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 10));
        JPanel footRow = new JPanel(new BorderLayout());
        footRow.setOpaque(false);
        footRow.add(mixName, BorderLayout.WEST);
        footRow.add(add, BorderLayout.EAST);
        foot.add(footRow, BorderLayout.NORTH);
        foot.setMinimumSize(new Dimension(HEAD_WIDTH, ArrangementView.MIX));
        foot.setPreferredSize(new Dimension(HEAD_WIDTH, ArrangementView.MIX));
        foot.setMaximumSize(new Dimension(HEAD_WIDTH, Integer.MAX_VALUE));
        foot.setAlignmentX(0f);
        column.add(foot);
        add.setEnabled(editable);
        count.setText(heads.isEmpty() ? "Keine Spur" : heads.size() + (heads.size() == 1 ? " Spur" : " Spuren"));
        fitHeights();
        column.revalidate();
        column.repaint();
    }

    private static void fix(JPanel panel, int height) {
        Dimension size = new Dimension(HEAD_WIDTH, height);
        panel.setMinimumSize(size);
        panel.setPreferredSize(size);
        panel.setMaximumSize(size);
        panel.setAlignmentX(0f);
    }

    /** The lanes share the height there is: few tracks get high lanes, many get low ones and the list scrolls. */
    private void fitHeights() {
        int free = scroll.getHeight() - ArrangementView.RULER - ArrangementView.MIX - 4;
        int height = heads.isEmpty() ? 60 : Math.max(54, Math.min(150, free / heads.size()));
        for (Head head : heads) {
            fix(head, height);
        }
        view.setLaneHeight(height);
        column.revalidate();
    }

    private void addTrack() {
        int sequence = 1;
        while (containsId("voice_" + sequence, null)) {
            sequence++;
        }
        List<MusicTrack> tracks = new ArrayList<MusicTrack>(tracks());
        tracks.add(new MusicTrack("voice_" + sequence, MusicTrack.TEXTURE, GeneralMidi.name(73), 73, "builtin"));
        fill(tracks);
        changed();
        javax.swing.SwingUtilities.invokeLater(() -> {
            Head added = heads.get(heads.size() - 1);
            column.scrollRectToVisible(added.getBounds());
            added.openMenu(added.menu, 0, added.menu.getHeight());
        });
    }

    /** Moves a track one place up or down; the order is the order of the lanes, of the score and of the channels. */
    private void moveTrack(Head head, int by) {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>(tracks());
        int from = heads.indexOf(head);
        int to = from + by;
        if (from < 0 || to < 0 || to >= tracks.size()) {
            return;
        }
        tracks.add(to, tracks.remove(from));
        fill(tracks);
        changed();
    }

    private void removeTrack(Head head) {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (Head other : heads) {
            if (other != head) {
                tracks.add(other.track);
            }
        }
        fill(tracks);
        changed();
    }

    private boolean containsId(String id, Head except) {
        for (Head head : heads) {
            if (head != except && head.track.id().equals(id)) {
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

    /** The head of one lane. */
    private final class Head extends JPanel {
        private MusicTrack track;
        private final int index;
        private final JLabel name = new JLabel();
        private final JLabel info = new JLabel();
        private final JSlider fader = new JSlider((int) SoundSourcePlacement.MIN_LEVEL,
                (int) SoundSourcePlacement.MAX_LEVEL, 0);
        private final JLabel loudness = new JLabel("±0 dB");
        private final JToggleButton mute = new JToggleButton("M");
        private final JButton menu = new JButton("⋯");
        private boolean filling;
        private final JPanel words = new JPanel();
        private javax.swing.JTextField renaming;
        private Work work;
        private String workLabel = "";

        Head(MusicTrack shown, int index) {
            super(new GridBagLayout());
            this.track = shown;
            this.index = index;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 8));
            name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD, 13.5f));
            name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            name.setToolTipText("Klick: diese Spur allein zur Wiedergabe wählen · Doppelklick: umbenennen");
            info.setFont(info.getFont().deriveFont(11.5f));
            fader.setOpaque(false);
            fader.setToolTipText("Lautstärke der Spur in der Mischung");
            fader.setPreferredSize(new Dimension(84, 22));
            fader.setMinimumSize(new Dimension(84, 22));
            loudness.setFont(AudioStudioTheme.display(10.5f));
            loudness.setForeground(AudioStudioTheme.TEXT_DIM);
            loudness.setPreferredSize(new Dimension(44, 18));
            loudness.setHorizontalAlignment(JLabel.RIGHT);
            mute.setToolTipText("Stumm: die Spur aus der Mischung nehmen, ohne sie zu löschen");
            mute.setMargin(new Insets(2, 6, 2, 6));
            mute.setFocusable(false);
            menu.setToolTipText("Alles Weitere zu dieser Spur");
            menu.setMargin(new Insets(2, 6, 2, 6));
            menu.setFocusable(false);

            words.setOpaque(false);
            words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
            words.add(name);
            words.add(Box.createVerticalStrut(2));
            words.add(info);
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = 0;
            c.gridx = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.WEST;
            words.setMinimumSize(new Dimension(40, 34));
            words.setPreferredSize(new Dimension(40, 36));
            add(words, c);
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.gridx = 1;
            c.insets = new Insets(0, 6, 0, 0);
            add(fader, c);
            c.gridx = 2;
            c.insets = new Insets(0, 0, 0, 6);
            add(loudness, c);
            c.gridx = 3;
            c.insets = new Insets(0, 0, 0, 4);
            add(mute, c);
            c.gridx = 4;
            c.insets = new Insets(0, 0, 0, 0);
            add(menu, c);

            showTrack();
            showStage();
            fader.addChangeListener(event -> {
                loudness.setText(StageView.decibels(fader.getValue()));
                SoundSourcePlacement placement = filling ? null : stage.placement(track.id());
                if (placement != null) {
                    stage.change(placement.withLevel(fader.getValue()), !fader.getValueIsAdjusting());
                }
            });
            mute.addActionListener(event -> setMuted(mute.isSelected()));
            menu.addActionListener(event -> openMenu(menu, 0, menu.getHeight()));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    if (event.isPopupTrigger()) {
                        openMenu(event.getComponent(), event.getX(), event.getY());
                    } else if (event.getComponent() == name && event.getButton() == MouseEvent.BUTTON1) {
                        if (event.getClickCount() == 2 && editable) {
                            rename();
                        } else if (event.getClickCount() == 1) {
                            solo.choose(track.id());
                        }
                    }
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (event.isPopupTrigger()) {
                        openMenu(event.getComponent(), event.getX(), event.getY());
                    }
                }
            };
            addMouseListener(mouse);
            name.addMouseListener(mouse);
            info.addMouseListener(mouse);
            words.addMouseListener(mouse);
        }

        /** Name, and under it the role and the instrument; while the studio works on the track, how it gets on. */
        void showTrack() {
            name.setText(track.id());
            name.setForeground(AudioStudioTheme.TEXT);
            String bank = "builtin".equals(track.libraryId()) ? "" : " · " + bankName(track.libraryId());
            if (work == null) {
                info.setText(track.role() + " · " + GeneralMidi.entry(track.program()));
                info.setForeground(AudioStudioTheme.TEXT_DIM);
                info.setToolTipText("Für die KI: " + track.instrument() + bank);
            } else {
                info.setText(work == Work.WAITING ? "wartet …" : work == Work.WORKING ? workLabel + " arbeitet …"
                        : work == Work.DONE ? workLabel + " fertig ✓" : "Fehler");
                info.setForeground(work == Work.FAILED ? AudioStudioTheme.RED : AudioStudioTheme.ACCENT_SOFT);
            }
            repaint();
        }

        /** Shows the level and the muting of the track as the stage has them. */
        void showStage() {
            SoundSourcePlacement placement = stage.placement(track.id());
            boolean before = filling;
            filling = true;
            try {
                fader.setEnabled(placement != null);
                mute.setEnabled(placement != null);
                if (placement != null) {
                    fader.setValue((int) Math.round(placement.levelDb()));
                    mute.setSelected(placement.muted());
                }
                loudness.setText(StageView.decibels(fader.getValue()));
            } finally {
                filling = before;
            }
            repaint();
        }

        private void setMuted(boolean silent) {
            SoundSourcePlacement placement = stage.placement(track.id());
            if (placement != null) {
                stage.change(placement.withMuted(silent), true);
            }
            mute.setSelected(silent);
            repaint();
        }

        /** Takes over a changed track if it is valid and its name is its own; says what is wrong otherwise. */
        private void change(String id, String role, String instrument, int program, String libraryId) {
            try {
                MusicTrack typed = new MusicTrack(id, role, instrument, program, libraryId);
                if (containsId(typed.id(), this)) {
                    throw new IllegalArgumentException("Den Spurnamen gibt es schon: " + typed.id());
                }
                String before = track.id();
                track = typed;
                if (!before.equals(typed.id())) {
                    stage.renamed(before, typed.id());
                }
                showTrack();
                changed();
            } catch (IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(TrackLanes.this, invalid.getMessage(), "Spur",
                        JOptionPane.WARNING_MESSAGE);
            }
        }

        /** The name becomes a field where it stands: Enter or leaving it takes the new name, Escape keeps the old. */
        private void rename() {
            if (renaming != null) {
                return;
            }
            final javax.swing.JTextField field = new javax.swing.JTextField(track.id());
            renaming = field;
            field.setFont(name.getFont());
            field.setMargin(new Insets(0, 2, 0, 2));
            field.setToolTipText("Kleinbuchstaben, Ziffern, - und _ · Enter übernimmt, Esc verwirft");
            field.setAlignmentX(0f);
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            final boolean[] done = {false};
            // A click anywhere else ends the renaming and takes the name, also on what cannot take
            // the keyboard (a lane, an empty place): there the field would never hear that it was left.
            final java.awt.event.AWTEventListener[] outside = new java.awt.event.AWTEventListener[1];
            final Runnable finish = () -> {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(outside[0]);
                renaming = null;
                String typed = field.getText().trim();
                words.remove(field);
                words.add(name, 0);
                words.revalidate();
                words.repaint();
                if (!typed.isEmpty() && !typed.equals(track.id())) {
                    change(typed, track.role(), track.instrument(), track.program(), track.libraryId());
                }
            };
            outside[0] = event -> {
                if (event.getID() == MouseEvent.MOUSE_PRESSED && event.getSource() != field) {
                    finish.run();
                }
            };
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(outside[0], java.awt.AWTEvent.MOUSE_EVENT_MASK);
            field.addActionListener(event -> finish.run());
            field.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent event) {
                    if (!event.isTemporary()) {
                        finish.run();
                    }
                }
            });
            field.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "keep");
            field.getActionMap().put("keep", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    field.setText(track.id());
                    finish.run();
                }
            });
            words.remove(name);
            words.add(field, 0);
            words.revalidate();
            words.repaint();
            field.requestFocusInWindow();
            field.selectAll();
        }

        private void describe() {
            Object typed = JOptionPane.showInputDialog(TrackLanes.this,
                    "Instrument oder Klangfarbe, wie die KI sie verstehen soll:", "Beschreibung für die KI",
                    JOptionPane.PLAIN_MESSAGE, null, null, track.instrument());
            if (typed != null) {
                change(track.id(), track.role(), String.valueOf(typed), track.program(), track.libraryId());
            }
        }

        void openMenu(java.awt.Component invoker, int x, int y) {
            JPopupMenu popup = new JPopupMenu();
            JMenuItem listen = new JMenuItem("Solo anhören");
            listen.addActionListener(event -> solo.listen(track.id()));
            popup.add(listen);
            JCheckBoxMenuItem silent = new JCheckBoxMenuItem("Stumm", mute.isSelected());
            silent.setEnabled(mute.isEnabled());
            silent.addActionListener(event -> setMuted(silent.isSelected()));
            popup.add(silent);
            JMenuItem again = new JMenuItem("Von der KI neu komponieren lassen");
            again.setToolTipText("Nur diese Spur; die anderen bleiben, wie sie sind");
            again.setEnabled(editable);
            again.addActionListener(event -> recompose.again(track.id()));
            popup.add(again);
            popup.addSeparator();

            JMenuItem rename = new JMenuItem("Umbenennen");
            rename.setEnabled(editable);
            rename.addActionListener(event -> rename());
            popup.add(rename);
            JMenu roles = new JMenu("Rolle");
            roles.setEnabled(editable);
            ButtonGroup oneRole = new ButtonGroup();
            for (final String role : MusicTrack.ROLES) {
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(role, role.equals(track.role()));
                item.addActionListener(event -> change(track.id(), role, track.instrument(), track.program(),
                        track.libraryId()));
                oneRole.add(item);
                roles.add(item);
            }
            popup.add(roles);
            JMenu instruments = new JMenu("Instrument");
            instruments.setEnabled(editable);
            ButtonGroup oneInstrument = new ButtonGroup();
            for (int family = 0; family < FAMILIES.length; family++) {
                JMenu group = new JMenu(FAMILIES[family] + (track.program() / 8 == family ? "  ●" : ""));
                for (int offset = 0; offset < 8; offset++) {
                    final int program = family * 8 + offset;
                    JRadioButtonMenuItem item = new JRadioButtonMenuItem(GeneralMidi.entry(program),
                            program == track.program());
                    item.addActionListener(event -> {
                        // A description that only named the old instrument follows the new one.
                        String words = track.instrument().trim().equalsIgnoreCase(GeneralMidi.name(track.program()))
                                ? GeneralMidi.name(program) : track.instrument();
                        change(track.id(), track.role(), words, program, track.libraryId());
                    });
                    oneInstrument.add(item);
                    group.add(item);
                }
                instruments.add(group);
            }
            popup.add(instruments);
            JMenuItem describe = new JMenuItem("Beschreibung für die KI …");
            describe.setToolTipText(track.instrument());
            describe.setEnabled(editable);
            describe.addActionListener(event -> describe());
            popup.add(describe);
            JMenu banks = new JMenu("Soundbank");
            banks.setEnabled(editable);
            ButtonGroup oneBank = new ButtonGroup();
            JRadioButtonMenuItem builtIn = new JRadioButtonMenuItem(BUILT_IN, "builtin".equals(track.libraryId()));
            builtIn.addActionListener(event -> change(track.id(), track.role(), track.instrument(), track.program(),
                    "builtin"));
            oneBank.add(builtIn);
            banks.add(builtIn);
            boolean found = "builtin".equals(track.libraryId());
            for (final InstrumentLibrary library : registry.installedInstruments()) {
                boolean used = library.id().equals(track.libraryId());
                found |= used;
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(library.name(), used);
                item.addActionListener(event -> change(track.id(), track.role(), track.instrument(),
                        track.program(), library.id()));
                oneBank.add(item);
                banks.add(item);
            }
            if (!found) {
                JRadioButtonMenuItem missing = new JRadioButtonMenuItem(bankName(track.libraryId()), true);
                oneBank.add(missing);
                banks.add(missing);
            }
            popup.add(banks);
            popup.addSeparator();
            JMenuItem higher = new JMenuItem("Nach oben");
            higher.setEnabled(editable && index > 0);
            higher.addActionListener(event -> moveTrack(Head.this, -1));
            popup.add(higher);
            JMenuItem lower = new JMenuItem("Nach unten");
            lower.setEnabled(editable && index < heads.size() - 1);
            lower.addActionListener(event -> moveTrack(Head.this, 1));
            popup.add(lower);
            JMenuItem remove = new JMenuItem("Spur entfernen");
            remove.setEnabled(editable);
            remove.addActionListener(event -> removeTrack(Head.this));
            popup.add(remove);
            popup.show(invoker, x, y);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = DeckWidgets.smooth(graphics);
            Color color = mute.isSelected() ? new Color(0x6A6C78) : AudioStudioTheme.channel(index);
            boolean chosen = track.id().equals(alone);
            g.setColor(index % 2 == 0 ? new Color(0x1E2028) : new Color(0x1B1D24));
            g.fillRect(0, 0, getWidth(), getHeight());
            if (chosen) {
                g.setColor(AudioStudioTheme.withAlpha(color, 40));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            float glow = (float) Math.abs(Math.sin(phase * Math.PI * 2));
            int bar = chosen ? 7 : 5;
            g.setColor(work == Work.WORKING ? AudioStudioTheme.withAlpha(color, 90 + Math.round(165 * glow))
                    : work == Work.FAILED ? AudioStudioTheme.RED : color);
            g.fillRect(0, 4, bar, getHeight() - 8);
            g.setColor(new Color(255, 255, 255, 14));
            g.fillRect(0, getHeight() - 1, getWidth(), 1);
            g.setColor(AudioStudioTheme.STROKE);
            g.fillRect(getWidth() - 1, 0, 1, getHeight());
            g.dispose();
        }
    }
}
