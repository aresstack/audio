package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelLineup;
import com.aresstack.audio.studio.album.Album;
import com.aresstack.audio.studio.album.AlbumConcept;
import com.aresstack.audio.studio.album.AlbumExporter;
import com.aresstack.audio.studio.album.AlbumFiles;
import com.aresstack.audio.studio.album.AlbumProducer;
import com.aresstack.audio.studio.album.AlbumProductionCoordinator;
import com.aresstack.audio.studio.album.AlbumProductionState;
import com.aresstack.audio.studio.album.AlbumProductionState.Status;
import com.aresstack.audio.studio.album.AlbumRoles;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.album.AutopilotPieceProduction;
import com.aresstack.audio.studio.album.ChatAlbumPlanner;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        /** The model as the settings say; throws with words for the user if they are incomplete. */
        ChatModel model();

        int tokens();

        /** How many requests the model takes at once: the limit over all pieces of an album together. */
        int parallelRequests();

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
    }

    /** How many pieces of an album are made at the same time. */
    static final int PIECES_AT_ONCE = 2;

    private final Host host;
    private final SongLibrary songs;
    private final InstrumentRegistry registry;
    private final AlbumFiles files;

    private final DefaultListModel<Album> albums = new DefaultListModel<Album>();
    private final JList<Album> albumList = new JList<Album>(albums);
    private final JTextField title = new JTextField();
    private final JTextField description = new JTextField();
    private final JTextField style = new JTextField();
    private final JSpinner trackCount = new JSpinner(new SpinnerNumberModel(12, 1, AlbumConcept.MAX_TRACKS, 1));
    private final JSpinner minutes = new JSpinner(new SpinnerNumberModel(5.0, 0.5, 60.0, 0.5));
    private final Tracks tracks = new Tracks();
    private final JTable table = new JTable(tracks);
    private final JLabel status = new JLabel(" ");
    private final JButton create = new JButton("Neues Album");
    private final JButton delete = new JButton("Löschen");
    private final JButton plan = new JButton("Trackliste planen (KI)");
    private final JButton add = new JButton("Titel hinzufügen");
    private final JButton remove = new JButton("Titel entfernen");
    private final JButton up = new JButton("▲");
    private final JButton down = new JButton("▼");
    private final JButton produce = new JButton("Album produzieren");
    private final JButton cancel = new JButton("Abbrechen");
    private final JButton again = new JButton("Titel neu produzieren");
    private final JButton open = new JButton("Im Editor öffnen");
    private final JButton export = new JButton("Exportieren …");

    private Album album;
    private AlbumProductionState state = AlbumProductionState.EMPTY;
    /** What the piece of each title is doing right now, while the album is produced. */
    private final Map<String, String> doing = new HashMap<String, String>();
    private AlbumProductionCoordinator.Production production;
    private boolean busy;
    private boolean showing;

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
            }
        });
        JScrollPane listScroll = new JScrollPane(albumList);
        listScroll.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        JPanel listKeys = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
        listKeys.setOpaque(false);
        listKeys.add(create);
        listKeys.add(delete);
        JPanel left = new JPanel(new BorderLayout(0, 8));
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
        row(concept, c, 0, "Titel", title, 3);
        row(concept, c, 1, "Worum geht es? Das bekommt die KI gesagt.", description, 3);
        row(concept, c, 2, "Stil", style, 3);
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
        concept.add(minutes, c);
        c.gridx = 4;
        c.weightx = 1;
        JPanel planRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        planRow.setOpaque(false);
        planRow.add(plan);
        concept.add(planRow, c);

        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        int[] widths = {34, 210, 170, 56, 90, 56, 200, 120, 190};
        for (int column = 0; column < widths.length; column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
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

        JPanel listEdit = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        listEdit.setOpaque(false);
        for (JButton key : new JButton[]{add, remove, up, down, open, again}) {
            listEdit.add(key);
        }
        JPanel making = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        making.setOpaque(false);
        making.add(export);
        making.add(cancel);
        making.add(produce);
        produce.setPreferredSize(new Dimension(200, 40));
        JPanel keys = new JPanel(new BorderLayout());
        keys.setOpaque(false);
        keys.add(listEdit, BorderLayout.WEST);
        keys.add(making, BorderLayout.EAST);
        status.setForeground(AudioStudioTheme.TEXT_DIM);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(keys, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(0, 12));
        right.setOpaque(false);
        right.add(concept, BorderLayout.NORTH);
        right.add(tableScroll, BorderLayout.CENTER);
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
        trackCount.setValue(chosen == null ? 12 : chosen.concept().trackCount());
        minutes.setValue(chosen == null ? 5.0 : chosen.concept().secondsPerTrack() / 60.0);
        tracks.fireTableDataChanged();
        say(chosen == null ? "Noch kein Album. „Neues Album“ legt eines an." : summary());
        keys();
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
        for (Component field : new Component[]{title, description, style, trackCount, minutes}) {
            field.setEnabled(free);
        }
        plan.setEnabled(free);
        add.setEnabled(free);
        remove.setEnabled(free && selected != null);
        up.setEnabled(free && selected != null && row > 0);
        down.setEnabled(free && selected != null && row < album.tracks().size() - 1);
        produce.setEnabled(free && !album.tracks().isEmpty());
        cancel.setEnabled(running);
        again.setEnabled(free && selected != null && selected.hasPiece());
        open.setEnabled(there && selected != null && selected.hasPiece());
        export.setEnabled(free && state.count(album, Status.DONE) > 0);
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
                    (int) Math.round(((Number) minutes.getValue()).doubleValue() * 60));
            Album changed = album.withTitle(title.getText()).withConcept(concept);
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
    }

    void createAlbum() {
        String name = host.ask("Wie soll das Album heißen?", "Neues Album");
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            Album made = files.create(name.trim(), new AlbumConcept("", "", 12, 300));
            reload(made.id());
            description.requestFocusInWindow();
        } catch (IOException failed) {
            say("Das Album ließ sich nicht anlegen: " + failed.getMessage());
        }
    }

    private void deleteAlbum() {
        if (album == null || !host.confirm("Das Album „" + album.title() + "“ löschen?\n\n"
                + "Seine Stücke bleiben in der Bibliothek.")) {
            return;
        }
        try {
            files.delete(album.id());
            reload(null);
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
            model = ModelLineup.of(host.model()).model(AlbumRoles.ALBUM);
        } catch (RuntimeException invalid) {
            say(invalid.getMessage());
            return;
        }
        final Album asked = album;
        final int tokens = host.tokens();
        busy = true;
        keys();
        say("Die KI plant die Trackliste ...");
        Thread worker = new Thread(() -> {
            Album planned = null;
            String problem = null;
            try {
                planned = new ChatAlbumPlanner(model, tokens).plan(asked, files.folder(asked.id()), host::log);
                files.save(planned);
            } catch (IOException | RuntimeException failed) {
                problem = failed.getMessage() == null ? failed.toString() : failed.getMessage();
            }
            final Album result = planned;
            final String said = problem;
            SwingUtilities.invokeLater(() -> {
                busy = false;
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
                + "Das Stück bleibt in der Bibliothek.")) {
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
        final AlbumProducer producer;
        try {
            // Every model of the production goes through the same permits: the limit holds over all pieces.
            Semaphore permits = new Semaphore(Math.max(1, host.parallelRequests()), true);
            ModelLineup lineup = ModelLineup.of(new LimitedChatModel(host.model(), permits));
            PieceAutopilot autopilot = new PieceAutopilot(registry, lineup, host.tokens(),
                    Math.max(1, host.parallelRequests()), host.rendering());
            producer = new AlbumProducer(files, songs, new AutopilotPieceProduction(autopilot, songs), PIECES_AT_ONCE);
        } catch (RuntimeException invalid) {
            say(invalid.getMessage());
            return;
        }
        start(producer);
    }

    /** Starts the production with this producer; a test hands in one that needs no model. */
    void start(AlbumProducer producer) {
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
            });
        } catch (IOException | RuntimeException failed) {
            say("Das Album ließ sich nicht produzieren: " + failed.getMessage());
            return;
        }
        say("Das Album wird produziert: " + PIECES_AT_ONCE + " Stücke gleichzeitig.");
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
                    say((failed > 0 ? failed + " Titel fehlgeschlagen; „Album produzieren“ versucht sie erneut. "
                            : waiting > 0 ? "Angehalten; „Album produzieren“ setzt fort. " : "Das Album ist fertig. ")
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
        try {
            state = state.with(track.id(), Status.QUEUED);
            state.save(files.folder(album.id()));
            tracks.fireTableDataChanged();
            say("„" + track.title() + "“ wird beim nächsten „Album produzieren“ neu gemacht.");
        } catch (IOException failed) {
            say("Das ließ sich nicht vormerken: " + failed.getMessage());
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
            return album == null ? 0 : album.tracks().size();
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
            if (column == 0 || column == STATE || busy || (production != null && production.running())) {
                return false;
            }
            Status at = state.status(album.tracks().get(row).id());
            // The name can always be changed; what the piece is composed with only before it is made.
            return column == 1 || (at != Status.DONE && at != Status.RUNNING);
        }

        @Override
        public Object getValueAt(int row, int column) {
            AlbumTrack track = album.tracks().get(row);
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
