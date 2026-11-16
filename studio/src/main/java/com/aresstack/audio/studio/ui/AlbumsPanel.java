package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelLineup;
import com.aresstack.audio.studio.album.Album;
import com.aresstack.audio.studio.album.AlbumConcept;
import com.aresstack.audio.studio.album.AlbumExporter;
import com.aresstack.audio.studio.album.AlbumCover;
import com.aresstack.audio.studio.album.CoverArtist;
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
import com.aresstack.audio.studio.album.AlbumTemplates;
import com.aresstack.audio.studio.album.AlbumTools;
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
import javax.swing.JComboBox;
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

        /**
         * Lets the user choose files of notes (MIDI, MusicXML, ABC ...) as templates for titles;
         * empty if they choose none.
         */
        default List<Path> chooseScores(String title) {
            return List.of();
        }

        /** How many lines of a template written as text the AI writes over in one request. */
        default int templateLines() {
            return com.aresstack.audio.studio.template.TextTemplate.DEFAULT_LINES;
        }

        /** Lets the user choose a folder to export into; {@code null} if they do not. */
        Path chooseFolder(String title);

        /** Lets the user choose a picture (PNG, JPG) of their own, e.g. as the cover of an album; {@code null} if none. */
        default Path choosePicture(String title) {
            return null;
        }

        void log(String line);

        /** Asks for a line of text; {@code null} if the user cancels. */
        String ask(String question, String proposal);

        boolean confirm(String question);

        /** The library changed: pieces were made. */
        void piecesChanged();

        /**
         * Where the piece of a title shows itself while the AI writes it: notes, instruments and
         * form as they stream in, if the piece is open in the window. {@code null}: nowhere.
         */
        default com.aresstack.audio.studio.progress.SketchProgress pieceWatch(Path piece) {
            return null;
        }

        /** The production of this piece ended (finished or failed): the window shows it as it is now. */
        default void pieceProduced(Path piece) {
        }

        /** The user chose this album in the list of the page; the window's choice of the album follows. */
        /** The page shows this album now ({@code null}: none): its own models are shown beside it. */
        default void albumShown(String id) {
        }

        default void albumChosen(String id) {
        }

        /** An album was written: its title, its concept or its list of titles changed. */
        default void albumChanged() {
        }

        /** The AI help of the fields; {@code null}: the page has none. */
        default Assistant assistant() {
            return null;
        }
    }

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
    /** Description and style open into a large box: an album may need a long text, not one line. */
    private final WideText titleWide = new WideText(title, titleBox, 3);
    private final WideText descriptionWide = new WideText(description, descriptionBox);
    private final WideText styleWide = new WideText(style, styleBox);
    private final JSpinner trackCount = new JSpinner(new SpinnerNumberModel(12, 1, AlbumConcept.MAX_TRACKS, 1));
    /** How long a piece may be: from … to … minutes; the plan gives each title its own length in it. */
    private final JSpinner minutes = new JSpinner(new SpinnerNumberModel(
            AlbumConcept.DEFAULT_MIN_SECONDS / 60.0, 0.5, 60.0, 0.5));
    private final JSpinner minutesTo = new JSpinner(new SpinnerNumberModel(
            AlbumConcept.DEFAULT_MAX_SECONDS / 60.0, 0.5, 60.0, 0.5));
    /** Whether the number of pieces is set here ("automatisch") or follows the track list. */
    private final JComboBox<String> countMode = new JComboBox<String>(new String[]{"automatisch", "aus der Trackliste"});
    /** The advanced settings of the title chosen, opened by its ⚙ in the list. */
    private final SpecChoice rowSpec = new SpecChoice(SpecChoice.Length.MINUTES);
    private final JPanel rowSettings = new JPanel(new BorderLayout(10, 0));
    private final JLabel rowSettingsTitle = new JLabel(" ");
    private final JButton rowSettingsClose = new DeckWidgets.HeadKey("Schließen");
    /** The id of the title whose advanced settings are open; {@code null} while they are closed. */
    private String rowSettingsOf;
    private boolean rowSettingsShowing;
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
    private final JButton scores = new DeckWidgets.HeadKey("Noten vorgeben …");
    /** "Original nachbilden": the album rebuilds an existing work its wish names, it does not invent one. */
    private final javax.swing.JCheckBox original = new javax.swing.JCheckBox("Original nachbilden");
    private final JButton noScore = new DeckWidgets.HeadKey("Ohne Vorlage");
    /** The name of the template file of each title that has one, for the album shown. */
    private final Map<String, String> templateNames = new HashMap<String, String>();

    private Album album;
    private AlbumProductionState state = AlbumProductionState.EMPTY;
    /** What the piece of each title is doing right now, while the album is produced. */
    private final Map<String, String> doing = new HashMap<String, String>();
    /**
     * The productions under way, by album: one album may be produced while another is shown and
     * edited. Only on the event thread.
     */
    private final Map<String, AlbumProductionCoordinator.Production> productions =
            new java.util.LinkedHashMap<String, AlbumProductionCoordinator.Production>();
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

    /** The cover of the album, at the left of its concept: drawn by the AI or a picture of the user's own. */
    private final CoverBox cover = new CoverBox();
    private final JButton drawCover = new DeckWidgets.IconKey(DeckWidgets.IconKey.Sign.SPARKLE,
            "Cover von der KI zeichnen lassen, aus Titel, Beschreibung, Stil und Titelliste des Albums. "
                    + "Ein vorhandenes Cover wird ersetzt. Beim Exportieren liegt es als cover.png bei den Titeln.");
    private final JButton ownCover = new DeckWidgets.HeadKey("Bild …");
    /** The album whose cover the AI is drawing, by its id; {@code null} while it draws none. */
    private String drawingCoverOf;
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
        // At the top, also when a field of the concept is opened to a large box.
        JPanel atTop = new JPanel(new BorderLayout());
        atTop.setOpaque(false);
        atTop.add(component, BorderLayout.NORTH);
        conceptRow.add(atTop, BorderLayout.EAST);
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
        Assistant assistant = host.assistant();
        // Every row is built alike: a short name (what it means is its tooltip), the field, and at the
        // right the square keys, so that the fields have one width and the keys stand under each other.
        formRow(concept, 0, "Titel", "Der Titel des Albums, kurz und einprägsam", titleWide, titleWide.toggleKey(),
                assistant == null ? null : assistant.key("KI-Hilfe für den Titel des Albums", () -> textHelp("Titel",
                        "title", title, "the title of the album, short and memorable.")));
        formRow(concept, 1, "Beschreibung", "Worum geht es? Das bekommt die KI gesagt: die Geschichte oder das Thema, "
                        + "die Stücke und ihre Reihenfolge, alles, was sie zum Planen und Komponieren wissen soll. Darf lang sein.",
                descriptionWide, descriptionWide.toggleKey(),
                assistant == null ? null : assistant.key("KI-Hilfe für die Beschreibung des Albums", () -> textHelp(
                        "Beschreibung", "description", description, "what the album is about, in the user's words: "
                                + "the story or theme, the pieces and their order, everything the AI that plans and "
                                + "composes the album should know. It may be long.")));
        formRow(concept, 2, "Stil", "Wie das Album klingt: Genre, Instrumente, Klang, in ein paar Zeilen",
                styleWide, styleWide.toggleKey(),
                assistant == null ? null : assistant.key("KI-Hilfe für den Stil", () -> textHelp("Stil", "style", style,
                        "how the album sounds: genre, instruments, sound, in a few lines.")));
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
        typed(title, afterTitle, titleWide.area());
        typed(description, afterDescription, descriptionWide.area());
        typed(style, afterStyle, styleWide.area());
        JPanel counted = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        counted.setOpaque(false);
        countMode.setToolTipText("automatisch: die KI plant so viele Titel, wie hier steht. Aus der Trackliste: die "
                + "Titel der Liste (oder einer Liste in der Beschreibung) sind das Album; die Zahl ergibt sich daraus.");
        trackCount.setToolTipText("So viele Titel hat das Album");
        minutes.setToolTipText("So lang ist ein Stück mindestens, in Minuten");
        minutesTo.setToolTipText("So lang ist ein Stück höchstens, in Minuten; die KI gibt jedem Titel eine eigene Länge dazwischen");
        counted.add(countMode);
        counted.add(javax.swing.Box.createHorizontalStrut(8));
        counted.add(trackCount);
        counted.add(javax.swing.Box.createHorizontalStrut(20));
        counted.add(dim("je"));
        counted.add(javax.swing.Box.createHorizontalStrut(8));
        counted.add(minutes);
        counted.add(javax.swing.Box.createHorizontalStrut(8));
        counted.add(dim("bis"));
        counted.add(javax.swing.Box.createHorizontalStrut(8));
        counted.add(minutesTo);
        counted.add(javax.swing.Box.createHorizontalStrut(8));
        counted.add(dim("Minuten"));
        countMode.addActionListener(event -> {
            if (!showing) {
                showCountMode();
                keepConcept();
            }
        });
        formRow(concept, 3, "Stücke", "Wie viele Titel das Album hat und wie lang jeder ungefähr wird", counted, null,
                assistant == null ? null : assistant.key("KI-Hilfe für Anzahl und Länge der Stücke", this::piecesHelp));
        original.setOpaque(false);
        original.setForeground(AudioStudioTheme.TEXT_DIM);
        original.setToolTipText("Der Text nennt ein bestehendes Werk oder Quellen (z. B. Vivaldis Vier Jahreszeiten):"
                + " die KI plant die echten Sätze mit Titeln, Tonarten, Tempi und üblicher Länge (die Minuten oben"
                + " gelten dann nicht) und gibt die bekannten Themen so getreu wie möglich wieder. Genau wird es mit"
                + " „Noten vorgeben …“.");
        original.addActionListener(event -> {
            if (!showing) {
                keepConcept();
                keys();
            }
        });

        table.setRowHeight(28);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        int[] widths = {34, 210, 170, 56, 90, 56, 200, 120, 34, 190};
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
        DefaultTableCellRenderer gear = new DefaultTableCellRenderer();
        gear.setHorizontalAlignment(JLabel.CENTER);
        gear.setToolTipText("Erweitert: Länge, Tempo, Tonart und Taktart dieses Titels. Automatisch wählt die KI; "
                + "was du setzt, hält die Planung ein.");
        table.getColumnModel().getColumn(Tracks.GEAR).setCellRenderer(gear);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                if (table.columnAtPoint(event.getPoint()) == Tracks.GEAR && album != null && preview == null
                        && row >= 0 && row < album.tracks().size()) {
                    String id = album.tracks().get(row).id();
                    rowSettingsOf = id.equals(rowSettingsOf) ? null : id;
                    showRowSettings();
                }
            }
        });
        rowSettings.setOpaque(false);
        rowSettings.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, AudioStudioTheme.STROKE),
                BorderFactory.createEmptyBorder(8, 4, 4, 4)));
        rowSettingsTitle.setForeground(AudioStudioTheme.TEXT_DIM);
        rowSettings.add(rowSettingsTitle, BorderLayout.NORTH);
        rowSettings.add(rowSpec, BorderLayout.CENTER);
        JPanel closing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        closing.setOpaque(false);
        closing.add(rowSettingsClose);
        rowSettings.add(closing, BorderLayout.EAST);
        rowSettings.setVisible(false);
        rowSettingsClose.addActionListener(event -> {
            rowSettingsOf = null;
            showRowSettings();
        });
        rowSpec.onChange(this::keepRowSettings);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));

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
        hint(scores, "Eigene Noten (MIDI, MusicXML, ABC …) als Vorlage geben: eine Datei für den gewählten Titel,"
                + " mehrere für die Titel ab dem gewählten, neue Titel nach Bedarf. Die KI komponiert diese Titel"
                + " nicht frei, sondern übernimmt die Noten und berichtigt nur Format und offensichtliche Fehler.");
        hint(noScore, "Der gewählte Titel folgt keiner Notenvorlage mehr; er wird wieder frei komponiert");
        // The head carries what is done with the whole album; what concerns the list and the title
        // chosen in it stands right over the list, where the eye is when a title is chosen.
        for (java.awt.Component key : new java.awt.Component[]{plan, produce, cancel, export}) {
            headKeys.add(key);
        }
        JPanel listKeysRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        listKeysRow.setOpaque(false);
        JLabel listName = DeckWidgets.lettering("Trackliste");
        listName.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        listKeysRow.add(listName);
        for (java.awt.Component key : new java.awt.Component[]{add, remove, up, down}) {
            listKeysRow.add(key);
        }
        if (assistant != null) {
            listKeysRow.add(assistant.key("KI-Hilfe für die Trackliste: einen Text oder eine Liste hineinkopieren, "
                    + "die KI legt die Titel an, ändert, sortiert sie und setzt das Hauptthema", this::tracksHelp));
        }
        JLabel titleName = DeckWidgets.lettering("Gewählter Titel");
        titleName.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 6));
        listKeysRow.add(titleName);
        for (java.awt.Component key : new java.awt.Component[]{open, again, scores, noScore}) {
            listKeysRow.add(key);
        }
        if (assistant != null) {
            listKeysRow.add(assistant.key("KI-Hilfe für die Noten des gewählten Titels: aus einer Beschreibung, Akkorden, "
                    + "einer Melodie in Worten oder ABC schreibt die KI mit dir die Notenvorlage", this::scoreHelp));
        }
        JPanel listHead = new JPanel(new BorderLayout());
        listHead.setOpaque(false);
        listHead.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        listHead.add(listKeysRow, BorderLayout.WEST);
        status.setForeground(AudioStudioTheme.TEXT_DIM);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        // Below the list: what the page says at the left, settings of the album at the right.
        JPanel foot = new JPanel(new BorderLayout(12, 0));
        foot.setOpaque(false);
        foot.add(status, BorderLayout.CENTER);
        JPanel settings = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 12, 0));
        settings.setOpaque(false);
        settings.add(original);
        foot.add(settings, BorderLayout.EAST);
        bottom.add(foot, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(0, 12));
        right.setOpaque(false);
        conceptRow.setOpaque(false);
        // At the top, level with the cover and the models beside it.
        JPanel conceptAtTop = new JPanel(new BorderLayout());
        conceptAtTop.setOpaque(false);
        conceptAtTop.add(concept, BorderLayout.NORTH);
        conceptRow.add(conceptAtTop, BorderLayout.CENTER);
        // At the left of the concept: the place of the cover, with its keys over it.
        ownCover.setToolTipText("Ein eigenes Bild als Cover wählen (PNG oder JPG)");
        ownCover.addActionListener(event -> chooseCover());
        drawCover.addActionListener(event -> drawCover());
        JPanel coverKeys = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        coverKeys.setOpaque(false);
        coverKeys.add(ownCover);
        coverKeys.add(drawCover);
        JPanel coverHead = new JPanel(new BorderLayout());
        coverHead.setOpaque(false);
        JLabel coverName = DeckWidgets.lettering("Cover");
        coverName.setToolTipText("Das Bild des Albums. Beim Exportieren liegt es als cover.png bei den Titeln; "
                + "hat das Album keines, zeichnet die KI dann eines, und gelingt das nicht, liegt ein Platzhalter dabei.");
        coverHead.add(coverName, BorderLayout.WEST);
        coverHead.add(coverKeys, BorderLayout.EAST);
        JPanel coverSide = new JPanel(new BorderLayout(0, 6));
        coverSide.setOpaque(false);
        coverSide.add(coverHead, BorderLayout.NORTH);
        coverSide.add(cover, BorderLayout.CENTER);
        JPanel coverAtTop = new JPanel(new BorderLayout());
        coverAtTop.setOpaque(false);
        coverAtTop.add(coverSide, BorderLayout.NORTH);
        conceptRow.add(coverAtTop, BorderLayout.WEST);
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
        JPanel listedWithSettings = new JPanel(new BorderLayout(0, 0));
        listedWithSettings.setOpaque(false);
        listedWithSettings.add(listHead, BorderLayout.NORTH);
        listedWithSettings.add(listed, BorderLayout.CENTER);
        listedWithSettings.add(rowSettings, BorderLayout.SOUTH);
        right.add(listedWithSettings, BorderLayout.CENTER);
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
        scores.addActionListener(event -> giveScores());
        noScore.addActionListener(event -> dropScore());
        reload(null);
    }

    private static JLabel dim(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(AudioStudioTheme.TEXT_DIM);
        return label;
    }

    /**
     * One row of a form: a short name whose tooltip says what the field means, the field, which
     * takes the width, and at the right the square keys of the row. Rows built with this have
     * fields of one width and keys that stand under each other, whichever keys a row has.
     *
     * @param first  the key nearer the field (opening it to a large box), or {@code null}
     * @param second the key at the edge (the AI help), or {@code null}
     */
    static void formRow(JPanel form, int y, String name, String meaning, Component field, Component first,
                        Component second) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(0, 0, 8, 12);
        c.anchor = GridBagConstraints.NORTHWEST;
        JLabel label = dim(name);
        label.setToolTipText(meaning);
        label.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
        c.gridx = 0;
        form.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 8, 6);
        form.add(field, c);
        JPanel keys = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        keys.setOpaque(false);
        if (first != null) {
            keys.add(first);
            keys.add(javax.swing.Box.createHorizontalStrut(4));
        }
        if (second != null) {
            keys.add(second);
        }
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(keys, BorderLayout.NORTH);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.HORIZONTAL; // the keys stand at the right edge, whatever the other rows have
        c.anchor = GridBagConstraints.NORTHEAST;
        c.insets = new Insets(1, 0, 8, 0);
        form.add(top, c);
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
        showCover();
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
        boolean wasShowing = showing;
        showing = true;
        original.setSelected(chosen != null && chosen.concept().original());
        countMode.setSelectedIndex(chosen != null && chosen.concept().fromList() ? 1 : 0);
        showing = wasShowing;
        showCountMode();
        showRowSettings();
        readTemplates();
        tracks.fireTableDataChanged();
        say(chosen == null ? "Noch kein Album. „Neues Album“ legt eines an." : summary());
        host.albumShown(chosen == null ? null : chosen.id());
        keys();
        // Showing an album asks the AI nothing: proposals come when the user asks for them (opening
        // a list, typing) or for an album just made, not when a title is produced or the page opened.
    }

    /**
     * Shows this album as the window chose it, read anew from its file, unless the page is in the
     * middle of something: a plan, a proposal or a production.
     */
    void follow(String id) {
        if (busy) {
            return;
        }
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        if (album != null && !shownRunning()) {
            // An album being produced is written by its production alone; its fields are locked.
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
        boolean running = shownRunning();
        boolean free = there && !running && !busy;
        boolean other = producing(); // one production at a time: "Parallel" limits all connections
        int row = table.getSelectedRow();
        AlbumTrack selected = there && row >= 0 && row < album.tracks().size() ? album.tracks().get(row) : null;
        create.setEnabled(!busy);
        delete.setEnabled(free);
        for (Component field : new Component[]{titleWide, descriptionWide, styleWide, countMode, minutes, minutesTo,
                original}) {
            field.setEnabled(free);
        }
        // With the list the number of pieces follows from it.
        trackCount.setEnabled(free && countMode.getSelectedIndex() == 0);
        rowSpec.lock(!rowSettingsEditable());
        plan.setEnabled(free);
        // The cover is the album's own business: it may be drawn or chosen while titles are produced.
        drawCover.setEnabled(there && drawingCoverOf == null);
        ownCover.setEnabled(there && drawingCoverOf == null);
        if (beside != null) {
            // Each album has models of its own: those of an album being produced stay as they are.
            beside.setEnabled(there && !running && !busy);
        }
        add.setEnabled(free);
        remove.setEnabled(free && selected != null);
        up.setEnabled(free && selected != null && row > 0);
        down.setEnabled(free && selected != null && row < album.tracks().size() - 1);
        produce.setEnabled(free && !other && !album.tracks().isEmpty());
        // A run that left titles undone (failed, cancelled) is taken up: only what is missing is made.
        int done = there ? state.count(album, Status.DONE) : 0;
        boolean partly = there && done < album.tracks().size()
                && (done > 0 || state.count(album, Status.FAILED) > 0);
        produce.setText(partly ? "Fehlende Titel produzieren" : "Album produzieren");
        produce.setToolTipText(partly ? "Macht nur die Titel, die noch nicht fertig sind; fertige bleiben, wie sie sind"
                : null);
        cancel.setEnabled(running);
        again.setEnabled(free && !other && selected != null && selected.hasPiece());
        open.setEnabled(there && selected != null && selected.hasPiece());
        // A piece put into the album by hand, or an old one in the unnamed album, can be exported as well.
        scores.setEnabled(free);
        noScore.setEnabled(free && selected != null && templateNames.containsKey(selected.id()));
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
    private void typed(JTextField field, Timer timer, Component box) {
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
                if (album != null && (field.isFocusOwner() || box.isFocusOwner())) {
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
        if (asked == null || busy || shownRunning()) {
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

    // ---- the advanced settings of a title ----

    /** The title whose advanced settings are open; {@code null} while none are. */
    private AlbumTrack rowSettingsTrack() {
        return album == null || rowSettingsOf == null ? null : album.track(rowSettingsOf);
    }

    /** Whether the title whose settings are open can still be changed: not while it is made or once it is. */
    private boolean rowSettingsEditable() {
        AlbumTrack track = rowSettingsTrack();
        if (track == null || busy || preview != null || shownRunning()) {
            return false;
        }
        Status at = state.status(track.id());
        return at != Status.DONE && at != Status.RUNNING;
    }

    /** Shows the advanced settings of the title chosen by its ⚙, or hides them. */
    private void showRowSettings() {
        AlbumTrack track = rowSettingsTrack();
        if (track == null) {
            rowSettingsOf = null;
            rowSettings.setVisible(false);
            return;
        }
        rowSettingsShowing = true;
        try {
            com.aresstack.audio.studio.application.CompositionSpec spec = track.spec();
            SpecChoice.Values planned = new SpecChoice.Values(track.targetSeconds() / 60.0, track.bpm(), spec.key(),
                    spec.numerator(), spec.denominator());
            rowSpec.show(planned, track.fixed());
            rowSpec.showChosen(planned); // the automatic fields say what the plan has for them
            rowSpec.lock(!rowSettingsEditable());
            rowSettingsTitle.setText("Vorgaben für „" + track.title() + "“ · automatisch wählt die KI, "
                    + "was du setzt, hält sie ein");
        } finally {
            rowSettingsShowing = false;
        }
        rowSettings.setVisible(true);
        rowSettings.revalidate();
    }

    /** Takes the advanced settings into the title: what is set is the user's, the rest is left to planning. */
    private void keepRowSettings() {
        AlbumTrack track = rowSettingsTrack();
        if (rowSettingsShowing || track == null || !rowSettingsEditable()) {
            return;
        }
        try {
            SpecChoice.Values values = rowSpec.values();
            java.util.Set<String> set = rowSpec.fixed();
            java.util.Set<String> fixed = new java.util.TreeSet<String>(track.fixed());
            fixed.removeAll(SpecChoice.FIELDS);
            fixed.addAll(set);
            AlbumTrack changed = track.withFixed(fixed);
            if (set.contains(AlbumTrack.LENGTH)) {
                changed = changed.withSeconds((int) Math.round(values.length() * 60));
            }
            if (set.contains(AlbumTrack.BPM)) {
                changed = changed.withBpm(values.bpm());
            }
            if (set.contains(AlbumTrack.KEY)) {
                changed = changed.withKey(values.key().name());
            }
            if (set.contains(AlbumTrack.METER)) {
                changed = changed.withMeter(values.numerator() + "/" + values.denominator());
            }
            if (!changed.equals(track)) {
                keep(album.with(changed));
                int row = album.tracks().indexOf(changed);
                if (row >= 0) {
                    tracks.fireTableRowsUpdated(row, row);
                }
                say(summary());
            }
        } catch (IllegalArgumentException | IOException failed) {
            say("Die Einstellung ließ sich nicht speichern: " + failed.getMessage());
        }
    }

    /** Shows the count as the mode says: with the list it is the number of its titles. */
    private void showCountMode() {
        if (countMode.getSelectedIndex() == 1 && album != null) {
            boolean was = showing;
            showing = true;
            trackCount.setValue(Math.max(1, Math.min(AlbumConcept.MAX_TRACKS, album.tracks().size())));
            showing = was;
        }
        keys();
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
                    (int) Math.round(((Number) minutesTo.getValue()).doubleValue() * 60))
                    .withOriginal(original.isSelected()).withFromList(countMode.getSelectedIndex() == 1);
            if (concept.fromList()) {
                // the list says how many pieces: as many as it has titles
                concept = concept.withTrackCount(Math.max(1, Math.min(AlbumConcept.MAX_TRACKS,
                        files.album(album.id()).orElse(album).tracks().size())));
            }
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

    /** Whether an album is being produced, any album. */
    boolean producing() {
        for (AlbumProductionCoordinator.Production each : productions.values()) {
            if (each.running()) {
                return true;
            }
        }
        return false;
    }

    /** The production of the album shown, or {@code null}. */
    private AlbumProductionCoordinator.Production shownProduction() {
        return album == null ? null : productions.get(album.id());
    }

    /** Whether the album shown is being produced; another album may be, and this one stays free. */
    private boolean shownRunning() {
        AlbumProductionCoordinator.Production shown = shownProduction();
        return shown != null && shown.running();
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
            if (album != null && !AlbumMembership.isUntitled(album)) {
                // The title stands: first directions for the style, and ideas for the other fields.
                propose(Field.STYLE);
                propose(Field.DESCRIPTION);
                propose(Field.TITLE);
            }
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

    // ---- the AI help of the fields ----

    /** The help of a text field of the album shown; {@code null} without an album. */
    private Assistant.Field textHelp(String label, String name, JTextField field, String what) {
        if (album == null) {
            say("Erst ein Album wählen oder anlegen.");
            return null;
        }
        return Assistant.textField(label, "album-" + album.id() + "-" + name, field, what,
                () -> album == null ? "" : AlbumTools.read(album), this::keepConcept);
    }

    /** The help of the track list of the album shown: it reads the album and changes only its titles and theme. */
    private Assistant.Field tracksHelp() {
        if (album == null) {
            say("Erst ein Album wählen oder anlegen.");
            return null;
        }
        final String id = album.id();
        return new Assistant.Field() {
            @Override
            public String label() {
                return "Trackliste";
            }

            @Override
            public String key() {
                return "album-" + id + "-tracks";
            }

            @Override
            public String system() {
                return "You help the user of a music studio with the track list of an album: put titles in, change, "
                        + "remove and order them, and set the main theme (leitmotif) of the album as notes. Talk with "
                        + "the user in their language. Read the album first with read_album. When the user gives a "
                        + "text or a list, take the titles and what is said about each (as mood) from it exactly; "
                        + "fill in tempo, key, meter, players, form, length and how the main theme appears where "
                        + "it helps, or leave them for the planning. Titles with a piece already cannot be changed. "
                        + "You can change nothing but the track list and the main theme. After a change, say in a "
                        + "few sentences what you did.";
            }

            @Override
            public List<com.aresstack.audio.studio.assist.AssistTool> tools() {
                String fields = "{\"type\":\"object\",\"properties\":{" + AlbumTools.TRACK_FIELDS + "}}";
                return List.of(
                        com.aresstack.audio.studio.assist.AssistTool.reading("read_album",
                                "Reads the album: title, description, style, number of pieces and length range, the "
                                        + "titles with their numbers, and the main theme.",
                                arguments -> Assistant.onScreen(() -> AlbumTools.read(current(id)))),
                        com.aresstack.audio.studio.assist.AssistTool.of("add_titles",
                                "Puts titles into the track list, in order. replace=true first removes the titles "
                                        + "that have no piece yet.",
                                "{\"type\":\"object\",\"properties\":{\"titles\":{\"type\":\"array\",\"items\":"
                                        + fields + "},\"replace\":{\"type\":\"boolean\"}},\"required\":[\"titles\"]}",
                                arguments -> changeAlbum(id, now -> AlbumTools.addTracks(now, arguments))),
                        com.aresstack.audio.studio.assist.AssistTool.of("change_title",
                                "Changes the fields given of the title with this number (from 1).",
                                "{\"type\":\"object\",\"properties\":{\"number\":{\"type\":\"integer\"},"
                                        + AlbumTools.TRACK_FIELDS + "},\"required\":[\"number\"]}",
                                arguments -> changeAlbum(id, now -> AlbumTools.changeTrack(now, arguments))),
                        com.aresstack.audio.studio.assist.AssistTool.of("remove_title",
                                "Removes the title with this number (from 1) from the list.",
                                "{\"type\":\"object\",\"properties\":{\"number\":{\"type\":\"integer\"}},"
                                        + "\"required\":[\"number\"]}",
                                arguments -> changeAlbum(id, now -> AlbumTools.removeTrack(now, arguments))),
                        com.aresstack.audio.studio.assist.AssistTool.of("move_title",
                                "Moves the title with this number to the place 'to' (both from 1).",
                                "{\"type\":\"object\",\"properties\":{\"number\":{\"type\":\"integer\"},"
                                        + "\"to\":{\"type\":\"integer\"}},\"required\":[\"number\",\"to\"]}",
                                arguments -> changeAlbum(id, now -> AlbumTools.moveTrack(now, arguments))),
                        com.aresstack.audio.studio.assist.AssistTool.of("set_main_theme",
                                "Sets the main theme of the album: a melody of 4 to 8 bars as notes (beat and "
                                        + "duration in quarters from its start, MIDI pitch).",
                                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                                        + "\"description\":{\"type\":\"string\"},\"key\":{\"type\":\"string\"},"
                                        + "\"notes\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                                        + "\"beat\":{\"type\":\"number\"},\"duration\":{\"type\":\"number\"},"
                                        + "\"pitch\":{\"type\":\"integer\"}}}}},\"required\":[\"notes\"]}",
                                arguments -> changeAlbum(id, now -> AlbumTools.setTheme(now, arguments))));
            }

            @Override
            public Runnable snapshot() {
                Album was = current(id);
                return () -> {
                    if (was != null && !busy && !shownRunning()) {
                        try {
                            files.save(was);
                            reload(was.id());
                            host.albumChanged();
                        } catch (IOException failed) {
                            say("Die Änderung ließ sich nicht zurücknehmen: " + failed.getMessage());
                        }
                    }
                };
            }
        };
    }

    /** The help of the number and length of the pieces: it reads the album and sets only these. */
    private Assistant.Field piecesHelp() {
        if (album == null) {
            say("Erst ein Album wählen oder anlegen.");
            return null;
        }
        final String id = album.id();
        return new Assistant.Field() {
            @Override
            public String label() {
                return "Stücke";
            }

            @Override
            public String key() {
                return "album-" + id + "-pieces";
            }

            @Override
            public String system() {
                return "You help the user of a music studio with how many pieces an album has and how long they "
                        + "are. Talk with the user in their language. Read the album first with read_album. "
                        + "mode 'automatic' means the planning makes as many titles as count says; mode 'list' "
                        + "means the titles of the track list (or of a list in the description) are the album and "
                        + "the count follows from them. A length range in minutes (from 0.25 to 60, from <= to) "
                        + "gives each piece its own length between the two. You can change nothing else. After a "
                        + "change, say in a sentence what you set.";
            }

            @Override
            public List<com.aresstack.audio.studio.assist.AssistTool> tools() {
                return List.of(
                        com.aresstack.audio.studio.assist.AssistTool.reading("read_album",
                                "Reads the album: title, description, style, number of pieces and length range, the "
                                        + "titles with their numbers, and the main theme.",
                                arguments -> Assistant.onScreen(() -> AlbumTools.read(current(id)))),
                        com.aresstack.audio.studio.assist.AssistTool.of("set_pieces",
                                "Sets how the number of pieces is found and how long they are; fields left out stay.",
                                "{\"type\":\"object\",\"properties\":{"
                                        + "\"mode\":{\"type\":\"string\",\"enum\":[\"automatic\",\"list\"]},"
                                        + "\"count\":{\"type\":\"integer\"},"
                                        + "\"minutes_from\":{\"type\":\"number\"},"
                                        + "\"minutes_to\":{\"type\":\"number\"}}}",
                                arguments -> Assistant.onScreen(() -> setPieces(id, arguments))));
            }

            @Override
            public Runnable snapshot() {
                Album was = current(id);
                return () -> {
                    if (album != null && album.id().equals(id) && !busy && !shownRunning()) {
                        showPieces(was.concept());
                        keepConcept();
                    }
                };
            }
        };
    }

    /** Sets number and length of the pieces as the help asks, in the fields, and keeps them. */
    private String setPieces(String id, com.google.gson.JsonObject arguments) {
        if (album == null || !album.id().equals(id)) {
            throw new IllegalStateException("Das Album ist nicht mehr gezeigt.");
        }
        if (busy || shownRunning()) {
            throw new IllegalStateException("Das Album wird gerade geplant oder produziert; bitte danach.");
        }
        AlbumConcept now = album.concept();
        boolean fromList = arguments.has("mode") ? "list".equals(arguments.get("mode").getAsString())
                : now.fromList();
        int count = arguments.has("count") ? arguments.get("count").getAsInt() : now.trackCount();
        double from = arguments.has("minutes_from") ? arguments.get("minutes_from").getAsDouble()
                : now.minSeconds() / 60.0;
        double to = arguments.has("minutes_to") ? arguments.get("minutes_to").getAsDouble() : now.maxSeconds() / 60.0;
        if (count < 1 || count > AlbumConcept.MAX_TRACKS) {
            throw new IllegalArgumentException("count is 1 to " + AlbumConcept.MAX_TRACKS);
        }
        if (from < 0.25 || to > 60 || from > to) {
            throw new IllegalArgumentException("minutes are 0.25 to 60, from <= to");
        }
        showPieces(new AlbumConcept(now.description(), now.style(), count, (int) Math.round(from * 60),
                (int) Math.round(to * 60)).withFromList(fromList));
        if (!keepConcept()) {
            throw new IllegalArgumentException("not kept");
        }
        AlbumConcept kept = album.concept();
        return "Set: " + (kept.fromList() ? "from the track list" : "automatic") + ", " + kept.trackCount()
                + " pieces, " + kept.minSeconds() / 60.0 + " to " + kept.maxSeconds() / 60.0 + " minutes.";
    }

    /** Shows count, its mode and the length range of a concept in the fields, without keeping them. */
    private void showPieces(AlbumConcept concept) {
        boolean was = showing;
        showing = true;
        try {
            countMode.setSelectedIndex(concept.fromList() ? 1 : 0);
            trackCount.setValue(concept.trackCount());
            minutes.setValue(concept.minSeconds() / 60.0);
            minutesTo.setValue(concept.maxSeconds() / 60.0);
        } finally {
            showing = was;
        }
        showCountMode();
    }

    /** The help of the score template of the title chosen in the list. */
    private Assistant.Field scoreHelp() {
        int row = table.getSelectedRow();
        if (album == null || row < 0 || row >= album.tracks().size()) {
            say("Erst den Titel wählen, für den die KI Noten schreiben soll.");
            return null;
        }
        final String id = album.id();
        final AlbumTrack track = album.tracks().get(row);
        if (track.hasPiece()) {
            say("Dieser Titel ist schon ein Stück; seine Noten ändert der Editor.");
            return null;
        }
        final AlbumTemplates templates = new AlbumTemplates(files);
        return Assistant.scoreField("Noten für „" + track.title() + "“", "album-" + id + "-" + track.id() + "-score",
                () -> templates.titleFolder(id, track.id()),
                () -> {
                    Album now = current(id);
                    AlbumTrack shown = now.track(track.id());
                    return "Titel: " + (shown == null ? track : shown) + "\nAlbum:\n" + AlbumTools.read(now);
                },
                () -> {
                    if (album != null && album.id().equals(id)) {
                        readTemplates();
                        tracks.fireTableDataChanged();
                        keys();
                    }
                });
    }

    /** The album as its file has it now. */
    private Album current(String id) {
        return files.album(id).orElseThrow(() -> new IllegalArgumentException("Das Album gibt es nicht mehr."));
    }

    /** Changes the album through the help, on the event thread; refused while it is planned or produced. */
    private String changeAlbum(String id, java.util.function.UnaryOperator<Album> change) throws Exception {
        return Assistant.onScreen(() -> {
            if (busy || productions.containsKey(id)) {
                throw new IllegalStateException("Das Album wird gerade geplant oder produziert; bitte danach.");
            }
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            Album changed = change.apply(current(id));
            try {
                files.save(changed);
            } catch (IOException failed) {
                throw new IllegalStateException(failed.getMessage(), failed);
            }
            host.albumChanged();
            if (album != null && album.id().equals(id)) {
                reload(id);
            }
            return "Die Trackliste hat jetzt " + changed.tracks().size() + " Titel"
                    + (changed.theme().isEmpty() ? "." : "; Hauptthema „" + changed.theme().name() + "“.");
        });
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
        if (album.concept().fromList() && album.tracks().isEmpty()
                && com.aresstack.audio.studio.album.TitleList.in(album.concept().description()).isEmpty()) {
            say("„Stücke: aus der Trackliste“: erst Titel in die Liste eintragen (oder eine Liste in "
                    + "„Worum geht es?“ geben), oder auf „automatisch“ stellen.");
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
        final int requests = Math.max(1, host.parallelRequests());
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
                planned = new ChatAlbumPlanner(model, tokens).parallel(requests).watching(seen -> SwingUtilities.invokeLater(() -> {
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
        try {
            new AlbumTemplates(files).remove(album.id(), gone.id()); // a later title may get its id
        } catch (IOException | RuntimeException kept) {
            say("Die Notenvorlage des Titels ließ sich nicht entfernen: " + kept.getMessage());
        }
        templateNames.remove(gone.id());
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

    /** The piece of the title selected in the table, or {@code null} if it has none (yet). */
    Path selectedPiece() {
        int row = table.getSelectedRow();
        if (album == null || row < 0 || row >= album.tracks().size() || !album.tracks().get(row).hasPiece()) {
            return null;
        }
        Path piece = songs.root().resolve(album.tracks().get(row).piece());
        return java.nio.file.Files.isDirectory(piece) ? piece : null;
    }

    /** Selects the title of this piece in the table, if the album shown has it. */
    void selectPiece(Path piece) {
        if (album == null || piece == null) {
            return;
        }
        Path wanted = piece.toAbsolutePath().normalize();
        for (int row = 0; row < album.tracks().size(); row++) {
            AlbumTrack track = album.tracks().get(row);
            if (track.hasPiece() && songs.root().resolve(track.piece()).toAbsolutePath().normalize().equals(wanted)) {
                table.setRowSelectionInterval(row, row);
                table.scrollRectToVisible(table.getCellRect(row, 0, true));
                return;
            }
        }
    }

    private void openPiece() {
        int row = table.getSelectedRow();
        if (row >= 0 && album.tracks().get(row).hasPiece()) {
            host.openPiece(songs.root().resolve(album.tracks().get(row).piece()));
        }
    }

    // ---- notes given as templates ----

    /** Reads which titles of the album shown follow a template. */
    private void readTemplates() {
        templateNames.clear();
        if (album == null) {
            return;
        }
        AlbumTemplates templates = new AlbumTemplates(files);
        for (AlbumTrack track : album.tracks()) {
            try {
                templates.of(album.id(), track.id()).ifPresent(file -> templateNames.put(track.id(),
                        file.getFileName().toString()));
            } catch (RuntimeException odd) {
                // a title whose id names no folder has no template
            }
        }
    }

    /**
     * Notes the user gives as templates: one file for the title chosen in the list, several for
     * the titles from the chosen one on (from the first title without notes if none is chosen),
     * with new titles where there are more files than titles. A new title is named after its file.
     */
    void giveScores() {
        if (!keepConcept()) {
            return;
        }
        List<Path> chosen = host.chooseScores("Noten als Vorlage für die Titel wählen");
        if (chosen == null || chosen.isEmpty()) {
            return;
        }
        giveScores(chosen);
    }

    /** Gives these files as templates, as {@link #giveScores()} describes; a test calls it directly. */
    void giveScores(List<Path> chosen) {
        List<Path> sorted = new ArrayList<Path>(chosen);
        sorted.sort(java.util.Comparator.comparing(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)));
        int row = table.getSelectedRow();
        if (row < 0 || sorted.size() > 1 && row >= album.tracks().size()) {
            row = 0;
            while (row < album.tracks().size() && templateNames.containsKey(album.tracks().get(row).id())) {
                row++;
            }
        }
        List<AlbumTrack> changed = new ArrayList<AlbumTrack>(album.tracks());
        Album growing = album;
        AlbumTemplates templates = new AlbumTemplates(files);
        int given = 0;
        try {
            for (int i = 0; i < sorted.size(); i++) {
                Path file = sorted.get(i);
                int at = row + i;
                if (at >= changed.size()) {
                    AlbumTrack fresh = new AlbumTrack(growing.freshTrackId(), titleOf(file), "", 100,
                            "C major", "4/4", "", "", album.concept().secondsPerTrack(), "");
                    changed.add(fresh);
                    growing = growing.withTracks(changed);
                    at = changed.size() - 1;
                }
                templates.put(album.id(), changed.get(at).id(), file);
                // A title the album only numbered takes the name the notes give, not one the AI invents.
                if (changed.get(at).title().matches("Titel \\d+|Ohne Titel")) {
                    changed.set(at, changed.get(at).withTitle(titleOf(file)));
                }
                given++;
            }
        } catch (IOException | RuntimeException failed) {
            say("Die Noten ließen sich nicht übernehmen: " + failed.getMessage());
        }
        change(changed, row < changed.size() ? row : -1);
        readTemplates();
        tracks.fireTableDataChanged();
        if (row >= 0 && row < changed.size()) {
            table.setRowSelectionInterval(row, row);
        }
        if (given > 0) {
            say(given == 1 ? "Der Titel folgt jetzt dieser Notenvorlage; „Album produzieren“ macht ihn daraus"
                    + " (ein fertiger Titel mit „Titel neu“)."
                    : given + " Titel folgen jetzt Notenvorlagen; „Album produzieren“ macht sie daraus.");
        }
        keys();
    }

    /**
     * The name of a piece as its notes give it: the title of a MusicXML score (work and movement)
     * or of a MIDI file, else the name of the file. Nothing is asked of the AI here.
     */
    static String titleOf(Path file) {
        String name = file.getFileName().toString();
        String named = (name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name).replace('_', ' ').trim();
        try {
            com.aresstack.audio.studio.template.TemplateScore score =
                    com.aresstack.audio.studio.template.TemplateReader.read(file, null, 0, 0, file.getParent(), null);
            if (!score.title().isBlank() && !score.title().equals(named)) {
                return score.title();
            }
        } catch (IOException | RuntimeException notReadableHere) {
            // text notations are read by the AI later; the file's name does until then
        }
        return named.isEmpty() ? "Ohne Titel" : named;
    }

    private void dropScore() {
        int row = table.getSelectedRow();
        if (album == null || row < 0 || row >= album.tracks().size()) {
            return;
        }
        try {
            AlbumTrack track = album.tracks().get(row);
            new AlbumTemplates(files).remove(album.id(), track.id());
            // The piece made from it got a copy; without it the title is composed freely again.
            if (track.hasPiece() && AlbumTrack.isFolderName(track.piece())) {
                com.aresstack.audio.studio.application.ScoreTemplates.remove(songs.root().resolve(track.piece()));
            }
            say("„" + album.tracks().get(row).title() + "“ wird wieder frei komponiert.");
        } catch (IOException | RuntimeException failed) {
            say("Die Notenvorlage ließ sich nicht entfernen: " + failed.getMessage());
        }
        readTemplates();
        tracks.fireTableDataChanged();
        table.setRowSelectionInterval(row, row);
        keys();
    }

    // ---- making the pieces ----

    void produceAlbum() {
        if (producing()) {
            say("Erst die laufende Produktion abwarten oder abbrechen: es wird ein Album nach dem anderen produziert.");
            return;
        }
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
            final int templateLines = host.templateLines();
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
                        .sectionBars(sectionBars).growBars(growBars).voices(voices[0], voices[1])
                                .templateLines(templateLines),
                        songs);
            // As many titles at once as there are requests allowed: every title gets its turn at the
            // shared permits, so the setting "Parallel" alone limits the connections.
            }, requests);
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
            AlbumProductionCoordinator.Production started = producer.start(id, new AlbumProductionCoordinator.Listener() {
                @Override
                public void changed(Album now, AlbumProductionState at) {
                    SwingUtilities.invokeLater(() -> produced(id, now, at));
                    for (AlbumTrack each : now.tracks()) {
                        Status is = at.status(each.id());
                        if (is == Status.RUNNING) {
                            running.add(each.id());
                        } else if (each.hasPiece() && running.remove(each.id())) {
                            // A piece this run worked on is finished, failed or stopped.
                            Path piece = songs.root().resolve(each.piece());
                            SwingUtilities.invokeLater(() -> host.pieceProduced(piece));
                        }
                    }
                }

                /** The last step of each title told in the protocol. */
                private final java.util.Map<String, String> logged = new java.util.concurrent.ConcurrentHashMap<>();

                /** The titles this run is working on. */
                private final java.util.Set<String> running = java.util.concurrent.ConcurrentHashMap.newKeySet();

                @Override
                public com.aresstack.audio.studio.progress.Progress watching(AlbumTrack track, Path piece,
                        com.aresstack.audio.studio.progress.Progress plain) {
                    com.aresstack.audio.studio.progress.SketchProgress shown = host.pieceWatch(piece);
                    return shown == null ? plain : new PieceWatch(plain, shown);
                }

                @Override
                public void progress(AlbumTrack track, String message) {
                    // The protocol tells what the piece does, as when the window composes it: each
                    // step once, not every count of characters on the way.
                    String step = message.replaceAll("\\d+", "#");
                    if (!step.equals(logged.put(track.id(), step))) {
                        SwingUtilities.invokeLater(() -> host.log("„" + track.title() + "“: " + message));
                    }
                    // Many titles stream at once: the table is drawn once for what came meanwhile,
                    // not once for every piece of text, so the window stays free to answer.
                    latest.put(track.id(), message);
                    if (drawing.compareAndSet(false, true)) {
                        SwingUtilities.invokeLater(() -> {
                            drawing.set(false);
                            if (album != null && album.id().equals(id)) {
                                doing.putAll(latest);
                                tracks.fireTableRowsUpdated(0, Math.max(0, album.tracks().size() - 1));
                            }
                        });
                    }
                }

                /** What each title does now, as the stream told it last. */
                private final java.util.Map<String, String> latest = new java.util.concurrent.ConcurrentHashMap<>();

                /** Whether the table is about to be drawn already. */
                private final java.util.concurrent.atomic.AtomicBoolean drawing =
                        new java.util.concurrent.atomic.AtomicBoolean();
            }, onlyTrack);
            productions.put(id, started);
        } catch (IOException | RuntimeException failed) {
            say("Das Album ließ sich nicht produzieren: " + failed.getMessage());
            return;
        }
        say(onlyTrack != null ? "Der Titel wird neu produziert."
                : "Das Album wird produziert: bis zu " + Math.max(1, host.parallelRequests())
                + " KI-Anfragen gleichzeitig, verteilt auf die Titel.");
        keys();
        final AlbumProductionCoordinator.Production running = productions.get(id);
        Thread waiter = new Thread(() -> {
            try {
                running.await();
            } catch (InterruptedException stopped) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (productions.get(id) == running) {
                    productions.remove(id);
                }
                if (album != null && album.id().equals(id)) {
                    doing.clear();
                }
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
        AlbumProductionCoordinator.Production shown = shownProduction();
        if (shown != null) {
            shown.cancel();
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
        if (producing()) {
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

    // ---- cover ----

    /** Shows the cover of the album that is open, and whether the AI is drawing it. */
    private void showCover() {
        cover.show(album == null ? null : AlbumCover.read(files.folder(album.id())));
        cover.say(album != null && album.id().equals(drawingCoverOf) ? "Die KI zeichnet …" : "");
    }

    /** The AI draws the cover of the album that is open; it replaces the one the album has. */
    private void drawCover() {
        if (album == null || drawingCoverOf != null || !keepConcept()) {
            return;
        }
        final Album drawn = album;
        final Path folder = files.folder(drawn.id());
        final ChatModel model;
        try {
            model = host.models().model(CoverArtist.ROLE);
        } catch (RuntimeException noModel) {
            say("Das Cover lässt sich nicht zeichnen: " + noModel.getMessage());
            return;
        }
        drawingCoverOf = drawn.id();
        showCover();
        keys();
        say("Die KI zeichnet das Cover von „" + drawn.title() + "“ ...");
        Thread worker = new Thread(() -> {
            String said;
            try {
                CoverArtist.Drawn made = new CoverArtist(model).draw(drawn, folder.resolve(COVER_ANSWERS));
                AlbumCover.save(folder, made.svg(), made.image());
                said = "Das Cover von „" + drawn.title() + "“ ist gezeichnet.";
            } catch (Exception failed) {
                said = "Das Cover ließ sich nicht zeichnen: " + (failed.getMessage() == null ? failed : failed.getMessage());
            }
            final String line = said;
            SwingUtilities.invokeLater(() -> {
                drawingCoverOf = null;
                showCover();
                say(line);
                host.log(line);
                keys();
            });
        }, "album-cover");
        worker.setDaemon(true);
        worker.start();
    }

    /** The folder in the folder of an album where the answers of the AI about its cover are kept. */
    private static final String COVER_ANSWERS = "cover-answers";

    /** A picture of the user's own becomes the cover of the album that is open. */
    private void chooseCover() {
        if (album == null || drawingCoverOf != null) {
            return;
        }
        Path chosen = host.choosePicture("Cover für „" + album.title() + "“ wählen");
        if (chosen == null) {
            return;
        }
        try {
            java.awt.image.BufferedImage picture = javax.imageio.ImageIO.read(chosen.toFile());
            if (picture == null) {
                say("Das ist kein Bild, das sich lesen lässt: " + chosen.getFileName());
                return;
            }
            AlbumCover.save(files.folder(album.id()), null, picture);
            showCover();
            say("„" + chosen.getFileName() + "“ ist das Cover von „" + album.title() + "“.");
        } catch (IOException | RuntimeException failed) {
            say("Das Bild ließ sich nicht als Cover nehmen: " + failed.getMessage());
        }
    }

    // ---- export ----

    private void exportAlbum() {
        final Path folder = host.chooseFolder("Album exportieren nach");
        if (folder == null) {
            return;
        }
        final Album exported = album;
        final Path albumFolder = files.folder(exported.id());
        // Asked for here, where the choices of the page can be read; used only if the album has no cover.
        ChatModel artist;
        try {
            artist = host.models().model(CoverArtist.ROLE);
        } catch (RuntimeException noModel) {
            artist = null;
        }
        final ChatModel coverModel = artist;
        busy = true;
        keys();
        say("Das Album wird exportiert ...");
        Thread worker = new Thread(() -> {
            String said;
            try {
                // The album's own cover; an album without one has it drawn now and keeps it.
                AlbumExporter.Cover source = of -> {
                    byte[] own = AlbumCover.bytes(albumFolder);
                    if (own != null) {
                        return own;
                    }
                    if (coverModel == null) {
                        return null;
                    }
                    SwingUtilities.invokeLater(() -> say("Die KI zeichnet das Cover ..."));
                    CoverArtist.Drawn made = new CoverArtist(coverModel).draw(of, albumFolder.resolve(COVER_ANSWERS));
                    AlbumCover.save(albumFolder, made.svg(), made.image());
                    return AlbumCover.png(made.image());
                };
                AlbumExporter.Result result = new AlbumExporter(songs.root(), new StageMixdown(), source).export(
                        exported, folder, AlbumExporter.Options.STANDARD,
                        line -> SwingUtilities.invokeLater(() -> say(line)));
                said = result.titles().size() + " Titel exportiert nach " + folder
                        + (result.skipped().isEmpty() ? "" : " · " + result.skipped().size() + " fehlen")
                        + (result.coverNote() == null ? "" : " · " + result.coverNote());
            } catch (Exception failed) {
                said = "Der Export ist fehlgeschlagen: " + (failed.getMessage() == null ? failed : failed.getMessage());
            }
            final String line = said;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                showCover(); // the export may have had it drawn
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
        static final int GEAR = 8;
        static final int STATE = 9;
        private final String[] names = {"Nr.", "Titel", "Stimmung", "BPM", "Tonart", "Takt", "Besetzung", "Länge",
                "⚙", "Stand"};

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
            if (column == 0 || column == GEAR || column == STATE || busy || preview != null || shownRunning()) {
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
                    // A title made from notes is as long as its notes: the album's minutes do not apply to it.
                    if (templateNames.containsKey(track.id())) {
                        return entry.status() == Status.DONE && entry.seconds() > 0 ? clock(entry.seconds())
                                : "aus den Noten";
                    }
                    if (entry.status() == Status.DONE && entry.seconds() > 0) {
                        return clock(entry.seconds()) + (track.asLongAsAsked(entry.seconds()) ? ""
                                : "  (Ziel " + clock(track.targetSeconds()) + ")");
                    }
                    return clock(track.targetSeconds()) + " (" + track.spec().bars() + " Takte)";
                case GEAR:
                    // a mark when the user set some of the advanced settings
                    return track.fixed().isEmpty() ? "⚙" : "⚙•";
                default:
                    String notes = templateNames.containsKey(track.id())
                            ? "  · Noten: " + templateNames.get(track.id()) : "";
                    String theme = track.theme().isEmpty() ? "" : "  · Hauptthema: " + track.theme();
                    return stateOf(track, entry) + notes + theme;
            }
        }

        private String stateOf(AlbumTrack track, AlbumProductionState.Entry entry) {
            switch (entry.status()) {
                case DONE:
                    return "fertig";
                case FAILED:
                    return "Fehler: " + entry.reason();
                case RUNNING:
                    if (!shownRunning()) {
                        // Left from a run that ended without finishing it: it goes on at the next start.
                        return "unterbrochen";
                    }
                    String now = doing.get(track.id());
                    return now == null ? "in Arbeit ..." : now;
                case QUEUED:
                    // "wartet" only while a production of this album goes on; else it is open.
                    return shownRunning() ? "wartet" : "offen";
                default:
                    return track.hasPiece() ? "eigenes Stück" : track.given() ? "vorgegeben" : "geplant";
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
                        // A title typed in is given: planning keeps it and only fills in the rest.
                        changed = t.withTitle(text).withGiven(t.given() || !t.hasPiece());
                        break;
                    case 2:
                        changed = t.withMood(text).withGiven(t.given() || !t.hasPiece());
                        break;
                    case 3:
                        changed = t.withBpm(Integer.parseInt(text)).fix(AlbumTrack.BPM);
                        break;
                    case 4:
                        changed = t.withKey(text).fix(AlbumTrack.KEY);
                        break;
                    case 5:
                        changed = t.withMeter(text).fix(AlbumTrack.METER);
                        break;
                    case 6:
                        changed = t.withInstrumentation(text).fix(AlbumTrack.PLAYERS);
                        break;
                    case 7:
                        changed = t.withSeconds(seconds(text)).fix(AlbumTrack.LENGTH);
                        break;
                    default:
                        return;
                }
                if (!changed.equals(t)) {
                    keep(album.with(changed));
                    fireTableRowsUpdated(row, row);
                    showRowSettings();
                    say(summary());
                }
            } catch (NumberFormatException notANumber) {
                say("Das ist keine Zahl: " + text);
            } catch (IOException | RuntimeException failed) {
                say("Die Änderung ließ sich nicht speichern: " + failed.getMessage());
            }
        }
    }

    /** The progress of a piece of the album that tells the page and shows the piece where it is open. */
    private static final class PieceWatch implements com.aresstack.audio.studio.progress.SketchProgress {
        private final com.aresstack.audio.studio.progress.Progress plain;
        private final com.aresstack.audio.studio.progress.SketchProgress shown;

        PieceWatch(com.aresstack.audio.studio.progress.Progress plain,
                   com.aresstack.audio.studio.progress.SketchProgress shown) {
            this.plain = plain;
            this.shown = shown;
        }

        @Override
        public void update(String message) {
            plain.update(message);
        }

        @Override
        public void track(String trackId, Stage stage, State state, String detail) {
            shown.track(trackId, stage, state, detail);
        }

        @Override
        public void sketch(List<String> arrangement) {
            shown.sketch(arrangement);
        }

        @Override
        public void arriving(long characters) {
            shown.arriving(characters);
        }

        @Override
        public void preparing(String what, long characters) {
            shown.preparing(what, characters);
        }

        @Override
        public void planned(List<String> chords) {
            shown.planned(chords);
        }

        @Override
        public void form(List<com.aresstack.audio.studio.progress.FormSection> sections) {
            shown.form(sections);
        }

        @Override
        public void instruments(List<String[]> chosen) {
            shown.instruments(chosen);
        }

        @Override
        public void done(double share) {
            shown.done(share);
        }
    }
}
