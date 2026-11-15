package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelLineup;
import com.aresstack.audio.studio.album.Album;
import com.aresstack.audio.studio.album.AlbumConcept;
import com.aresstack.audio.studio.album.AlbumExporter;
import com.aresstack.audio.studio.album.AlbumFiles;
import com.aresstack.audio.studio.album.AlbumMembership;
import com.aresstack.audio.studio.album.AlbumProducer;
import com.aresstack.audio.studio.album.AlbumProductionCoordinator;
import com.aresstack.audio.studio.album.AlbumProductionState;
import com.aresstack.audio.studio.album.AlbumProductionState.Status;
import com.aresstack.audio.studio.album.AlbumRoles;
import com.aresstack.audio.studio.album.AlbumSuggester;
import com.aresstack.audio.studio.album.AlbumSuggester.Field;
import com.aresstack.audio.studio.album.AlbumSuggestions;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.album.AutopilotPieceProduction;
import com.aresstack.audio.studio.album.ChatAlbumPlanner;
import com.aresstack.audio.studio.album.ChatAlbumSuggester;
import com.aresstack.audio.studio.album.LimitedChatModel;
import com.aresstack.audio.studio.application.PieceAutopilot;
import com.aresstack.audio.studio.application.SongLibrary;
import com.aresstack.audio.studio.application.StageMixdown;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The page "Alben": several pieces planned and made as one album, beside the single piece. The
 * user says once what the album is about, the AI drafts the list of titles, the user changes it,
 * and one key lets every title become a piece of the library without further questions. A title
 * that has its piece is opened in the usual editor from here.
 *
 * <p>The page only shows and asks; what an album is, how it is kept, planned, produced and
 * exported lies in {@code studio.album}.
 */
final class AlbumsPanel extends JPanel {
    /** What the page needs from the window. */
    interface Host {
        /**
         * The models as the settings say, one lineup per call: for one plan or one production.
         * Throws with words for the user if the settings are incomplete.
         */
        ModelLineup models();

        int tokens();

        /** How many requests the model takes at once: the limit over all pieces of an album together. */
        int parallelRequests();

        /** How many bars one request for notes covers (setting "Takte je Abschnitt"). */
        default int sectionBars() {
            return com.aresstack.audio.studio.application.CompositionSpec.SECTION_BARS;
        }

        /** How many bars a long piece begins with and grows by (setting "Takte je Wachstumsschritt"). */
        default int growBars() {
            return PieceAutopilot.GROW_BARS;
        }

        /** The fewest and the most instruments the AI may choose for a title; {@code {0, 0}} leaves it to the AI. */
        default int[] voices() {
            return new int[]{0, 0};
        }

        PieceAutopilot.Rendering rendering();

        /** Opens a piece in the editor of the single piece. */
        void openPiece(Path folder);

        /** Lets the user choose a folder to export into; {@code null} if they do not. */
        Path chooseFolder(String title);

        void log(String line);

        /** Asks for a line of text; {@code null} if the user cancels. */
        String ask(String question, String proposal);

        boolean confirm(String question);

        /** The library changed: pieces were made. */
        void piecesChanged();

        /** The user chose this album in the list of the page; the window's choice of the album follows. */
        default void albumChosen(String id) {
        }

        /** An album was written: its title, its concept or its list of titles changed. */
        default void albumChanged() {
        }
    }

    /** How many pieces of an album are made at the same time. */
    static final int PIECES_AT_ONCE = 2;

    private final Host host;
    /** The list of the albums at the left of the page; hidden when the window has a tree of them. */
    private final JPanel left = new JPanel(new BorderLayout(0, 8));
    private final SongLibrary songs;
    private final InstrumentRegistry registry;
    private final AlbumFiles files;

    private final DefaultListModel<Album> albums = new DefaultListModel<Album>();
    private final JList<Album> albumList = new JList<Album>(albums);
    private final JTextField title = new JTextField();
    private final JTextField description = new JTextField();
    private final JTextField style = new JTextField();
    private final SuggestBox titleBox = new SuggestBox(title);
    private final SuggestBox descriptionBox = new SuggestBox(description);
    private final SuggestBox styleBox = new SuggestBox(style);
    private final JSpinner trackCount = new JSpinner(new SpinnerNumberModel(12, 1, AlbumConcept.MAX_TRACKS, 1));
    /** How long a piece may be: from … to … minutes; the plan gives each title its own length in it. */
    private final JSpinner minutes = new JSpinner(new SpinnerNumberModel(
            AlbumConcept.DEFAULT_MIN_SECONDS / 60.0, 0.5, 60.0, 0.5));
    private final JSpinner minutesTo = new JSpinner(new SpinnerNumberModel(
            AlbumConcept.DEFAULT_MAX_SECONDS / 60.0, 0.5, 60.0, 0.5));
    private final Tracks tracks = new Tracks();
    private final JTable table = new JTable(tracks);
    private final JLabel status = new JLabel(" ");
    private final JButton create = new JButton("Neues Album");
    private final JButton delete = new JButton("Löschen");
    private final JButton plan = new DeckWidgets.HeadKey("Trackliste planen");
    private final JButton add = new DeckWidgets.HeadKey("+ Titel");
    private final JButton remove = new DeckWidgets.HeadKey("− Titel");
    private final JButton up = new DeckWidgets.HeadKey("▲");
    private final JButton down = new DeckWidgets.HeadKey("▼");
    private final JButton produce = new DeckWidgets.HeadKey("Album produzieren");
    private final JButton cancel = new DeckWidgets.HeadKey("Abbrechen");
    private final JButton again = new DeckWidgets.HeadKey("Titel neu");
    private final JButton open = new DeckWidgets.HeadKey("Öffnen");
    private final JButton export = new DeckWidgets.HeadKey("Exportieren");

    private Album album;
    private AlbumProductionState state = AlbumProductionState.EMPTY;
    /** What the piece of each title is doing right now, while the album is produced. */
    private final Map<String, String> doing = new HashMap<String, String>();
    private AlbumProductionCoordinator.Production production;
    private boolean busy;
    private boolean showing;

    /** How long the user must have stopped typing before the AI is asked for proposals. */
    static final int TYPING_PAUSE_MILLIS = 900;
    /** The proposals made for the fields of the album shown; kept with the album. */
    private AlbumSuggestions suggestions = AlbumSuggestions.EMPTY;
    /** The titles the AI has planned so far while it still writes the list; {@code null} otherwise. */
    private List<AlbumTrack> preview;
    /** Who proposes; {@code null} asks the model of the settings. Set by a test. */
    private AlbumSuggester suggester;
    /** One request after the other, so that typing never floods the model. */
    private final ExecutorService proposing = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "album-proposals");
        thread.setDaemon(true);
        return thread;
    });
    /** What each field was last asked with, so that the same question is not asked twice. */
    private final Map<Field, String> askedWith = new EnumMap<Field, String>(Field.class);
    private final Map<Field, Integer> waiting = new EnumMap<Field, Integer>(Field.class);
    /** Why the last request for a field gave nothing; told at the field and under the list. */
    private final Map<Field, String> unanswered = new EnumMap<Field, String>(Field.class);
    private final Timer afterTitle = pause(() -> {
        propose(Field.STYLE);
        propose(Field.DESCRIPTION);
        propose(Field.TITLE);
    });
    private final Timer afterDescription = pause(() -> propose(Field.STYLE));
    private final Timer afterStyle = pause(() -> propose(Field.STYLE));

    /** The concept of the album, and beside it what the window adds (the choice of the models). */
    private final JPanel conceptRow = new JPanel(new BorderLayout(16, 0));
    private javax.swing.JComponent beside;
    /** Turns over the track list while the AI plans it. */
    private final DeckWidgets.WaitCircle planning = new DeckWidgets.WaitCircle();

    /** Whether the wait circle over the track list turns (for a test). */
    boolean waitingForTracks() {
        return planning.turning();
    }

    /** Something that belongs beside the concept of the album: the models that plan and compose it. */
    void setBesideConcept(javax.swing.JComponent component) {
        beside = component;
        conceptRow.add(component, BorderLayout.EAST);
        conceptRow.revalidate();
    }

    AlbumsPanel(Host host, SongLibrary songs, InstrumentRegistry registry) {
        super(new BorderLayout(14, 0));
        this.host = host;
        this.songs = songs;
        this.registry = registry;
        this.files = new AlbumFiles(songs.root());
        setOpaque(false);

        albumList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        albumList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focused) {
                Album shown = (Album) value;
                return super.getListCellRendererComponent(list, shown.title() + "   ·   " + shown.tracks().size()
                        + " Titel", index, selected, focused);
            }
        });
        albumList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !showing) {
                show(albumList.getSelectedValue());
                if (album != null) {
                    host.albumChosen(album.id());
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(albumList);
        listScroll.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        JPanel listKeys = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
        listKeys.setOpaque(false);
        listKeys.add(create);
        listKeys.add(delete);
        left.setOpaque(false);
        left.setPreferredSize(new Dimension(250, 200));
        left.add(DeckWidgets.lettering("Alben"), BorderLayout.NORTH);
        left.add(listScroll, BorderLayout.CENTER);
        left.add(listKeys, BorderLayout.SOUTH);
        add(left, BorderLayout.WEST);

        JPanel concept = new JPanel(new GridBagLayout());
        concept.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 6, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        row(concept, c, 0, "Titel", titleBox, 3);
        row(concept, c, 1, "Worum geht es? Das bekommt die KI gesagt.", descriptionBox, 3);
        row(concept, c, 2, "Stil", styleBox, 3);
        // An empty list that is opened asks the AI (again): a request that failed is not the last word.
        for (final Field field : Field.values()) {
            box(field).addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
                    if (suggestions.of(field).isEmpty()) {
                        propose(field);
                    }
                }

                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
                }
            });
        }
        typed(title, afterTitle);
        typed(description, afterDescription);
        typed(style, afterStyle);
        c.gridy = 3;
        c.gridwidth = 1;
        c.gridx = 0;
        c.weightx = 0;
        concept.add(dim("Stücke"), c);
        c.gridx = 1;
        concept.add(trackCount, c);
        c.gridx = 2;
        concept.add(dim("Minuten je Stück"), c);
        c.gridx = 3;
        JPanel range = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        range.setOpaque(false);
        minutes.setToolTipText("So lang ist ein Stück mindestens");
        minutesTo.setToolTipText("So lang ist ein Stück höchstens; die KI gibt jedem Titel eine eigene Länge dazwischen");
        range.add(dim("von"));
        range.add(minutes);
        range.add(dim("bis"));
        range.add(minutesTo);
        concept.add(range, c);
        c.gridx = 4;
        c.weightx = 1;
        concept.add(javax.swing.Box.createHorizontalGlue(), c);

        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        int[] widths = {34, 210, 170, 56, 90, 56, 200, 120, 190};
        // The columns keep their widths; only the state at the end takes what is left. A long
        // message there is cut with "…" and shows whole as the tooltip, instead of squeezing the others.
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        for (int column = 0; column < widths.length; column++) {
            javax.swing.table.TableColumn each = table.getColumnModel().getColumn(column);
            each.setPreferredWidth(widths[column]);
            each.setWidth(widths[column]);
            if (column != Tracks.STATE) {
                each.setMinWidth(widths[column] * 3 / 4);
            }
        }
        table.getColumnModel().getColumn(Tracks.STATE).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable shown, Object value, boolean selected,
                                                           boolean focused, int row, int column) {
                Component cell = super.getTableCellRendererComponent(shown, value, selected, focused, row, column);
                Status at = state.status(album.tracks().get(row).id());
                cell.setForeground(at == Status.DONE ? AudioStudioTheme.GREEN : at == Status.FAILED
                        ? AudioStudioTheme.RED : at == Status.RUNNING ? AudioStudioTheme.AMBER
                        : AudioStudioTheme.TEXT_DIM);
                setToolTipText(value == null ? null : value.toString());
                return cell;
            }
        });
        table.getSelectionModel().addListSelectionListener(event -> keys());
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));

        // The keys of the album stand in the head of its module, beside the name, as on the other
        // pages: first what the AI does with the whole album, then the keys of the chosen title.
        headKeys.setOpaque(false);
        headKeys.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        hint(plan, "Die KI plant aus dem Konzept die Trackliste des Albums");
        hint(add, "Einen Titel ans Ende der Trackliste anfügen");
        hint(remove, "Den gewählten Titel aus der Trackliste nehmen");
        hint(up, "Den gewählten Titel nach oben schieben");
        hint(down, "Den gewählten Titel nach unten schieben");
        hint(open, "Den gewählten Titel im Editor öffnen");
        hint(again, "Den gewählten Titel neu produzieren");
        hint(export, "Das Album in einen Ordner exportieren");
        hint(cancel, "Die laufende Produktion abbrechen");
        for (java.awt.Component key : new java.awt.Component[]{plan, produce, cancel, export,
                javax.swing.Box.createHorizontalStrut(10), add, remove, up, down, open, again}) {
            headKeys.add(key);
        }
        status.setForeground(AudioStudioTheme.TEXT_DIM);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(status, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(0, 12));
        right.setOpaque(false);
        conceptRow.setOpaque(false);
        conceptRow.add(concept, BorderLayout.CENTER);
        right.add(conceptRow, BorderLayout.NORTH);
        // The wait circle lies over the list: two components on one place, the circle on top.
        JPanel listed = new JPanel() {
            @Override
            public boolean isOptimizedDrawingEnabled() {
                return false;
            }
        };
        listed.setLayout(new javax.swing.OverlayLayout(listed));
        listed.setOpaque(false);
        planning.setAlignmentX(0.5f);
        planning.setAlignmentY(0.5f);
        tableScroll.setAlignmentX(0.5f);
        tableScroll.setAlignmentY(0.5f);
        listed.add(planning);
        listed.add(tableScroll);
        right.add(listed, BorderLayout.CENTER);
        right.add(bottom, BorderLayout.SOUTH);
        add(right, BorderLayout.CENTER);

        create.addActionListener(event -> createAlbum());
        delete.addActionListener(event -> deleteAlbum());
        plan.addActionListener(event -> planTracks());
        add.addActionListener(event -> addTrack());
        remove.addActionListener(event -> removeTrack());
        up.addActionListener(event -> moveTrack(-1));
        down.addActionListener(event -> moveTrack(1));
        produce.addActionListener(event -> produceAlbum());
        cancel.addActionListener(event -> cancelProduction());
        again.addActionListener(event -> produceAgain());
        open.addActionListener(event -> openPiece());
        export.addActionListener(event -> exportAlbum());
        reload(null);
    }

    private static JLabel dim(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(AudioStudioTheme.TEXT_DIM);
        return label;
    }

    private static void row(JPanel panel, GridBagConstraints c, int y, String label, Component field, int span) {
        c.gridy = y;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(dim(label), c);
        c.gridx = 1;
        c.gridwidth = span + 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    // ---- which album ----

    /** Reads the albums again and shows the one with this id, else the first. */
    void reload(String id) {
        showing = true;
        Album chosen = null;
        albums.clear();
        for (Album each : files.albums()) {
            albums.addElement(each);
            if (each.id().equals(id)) {
                chosen = each;
            }
        }
        if (chosen == null && !albums.isEmpty()) {
            chosen = albums.get(0);
        }
        albumList.setSelectedValue(chosen, true);
        showing = false;
        show(chosen);
    }

    private void show(Album chosen) {
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        album = chosen;
        doing.clear();
        state = chosen == null ? AlbumProductionState.EMPTY
                : AlbumProductionState.load(files.folder(chosen.id()));
        title.setText(chosen == null ? "" : chosen.title());
        description.setText(chosen == null ? "" : chosen.concept().description());
        style.setText(chosen == null ? "" : chosen.concept().style());
        afterTitle.stop();
        afterDescription.stop();
        afterStyle.stop();
        askedWith.clear();
        unanswered.clear();
        suggestions = chosen == null ? AlbumSuggestions.EMPTY : AlbumSuggestions.load(files.folder(chosen.id()));
        showProposals();
        trackCount.setValue(chosen == null ? 12 : chosen.concept().trackCount());
        minutes.setValue(chosen == null ? AlbumConcept.DEFAULT_MIN_SECONDS / 60.0 : chosen.concept().minSeconds() / 60.0);
        minutesTo.setValue(chosen == null ? AlbumConcept.DEFAULT_MAX_SECONDS / 60.0 : chosen.concept().maxSeconds() / 60.0);
        tracks.fireTableDataChanged();
        say(chosen == null ? "Noch kein Album. „Neues Album“ legt eines an." : summary());
        keys();
        if (chosen != null && suggestions.of(Field.STYLE).isEmpty() && !AlbumMembership.isUntitled(chosen)) {
            // The title stands: first directions for the style, and ideas for the other fields.
            propose(Field.STYLE);
            propose(Field.DESCRIPTION);
            propose(Field.TITLE);
        }
    }

    /**
     * Shows this album as the window chose it, read anew from its file, unless the page is in the
     * middle of something: a plan, a proposal or a production.
     */
    void follow(String id) {
        if (busy || (production != null && production.running())) {
            return;
        }
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        if (album != null) {
            keepConcept(); // what was typed into title, description or style is kept, not thrown away
        }
        reload(id);
    }

    /** The album shown, for a test. */
    Album album() {
        return album;
    }

    JTable table() {
        return table;
    }

    String status() {
        return status.getText();
    }

    private String summary() {
        int done = state.count(album, Status.DONE);
        int failed = state.count(album, Status.FAILED);
        return album.tracks().size() + " Titel · Ziellänge " + clock(album.targetSeconds()) + " · " + done
                + " fertig" + (failed > 0 ? " · " + failed + " fehlgeschlagen" : "");
    }

    private void say(String text) {
        status.setText(text == null || text.isEmpty() ? " " : text);
    }

    private void keys() {
        boolean there = album != null;
        boolean running = production != null && production.running();
        boolean free = there && !running && !busy;
        int row = table.getSelectedRow();
        AlbumTrack selected = there && row >= 0 && row < album.tracks().size() ? album.tracks().get(row) : null;
        create.setEnabled(!running && !busy);
        delete.setEnabled(free);
        for (Component field : new Component[]{titleBox, descriptionBox, styleBox, trackCount, minutes, minutesTo}) {
            field.setEnabled(free);
        }
        plan.setEnabled(free);
        if (beside != null) {
            beside.setEnabled(!running && !busy); // no model is chosen while the models are at work
        }
        add.setEnabled(free);
        remove.setEnabled(free && selected != null);
        up.setEnabled(free && selected != null && row > 0);
        down.setEnabled(free && selected != null && row < album.tracks().size() - 1);
        produce.setEnabled(free && !album.tracks().isEmpty());
        // A run that left titles undone (failed, cancelled) is taken up: only what is missing is made.
        int done = there ? state.count(album, Status.DONE) : 0;
        boolean partly = there && done < album.tracks().size()
                && (done > 0 || state.count(album, Status.FAILED) > 0);
        produce.setText(partly ? "Fehlende Titel produzieren" : "Album produzieren");
        produce.setToolTipText(partly ? "Macht nur die Titel, die noch nicht fertig sind; fertige bleiben, wie sie sind"
                : null);
        cancel.setEnabled(running);
        again.setEnabled(free && selected != null && selected.hasPiece());
        open.setEnabled(there && selected != null && selected.hasPiece());
        // A piece put into the album by hand, or an old one in the unnamed album, can be exported as well.
        export.setEnabled(free && (state.count(album, Status.DONE) > 0
                || album != null && album.tracks().stream().anyMatch(AlbumTrack::hasPiece)));
    }

    // ---- proposals of the AI for the fields ----

    private static Timer pause(Runnable then) {
        Timer timer = new Timer(TYPING_PAUSE_MILLIS, event -> then.run());
        timer.setRepeats(false);
        return timer;
    }

    /** When the user has typed into a field and paused, proposals are asked for. */
    private void typed(JTextField field, Timer timer) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
            }

            private void changed() {
                // Only what the user types counts, not what the page puts into the field itself.
                if (album != null && field.isFocusOwner()) {
                    timer.restart();
                }
            }
        });
    }

    void suggester(AlbumSuggester suggester) {
        this.suggester = suggester;
    }

    SuggestBox box(Field field) {
        return field == Field.TITLE ? titleBox : field == Field.DESCRIPTION ? descriptionBox : styleBox;
    }

    private void showProposals() {
        for (Field field : Field.values()) {
            List<String> known = suggestions.of(field);
            box(field).proposals(known);
            String problem = unanswered.get(field);
            box(field).setToolTipText(problem != null
                    ? "Die Vorschläge der KI blieben aus: " + problem + " · Den Pfeil öffnen fragt noch einmal."
                    : known.isEmpty() ? "Vorschläge der KI erscheinen hier, sobald ein Titel steht."
                    : known.size() + " Vorschläge der KI: mit dem Pfeil öffnen.");
        }
        // A style proposed while none is written yet shows faintly in the field, as a first idea.
        List<String> styles = suggestions.of(Field.STYLE);
        style.putClientProperty("JTextField.placeholderText", styles.isEmpty() ? null : styles.get(0));
        style.repaint();
    }

    /**
     * Asks for proposals for a field with what the fields say now, in the background; they are
     * added in front of the ones made before and kept with the album. Nothing is asked without a
     * title, twice with the same words, or while an album is being produced.
     */
    void propose(Field field) {
        Album asked = album;
        if (asked == null || busy || (production != null && production.running())) {
            return;
        }
        String named = title.getText().trim();
        String about = description.getText().trim();
        String sounds = style.getText().trim();
        if (named.isEmpty()) {
            return;
        }
        String with = field == Field.TITLE ? named + "\n" + about
                : field == Field.DESCRIPTION ? named + "\n" + sounds : named + "\n" + about + "\n" + sounds;
        if (with.equals(askedWith.get(field))) {
            return;
        }
        final AlbumSuggester proposer;
        try {
            proposer = suggester != null ? suggester
                    : new ChatAlbumSuggester(host.models().model(AlbumRoles.ALBUM), host.tokens());
        } catch (RuntimeException noModel) {
            // Without a model there are no proposals; the fields work as before, and say why.
            unanswered.put(field, String.valueOf(noModel.getMessage()));
            showProposals();
            return;
        }
        askedWith.put(field, with);
        List<String> known = suggestions.of(field);
        waiting.merge(field, 1, Integer::sum);
        box(field).setToolTipText("Die KI sucht Vorschläge …");
        proposing.execute(() -> {
            List<String> fresh;
            String problem = null;
            try {
                // Each proposal shows in the list as soon as the AI has written it.
                fresh = proposer.suggest(field, named, about, sounds, known, seen -> SwingUtilities.invokeLater(() -> {
                    if (album == null || !album.id().equals(asked.id()) || !with.equals(askedWith.get(field))) {
                        return;
                    }
                    List<String> shown = new ArrayList<String>(seen);
                    for (String old : known) {
                        if (shown.stream().noneMatch(one -> one.equalsIgnoreCase(old))) {
                            shown.add(old);
                        }
                    }
                    boolean open = box(field).isPopupVisible();
                    box(field).proposals(shown);
                    box(field).setToolTipText("Die KI schreibt Vorschläge … " + seen.size() + " bisher");
                    if (open) {
                        box(field).showPopup();
                    }
                }));
            } catch (IOException | RuntimeException failed) {
                fresh = List.of();
                problem = String.valueOf(failed.getMessage());
                host.log("Vorschläge der KI (" + field.key() + ") blieben aus: " + failed.getMessage());
            }
            List<String> found = fresh;
            String said = problem;
            SwingUtilities.invokeLater(() -> {
                waiting.merge(field, -1, Integer::sum);
                if (album == null || !album.id().equals(asked.id())) {
                    return;
                }
                if (said != null) {
                    // A request that failed is no answer: the same words may be asked again.
                    if (with.equals(askedWith.get(field))) {
                        askedWith.remove(field);
                    }
                    unanswered.put(field, said);
                    say("Die Vorschläge der KI blieben aus: " + said);
                } else {
                    unanswered.remove(field);
                }
                AlbumSuggestions grown = suggestions.with(field, found);
                if (grown != suggestions) {
                    suggestions = grown;
                    try {
                        suggestions.save(files.folder(asked.id()));
                    } catch (IOException failed) {
                        host.log("Die Vorschläge ließen sich nicht speichern: " + failed.getMessage());
                    }
                }
                boolean open = box(field).isPopupVisible();
                showProposals();
                if (open) {
                    box(field).showPopup();
                }
            });
        });
    }

    /** Whether proposals are still being asked for (for a test). */
    boolean proposingNow() {
        for (int count : waiting.values()) {
            if (count > 0) {
                return true;
            }
        }
        return false;
    }

    // ---- the concept ----

    /** Takes what the fields say into the album and keeps it. {@code false} if it cannot be kept. */
    private boolean keepConcept() {
        if (album == null) {
            return false;
        }
        try {
            AlbumConcept concept = new AlbumConcept(description.getText(), style.getText(),
                    (Integer) trackCount.getValue(),
                    (int) Math.round(((Number) minutes.getValue()).doubleValue() * 60),
                    (int) Math.round(((Number) minutesTo.getValue()).doubleValue() * 60));
            // The titles as the file has them: the window may have put a piece into the album meanwhile.
            Album changed = files.album(album.id()).orElse(album).withTitle(title.getText()).withConcept(concept);
            if (!changed.equals(album)) {
                keep(changed);
            }
            return true;
        } catch (IllegalArgumentException | IOException failed) {
            say("Das Album ließ sich nicht speichern: " + failed.getMessage());
            return false;
        }
    }

    private void keep(Album changed) throws IOException {
        files.save(changed);
        album = changed;
        int at = albumList.getSelectedIndex();
        if (at >= 0) {
            showing = true;
            albums.set(at, changed);
            showing = false;
        }
        host.albumChanged();
    }

    private final JPanel headKeys = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

    /** Gives a key its hint unless it has one already. */
    private static void hint(JButton key, String text) {
        if (key.getToolTipText() == null) {
            key.setToolTipText(text);
        }
    }

    /** The keys of the album, to stand in the head of its module beside the name. */
    JPanel headKeys() {
        return headKeys;
    }

    /** The window chooses the albums in a tree of its own: the list of the page is not shown. */
    void listInTree() {
        remove(left);
        revalidate();
    }

    /** Whether the page waits for the AI: a plan or a proposal. */
    boolean busy() {
        return busy;
    }

    /** Whether an album is being produced. */
    boolean producing() {
        return production != null && production.running();
    }

    void createAlbum() {
        String name = host.ask("Wie soll das Album heißen? Ohne Titel heißt es „" + AlbumMembership.UNTITLED + "“.",
                "Neues Album");
        if (name == null) {
            return;
        }
        try {
            Album made = files.create(name.isBlank() ? AlbumMembership.UNTITLED : name.trim(),
                    AlbumConcept.standard("", "", 12));
            reload(made.id());
            host.albumChosen(made.id()); // the album chosen above follows the new one
            description.requestFocusInWindow();
        } catch (IOException failed) {
            say("Das Album ließ sich nicht anlegen: " + failed.getMessage());
        }
    }

    void deleteAlbum() {
        if (album == null || !host.confirm("Das Album „" + album.title() + "“ löschen?\n\n"
                + "Seine Stücke bleiben erhalten und kommen ins unbenannte Album.")) {
            return;
        }
        try {
            files.delete(album.id());
            reload(null);
            if (album != null) {
                host.albumChosen(album.id());
            }
        } catch (IOException failed) {
            say("Das Album ließ sich nicht löschen: " + failed.getMessage());
        }
    }

    // ---- the list of titles ----

    void planTracks() {
        if (!keepConcept()) {
            return;
        }
        if (album.concept().description().isEmpty()) {
            say("Erst sagen, worum es in dem Album geht.");
            return;
        }
        final ChatModel model;
        try {
            model = host.models().model(AlbumRoles.ALBUM);
        } catch (RuntimeException invalid) {
            say(invalid.getMessage());
            return;
        }
        final Album asked = album;
        final int tokens = host.tokens();
        busy = true;
        table.requestFocusInWindow(); // the key that was pressed is locked now: the focus goes to the list, not to a choice
        keys();
        planning.turn(true);
        say("Die KI plant die Trackliste ...");
        Thread worker = new Thread(() -> {
            Album planned = null;
            String problem = null;
            try {
                // Every title shows in the list as soon as the AI has written it.
                planned = new ChatAlbumPlanner(model, tokens).watching(seen -> SwingUtilities.invokeLater(() -> {
                    if (busy && album != null && album.id().equals(asked.id())) {
                        preview = seen;
                        tracks.fireTableDataChanged();
                    }
                })).plan(asked, files.folder(asked.id()), host::log);
                files.save(planned);
                host.albumChanged(); // the tree shows the planned titles at once
            } catch (IOException | RuntimeException failed) {
                problem = failed.getMessage() == null ? failed.toString() : failed.getMessage();
            }
            final Album result = planned;
            final String said = problem;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                preview = null;
                tracks.fireTableDataChanged();
                planning.turn(false);
                if (result != null) {
                    reload(result.id());
                    say("Trackliste bereit. Du kannst sie ändern und danach das Album produzieren.");
                } else {
                    say("Die Trackliste ließ sich nicht planen: " + said);
                    keys();
                }
            });
        }, "album-plan");
        worker.setDaemon(true);
        worker.start();
    }

    private void change(List<AlbumTrack> changed, int select) {
        try {
            keep(album.withTracks(changed));
            tracks.fireTableDataChanged();
            if (select >= 0 && select < changed.size()) {
                table.setRowSelectionInterval(select, select);
            }
            say(summary());
        } catch (IOException | RuntimeException failed) {
            say("Die Trackliste ließ sich nicht speichern: " + failed.getMessage());
        }
        keys();
    }

    private void addTrack() {
        if (!keepConcept()) {
            return;
        }
        List<AlbumTrack> changed = new ArrayList<AlbumTrack>(album.tracks());
        changed.add(new AlbumTrack(album.freshTrackId(), "Titel " + (changed.size() + 1), "", 100, "C major", "4/4",
                "", "", album.concept().secondsPerTrack(), ""));
        change(changed, changed.size() - 1);
    }

    private void removeTrack() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        AlbumTrack gone = album.tracks().get(row);
        if (gone.hasPiece() && !host.confirm("„" + gone.title() + "“ aus dem Album nehmen?\n\n"
                + "Das Stück bleibt erhalten und kommt ins unbenannte Album.")) {
            return;
        }
        List<AlbumTrack> changed = new ArrayList<AlbumTrack>(album.tracks());
        changed.remove(row);
        change(changed, Math.min(row, changed.size() - 1));
    }

    private void moveTrack(int by) {
        int row = table.getSelectedRow();
        int to = row + by;
        if (row < 0 || to < 0 || to >= album.tracks().size()) {
            return;
        }
        List<AlbumTrack> changed = new ArrayList<AlbumTrack>(album.tracks());
        changed.add(to, changed.remove(row));
        change(changed, to);
    }

    private void openPiece() {
        int row = table.getSelectedRow();
        if (row >= 0 && album.tracks().get(row).hasPiece()) {
            host.openPiece(songs.root().resolve(album.tracks().get(row).piece()));
        }
    }

    // ---- making the pieces ----

    void produceAlbum() {
        if (!keepConcept() || album.tracks().isEmpty()) {
            return;
        }
        AlbumProducer producer = producer();
        if (producer != null) {
            start(producer);
        }
    }

    /** A producer with the settings of the window as they are now; {@code null} if one is missing (said). */
    private AlbumProducer producer() {
        final AlbumProducer producer;
        try {
            // What the window says is read once, here on the event thread; the workers never ask it.
            final int requests = Math.max(1, host.parallelRequests());
            final int sectionBars = host.sectionBars();
            final int growBars = host.growBars();
            final int[] voices = host.voices();
            final int tokens = host.tokens();
            final PieceAutopilot.Rendering rendering = host.rendering();
            // Every model of the production goes through the same permits: the limit holds over all pieces.
            final Semaphore permits = new Semaphore(requests, true);
            // The settings are checked now, so that a missing one is said before anything starts.
            final ModelLineup[] first = {host.models()};
            // Every start gets models of its own: "Abbrechen" stops those of its run for good.
            producer = new AlbumProducer(files, songs, () -> {
                ModelLineup models = first[0] != null ? first[0] : host.models();
                first[0] = null;
                return new AutopilotPieceProduction(new PieceAutopilot(registry,
                        models.through(model -> new LimitedChatModel(model, permits)), tokens, requests, rendering)
                        .sectionBars(sectionBars).growBars(growBars).voices(voices[0], voices[1]),
                        songs);
            }, PIECES_AT_ONCE);
        } catch (RuntimeException invalid) {
            say(invalid.getMessage());
            return null;
        }
        return producer;
    }

    /** Starts the production with this producer; a test hands in one that needs no model. */
    void start(AlbumProducer producer) {
        start(producer, null);
    }

    /** Starts the production of every unfinished title, or of the one title {@code onlyTrack}. */
    void start(AlbumProducer producer, String onlyTrack) {
        final String id = album.id();
        try {
            production = producer.start(id, new AlbumProductionCoordinator.Listener() {
                @Override
                public void changed(Album now, AlbumProductionState at) {
                    SwingUtilities.invokeLater(() -> produced(id, now, at));
                }

                @Override
                public void progress(AlbumTrack track, String message) {
                    SwingUtilities.invokeLater(() -> {
                        if (album != null && album.id().equals(id)) {
                            doing.put(track.id(), message);
                            tracks.fireTableRowsUpdated(0, Math.max(0, album.tracks().size() - 1));
                        }
                    });
                }
            }, onlyTrack);
        } catch (IOException | RuntimeException failed) {
            say("Das Album ließ sich nicht produzieren: " + failed.getMessage());
            return;
        }
        say(onlyTrack != null ? "Der Titel wird neu produziert."
                : "Das Album wird produziert: " + PIECES_AT_ONCE + " Stücke gleichzeitig.");
        keys();
        final AlbumProductionCoordinator.Production running = production;
        Thread waiter = new Thread(() -> {
            try {
                running.await();
            } catch (InterruptedException stopped) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (production == running) {
                    production = null;
                }
                doing.clear();
                host.piecesChanged();
                if (album != null && album.id().equals(id)) {
                    reload(id);
                    int failed = state.count(album, Status.FAILED);
                    int waiting = state.count(album, Status.QUEUED);
                    say((failed > 0 ? failed + " Titel fehlgeschlagen; „Fehlende Titel produzieren“ versucht sie erneut. "
                            : waiting > 0 ? "Angehalten; der Knopf unten rechts setzt fort. " : "Das Album ist fertig. ")
                            + summary());
                }
                keys();
            });
        }, "album-wait");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void produced(String id, Album now, AlbumProductionState at) {
        if (album == null || !album.id().equals(id)) {
            return;
        }
        album = now;
        state = at;
        int row = table.getSelectedRow();
        tracks.fireTableDataChanged();
        if (row >= 0 && row < now.tracks().size()) {
            table.setRowSelectionInterval(row, row);
        }
        say("Das Album wird produziert · " + summary());
        keys();
    }

    private void cancelProduction() {
        if (production != null) {
            production.cancel();
            say("Wird angehalten ...");
        }
    }

    /**
     * Produces this one title of the album shown, at once, with what the album tells it: the same
     * as choosing it in the list and "Titel neu produzieren".
     */
    void produceTitle(String trackId) {
        if (album == null) {
            return;
        }
        for (int row = 0; row < album.tracks().size(); row++) {
            if (album.tracks().get(row).id().equals(trackId)) {
                table.getSelectionModel().setSelectionInterval(row, row);
                produceAgain();
                return;
            }
        }
        say("Dieser Titel steht nicht in „" + album.title() + "“.");
    }

    private void produceAgain() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        AlbumTrack track = album.tracks().get(row);
        if (state.status(track.id()) == Status.DONE && !host.confirm("„" + track.title() + "“ neu produzieren?\n\n"
                + "Was du an dem Stück im Editor geändert hast, kann dabei verloren gehen.")) {
            return;
        }
        if (production != null && production.running()) {
            say("Erst die laufende Produktion abwarten oder abbrechen, dann diesen Titel neu produzieren.");
            return;
        }
        if (!keepConcept()) {
            return;
        }
        try {
            state = state.with(track.id(), Status.QUEUED);
            state.save(files.folder(album.id()));
            tracks.fireTableDataChanged();
        } catch (IOException failed) {
            say("Das ließ sich nicht vormerken: " + failed.getMessage());
            keys();
            return;
        }
        // It starts now, this title alone; the others keep their state.
        AlbumProducer producer = producer();
        if (producer != null) {
            start(producer, track.id());
        }
        keys();
    }

    // ---- export ----

    private void exportAlbum() {
        final Path folder = host.chooseFolder("Album exportieren nach");
        if (folder == null) {
            return;
        }
        final Album exported = album;
        busy = true;
        keys();
        say("Das Album wird exportiert ...");
        Thread worker = new Thread(() -> {
            String said;
            try {
                AlbumExporter.Result result = new AlbumExporter(songs.root(), new StageMixdown()).export(exported,
                        folder, AlbumExporter.Options.STANDARD,
                        line -> SwingUtilities.invokeLater(() -> say(line)));
                said = result.titles().size() + " Titel exportiert nach " + folder
                        + (result.skipped().isEmpty() ? "" : " · " + result.skipped().size() + " fehlen");
            } catch (Exception failed) {
                said = "Der Export ist fehlgeschlagen: " + (failed.getMessage() == null ? failed : failed.getMessage());
            }
            final String line = said;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                say(line);
                host.log(line);
                keys();
            });
        }, "album-export");
        worker.setDaemon(true);
        worker.start();
    }

    static String clock(double seconds) {
        long whole = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60);
    }

    /** Minutes and seconds as the user writes them: "5:00", "4:30", or minutes alone ("5", "4,5"). */
    static int seconds(String text) {
        String clean = text.trim().replace(',', '.');
        int space = clean.indexOf(' ');
        if (space > 0) {
            clean = clean.substring(0, space); // "5:00 (150 Takte)"
        }
        int colon = clean.indexOf(':');
        if (colon >= 0) {
            return Integer.parseInt(clean.substring(0, colon).trim()) * 60
                    + Integer.parseInt(clean.substring(colon + 1).trim());
        }
        return (int) Math.round(Double.parseDouble(clean) * 60);
    }

    /** The list of titles as a table; a title is changed in its cells until its piece is finished. */
    private final class Tracks extends AbstractTableModel {
        static final int STATE = 8;
        private final String[] names = {"Nr.", "Titel", "Stimmung", "BPM", "Tonart", "Takt", "Besetzung", "Länge",
                "Stand"};

        @Override
        public int getRowCount() {
            return album == null ? 0 : rows().size();
        }

        /** The titles shown: those the AI is planning right now, else those of the album. */
        private List<AlbumTrack> rows() {
            return preview != null ? preview : album.tracks();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int column) {
            return names[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            if (column == 0 || column == STATE || busy || preview != null
                    || (production != null && production.running())) {
                return false;
            }
            Status at = state.status(album.tracks().get(row).id());
            // The name can always be changed; what the piece is composed with only before it is made.
            return column == 1 || (at != Status.DONE && at != Status.RUNNING);
        }

        @Override
        public Object getValueAt(int row, int column) {
            AlbumTrack track = rows().get(row);
            if (preview != null && column == STATE && !track.hasPiece()) {
                return "kommt von der KI …";
            }
            AlbumProductionState.Entry entry = state.of(track.id());
            switch (column) {
                case 0:
                    return row + 1;
                case 1:
                    return track.title();
                case 2:
                    return track.mood();
                case 3:
                    return track.bpm();
                case 4:
                    return track.key();
                case 5:
                    return track.meter();
                case 6:
                    return track.instrumentation();
                case 7:
                    if (entry.status() == Status.DONE && entry.seconds() > 0) {
                        return clock(entry.seconds()) + (track.asLongAsAsked(entry.seconds()) ? ""
                                : "  (Ziel " + clock(track.targetSeconds()) + ")");
                    }
                    return clock(track.targetSeconds()) + " (" + track.spec().bars() + " Takte)";
                default:
                    switch (entry.status()) {
                        case DONE:
                            return "fertig";
                        case FAILED:
                            return "Fehler: " + entry.reason();
                        case RUNNING:
                            if (production == null || !production.running()) {
                                // Left from a run that ended without finishing it: it goes on at the next start.
                                return "unterbrochen";
                            }
                            String now = doing.get(track.id());
                            return now == null ? "in Arbeit ..." : now;
                        case QUEUED:
                            return "wartet";
                        default:
                            return track.hasPiece() ? "eigenes Stück" : "geplant";
                    }
            }
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            AlbumTrack t = album.tracks().get(row);
            String text = value == null ? "" : value.toString().trim();
            try {
                AlbumTrack changed;
                switch (column) {
                    case 1:
                        changed = t.withTitle(text);
                        break;
                    case 2:
                        changed = new AlbumTrack(t.id(), t.title(), text, t.bpm(), t.key(), t.meter(),
                                t.instrumentation(), t.form(), t.targetSeconds(), t.piece());
                        break;
                    case 3:
                        changed = new AlbumTrack(t.id(), t.title(), t.mood(), Integer.parseInt(text), t.key(),
                                t.meter(), t.instrumentation(), t.form(), t.targetSeconds(), t.piece());
                        break;
                    case 4:
                        changed = new AlbumTrack(t.id(), t.title(), t.mood(), t.bpm(), text, t.meter(),
                                t.instrumentation(), t.form(), t.targetSeconds(), t.piece());
                        break;
                    case 5:
                        changed = new AlbumTrack(t.id(), t.title(), t.mood(), t.bpm(), t.key(), text,
                                t.instrumentation(), t.form(), t.targetSeconds(), t.piece());
                        break;
                    case 6:
                        changed = new AlbumTrack(t.id(), t.title(), t.mood(), t.bpm(), t.key(), t.meter(), text,
                                t.form(), t.targetSeconds(), t.piece());
                        break;
                    case 7:
                        changed = new AlbumTrack(t.id(), t.title(), t.mood(), t.bpm(), t.key(), t.meter(),
                                t.instrumentation(), t.form(), seconds(text), t.piece());
                        break;
                    default:
                        return;
                }
                if (!changed.equals(t)) {
                    keep(album.with(changed));
                    fireTableRowsUpdated(row, row);
                    say(summary());
                }
            } catch (NumberFormatException notANumber) {
                say("Das ist keine Zahl: " + text);
            } catch (IOException | RuntimeException failed) {
                say("Die Änderung ließ sich nicht speichern: " + failed.getMessage());
            }
        }
    }
}
