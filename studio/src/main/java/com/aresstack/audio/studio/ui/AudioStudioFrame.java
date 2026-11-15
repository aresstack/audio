package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.instruments.network.AudioProxyRoutes;
import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.aresstack.audio.studio.application.GemmaMusicComposer;
import com.aresstack.audio.studio.application.InstrumentProposalService;
import com.aresstack.audio.studio.application.PieceAutopilot;
import com.aresstack.audio.studio.application.MusicTrack;
import com.aresstack.audio.studio.application.StudioMusicRenderer;
import com.aresstack.audio.studio.application.TrackArrangement;
import com.aresstack.audio.studio.application.TrackArrangementStore;
import com.aresstack.audio.studio.keepass.AudioKeePassService;
import com.aresstack.audio.studio.keepass.KeePassEntry;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.progress.TrackProgress;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine;
import com.aresstack.audio.studio.settings.AiDefaults;
import com.aresstack.audio.studio.settings.StudioSettingsStore;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.net.URI;
import java.net.PasswordAuthentication;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Keep UI state and interactions here; delegate model calls, rendering and persistence to services. */
public final class AudioStudioFrame extends JFrame {
    private final InstrumentRegistry registry;
    private final TrackArrangementStore trackStore = new TrackArrangementStore();
    private TrackLanes trackTable;
    // The stage of the piece: where each track stands, how loud it is, and the room. It is kept in
    // the folder of the piece; moving something only mixes the rendered tracks again.
    private com.aresstack.audio.studio.stage.SpatialScene scene =
            com.aresstack.audio.studio.stage.SpatialScene.standard(MusicTrack.defaults());
    private final com.aresstack.audio.studio.stage.SpatialSceneStore sceneStore =
            new com.aresstack.audio.studio.stage.SpatialSceneStore();
    private StagePanel stagePanel;
    private final javax.swing.Timer remixTimer = new javax.swing.Timer(350, event -> remix());
    private final java.util.concurrent.ExecutorService mixQueue =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "stage-mix");
                thread.setDaemon(true);
                return thread;
            });
    private volatile int mixWanted;
    private Map<String, DeckPlayer.Envelope> stemEnvelopes = new HashMap<String, DeckPlayer.Envelope>();
    private boolean loadingTracks;
    private final StudioSettingsStore settingsStore = StudioSettingsStore.standard();
    private final JTextField endpoint = new JTextField(38);
    private final JPasswordField apiKey = new JPasswordField(38);
    private final JTextField model = new JTextField("google/gemma-4-31B-it", 23);
    private final JSpinner tokenLimit = new JSpinner(
            new SpinnerNumberModel(16384, 256, Integer.MAX_VALUE, 256));
    private final JTextField proxyHost = new JTextField(16);
    private final JTextField proxyPort = new JTextField("", 5);
    private final JComboBox<String> proxyMode = new JComboBox<String>(new String[]{
            "Standard (Windows: PAC per PowerShell, sonst kein Proxy)", "PAC_URL_POWERSHELL",
            "PAC_URL_WINDOWS_SETTINGS", "PAC_URL_WSCRIPT", "PAC_URL_MANUAL",
            "WINDOWS_STATIC_PROXY", "MANUAL_PROXY", "DISABLED"
    });
    private final JTextField proxyPacUrl = new JTextField(34);
    private final JTextField proxyNonProxyHosts = new JTextField(24);
    private final JCheckBox proxyBasic = new JCheckBox("Proxy-BASIC via KeePass");
    private final JTextField proxyCredentialRef = new JTextField(22);
    private final JComboBox<KeePassEntry> keePassEntries = new JComboBox<KeePassEntry>();
    private final JTextField directory = new JTextField(Paths.get(System.getProperty("user.home"),
            ".aresstack", "audio", "compositions", "district-dawn").toString(), 38);
    private final JTextField arrangementFile = new JTextField(38);
    private final JComboBox<String> sf2Engine = new JComboBox<String>(new String[]{
            "Java / Gervill (integriert, kein zusätzlicher Download)",
            "FluidSynth (optional, extern)"
    });
    private final JTextField fluidSynth = new JTextField("fluidsynth", 17);
    private final JTextField sfizz = new JTextField("sfizz_render", 17);
    private final JTextArea description = new JTextArea(
            "A charming pastel city slowly grows; compose a playful, cozy, uplifting orchestral theme.", 3, 54);
    private final JTextArea log = new JTextArea(10, 70);
    private final JComboBox<String> playbackSelection = new JComboBox<String>(new String[]{
            "Mixed sampled arrangement", "Java reference mix"
    });
    private final JSpinner parallelRequests = new JSpinner(
            new SpinnerNumberModel(32, 1, 1024, 1));
    private final JButton proposalButton = new JButton("KI-Instrumentvorschlag");
    private final JButton autopilotButton = new JButton("Vollautomatisch");
    private final JButton generateButton = new JButton("Mit Gemma komponieren");
    private final JButton renderButton = new JButton("Rendern");
    private final JButton cancelButton = new JButton("Abbrechen");
    private SwingWorker<Path, String> worker;
    private volatile KipitzChatClient activeClient;
    private String preferredKeePassEntry = "";
    // The desk: display, transport, waveform, master meters, and the pages behind the keys under the display.
    private final DeckPlayer player = new DeckPlayer();
    private final DeckDisplays.Lcd lcd = new DeckDisplays.Lcd();
    /** The line at the lower edge of the window: what the studio is doing or did last. */
    private final JLabel statusLine = new JLabel("Bereit");
    /** The keys under the display that choose the page, by the name of the page. */
    private final Map<String, DeckWidgets.PageKey> pageKeys = new java.util.LinkedHashMap<String, DeckWidgets.PageKey>();
    /** Beside the choice of the piece under the name of the studio: new, open, and what is seldom needed. */
    private final JButton songMenuKey = new JButton("▾");
    private final ArrangementView arrangementView = new ArrangementView(new ArrangementView.Listener() {
        @Override
        public void seek(double second) {
            double length = player.length();
            AudioStudioFrame.this.seek(length <= 0 ? 0 : Math.min(1, second / length));
        }

    });
    /** The piece written out as a conductor's score, on the page of the composition. */
    private final ScoreView scoreView = new ScoreView();
    private final com.aresstack.audio.studio.application.SongLibrary songs =
            com.aresstack.audio.studio.application.SongLibrary.standard();
    /** The model of the open piece; it follows what the studio still writes the old way. */
    private final com.aresstack.audio.studio.application.PieceModel pieceModel =
            new com.aresstack.audio.studio.application.PieceModel(this::append);
    private final JComboBox<com.aresstack.audio.studio.application.SongLibrary.Song> songChoice =
            new JComboBox<com.aresstack.audio.studio.application.SongLibrary.Song>();
    private boolean fillingSongs;
    /** What the plan says about the piece: tempo, key, bars, mood. */
    private final JLabel songFacts = new JLabel(" ");
    private final javax.swing.JToggleButton aiChooses = new javax.swing.JToggleButton("KI wählt Instrumente");
    private final javax.swing.JToggleButton ownTracks = new javax.swing.JToggleButton("Meine Spuren");
    private final JButton composeButton = new JButton("Komponieren");
    private final DeckDisplays.Meter masterLeft = new DeckDisplays.Meter(false);
    private final DeckDisplays.Meter masterRight = new DeckDisplays.Meter(false);
    private final DeckWidgets.TransportKey playKey = new DeckWidgets.TransportKey(
            DeckWidgets.Symbol.PLAY, AudioStudioTheme.GREEN, "Abspielen, was unter Wiedergabe gewählt ist (Leertaste)");
    private final DeckWidgets.TransportKey stopKey = new DeckWidgets.TransportKey(
            DeckWidgets.Symbol.STOP, AudioStudioTheme.TEXT, "Wiedergabe anhalten und an den Anfang");
    private final DeckWidgets.TransportKey renderKey = new DeckWidgets.TransportKey(
            DeckWidgets.Symbol.RECORD, AudioStudioTheme.ACCENT, "Rendern: aus dem Arrangement WAV-Dateien erzeugen");
    private final DeckWidgets.TransportKey cancelKey = new DeckWidgets.TransportKey(
            DeckWidgets.Symbol.CANCEL, AudioStudioTheme.AMBER, "Laufenden Vorgang abbrechen");
    private final java.awt.CardLayout pages = new java.awt.CardLayout();
    private final JPanel pageHolder = new JPanel(pages);
    private final javax.swing.Timer clock = new javax.swing.Timer(33, event -> tick());
    private boolean choosingPlayback;

    public AudioStudioFrame(InstrumentRegistry registry) {
        super("AresStack Audio Studio");
        this.registry = registry;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 700));
        setSize(1440, 900);
        setLocationRelativeTo(null);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(AudioStudioTheme.display(12f));
        log.setForeground(AudioStudioTheme.LCD_TEXT);
        log.setBackground(AudioStudioTheme.LCD_GLASS);
        log.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        initializeContents();
        loadSettings();
        cancelButton.setEnabled(false);
        cancelKey.setEnabled(false);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                keepSong();
                saveSettings(false);
                stopPlayback();
            }
        });
        generateButton.addActionListener(event -> generate());
        renderButton.addActionListener(event -> render());
        cancelButton.addActionListener(event -> cancel());
        playbackSelection.addActionListener(event -> {
            if (!choosingPlayback) {
                showPlaybackChoice();
            }
        });
        showPlaybackChoice();
        clock.start();
    }

    private void initializeContents() {
        JPanel desk = new JPanel(new BorderLayout(14, 10));
        desk.setBackground(AudioStudioTheme.DESK);
        desk.setBorder(BorderFactory.createEmptyBorder(14, 14, 8, 14));
        desk.add(transportBar(), BorderLayout.NORTH);
        pageHolder.setOpaque(false);
        JPanel lanesPage = deckPage(); // also builds what the page of the piece shows
        pageHolder.add(songPage(), "song");
        pageHolder.add(albumsPage(), "albums");
        pageHolder.add(lanesPage, "deck");
        pageHolder.add(stagePage(), "stage");
        pageHolder.add(libraryPage(), "library");
        pageHolder.add(settingsPanel(), "settings");
        pageHolder.add(consolePage(), "console");
        desk.add(pageHolder, BorderLayout.CENTER);
        statusLine.setFont(statusLine.getFont().deriveFont(12f));
        statusLine.setForeground(AudioStudioTheme.TEXT_DIM);
        statusLine.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        statusLine.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        statusLine.setToolTipText("Klick: das ganze Protokoll zeigen");
        statusLine.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                showPage("console");
            }
        });
        desk.add(statusLine, BorderLayout.SOUTH);
        setContentPane(desk);

        // The space bar starts and stops the playback, unless something is being typed.
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event -> {
            if (event.getID() == java.awt.event.KeyEvent.KEY_PRESSED
                    && event.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE && isActive()
                    && !(event.getComponent() instanceof javax.swing.text.JTextComponent)
                    && !(event.getComponent() instanceof javax.swing.AbstractButton)
                    && !(event.getComponent() instanceof JComboBox)) {
                togglePlayback();
                return true;
            }
            return false;
        });
    }

    /** The head of the desk: its name, the display, the transport keys and the master meters. */
    private JPanel transportBar() {
        JPanel name = new JPanel();
        name.setOpaque(false);
        name.setLayout(new javax.swing.BoxLayout(name, javax.swing.BoxLayout.Y_AXIS));
        JLabel brand = DeckWidgets.lettering("AresStack");
        brand.setForeground(AudioStudioTheme.ACCENT_SOFT);
        JLabel title = new JLabel("Audio Studio");
        title.setFont(new java.awt.Font(java.awt.Font.SERIF, java.awt.Font.BOLD | java.awt.Font.ITALIC, 27));
        title.setForeground(AudioStudioTheme.TEXT);
        // Under the name: the choice of the piece, and beside it a small menu for new, open and the like.
        songChoice.setToolTipText("Die Komposition wählen, an der gearbeitet wird");
        songChoice.setPreferredSize(new Dimension(196, 28));
        songMenuKey.setToolTipText("Neue Komposition, öffnen, Ordner zeigen, Plan ansehen, Demo laden");
        songMenuKey.setMargin(new Insets(2, 4, 2, 4));
        songMenuKey.setFocusable(false);
        songMenuKey.setPreferredSize(new Dimension(30, 28));
        JPanel line = new JPanel(new BorderLayout(4, 0));
        line.setOpaque(false);
        line.add(songChoice, BorderLayout.CENTER);
        line.add(songMenuKey, BorderLayout.EAST);
        line.setAlignmentX(0f);
        line.setMaximumSize(new Dimension(232, 28));
        brand.setAlignmentX(0f);
        title.setAlignmentX(0f);
        name.add(javax.swing.Box.createVerticalGlue());
        name.add(brand);
        name.add(title);
        name.add(javax.swing.Box.createVerticalStrut(6));
        name.add(line);
        name.add(javax.swing.Box.createVerticalGlue());
        name.setPreferredSize(new Dimension(236, 96));

        playKey.addActionListener(event -> togglePlayback());
        stopKey.addActionListener(event -> stopPlayback());
        renderKey.addActionListener(event -> render());
        cancelKey.addActionListener(event -> cancel());
        JPanel keys = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        keys.setOpaque(false);
        keys.add(playKey);
        keys.add(stopKey);
        keys.add(javax.swing.Box.createHorizontalStrut(10));
        keys.add(renderKey);
        keys.add(cancelKey);
        JPanel keysWithNames = new JPanel(new BorderLayout(0, 2));
        keysWithNames.setOpaque(false);
        keysWithNames.add(keys, BorderLayout.CENTER);
        JPanel names = new JPanel(new java.awt.GridLayout(1, 4, 6, 0));
        names.setOpaque(false);
        for (String text : new String[]{"Play", "Stop", "Render", "Abbruch"}) {
            JLabel label = DeckWidgets.lettering(text);
            label.setFont(AudioStudioTheme.lettering(9f));
            label.setHorizontalAlignment(JLabel.CENTER);
            names.add(label);
        }
        keysWithNames.add(names, BorderLayout.SOUTH);

        JPanel master = new JPanel(new GridBagLayout());
        master.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 0);
        master.add(DeckWidgets.lettering("Master"), c);
        c.gridwidth = 1;
        c.gridy = 1;
        c.insets = new Insets(0, 0, 5, 6);
        master.add(side("L"), c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 5, 0);
        master.add(masterLeft, c);
        c.gridx = 0;
        c.gridy = 2;
        c.insets = new Insets(0, 0, 0, 6);
        master.add(side("R"), c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 0, 0);
        master.add(masterRight, c);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 18, 0));
        right.setOpaque(false);
        right.add(keysWithNames);
        right.add(master);

        JPanel inner = new JPanel(new BorderLayout(18, 0));
        inner.setOpaque(false);
        inner.add(name, BorderLayout.WEST);
        JPanel display = new JPanel(new BorderLayout(0, 7));
        display.setOpaque(false);
        display.add(lcd, BorderLayout.CENTER);
        // Under the display, as on a player: one key per page of the desk.
        JPanel keysOfPages = new JPanel(new java.awt.GridLayout(1, 7, 6, 0));
        keysOfPages.setOpaque(false);
        javax.swing.ButtonGroup onePage = new javax.swing.ButtonGroup();
        String[][] entries = {{"song", "Komposition"}, {"albums", "Alben"}, {"deck", "Arrangement"},
                {"stage", "Bühne"},
                {"library", "Soundbänke"}, {"settings", "Einstellungen"}, {"console", "Protokoll"}};
        for (final String[] entry : entries) {
            DeckWidgets.PageKey key = new DeckWidgets.PageKey(entry[1]);
            key.addActionListener(event -> pages.show(pageHolder, entry[0]));
            onePage.add(key);
            pageKeys.put(entry[0], key);
            keysOfPages.add(key);
        }
        pageKeys.get("song").setSelected(true);

        display.add(keysOfPages, BorderLayout.SOUTH);
        inner.add(display, BorderLayout.CENTER);
        inner.add(right, BorderLayout.EAST);
        DeckWidgets.Module bar = new DeckWidgets.Module("Transport", inner);
        return bar;
    }

    private static JLabel side(String letter) {
        JLabel label = new JLabel(letter);
        label.setFont(AudioStudioTheme.display(11f));
        label.setForeground(AudioStudioTheme.TEXT_DIM);
        return label;
    }

    /**
     * The arrangement page: the tracks one under the other, each with its head in front of its
     * lane of notes, filling the page.
     */
    private JPanel deckPage() {
        JPanel sourceChoice = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        sourceChoice.setOpaque(false);
        sourceChoice.add(DeckWidgets.lettering("Wiedergabe"));
        playbackSelection.setPreferredSize(new Dimension(230, 28));
        playbackSelection.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focused) {
                return super.getListCellRendererComponent(list, value == null ? "" : playbackLabel(String.valueOf(value)),
                        index, selected, focused);
            }
        });
        sourceChoice.add(playbackSelection);

        trackTable = new TrackLanes(registry, this::tracksChanged, new TrackLanes.Solo() {
            @Override
            public void listen(String trackId) {
                listenAlone(trackId);
            }

            @Override
            public void choose(String trackId) {
                playbackSelection.setSelectedItem(trackId == null ? "Mixed sampled arrangement" : trackId);
            }
        }, this::composeAgain, new TrackLanes.Stage() {
            @Override
            public com.aresstack.audio.studio.stage.SoundSourcePlacement placement(String trackId) {
                return scene.placement(trackId);
            }

            @Override
            public void change(com.aresstack.audio.studio.stage.SoundSourcePlacement placement, boolean settled) {
                sceneChanged(scene.with(placement), settled, true);
            }

            @Override
            public void renamed(String from, String to) {
                com.aresstack.audio.studio.stage.SoundSourcePlacement placement = scene.placement(from);
                if (placement != null) {
                    scene = scene.with(placement.named(to));
                }
                // The notes and the sound of the track keep belonging to it under its new name.
                try {
                    com.aresstack.audio.studio.application.StageMixService.rename(
                            Paths.get(directory.getText().trim()), from, to);
                    pieceModel.synchronize(); // the files were written: the model follows them to the new name
                } catch (IOException failed) {
                    append("WARN: Die Dateien der Spur ließen sich nicht umbenennen: " + failed.getMessage());
                }
                if (from.equals(playbackSelection.getSelectedItem())) {
                    playbackSelection.setSelectedItem("Mixed sampled arrangement");
                }
            }
        }, arrangementView);
        DeckWidgets.Module output = new DeckWidgets.Module("Arrangement", trackTable);
        output.setHeadEnd(sourceChoice);

        // The piece: which one, and what it shall be. What is seldom needed is in the menu "Mehr".
        final javax.swing.JPopupMenu moreMenu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem newSong = new javax.swing.JMenuItem("Neue Komposition …");
        newSong.setToolTipText("Ein neues, leeres Stück anlegen");
        newSong.addActionListener(event -> createNewComposition());
        moreMenu.add(newSong);
        moreMenu.addSeparator();
        javax.swing.JMenuItem importFolder = new javax.swing.JMenuItem("Ordner einer Komposition öffnen …");
        importFolder.addActionListener(event -> selectDirectory());
        moreMenu.add(importFolder);
        javax.swing.JMenuItem importFile = new javax.swing.JMenuItem("Arrangement-Datei (TSV) öffnen …");
        importFile.addActionListener(event -> selectArrangement());
        moreMenu.add(importFile);
        javax.swing.JMenuItem showFolder = new javax.swing.JMenuItem("Ordner dieser Komposition zeigen");
        showFolder.setToolTipText("Dort liegen WAV, MIDI und die Antworten der KI");
        showFolder.addActionListener(event -> openSongFolder());
        moreMenu.add(showFolder);
        moreMenu.addSeparator();
        javax.swing.JMenuItem onlyPropose = new javax.swing.JMenuItem("Nur Instrumente vorschlagen lassen");
        onlyPropose.setToolTipText("Die KI schlägt Instrumente vor; komponiert wird noch nicht");
        onlyPropose.addActionListener(event -> proposeInstruments(false));
        moreMenu.add(onlyPropose);
        javax.swing.JMenuItem inspect = new javax.swing.JMenuItem("Plan und Antworten der KI ansehen");
        inspect.addActionListener(event -> inspectComposition());
        moreMenu.add(inspect);
        moreMenu.addSeparator();
        javax.swing.JMenuItem saveMix = new javax.swing.JMenuItem("Mischung als WAV speichern (24 Bit) …");
        saveMix.addActionListener(event -> exportMix());
        moreMenu.add(saveMix);
        javax.swing.JMenuItem saveScore = new javax.swing.JMenuItem("Partitur als Bild speichern (PNG) …");
        saveScore.addActionListener(event -> exportScore());
        moreMenu.add(saveScore);
        javax.swing.JMenuItem print = new javax.swing.JMenuItem("Partitur drucken oder als PDF …");
        print.setToolTipText("Öffnet den Druckdialog; mit einem PDF-Drucker des Systems entsteht eine PDF-Datei");
        print.addActionListener(event -> printScore());
        moreMenu.add(print);
        moreMenu.addSeparator();
        javax.swing.JMenuItem demo = new javax.swing.JMenuItem("Demo laden");
        demo.setToolTipText("Das mitgelieferte Stück laden, ohne die KI zu fragen");
        demo.addActionListener(event -> loadDemo());
        moreMenu.add(demo);
        songMenuKey.addActionListener(event -> moreMenu.show(songMenuKey, 0, songMenuKey.getHeight()));
        songChoice.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private final java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm");

            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focused) {
                String text = "";
                if (value instanceof com.aresstack.audio.studio.application.SongLibrary.Song) {
                    com.aresstack.audio.studio.application.SongLibrary.Song song =
                            (com.aresstack.audio.studio.application.SongLibrary.Song) value;
                    text = song.name() + (index >= 0 && song.changed() > 0
                            ? "   ·   " + day.format(new java.util.Date(song.changed())) : "");
                }
                return super.getListCellRendererComponent(list, text, index, selected, focused);
            }
        });
        songChoice.addActionListener(event -> {
            if (!fillingSongs && songChoice.getSelectedItem() != null) {
                openSong(((com.aresstack.audio.studio.application.SongLibrary.Song) songChoice.getSelectedItem()).folder());
            }
        });

        // Composing: one key, and the choice of who decides on the instruments.
        javax.swing.ButtonGroup who = new javax.swing.ButtonGroup();
        who.add(aiChooses);
        who.add(ownTracks);
        aiChooses.setSelected(true);
        aiChooses.setToolTipText("Die KI schlägt zur Beschreibung passende Instrumente vor und komponiert dann für sie");
        ownTracks.setToolTipText("Für genau die Spuren im Arrangement komponieren; was schon komponiert ist, bleibt");
        composeButton.setToolTipText("Komponieren und gleich rendern");
        composeButton.setFont(composeButton.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        composeButton.addActionListener(event -> compose());
        getRootPane().setDefaultButton(composeButton);

        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        page.add(output, BorderLayout.CENTER);
        return page;
    }

    /**
     * The page of the piece: which piece, and what the AI is told. This is where a piece
     * begins; the arrangement page is where what was composed is adjusted.
     */
    private JPanel songPage() {
        songFacts.setForeground(AudioStudioTheme.TEXT_DIM);
        songFacts.setFont(songFacts.getFont().deriveFont(12f));

        JScrollPane prompt = new JScrollPane(description);
        prompt.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        description.setFont(description.getFont().deriveFont(15f));
        JPanel wish = new JPanel(new BorderLayout(0, 6));
        wish.setOpaque(false);
        wish.add(DeckWidgets.lettering("Was für Musik soll es sein? Das bekommt die KI gesagt."), BorderLayout.NORTH);
        wish.add(prompt, BorderLayout.CENTER);
        prompt.setPreferredSize(new Dimension(300, 92));

        // Under the words for the AI: what came of them, as the score a conductor reads.
        JScrollPane sheet = new JScrollPane(scoreView, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sheet.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        sheet.getVerticalScrollBar().setUnitIncrement(40);
        JPanel notes = new JPanel(new BorderLayout(0, 6));
        notes.setOpaque(false);
        notes.add(DeckWidgets.lettering("Partitur · was die Instrumente spielen"), BorderLayout.NORTH);
        notes.add(sheet, BorderLayout.CENTER);
        JPanel written = new JPanel(new BorderLayout(0, 14));
        written.setOpaque(false);
        written.add(wish, BorderLayout.NORTH);
        written.add(notes, BorderLayout.CENTER);

        JPanel whoRow = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        whoRow.setOpaque(false);
        whoRow.add(aiChooses);
        whoRow.add(ownTracks);
        whoRow.setPreferredSize(new Dimension(380, 40));
        composeButton.setPreferredSize(new Dimension(260, 44));
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bottom.setOpaque(false);
        bottom.add(whoRow);
        bottom.add(javax.swing.Box.createHorizontalStrut(14));
        bottom.add(composeButton);

        JPanel session = new JPanel(new BorderLayout(0, 16));
        session.setOpaque(false);
        session.add(written, BorderLayout.CENTER);
        session.add(bottom, BorderLayout.SOUTH);
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        DeckWidgets.Module module = new DeckWidgets.Module("Komposition", session);
        module.setHeadEnd(songFacts);
        page.add(module, BorderLayout.CENTER);
        return page;
    }

    /** The stage: where the instruments stand, how loud they are, and the room around them. */
    private JPanel stagePage() {
        remixTimer.setRepeats(false);
        stagePanel = new StagePanel(new StagePanel.Host() {
            @Override
            public void sceneChanged(com.aresstack.audio.studio.stage.SpatialScene changed, boolean settled) {
                AudioStudioFrame.this.sceneChanged(changed, settled, false);
            }

            @Override
            public void askDirector() {
                AudioStudioFrame.this.askDirector();
            }
        });
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        page.add(stagePanel, BorderLayout.CENTER);
        return page;
    }

    /**
     * The stage was changed on the stage page or on a channel strip. The other view follows at
     * once; when the change has settled, it is saved with the piece and the rendered tracks are
     * mixed again. That needs neither the AI nor a synthesizer.
     */
    private void sceneChanged(com.aresstack.audio.studio.stage.SpatialScene changed, boolean settled,
                              boolean fromMixer) {
        scene = changed;
        if (fromMixer) {
            showStage();
        } else {
            trackTable.refreshStage();
        }
        if (settled) {
            saveScene();
            remixTimer.restart();
        }
    }

    private void showStage() {
        if (stagePanel != null && trackTable != null) {
            stagePanel.setScene(scene, trackTable.tracks());
        }
    }

    private void saveScene() {
        try {
            sceneStore.save(Paths.get(directory.getText().trim()), scene);
        } catch (Exception error) {
            append("WARN: Bühne nicht gespeichert: " + error.getMessage());
        }
    }

    /** The written arrangement of the piece in use, or {@code null} when nothing is composed yet. */
    private Path writtenArrangement() {
        String named = arrangementFile.getText().trim();
        Path file = named.isEmpty() ? Paths.get(directory.getText().trim(), "arrangement.tsv") : Paths.get(named);
        return Files.isRegularFile(file) ? file : null;
    }

    /** Mixes the rendered tracks again on the stage as it stands, in the background. */
    private void remix() {
        if (stagePanel == null || (worker != null && !worker.isDone())) {
            return; // what is running mixes on the saved stage when it gets there
        }
        final Path arrangement = writtenArrangement();
        final Path folder = Paths.get(directory.getText().trim());
        if (arrangement == null) {
            stagePanel.setStatus("Noch nichts komponiert: die Bühne gilt, sobald gerendert wird.");
            return;
        }
        final com.aresstack.audio.studio.stage.SpatialScene mixed = scene;
        final int ticket = ++mixWanted;
        stagePanel.setStatus("Mische neu …");
        mixQueue.submit(() -> {
            if (ticket != mixWanted) {
                return; // moved on in the meantime: only the newest stage is mixed
            }
            final long began = System.nanoTime();
            String problem = null;
            try {
                new com.aresstack.audio.studio.application.StageMixService().mix(arrangement, folder, mixed,
                        message -> { });
            } catch (Exception failed) {
                problem = String.valueOf(failed.getMessage());
            }
            final String said = problem;
            final double seconds = (System.nanoTime() - began) / 1e9;
            SwingUtilities.invokeLater(() -> mixedAgain(folder, ticket, said, seconds));
        });
    }

    private void mixedAgain(Path folder, int ticket, String problem, double seconds) {
        if (!folder.equals(Paths.get(directory.getText().trim()))) {
            return; // another piece is open by now
        }
        if (problem != null) {
            stagePanel.setStatus(problem);
            return;
        }
        if (ticket == mixWanted && stagePanel.proposal() == null) {
            stagePanel.setStatus(String.format(java.util.Locale.GERMANY,
                    "Neu gemischt in %.1f s · ohne KI, ohne Synthese", seconds));
        }
        String selected = (String) playbackSelection.getSelectedItem();
        if (selected == null || "Java reference mix".equals(selected)) {
            return;
        }
        // What plays goes on at the same place with the new mix; a track that plays alone is mixed anew too.
        Path file = playbackFile(selected);
        if (player.ready()) {
            try {
                player.swap(file); // goes on at the same place, without a gap
            } catch (Exception noSound) {
                player.show(file);
            }
        } else {
            player.show(file);
        }
        arrangementView.setMix(player.envelope(), soloTrack() == null ? "Mix" : "Solo", soloTrack(), player.length());
        lcd.setSource(playbackName(selected), true);
    }

    /** Writes the mix on the stage to a WAV file of the user's choice, with 24 bits. */
    private void exportMix() {
        final Path arrangement = writtenArrangement();
        if (arrangement == null) {
            message("Erst komponieren und rendern: dann gibt es eine Mischung zu speichern.");
            return;
        }
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Mischung als WAV speichern (24 Bit)");
        chooser.setSelectedFile(new java.io.File(songs.name(Paths.get(directory.getText().trim()))
                .replaceAll("[^\\p{L}\\p{N} _-]", "").trim() + ".wav"));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path chosen = chooser.getSelectedFile().toPath();
        final Path target = chosen.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".wav")
                ? chosen : chosen.resolveSibling(chosen.getFileName() + ".wav");
        try {
            new com.aresstack.audio.studio.application.StageMixService().export(arrangement,
                    Paths.get(directory.getText().trim()), scene, target, 24);
            append("Mischung gespeichert (24 Bit, 44,1 kHz): " + target);
        } catch (Exception failed) {
            message("Die Mischung ließ sich nicht speichern: " + failed.getMessage());
        }
    }

    /** Prints the score, or saves it as a PDF where the system offers a printer that writes PDF. */
    private void printScore() {
        java.awt.print.Printable sheets = scoreView.printable();
        if (sheets == null) {
            message("Es gibt noch keine Noten: erst komponieren.");
            return;
        }
        try {
            java.awt.print.PrinterJob job = java.awt.print.PrinterJob.getPrinterJob();
            job.setJobName(songs.name(Paths.get(directory.getText().trim())) + " - Partitur");
            java.awt.print.PageFormat page = job.defaultPage();
            page.setOrientation(java.awt.print.PageFormat.LANDSCAPE);
            job.setPrintable(sheets, page);
            if (job.printDialog()) {
                job.print();
                append("Partitur an den Drucker gegeben.");
            }
        } catch (Exception failed) {
            message("Die Partitur ließ sich nicht drucken: " + failed.getMessage());
        }
    }

    /** Writes the score as a picture, dark on white as on paper, to a PNG file of the user's choice. */
    private void exportScore() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Partitur als Bild speichern (PNG)");
        chooser.setSelectedFile(new java.io.File(songs.name(Paths.get(directory.getText().trim()))
                .replaceAll("[^\\p{L}\\p{N} _-]", "").trim() + " - Partitur.png"));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path chosen = chooser.getSelectedFile().toPath();
        Path target = chosen.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                ? chosen : chosen.resolveSibling(chosen.getFileName() + ".png");
        try {
            java.awt.image.BufferedImage sheet = scoreView.picture(1600);
            if (sheet == null) {
                message("Es gibt noch keine Noten: erst komponieren.");
                return;
            }
            javax.imageio.ImageIO.write(sheet, "png", target.toFile());
            append("Partitur gespeichert: " + target);
        } catch (Exception failed) {
            message("Die Partitur ließ sich nicht speichern: " + failed.getMessage());
        }
    }

    /** The AI is told what each track plays and proposes levels, their course, places and the room. */
    private void askDirector() {
        final Path arrangement = writtenArrangement();
        if (arrangement == null) {
            message("Erst komponieren oder die Demo laden: die KI mischt ab, was geschrieben ist.");
            return;
        }
        final KipitzChatClient client;
        try {
            client = createAiClient();
        } catch (RuntimeException invalid) {
            message(invalid.getMessage());
            return;
        }
        final Path output = Paths.get(directory.getText().trim());
        final String prompt = description.getText().trim();
        final int tokens = (Integer) tokenLimit.getValue();
        final com.aresstack.audio.studio.stage.SpatialScene current = scene;
        if (!saveSettings(false)) {
            return;
        }
        activeClient = client;
        stagePanel.setProposal(null);
        stagePanel.setStatus("Die KI schlägt eine Abmischung vor …");
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                try {
                    final com.aresstack.audio.studio.stage.SpatialScene proposed =
                            new com.aresstack.audio.studio.application.MixDirector(client, tokens)
                                    .direct(prompt, arrangement, current, output, progress);
                    SwingUtilities.invokeLater(() -> stagePanel.setProposal(proposed));
                    progress.update("Abmischung vorgeschlagen: auf der Bühne ansehen, dann übernehmen oder verwerfen.");
                    return null;
                } finally {
                    activeClient = null;
                }
            }
        });
    }

    /** The page of the albums: several pieces planned and made together, beside the single piece. */
    private JPanel albumsPage() {
        AlbumsPanel albums = new AlbumsPanel(new AlbumsPanel.Host() {
            @Override
            public com.aresstack.audio.studio.ai.ModelLineup models() {
                // The one place the page gets its models from; the model settings replace this line.
                return com.aresstack.audio.studio.ai.ModelLineup.of(createAiClient());
            }

            @Override
            public int tokens() {
                return (Integer) tokenLimit.getValue();
            }

            @Override
            public int parallelRequests() {
                return (Integer) parallelRequests.getValue();
            }

            @Override
            public PieceAutopilot.Rendering rendering() {
                return new PieceAutopilot.Rendering(
                        sf2Engine.getSelectedIndex() == 1 ? Sf2Engine.FLUIDSYNTH : Sf2Engine.GERVILL,
                        fluidSynth.getText().trim(), sfizz.getText().trim());
            }

            @Override
            public void openPiece(Path folder) {
                openSong(folder);
                showPage("song");
            }

            @Override
            public Path chooseFolder(String title) {
                javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
                chooser.setDialogTitle(title);
                chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
                return chooser.showSaveDialog(AudioStudioFrame.this) == javax.swing.JFileChooser.APPROVE_OPTION
                        ? chooser.getSelectedFile().toPath() : null;
            }

            @Override
            public void log(String line) {
                SwingUtilities.invokeLater(() -> append(line));
            }

            @Override
            public String ask(String question, String proposal) {
                return (String) JOptionPane.showInputDialog(AudioStudioFrame.this, question, "Alben",
                        JOptionPane.PLAIN_MESSAGE, null, null, proposal);
            }

            @Override
            public boolean confirm(String question) {
                return JOptionPane.showConfirmDialog(AudioStudioFrame.this, question, "Alben",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
            }

            @Override
            public void piecesChanged() {
                refreshSongs();
            }
        }, songs, registry);
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        page.add(new DeckWidgets.Module("Alben · mehrere Stücke in einem Zug", albums),
                BorderLayout.CENTER);
        return page;
    }

    private JPanel libraryPage() {
        InstrumentLibraryPanel libraries = new InstrumentLibraryPanel(registry, this::configureNetworkRoutes,
                this::refreshInstrumentChoices, this::append);
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        page.add(new DeckWidgets.Module("Soundbänke · SF2, SF3 und SFZ", libraries), BorderLayout.CENTER);
        return page;
    }

    private JPanel consolePage() {
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createLineBorder(new java.awt.Color(0x24352C), 1, true));
        DeckWidgets.Module module = new DeckWidgets.Module("Protokoll", scroll);
        JButton clear = new JButton("Leeren");
        clear.addActionListener(event -> log.setText(""));
        module.setHeadEnd(clear);
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        page.add(module, BorderLayout.CENTER);
        return page;
    }

    /** External AI, KeePass, network and sampler configuration, each in a module of its own. */
    private JPanel settingsPanel() {
        JPanel ai = form();
        int row = 0;
        addRow(ai, row++, "Completions-URL", endpoint);
        JButton keepassList = new JButton("KeePass verbinden");
        keepassList.setToolTipText("Mit KeePassRPC verbinden und die Einträge laden");
        keepassList.addActionListener(event -> loadKeePassEntries());
        keePassEntries.setPreferredSize(new Dimension(260, 30));
        JButton keepassUse = new JButton("Eintrag übernehmen");
        keepassUse.addActionListener(event -> useKeePassEntry());
        JPanel credentialButtons = line(keepassList, keePassEntries, keepassUse);
        addRow(ai, row++, "API-Key", apiKey);
        addRow(ai, row++, "KeePass", credentialButtons);
        JPanel modelControls = new JPanel(new BorderLayout(8, 0));
        modelControls.setOpaque(false);
        modelControls.add(model, BorderLayout.CENTER);
        JButton listModels = new JButton("Modelle laden");
        listModels.addActionListener(event -> loadModelNames());
        modelControls.add(listModels, BorderLayout.EAST);
        addRow(ai, row++, "Modell", modelControls);
        tokenLimit.setPreferredSize(new Dimension(110, 30));
        parallelRequests.setPreferredSize(new Dimension(110, 30));
        addRow(ai, row++, "Antwortlänge",
                line(tokenLimit, note("Tokens je Antwort; der Kompositionsplan nutzt höchstens 2.048.")));
        addRow(ai, row++, "Parallel",
                line(parallelRequests, note("Anfragen gleichzeitig; höchstens so viele wie Spuren.")));
        addRow(ai, row, "", note("Der API-Key wird lokal im Klartext gespeichert (studio.properties im Benutzerordner)."));

        JPanel network = form();
        row = 0;
        addRow(network, row++, "Proxy-Modus", proxyMode);
        JButton testProxy = new JButton("Proxyweg prüfen");
        testProxy.addActionListener(event -> testProxyRoute());
        proxyHost.setPreferredSize(new Dimension(260, 30));
        proxyPort.setPreferredSize(new Dimension(80, 30));
        addRow(network, row++, "Manueller Proxy",
                line(proxyHost, DeckWidgets.lettering("Port"), proxyPort, testProxy));
        addRow(network, row++, "PAC-Adresse", proxyPacUrl);
        addRow(network, row++, "Ohne Proxy für", proxyNonProxyHosts);
        proxyBasic.setOpaque(false);
        proxyCredentialRef.setPreferredSize(new Dimension(260, 30));
        addRow(network, row, "Anmeldung",
                line(proxyBasic, DeckWidgets.lettering("KeePass-Eintrag"), proxyCredentialRef));

        JPanel sound = form();
        row = 0;
        addRow(sound, row++, "SF2-Synthesizer", sf2Engine);
        JButton fluidHelp = new JButton("Installieren …");
        fluidHelp.addActionListener(event -> openEngineWebsite("https://www.fluidsynth.org/download/"));
        fluidSynth.setPreferredSize(new Dimension(260, 30));
        addRow(sound, row++, "FluidSynth", line(fluidSynth, fluidHelp));
        JButton sfizzHelp = new JButton("Installieren …");
        sfizzHelp.addActionListener(event -> openEngineWebsite("https://github.com/sfztools/sfizz/releases"));
        sfizz.setPreferredSize(new Dimension(260, 30));
        addRow(sound, row++, "sfizz", line(sfizz, sfizzHelp));
        addRow(sound, row, "", note("SF2: Java/Gervill ohne Installation · SF3: FluidSynth · SFZ: sfizz."));

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new javax.swing.BoxLayout(column, javax.swing.BoxLayout.Y_AXIS));
        DeckWidgets.Module aiModule = new DeckWidgets.Module("KI-Zugang · OpenAI-kompatible KIPITZ-API", ai);
        JButton save = new JButton("Einstellungen speichern");
        save.addActionListener(event -> saveSettings(true));
        aiModule.setHeadEnd(save);
        column.add(aiModule);
        column.add(javax.swing.Box.createVerticalStrut(14));
        column.add(new DeckWidgets.Module("Netzwerk · Proxy", network));
        column.add(javax.swing.Box.createVerticalStrut(14));
        column.add(new DeckWidgets.Module("Klangerzeugung", sound));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(column, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(top);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    /** Components side by side with gaps between them, beginning flush left. */
    private static JPanel line(java.awt.Component... parts) {
        JPanel line = new JPanel();
        line.setOpaque(false);
        line.setLayout(new javax.swing.BoxLayout(line, javax.swing.BoxLayout.X_AXIS));
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                line.add(javax.swing.Box.createHorizontalStrut(8));
            }
            if (parts[i] instanceof javax.swing.JComponent) {
                ((javax.swing.JComponent) parts[i]).setAlignmentY(0.5f);
                java.awt.Dimension preferred = parts[i].getPreferredSize();
                parts[i].setMaximumSize(new Dimension(preferred.width, Math.max(preferred.height, 30)));
            }
            line.add(parts[i]);
        }
        line.add(javax.swing.Box.createHorizontalGlue());
        return line;
    }

    private static JPanel form() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        return form;
    }

    private static JLabel note(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(AudioStudioTheme.TEXT_FAINT);
        label.setFont(label.getFont().deriveFont(12f));
        return label;
    }

    private void addRow(JPanel panel, int row, String name, java.awt.Component component) {
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.gridy = row;
        label.insets = new Insets(5, 0, 5, 14);
        label.anchor = GridBagConstraints.WEST;
        JLabel lettering = DeckWidgets.lettering(name);
        lettering.setPreferredSize(new Dimension(132, 18));
        panel.add(lettering, label);
        GridBagConstraints field = new GridBagConstraints();
        field.gridx = 1;
        field.gridy = row;
        field.weightx = 1;
        field.fill = GridBagConstraints.HORIZONTAL;
        field.anchor = GridBagConstraints.WEST;
        field.insets = new Insets(4, 0, 4, 0);
        panel.add(component, field);
    }

    /** The one key: the AI chooses the instruments first, or the tracks of the mixer are composed as they are. */
    private void compose() {
        if (aiChooses.isSelected()) {
            if (startAfresh()) {
                proposeInstruments(true);
            }
        } else if (planStillFits()) {
            generate();
        }
    }

    /**
     * The AI shall choose the instruments, but the piece in use is composed already. Its kept
     * answers would simply be used again and nothing would change, so the studio asks: a new
     * piece beside this one, or this one anew.
     *
     * @return whether composing shall go on
     */
    private boolean startAfresh() {
        Path folder = Paths.get(directory.getText().trim());
        if (!Files.isRegularFile(folder.resolve("composition-plan.json"))
                && !Files.isRegularFile(folder.resolve("instrument-proposal.json"))
                && !Files.isRegularFile(folder.resolve("arrangement.tsv"))) {
            return true;
        }
        Object[] choices = {"Neues Stück", "Dieses ersetzen", "Abbrechen"};
        int choice = JOptionPane.showOptionDialog(this,
                "„" + songs.name(folder) + "“ ist schon komponiert.\n\n"
                        + "Neues Stück: es kommt als eigenes Stück in die Liste, dieses bleibt, wie es ist.\n"
                        + "Dieses ersetzen: Instrumente und Noten werden neu erfunden; "
                        + "die bisherige Fassung liegt danach im Ordner „earlier“.",
                "Komponieren", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        try {
            if (choice == 0) {
                keepSong();
                stopPlayback();
                Path fresh = songs.createUntitled(description.getText());
                directory.setText(fresh.toString());
                arrangementFile.setText("");
                loadArrangementForCurrentDirectory(new Properties());
                saveSettings(false);
                refreshSongs();
                append("Neues Stück angelegt; seinen Namen bekommt es vom Plan der KI: " + fresh.getFileName());
                return true;
            }
            if (choice == 1) {
                int put = putAnswersAside(folder, null);
                append("Das Stück wird neu komponiert; " + put + " bisherige Antworten liegen im Ordner „earlier“.");
                return true;
            }
        } catch (IOException failed) {
            message("Das ließ sich nicht vorbereiten: " + failed.getMessage());
        }
        return false;
    }

    /**
     * Moves the kept answers of the AI into the folder "earlier", so that it is asked anew.
     *
     * @param only the one file to move, or {@code null} for the plan, the proposal and all tracks
     */
    private int putAnswersAside(Path folder, String only) throws IOException {
        int put = 0;
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        List<Path> kept = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                boolean answer = name.endsWith(".json") && !name.equals(TrackArrangementStore.FILE_NAME)
                        && !name.equals(com.aresstack.audio.studio.stage.SpatialSceneStore.FILE_NAME);
                if (Files.isRegularFile(file) && (only == null ? answer : name.equals(only))) {
                    kept.add(file);
                }
            }
        }
        for (Path file : kept) {
            Path aside = Files.createDirectories(folder.resolve("earlier"));
            Files.move(file, aside.resolve(System.currentTimeMillis() + "-" + file.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            put++;
        }
        return put;
    }

    /** Has the AI compose one track anew: its kept answer is put aside, the other tracks stay as they are. */
    private void composeAgain(String trackId) {
        Path folder = Paths.get(directory.getText().trim());
        java.util.regex.Pattern kept = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(trackId) + "(-[0-9a-f]{12})?\\.json");
        int put = 0;
        try (java.util.stream.Stream<Path> files = Files.isDirectory(folder) ? Files.list(folder)
                : java.util.stream.Stream.<Path>empty()) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (kept.matcher(file.getFileName().toString()).matches()) {
                    Path aside = Files.createDirectories(folder.resolve("earlier"));
                    Files.move(file, aside.resolve(System.currentTimeMillis() + "-" + file.getFileName()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    put++;
                }
            }
        } catch (IOException failed) {
            message("Die bisherige Fassung der Spur ließ sich nicht beiseitelegen: " + failed.getMessage());
            return;
        }
        append(put == 0 ? "Spur " + trackId + " wird komponiert."
                : "Spur " + trackId + " wird neu komponiert; die bisherige Fassung liegt im Ordner „earlier“.");
        ownTracks.setSelected(true);
        generate();
    }

    /**
     * Composing for the tracks as they are, but the words for the AI are no longer those the
     * piece was planned from: the kept plan would still follow the old words. The studio asks.
     *
     * @return whether composing shall go on
     */
    private boolean planStillFits() {
        Path folder = Paths.get(directory.getText().trim());
        String now = description.getText().trim();
        String planned = songs.plannedFrom(folder);
        if (!Files.isRegularFile(folder.resolve("composition-plan.json")) || planned == null
                || planned.trim().equals(now)) {
            return true;
        }
        Object[] choices = {"Alles neu komponieren", "Nur Fehlendes ergänzen", "Abbrechen"};
        int choice = JOptionPane.showOptionDialog(this,
                "Die Beschreibung ist nicht mehr die, nach der dieses Stück geplant wurde.\n\n"
                        + "Alles neu komponieren: Plan und Noten folgen der neuen Beschreibung; "
                        + "die bisherige Fassung liegt danach im Ordner „earlier“.\n"
                        + "Nur Fehlendes ergänzen: der bisherige Plan gilt weiter, vorhandene Spuren bleiben.",
                "Komponieren", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        if (choice == 0) {
            try {
                int put = putAnswersAside(folder, null);
                append("Nach der neuen Beschreibung wird neu komponiert; " + put
                        + " bisherige Antworten liegen im Ordner „earlier“.");
                return true;
            } catch (IOException failed) {
                message("Das ließ sich nicht vorbereiten: " + failed.getMessage());
                return false;
            }
        }
        return choice == 1;
    }

    /** Compose exactly the rows currently selected by the user. */
    private void generate() {
        final TrackArrangement chosen;
        final KipitzChatClient client;
        try {
            chosen = trackTable.arrangement();
            client = createAiClient();
        } catch (RuntimeException invalid) {
            message(invalid.getMessage());
            return;
        }
        final String prompt = description.getText().trim();
        final Path output = Paths.get(directory.getText().trim());
        final int tokens = (Integer) tokenLimit.getValue();
        final int parallel = (Integer) parallelRequests.getValue();
        final RenderSettings settings;
        try {
            settings = captureRenderSettings(chosen);
            trackStore.save(output, chosen);
        } catch (Exception invalid) {
            message(invalid.getMessage());
            return;
        }
        if (!saveSettings(false)) {
            return;
        }
        activeClient = client;
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                try {
                    Path arrangement = composeIntoPiece(new GemmaMusicComposer(client, tokens, parallel),
                            prompt, output, chosen, progress);
                    return renderArrangement(arrangement, output, settings, progress);
                } finally {
                    activeClient = null;
                }
            }
        });
    }

    /**
     * Composes into the folder of the piece; everything this writes (the arrangement, the plan,
     * and for a first plan the words it follows) then becomes part of the model of the piece.
     * Besides renaming a track this is the only place where the model takes over the old files:
     * rendering, playing, mixing and proposals never change the saved piece, so a file that was
     * changed behind the model's back stays out of it.
     */
    private Path composeIntoPiece(final GemmaMusicComposer composer, final String prompt, final Path output,
                                  final TrackArrangement tracks, final Progress progress) throws Exception {
        return com.aresstack.audio.studio.application.ComposingIntoPiece.run(
                () -> composer.compose(prompt, output, tracks, progress), output, prompt, songs, pieceModel);
    }

    /** First ask the AI only for a proposal; optionally continue automatically. */
    private void proposeInstruments(final boolean autopilot) {
        final KipitzChatClient client;
        try {
            client = createAiClient();
        } catch (RuntimeException invalid) {
            message(invalid.getMessage());
            return;
        }
        final Path output = Paths.get(directory.getText().trim());
        final String prompt = description.getText().trim();
        final int tokens = (Integer) tokenLimit.getValue();
        final int parallel = (Integer) parallelRequests.getValue();
        if (!saveSettings(false)) {
            return;
        }
        // What the autopilot needs from the window, read here on the event thread.
        final com.aresstack.audio.studio.stage.SpatialScene stageBefore = scene;
        final PieceAutopilot.Rendering rendering = new PieceAutopilot.Rendering(
                sf2Engine.getSelectedIndex() == 1 ? Sf2Engine.FLUIDSYNTH : Sf2Engine.GERVILL,
                fluidSynth.getText().trim(), sfizz.getText().trim());
        if (!autopilot) {
            // A proposal is asked for anew each time; the one kept from before would only come again.
            try {
                putAnswersAside(output, "instrument-proposal.json");
            } catch (IOException failed) {
                message("Der bisherige Vorschlag ließ sich nicht beiseitelegen: " + failed.getMessage());
                return;
            }
        }
        activeClient = client;
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                try {
                    if (autopilot) {
                        // The AI decides everything: the same steps the production of an album runs.
                        PieceAutopilot pilot = new PieceAutopilot(registry,
                                com.aresstack.audio.studio.ai.ModelLineup.of(client), tokens, parallel, rendering);
                        return composeIntoPiece(pilot, prompt, output, stageBefore, progress);
                    }
                    final InstrumentProposalService.Staged staged = new InstrumentProposalService(client, tokens)
                            .proposeStaged(prompt, output, progress);
                    final TrackArrangement selected = withPreferredSoundbanks(staged.tracks());
                    SwingUtilities.invokeAndWait(new Runnable() {
                        @Override
                        public void run() {
                            trackTable.setArrangement(selected);
                            stagePanel.setProposal(staged.scene());
                        }
                    });
                    progress.update("Instrumentenvorschlag bereit. "
                            + "Du kannst die Tabelle ändern und danach auf 'Aus Tabelle komponieren' klicken.");
                    return null;
                } finally {
                    activeClient = null;
                }
            }
        });
    }

    /**
     * The autopilot composes into the folder of the piece (see {@link #composeIntoPiece} above):
     * the players and the stage the AI chose are shown as soon as they are there, the stage is
     * taken but for what is protected, and what was composed becomes part of the model.
     */
    private Path composeIntoPiece(PieceAutopilot pilot, String prompt, Path output,
                                  com.aresstack.audio.studio.stage.SpatialScene stageBefore,
                                  Progress progress) throws Exception {
        return pilot.run(prompt, output, null, stageBefore, songs, pieceModel, progress,
                (selected, staged) -> SwingUtilities.invokeAndWait(() -> {
                    trackTable.setArrangement(selected);
                    scene = staged;
                    showStage();
                    trackTable.refreshStage();
                })).rendered();
    }

    private TrackArrangement withPreferredSoundbanks(TrackArrangement original) {
        return PieceAutopilot.withPreferredSoundbanks(original, registry);
    }

    private KipitzChatClient createAiClient() {
        String url = endpoint.getText().trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Trage unter 3 · Einstellungen die KIPITZ-URL ein.");
        }
        char[] secret = apiKey.getPassword();
        try {
            return new KipitzChatClient(url, new String(secret), model.getText().trim(),
                    configureNetworkRoutes());
        } finally {
            Arrays.fill(secret, (char) 0);
        }
    }

    private void render() {
        String file = arrangementFile.getText().trim();
        if (file.isEmpty()) {
            message("Choose an existing arrangement or load the bundled demo.");
            return;
        }
        final Path arrangement = Paths.get(file);
        final Path output = Paths.get(directory.getText().trim());
        final RenderSettings settings = captureRenderSettings();
        if (!saveSettings(false)) {
            return;
        }
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                return renderArrangement(arrangement, output, settings, progress);
            }
        });
    }

    /** The proxy settings as chosen here; nothing chosen means the defaults. */
    private Properties networkSettings() {
        Properties settings = new Properties();
        int modeIndex = proxyMode.getSelectedIndex();
        if (modeIndex > 0) {
            settings.setProperty("proxyMode", proxyMode.getItemAt(modeIndex));
        }
        if (modeIndex > 0) {
            String host = proxyHost.getText().trim();
            if (!host.isEmpty()) {
                settings.setProperty("proxyHost", host);
            }
            String port = proxyPort.getText().trim();
            if (!port.isEmpty()) {
                settings.setProperty("proxyPort", port);
            }
            String pac = proxyPacUrl.getText().trim();
            if (!pac.isEmpty()) {
                settings.setProperty("proxyPacUrl", pac);
            }
        }
        String exclusions = proxyNonProxyHosts.getText().trim();
        if (!exclusions.isEmpty()) {
            settings.setProperty("proxyNonProxyHosts", exclusions);
        }
        if (proxyBasic.isSelected()) {
            settings.setProperty("proxyAuthMode", "BASIC");
            settings.setProperty("proxyCredentialRef", proxyCredentialRef.getText().trim());
        }
        return settings;
    }

    private AudioProxyRoutes configureNetworkRoutes() {
        Properties settings = networkSettings();
        AudioProxyRoutes routes = new AudioProxyRoutes(settings);
        String credentialRef = AudioProxyRoutes.basicCredentialReference(settings);
        if (credentialRef != null && !credentialRef.trim().isEmpty()) {
            String title = credentialRef.startsWith("keepass:")
                    ? credentialRef.substring("keepass:".length()) : credentialRef;
            routes.enableBasicAuthentication(settings, () -> {
                try {
                    String[] credentials = AudioKeePassService.credentials(
                            AudioKeePassService.standardHome(), title, () -> null);
                    return credentials == null ? null
                            : new PasswordAuthentication(credentials[0], credentials[1].toCharArray());
                } catch (Exception unavailable) {
                    return null;
                }
            });
        }
        return routes;
    }

    private void testProxyRoute() {
        final String address = endpoint.getText().trim().isEmpty()
                ? "https://github.com/" : endpoint.getText().trim();
        final AudioProxyRoutes routes;
        try {
            routes = configureNetworkRoutes();
        } catch (RuntimeException error) {
            message(error.getMessage());
            return;
        }
        append("Prüfe Proxyweg (" + routes.describe() + ") für " + address);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                java.net.Proxy route = routes.proxyFor(URI.create(address));
                return route == java.net.Proxy.NO_PROXY ? "DIRECT" : route.toString();
            }

            @Override
            protected void done() {
                try {
                    append("Proxyweg: " + get());
                } catch (Exception failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    append("Proxyprüfung fehlgeschlagen: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private String pairingCode() {
        final AtomicReference<String> answer = new AtomicReference<String>();
        Runnable dialog = () -> answer.set(JOptionPane.showInputDialog(this,
                "KeePassRPC zeigt jetzt einen einmaligen Kopplungscode. Bitte hier eingeben:",
                "KeePass koppeln", JOptionPane.QUESTION_MESSAGE));
        if (SwingUtilities.isEventDispatchThread()) {
            dialog.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(dialog);
            } catch (Exception failed) {
                return null;
            }
        }
        return answer.get();
    }

    private void loadKeePassEntries() {
        append("KeePassRPC: Suche nach Einträgen ...");
        new SwingWorker<List<KeePassEntry>, Void>() {
            @Override
            protected List<KeePassEntry> doInBackground() throws Exception {
                return AudioKeePassService.entries(() -> pairingCode());
            }
            @Override
            protected void done() {
                try {
                    List<KeePassEntry> found = get();
                    keePassEntries.removeAllItems();
                    for (KeePassEntry entry : found) {
                        keePassEntries.addItem(entry);
                        if (entry.title().equalsIgnoreCase(preferredKeePassEntry)) {
                            keePassEntries.setSelectedItem(entry);
                        }
                    }
                    append("KeePassRPC: " + found.size() + " Einträge gefunden.");
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                    message("KeePass konnte nicht verbunden werden: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void useKeePassEntry() {
        final KeePassEntry selection = (KeePassEntry) keePassEntries.getSelectedItem();
        if (selection == null) {
            message("Bitte zuerst KeePass verbinden und einen Eintrag wählen.");
            return;
        }
        new SwingWorker<KeePassEntry, Void>() {
            @Override
            protected KeePassEntry doInBackground() throws Exception {
                return AudioKeePassService.entry(selection.title(), () -> pairingCode());
            }

            @Override
            protected void done() {
                try {
                    KeePassEntry selected = get();
                    if (selected == null) {
                        throw new IOException("Der Eintrag wurde nicht gefunden.");
                    }
                    apiKey.setText(selected.password());
                    preferredKeePassEntry = selected.title();
                    if (selected.hasUrl()) {
                        endpoint.setText(normalizeKeePassUrl(selected.url()));
                    }
                    append("KeePass-Eintrag übernommen: " + selected.title());
                    saveSettings(false);
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                    message("KeePass-Eintrag konnte nicht gelesen werden: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private static String normalizeKeePassUrl(String original) {
        String value = original.trim().replaceAll("/+$", "");
        if (value.endsWith("/chat/completions")) {
            return value;
        }
        return value + "/chat/completions";
    }

    private void loadModelNames() {
        if (endpoint.getText().trim().isEmpty()) {
            message("Bitte zuerst den KIPITZ-Endpunkt oder einen KeePass-Eintrag wählen.");
            return;
        }
        final String endpointUrl = endpoint.getText().trim();
        final String chosenModel = model.getText().trim();
        final String secret = new String(apiKey.getPassword());
        final AudioProxyRoutes routes = configureNetworkRoutes();
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return new KipitzChatClient(endpointUrl, secret, chosenModel, routes).listModels();
            }

            @Override
            protected void done() {
                try {
                    List<String> models = get();
                    if (models.isEmpty()) {
                        message("Der Server hat keine Modelle zurückgegeben.");
                        return;
                    }
                    Object choice = JOptionPane.showInputDialog(AudioStudioFrame.this,
                            "Modell für Komposition wählen:", "KIPITZ-Modelle",
                            JOptionPane.QUESTION_MESSAGE, null,
                            models.toArray(), models.contains(chosenModel)
                                    ? chosenModel : models.get(0));
                    if (choice != null) {
                        model.setText(choice.toString());
                        saveSettings(false);
                    }
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    message("Modelle konnten nicht geladen werden: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private RenderSettings captureRenderSettings() {
        return captureRenderSettings(trackTable.arrangement());
    }

    private RenderSettings captureRenderSettings(TrackArrangement arrangement) {
        Map<String, InstrumentLibrary> assignments = new HashMap<String, InstrumentLibrary>();
        Map<String, Integer> programs = new HashMap<String, Integer>();
        for (MusicTrack track : arrangement.tracks()) {
            programs.put(track.id(), track.program());
            if (!"builtin".equals(track.libraryId())) {
                InstrumentLibrary installed = findInstalledBank(track.libraryId());
                if (installed == null) {
                    throw new IllegalArgumentException("Soundbank nicht installiert: "
                            + track.libraryId() + " (Spur " + track.id() + ")");
                }
                assignments.put(track.id(), installed);
            }
        }
        Sf2Engine engine = sf2Engine.getSelectedIndex() == 1
                ? Sf2Engine.FLUIDSYNTH : Sf2Engine.GERVILL;
        return new RenderSettings(assignments, programs, engine,
                fluidSynth.getText().trim(), sfizz.getText().trim());
    }

    private InstrumentLibrary findInstalledBank(String libraryId) {
        for (InstrumentLibrary library : registry.installedInstruments()) {
            if (library.id().equals(libraryId)) {
                return library;
            }
        }
        return null;
    }

    private Path renderArrangement(Path source, Path destination, RenderSettings settings,
                                   Progress progress) throws Exception {
        return new StudioMusicRenderer(registry).render(source, destination, settings.assignments,
                settings.programs, settings.sf2Engine,
                settings.fluidSynthExecutable, settings.sfizzExecutable, progress);
    }

    private void createNewComposition() {
        String name = (String) JOptionPane.showInputDialog(this, "Wie soll das neue Stück heißen?", "Neues Stück",
                JOptionPane.PLAIN_MESSAGE, null, null, "Neues Stück");
        if (name == null) {
            return;
        }
        try {
            keepSong();
            Path folder = songs.create(name);
            directory.setText(folder.toString());
            arrangementFile.setText("");
            loadArrangementForCurrentDirectory(new Properties());
            saveSettings(false);
            refreshSongs();
            append("Neues Stück angelegt: " + songs.name(folder));
        } catch (IOException failed) {
            message("Das Stück ließ sich nicht anlegen: " + failed.getMessage());
        }
    }

    /** Switches to another piece: its tracks, its description, its arrangement and what was rendered. */
    private void openSong(Path folder) {
        if (folder.equals(Paths.get(directory.getText().trim()))) {
            return;
        }
        if (worker != null && !worker.isDone()) {
            message("Erst den laufenden Vorgang beenden oder abbrechen.");
            refreshSongs();
            return;
        }
        keepSong();
        stopPlayback();
        directory.setText(folder.toString());
        Path song = folder.resolve("arrangement.tsv");
        arrangementFile.setText(Files.isRegularFile(song) ? song.toString() : "");
        String kept = songs.description(folder);
        if (kept != null) {
            description.setText(kept);
            description.setCaretPosition(0);
        }
        loadArrangementForCurrentDirectory(new Properties());
        saveSettings(false);
        refreshSongs();
    }

    /** Keeps the description with the piece it belongs to. */
    private void keepSong() {
        Path folder = Paths.get(directory.getText().trim());
        if (Files.isDirectory(folder)) {
            try {
                songs.save(folder, null, description.getText());
            } catch (IOException ignored) {
                // The description stays in the studio's settings; the piece is not affected.
            }
        }
    }

    /** Fills the list of pieces and shows the one in use, also when it lies outside the library. */
    private void refreshSongs() {
        fillingSongs = true;
        try {
            Path current = Paths.get(directory.getText().trim());
            songChoice.removeAllItems();
            boolean listed = false;
            for (com.aresstack.audio.studio.application.SongLibrary.Song song : songs.songs()) {
                songChoice.addItem(song);
                listed |= song.folder().equals(current);
            }
            com.aresstack.audio.studio.application.SongLibrary.Song shown = songs.song(current);
            if (!listed) {
                songChoice.insertItemAt(shown, 0);
            }
            songChoice.setSelectedItem(shown);
            setTitle("AresStack Audio Studio — " + shown.name());
            songFacts.setText(songFacts(current));
        } finally {
            fillingSongs = false;
        }
    }

    /** One line about the piece from its plan: tempo, key, metre, length and mood; a hint when there is no plan. */
    private static String songFacts(Path folder) {
        Path plan = folder.resolve("composition-plan.json");
        if (!Files.isRegularFile(plan)) {
            return Files.isRegularFile(folder.resolve("arrangement.tsv")) ? "Arrangement ohne Plan der KI"
                    : "Noch nicht komponiert";
        }
        try {
            com.google.gson.JsonObject json = new com.google.gson.JsonParser().parse(
                    new String(Files.readAllBytes(plan), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            StringBuilder facts = new StringBuilder();
            String[][] parts = {{"tempoBpm", " BPM"}, {"key", ""}, {"timeSignature", ""}, {"bars", " Takte"}};
            for (String[] part : parts) {
                if (json.has(part[0]) && json.get(part[0]).isJsonPrimitive()) {
                    facts.append(facts.length() > 0 ? "  ·  " : "").append(json.get(part[0]).getAsString()).append(part[1]);
                }
            }
            if (json.has("mood") && json.get("mood").isJsonPrimitive()) {
                facts.append(facts.length() > 0 ? "  ·  " : "").append("Stimmung: ").append(json.get("mood").getAsString());
            }
            return facts.length() == 0 ? " " : facts.toString();
        } catch (Exception unreadable) {
            return " ";
        }
    }

    private void openSongFolder() {
        Path folder = Paths.get(directory.getText().trim());
        try {
            Files.createDirectories(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception failed) {
            message("Der Ordner ließ sich nicht öffnen: " + folder);
        }
    }

    private void loadSettings() {
        try {
            Properties saved = AiDefaults.withDefaults(settingsStore.load());
            preferredKeePassEntry = saved.getProperty("keepassEntry", "");
            endpoint.setText(saved.getProperty("endpoint", ""));
            apiKey.setText(saved.getProperty("apiKey", ""));
            model.setText(saved.getProperty("model", model.getText()));
            tokenLimit.setValue(AiDefaults.maxTokens(saved));
            proxyHost.setText(saved.getProperty("proxyHost", ""));
            proxyPort.setText(saved.getProperty("proxyPort", ""));
            proxyPacUrl.setText(saved.getProperty("proxyPacUrl", ""));
            proxyNonProxyHosts.setText(saved.getProperty("proxyNonProxyHosts", ""));
            proxyBasic.setSelected("BASIC".equalsIgnoreCase(saved.getProperty("proxyAuthMode", "")));
            proxyCredentialRef.setText(saved.getProperty("proxyCredentialRef", ""));
            String savedMode = saved.getProperty("proxyMode", "");
            if (!savedMode.isEmpty()) {
                proxyMode.setSelectedItem(savedMode);
            }
            append("Proxy: " + new AudioProxyRoutes(networkSettings()).describe());
            directory.setText(saved.getProperty("outputDirectory", directory.getText()));
            Path previousArrangement = Paths.get(directory.getText().trim(), "arrangement.tsv");
            if (Files.isRegularFile(previousArrangement)) {
                arrangementFile.setText(previousArrangement.toString());
                append("Vorhandene Komposition wiedergefunden: " + previousArrangement);
            }
            sf2Engine.setSelectedIndex("FLUIDSYNTH".equals(saved.getProperty("sf2Engine"))
                    ? 1 : 0);
            fluidSynth.setText(saved.getProperty("fluidSynth", fluidSynth.getText()));
            sfizz.setText(saved.getProperty("sfizz", sfizz.getText()));
            description.setText(saved.getProperty("description", description.getText()));
            parallelRequests.setValue(parseParallel(saved.getProperty("parallel", "32")));
            String keptDescription = songs.description(Paths.get(directory.getText().trim()));
            if (keptDescription != null) {
                description.setText(keptDescription);
            }
            ownTracks.setSelected("own".equals(saved.getProperty("instrumentMode")));
            loadArrangementForCurrentDirectory(saved);
            refreshSongs();
            append("Einstellungen geladen: " + settingsStore.location());
        } catch (IOException error) {
            append("Einstellungen konnten nicht geladen werden: " + error.getMessage());
        }
    }

    private boolean saveSettings(boolean notifyUser) {
        try {
            Properties saved = new Properties();
            saved.setProperty("endpoint", endpoint.getText().trim());
            saved.setProperty("keepassEntry", preferredKeePassEntry);
            char[] password = apiKey.getPassword();
            try {
                saved.setProperty("apiKey", new String(password));
            } finally {
                Arrays.fill(password, '\0');
            }
            saved.setProperty("model", model.getText().trim());
            saved.setProperty("maxTokens", tokenLimit.getValue().toString());
            saved.setProperty("proxyHost", proxyHost.getText().trim());
            saved.setProperty("proxyPort", proxyPort.getText().trim());
            String selectedProxyMode = proxyMode.getSelectedIndex() == 0 ? ""
                    : String.valueOf(proxyMode.getSelectedItem());
            saved.setProperty("proxyMode", selectedProxyMode);
            saved.setProperty("proxyPacUrl", proxyPacUrl.getText().trim());
            saved.setProperty("proxyNonProxyHosts", proxyNonProxyHosts.getText().trim());
            saved.setProperty("proxyAuthMode", proxyBasic.isSelected() ? "BASIC" : "");
            saved.setProperty("proxyCredentialRef", proxyCredentialRef.getText().trim());
            saved.setProperty("outputDirectory", directory.getText().trim());
            saved.setProperty("sf2Engine", sf2Engine.getSelectedIndex() == 1
                    ? "FLUIDSYNTH" : "GERVILL");
            saved.setProperty("fluidSynth", fluidSynth.getText().trim());
            saved.setProperty("sfizz", sfizz.getText().trim());
            saved.setProperty("description", description.getText());
            saved.setProperty("parallel", parallelRequests.getValue().toString());
            saved.setProperty("instrumentMode", ownTracks.isSelected() ? "own" : "ai");
            if (trackTable != null && !loadingTracks && trackTable.arrangement() != null) {
                trackStore.save(Paths.get(directory.getText().trim()), trackTable.currentArrangement());
            }
            settingsStore.save(saved);
            if (notifyUser) {
                JOptionPane.showMessageDialog(this, "Gespeichert unter " + settingsStore.location()
                        + "\nHinweis: Der API-Key steht dort im Klartext, nur für dein Benutzerprofil.");
            }
            return true;
        } catch (IOException error) {
            append("Fehler beim Speichern der Einstellungen: " + error.getMessage());
            if (notifyUser) {
                message(error.getMessage());
            }
            return false;
        }
    }

    private void start(final Operation action) {
        if (worker != null && !worker.isDone()) {
            return;
        }
        if (pageKeys.get("song").isSelected()) {
            showPage("deck"); // composing is watched where the tracks are
        }
        generateButton.setEnabled(false);
        composeButton.setEnabled(false);
        keepSong();
        proposalButton.setEnabled(false);
        autopilotButton.setEnabled(false);
        renderButton.setEnabled(false);
        trackTable.setEditingEnabled(false);
        stagePanel.setBusy(true);
        saveScene();
        cancelButton.setEnabled(true);
        cancelKey.setEnabled(true);
        renderKey.setEnabled(false);
        lcd.setBusy(true);
        trackStates.clear();
        trackTable.clearWork();
        worker = new SwingWorker<Path, String>() {
            @Override
            protected Path doInBackground() throws Exception {
                return action.execute(new TrackProgress() {
                    @Override
                    public void update(String update) {
                        publish(update);
                    }

                    @Override
                    public void track(final String trackId, final TrackProgress.Stage stage,
                                      final TrackProgress.State state, final String detail) {
                        SwingUtilities.invokeLater(() -> trackWork(trackId, stage, state, detail));
                    }
                });
            }

            @Override
            protected void process(java.util.List<String> updates) {
                for (String update : updates) {
                    append(update);
                }
            }

            @Override
            protected void done() {
                generateButton.setEnabled(true);
                composeButton.setEnabled(true);
                refreshSongs(); // the plan may have given the piece its title
                proposalButton.setEnabled(true);
                autopilotButton.setEnabled(true);
                renderButton.setEnabled(true);
                trackTable.setEditingEnabled(true);
                stagePanel.setBusy(false);
                cancelButton.setEnabled(false);
                cancelKey.setEnabled(false);
                renderKey.setEnabled(true);
                lcd.setBusy(false);
                trackStates.clear();
                trackTable.clearWork();
                        try {
                    Path result = get();
                    if (result != null) {
                        arrangementFile.setText(result.getParent().resolve("arrangement.tsv").toString());
                        append("Finished: " + result);
                    }
                } catch (java.util.concurrent.CancellationException cancelled) {
                    append("Operation cancelled.");
                } catch (Exception failed) {
                    Throwable reason = failed.getCause() == null ? failed : failed.getCause();
                    append("ERROR: " + reason.getMessage());
                } finally {
                    // Retain access to a successfully generated composition if sampled rendering fails.
                    Path savedArrangement = Paths.get(directory.getText().trim(), "arrangement.tsv");
                    if (Files.isRegularFile(savedArrangement)) {
                        arrangementFile.setText(savedArrangement.toString());
                    }
                    // What was rendered is there to be seen and heard at once.
                    showPlaybackChoice();
                    refreshStems();
                }
            }
        };
        worker.execute();
    }

    private void cancel() {
        KipitzChatClient request = activeClient;
        if (request != null) {
            request.cancel();
        }
        if (worker != null) {
            worker.cancel(true);
            append("Cancellation requested.");
        }
    }

    private void selectDirectory() {
        JFileChooser chooser = new JFileChooser(directory.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            keepSong();
            directory.setText(chooser.getSelectedFile().toString());
            String kept = songs.description(chooser.getSelectedFile().toPath());
            if (kept != null) {
                description.setText(kept);
            }
            loadArrangementForCurrentDirectory(new Properties());
            refreshSongs();
            Path song = chooser.getSelectedFile().toPath().resolve("arrangement.tsv");
            arrangementFile.setText(Files.isRegularFile(song) ? song.toString() : "");
        }
    }

    private void selectArrangement() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            arrangementFile.setText(chooser.getSelectedFile().toString());
            Path parent = chooser.getSelectedFile().toPath().toAbsolutePath().getParent();
            keepSong();
            directory.setText(parent.toString());
            loadArrangementForCurrentDirectory(new Properties());
            refreshSongs();
        }
    }

    private void openEngineWebsite(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception error) {
            message("Die Downloadseite konnte nicht geöffnet werden: " + url);
        }
    }

    private void loadDemo() {
        Path destination = Paths.get(directory.getText().trim(), "arrangement.tsv");
        try (java.io.InputStream input = getClass().getResourceAsStream("/district-dawn.tsv")) {
            if (input == null) {
                throw new IOException("Bundled sample arrangement is missing.");
            }
            Files.createDirectories(destination.getParent());
            Files.copy(input, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            arrangementFile.setText(destination.toString());
            trackTable.setArrangement(TrackArrangement.defaults());
            append("Loaded original Gemma composition (no API call): " + destination);
        } catch (IOException error) {
            message(error.getMessage());
        }
    }

    private void refreshInstrumentChoices() {
        trackTable.refreshLibraries();
    }

    private void inspectComposition() {
        StringBuilder document = new StringBuilder();
        Path folder = Paths.get(directory.getText().trim());
        List<Path> previewFiles = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> existing = Files.list(folder)) {
            existing.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted().forEach(previewFiles::add);
        } catch (IOException ignored) {
            message("Kein Kompositionsverzeichnis vorhanden: " + folder);
            return;
        }
        for (Path current : previewFiles) {
            String file = current.getFileName().toString();
            if (!Files.isRegularFile(current)) {
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(current);
                if (bytes.length > 250000) {
                    throw new IOException("JSON response too large to display: " + file);
                }
                document.append("=== ").append(file).append(" ===\n");
                document.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                document.append("\n\n");
            } catch (IOException error) {
                message(error.getMessage());
                return;
            }
        }
        if (document.length() == 0) {
            message("No saved Gemma JSON exists in this output directory.");
            return;
        }
        JTextArea preview = new JTextArea(document.toString());
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(preview);
        scroll.setPreferredSize(new Dimension(760, 480));
        JOptionPane.showMessageDialog(this, scroll,
                "Gemma composition plan and tracks", JOptionPane.INFORMATION_MESSAGE);
    }

    /** The WAV file behind a choice of the playback: the sampled mix, the Java mix, or one track. */
    private Path playbackFile(String selected) {
        if (selected == null) {
            return null;
        }
        Path folder = Paths.get(directory.getText().trim());
        if ("Mixed sampled arrangement".equals(selected)) {
            return folder.resolve("arrangement-instruments.wav");
        }
        if ("Java reference mix".equals(selected)) {
            return folder.resolve("arrangement.wav");
        }
        // One track alone is heard as it stands on the stage: with its level, its place and the room.
        Path arrangement = writtenArrangement();
        Path raw = com.aresstack.audio.studio.application.StageMixService.stem(folder, selected);
        if (arrangement != null && Files.isRegularFile(raw)) {
            try {
                return new com.aresstack.audio.studio.application.StageMixService().solo(arrangement, folder, scene,
                        selected);
            } catch (Exception failed) {
                return raw; // then the track as it was rendered
            }
        }
        return raw;
    }

    /** The track that plays alone, or {@code null} when a mix is chosen. */
    private String soloTrack() {
        String selected = (String) playbackSelection.getSelectedItem();
        return selected == null || "Mixed sampled arrangement".equals(selected)
                || "Java reference mix".equals(selected) ? null : selected;
    }

    /** Shows what is chosen under playback on the display and as a waveform, without playing it. */
    private void showPlaybackChoice() {
        String selected = (String) playbackSelection.getSelectedItem();
        Path file = playbackFile(selected);
        boolean there = file != null && Files.isRegularFile(file);
        player.close();
        if (there) {
            player.show(file);
        }
        List<String> ids = new ArrayList<String>();
        if (trackTable != null) {
            for (MusicTrack track : trackTable.tracks()) {
                ids.add(track.id());
            }
        }
        Path folder = Paths.get(directory.getText().trim());
        arrangementView.setArrangement(ArrangementView.Arrangement.read(folder.resolve("arrangement.tsv")), ids,
                there ? "" : "Noch nichts gerendert · Komponieren oder Demo laden");
        arrangementView.setMix(player.envelope(), soloTrack() == null ? "Mix" : "Solo", soloTrack(), player.length());
        trackTable.setAlone(soloTrack());
        com.aresstack.audio.studio.score.Score written =
                com.aresstack.audio.studio.score.Score.read(folder.resolve("arrangement.tsv"));
        if (stagePanel != null) {
            stagePanel.setBars(written == null ? 8 : written.bars());
        }
        scoreView.setScore(com.aresstack.audio.studio.score.Score.read(folder.resolve("arrangement.tsv")), ids,
                "Noch keine Noten: erst komponieren, dann steht hier die Partitur");
        lcd.setSource(selected == null ? "—" : playbackName(selected), there);
        lcd.setTime(0, player.length());
    }

    /** What a choice of the playback is called in the list. */
    private static String playbackLabel(String selected) {
        return "Mixed sampled arrangement".equals(selected) ? "Mix · Bühne"
                : "Java reference mix".equals(selected) ? "Referenz · Java-Synthese, ohne Bühne"
                : "Solo · " + selected;
    }

    private static String playbackName(String selected) {
        return "Mixed sampled arrangement".equals(selected) ? "MIX · BÜHNE"
                : "Java reference mix".equals(selected) ? "REFERENZ · OHNE BÜHNE"
                : "SOLO · " + selected.toUpperCase(java.util.Locale.ROOT);
    }

    /** Measures the rendered file of every track, so that the mixer's meters can follow the playback. */
    private void refreshStems() {
        if (trackTable == null) {
            return;
        }
        final List<String> ids = new ArrayList<String>();
        for (MusicTrack track : trackTable.tracks()) {
            ids.add(track.id());
        }
        final List<Path> files = new ArrayList<Path>();
        for (String id : ids) {
            files.add(com.aresstack.audio.studio.application.StageMixService.stem(
                    Paths.get(directory.getText().trim()), id));
        }
        new SwingWorker<Map<String, DeckPlayer.Envelope>, Void>() {
            @Override
            protected Map<String, DeckPlayer.Envelope> doInBackground() {
                Map<String, DeckPlayer.Envelope> measured = new HashMap<String, DeckPlayer.Envelope>();
                for (int i = 0; i < ids.size(); i++) {
                    DeckPlayer.Envelope envelope = DeckPlayer.measure(files.get(i));
                    if (envelope != null) {
                        measured.put(ids.get(i), envelope);
                    }
                }
                return measured;
            }

            @Override
            protected void done() {
                try {
                    stemEnvelopes = get();
                } catch (Exception ignored) {
                    // The meters stay dark; playing is not affected.
                }
            }
        }.execute();
    }

    /** How each track is getting on in the stage that is running, by track id. */
    private final Map<String, TrackProgress.State> trackStates =
            new java.util.LinkedHashMap<String, TrackProgress.State>();
    private TrackProgress.Stage trackStage;
    private long trackStageBegan;

    /** A track reports from the workers: its strip shows it, and the display counts all of them. */
    private void trackWork(String trackId, TrackProgress.Stage stage, TrackProgress.State state,
                           String detail) {
        if (worker == null || worker.isDone()) {
            return; // a late word after the work was cancelled
        }
        if (stage != trackStage) {
            trackStage = stage;
            trackStates.clear();
            trackTable.clearWork();
            trackStageBegan = System.currentTimeMillis();
        }
        trackStates.put(trackId, state);
        TrackLanes.Work work = state == TrackProgress.State.WAITING ? TrackLanes.Work.WAITING
                : state == TrackProgress.State.WORKING ? TrackLanes.Work.WORKING
                : state == TrackProgress.State.DONE ? TrackLanes.Work.DONE : TrackLanes.Work.FAILED;
        trackTable.setWork(trackId, work, stage == TrackProgress.Stage.COMPOSE ? "KI" : "Rendern", detail);
        showTrackWork();
    }

    private boolean tracksAtWork() {
        for (TrackProgress.State state : trackStates.values()) {
            if (state == TrackProgress.State.WAITING || state == TrackProgress.State.WORKING) {
                return true;
            }
        }
        return false;
    }

    /** One line for many tracks: how many are being worked on at the same time, and how many are done. */
    private void showTrackWork() {
        int working = 0;
        int waiting = 0;
        int done = 0;
        int failed = 0;
        for (TrackProgress.State state : trackStates.values()) {
            switch (state) {
                case WORKING: working++; break;
                case WAITING: waiting++; break;
                case DONE: done++; break;
                default: failed++; break;
            }
        }
        if (working + waiting == 0) {
            return; // the next line of the log takes the display again
        }
        long seconds = (System.currentTimeMillis() - trackStageBegan) / 1000;
        setStatus((trackStage == TrackProgress.Stage.COMPOSE ? "KI komponiert" : "Rendern") + ": "
                + working + " gleichzeitig · " + done + " von " + trackStates.size() + " fertig"
                + (waiting > 0 ? " · " + waiting + " warten" : "") + (failed > 0 ? " · " + failed + " Fehler" : "")
                + " · " + seconds + " s");
    }

    private void listenAlone(String trackId) {
        playbackSelection.setSelectedItem(trackId);
        play();
    }

    private void togglePlayback() {
        if (player.playing()) {
            player.pause();
        } else if (player.ready()) {
            player.play();
        } else {
            play();
        }
    }

    private void play() {
        String selected = (String) playbackSelection.getSelectedItem();
        Path output = playbackFile(selected);
        if (output == null || !Files.isRegularFile(output)) {
            message("Dazu gibt es noch keine Aufnahme: " + (output == null ? "" : output.getFileName())
                    + "\nErst „Demo laden“ oder komponieren, dann „Rendern“.");
            return;
        }
        try {
            player.open(output);
            arrangementView.setMix(player.envelope(), soloTrack() == null ? "Mix" : "Solo", soloTrack(), player.length());
            lcd.setSource(playbackName(selected), true);
            player.play();
        } catch (Exception error) {
            message("Cannot play " + output + ": " + error.getMessage());
        }
    }

    private void stopPlayback() {
        player.pause();
        player.seek(0);
    }

    private void seek(double share) {
        if (!player.ready() && player.file() != null) {
            try {
                Path file = player.file();
                player.open(file);
            } catch (Exception ignored) {
                return; // no sound card: the waveform stays a picture
            }
        }
        player.seek(share);
    }

    /** Thirty times a second: the display, the place in the waveform and the meters follow the playback. */
    private void tick() {
        boolean playing = player.playing();
        double second = player.seconds();
        double length = player.length();
        lcd.setPlaying(playing);
        lcd.setTime(second, length);
        lcd.tick();
        arrangementView.setSecond(second);
        scoreView.setSecond(playing || second > 0 ? second : -1);
        DeckPlayer.Envelope envelope = player.envelope();
        masterLeft.setLevel(playing && envelope != null ? envelope.at(second, false) : 0f);
        masterRight.setLevel(playing && envelope != null ? envelope.at(second, true) : 0f);
        if (trackTable != null) {
            trackTable.pulse();
        }
        if (stagePanel != null && stagePanel.isShowing()) {
            // On the stage every disc glows as loud as its track is right now.
            Map<String, Float> sounding = new HashMap<String, Float>();
            if (playing) {
                String only = soloTrack();
                for (Map.Entry<String, DeckPlayer.Envelope> entry : stemEnvelopes.entrySet()) {
                    if (only == null || only.equals(entry.getKey())) {
                        sounding.put(entry.getKey(), Math.max(entry.getValue().at(second, false),
                                entry.getValue().at(second, true)));
                    }
                }
            }
            stagePanel.setLevels(sounding);
        }
        if (tracksAtWork() && System.currentTimeMillis() / 1000 != shownSecond) {
            shownSecond = System.currentTimeMillis() / 1000;
            showTrackWork(); // the seconds on the display run on
        }
    }

    private long shownSecond;

    private void showPage(String page) {
        pages.show(pageHolder, page);
        DeckWidgets.PageKey key = pageKeys.get(page);
        if (key != null) {
            key.setSelected(true);
        }
    }

    private void setStatus(String text) {
        String line = text == null ? "" : text.replace('\n', ' ').trim();
        statusLine.setText(line.isEmpty() ? " " : line);
        statusLine.setForeground(line.startsWith("ERROR") ? AudioStudioTheme.RED
                : line.startsWith("WARN") ? AudioStudioTheme.AMBER : AudioStudioTheme.TEXT_DIM);
    }

    private void append(String text) {
        log.append(text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
        if (!tracksAtWork()) {
            setStatus(text); // while several tracks are worked on, the display counts them instead
        }
    }

    private void message(String text) {
        JOptionPane.showMessageDialog(this, text, "Audio Studio", JOptionPane.WARNING_MESSAGE);
    }

    private int parseParallel(String configured) {
        try {
            return Math.max(1, Math.min(1024, Integer.parseInt(configured)));
        } catch (NumberFormatException invalid) {
            return 32;
        }
    }

    private void loadArrangementForCurrentDirectory(Properties saved) {
        loadingTracks = true;
        try {
            Path folder = Paths.get(directory.getText().trim());
            TrackArrangement arrangement = trackStore.load(folder);
            if (!Files.isRegularFile(folder.resolve(TrackArrangementStore.FILE_NAME))) {
                List<MusicTrack> restored = new ArrayList<MusicTrack>();
                for (MusicTrack track : arrangement.tracks()) {
                    String bank = saved.getProperty("instrument." + track.id(), track.libraryId());
                    int program = track.program();
                    try {
                        program = Integer.parseInt(saved.getProperty("program." + track.id(),
                                Integer.toString(program)));
                    } catch (NumberFormatException ignored) {
                        // Preserve the default General MIDI program when legacy settings are malformed.
                    }
                    restored.add(new MusicTrack(track.id(), track.role(),
                            track.instrument(), program, bank));
                }
                arrangement = new TrackArrangement(restored);
            }
            // The stage of the piece; a piece from before the stage gets the usual seating.
            com.aresstack.audio.studio.stage.SpatialScene kept = null;
            try {
                kept = sceneStore.load(folder);
            } catch (IOException unreadable) {
                append("WARN: Gespeicherte Bühne nicht lesbar, es gilt die Standardaufstellung: "
                        + unreadable.getMessage());
            }
            scene = kept == null ? com.aresstack.audio.studio.stage.SpatialScene.standard(arrangement.tracks())
                    : kept.fitting(arrangement.tracks());
            if (stagePanel != null) {
                stagePanel.setProposal(null);
            }
            trackTable.setArrangement(arrangement);
            refreshPlaybackChoices();
        } catch (Exception invalid) {
            append("WARN: Gespeicherte Spuren konnten nicht geladen werden: "
                    + invalid.getMessage());
            trackTable.setArrangement(TrackArrangement.defaults());
        } finally {
            loadingTracks = false;
        }
        // The piece as a model: an old piece is taken into it here, its old files stay as they are.
        String chosen = directory.getText().trim();
        pieceModel.open(chosen.isEmpty() ? null : Paths.get(chosen));
    }

    private void tracksChanged() {
        // Every track has its place on the stage: a new one gets the usual seat of its role.
        scene = scene.fitting(trackTable.tracks());
        trackTable.refreshStage();
        showStage();
        refreshPlaybackChoices();
        if (loadingTracks) {
            return;
        }
        saveScene();
        try {
            trackStore.save(Paths.get(directory.getText().trim()), trackTable.arrangement());
        } catch (Exception error) {
            append("WARN: Spurauswahl nicht gespeichert: " + error.getMessage());
        }
    }

    private void refreshPlaybackChoices() {
        String previous = (String) playbackSelection.getSelectedItem();
        choosingPlayback = true;
        playbackSelection.removeAllItems();
        playbackSelection.addItem("Mixed sampled arrangement");
        playbackSelection.addItem("Java reference mix");
        if (trackTable != null) {
            try {
                for (MusicTrack track : trackTable.currentArrangement().tracks()) {
                    playbackSelection.addItem(track.id());
                }
            } catch (IllegalArgumentException ignored) {
                // Permit an empty table while the user edits it.
            }
        }
        if (previous != null) {
            playbackSelection.setSelectedItem(previous);
        }
        choosingPlayback = false;
        showPlaybackChoice();
        refreshStems();
    }

    private interface Operation {
        Path execute(Progress progress) throws Exception;
    }

    private static final class RenderSettings {
        private final Map<String, InstrumentLibrary> assignments;
        private final Map<String, Integer> programs;
        private final Sf2Engine sf2Engine;
        private final String fluidSynthExecutable;
        private final String sfizzExecutable;

        private RenderSettings(Map<String, InstrumentLibrary> assignments,
                               Map<String, Integer> programs, Sf2Engine sf2Engine,
                               String fluidSynthExecutable, String sfizzExecutable) {
            this.assignments = assignments;
            this.programs = programs;
            this.sf2Engine = sf2Engine;
            this.fluidSynthExecutable = fluidSynthExecutable;
            this.sfizzExecutable = sfizzExecutable;
        }
    }

}
