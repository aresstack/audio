package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.instruments.network.AudioProxyRoutes;
import com.aresstack.audio.studio.keepass.AudioKeePassService;
import com.aresstack.audio.studio.keepass.KeePassEntry;
import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.aresstack.audio.studio.application.GemmaMusicComposer;
import com.aresstack.audio.studio.application.StudioMusicRenderer;
import com.aresstack.audio.studio.application.MusicTrack;
import com.aresstack.audio.studio.application.TrackArrangement;
import com.aresstack.audio.studio.application.TrackArrangementStore;
import com.aresstack.audio.studio.application.InstrumentProposalService;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine;
import com.aresstack.audio.studio.settings.StudioSettingsStore;
import com.aresstack.audio.studio.settings.ExistingAiSettings;

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
    private MixerPanel trackTable;
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
            "Aus 3d4j / Windows übernehmen", "PAC_URL_POWERSHELL",
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
    // The desk: display, transport, waveform, master meters, and the pages behind the side rail.
    private final DeckPlayer player = new DeckPlayer();
    private final DeckDisplays.Lcd lcd = new DeckDisplays.Lcd();
    private final DeckDisplays.Waveform waveform = new DeckDisplays.Waveform(this::seek);
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
    private final JLabel trackCount = DeckWidgets.lettering("");
    private final javax.swing.Timer clock = new javax.swing.Timer(33, event -> tick());
    private boolean choosingPlayback;

    public AudioStudioFrame(InstrumentRegistry registry) {
        super("AresStack Audio Studio");
        this.registry = registry;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 700));
        setSize(1360, 900);
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
        JPanel desk = new JPanel(new BorderLayout(14, 14));
        desk.setBackground(AudioStudioTheme.DESK);
        desk.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        desk.add(transportBar(), BorderLayout.NORTH);
        desk.add(sideRail(), BorderLayout.WEST);
        pageHolder.setOpaque(false);
        pageHolder.add(deckPage(), "deck");
        pageHolder.add(libraryPage(), "library");
        pageHolder.add(settingsPanel(), "settings");
        pageHolder.add(consolePage(), "console");
        desk.add(pageHolder, BorderLayout.CENTER);
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
        JLabel line = new JLabel("Komponieren · Mischen · Rendern");
        line.setForeground(AudioStudioTheme.TEXT_FAINT);
        line.setFont(line.getFont().deriveFont(11.5f));
        name.add(javax.swing.Box.createVerticalGlue());
        name.add(brand);
        name.add(title);
        name.add(line);
        name.add(javax.swing.Box.createVerticalGlue());
        name.setPreferredSize(new Dimension(236, 78));

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
        inner.add(lcd, BorderLayout.CENTER);
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

    /** The rail on the left: one key per page of the desk. */
    private JPanel sideRail() {
        JPanel keys = new JPanel();
        keys.setOpaque(false);
        keys.setLayout(new javax.swing.BoxLayout(keys, javax.swing.BoxLayout.Y_AXIS));
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        String[][] entries = {{"deck", "Mischpult"}, {"library", "Soundbänke"}, {"settings", "Einstellungen"},
                {"console", "Protokoll"}};
        for (final String[] entry : entries) {
            DeckWidgets.RailKey key = new DeckWidgets.RailKey(entry[1]);
            key.addActionListener(event -> pages.show(pageHolder, entry[0]));
            key.setAlignmentX(0f);
            group.add(key);
            keys.add(key);
            keys.add(javax.swing.Box.createVerticalStrut(4));
            if ("deck".equals(entry[0])) {
                key.setSelected(true);
            }
        }
        keys.add(javax.swing.Box.createVerticalGlue());
        JLabel hint = new JLabel("<html><div style='width:118px'>Leertaste: Play / Pause<br>Klick in die Welle: springen</div></html>");
        hint.setForeground(AudioStudioTheme.TEXT_FAINT);
        hint.setFont(hint.getFont().deriveFont(11f));
        hint.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 0));
        hint.setAlignmentX(0f);
        keys.add(hint);
        DeckWidgets.Module rail = new DeckWidgets.Module("Studio", keys);
        rail.setPreferredSize(new Dimension(198, 100));
        return rail;
    }

    /** The mixing desk proper: the output, the mixer, the session and the steps of the work. */
    private JPanel deckPage() {
        JPanel sourceChoice = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        sourceChoice.setOpaque(false);
        sourceChoice.add(DeckWidgets.lettering("Wiedergabe"));
        playbackSelection.setPreferredSize(new Dimension(230, 28));
        sourceChoice.add(playbackSelection);
        DeckWidgets.Module output = new DeckWidgets.Module("Ausgang", waveform);
        output.setHeadEnd(sourceChoice);

        trackTable = new MixerPanel(registry, this::tracksChanged, this::listenAlone);
        DeckWidgets.Module mixer = new DeckWidgets.Module("Mixer", trackTable);
        mixer.setHeadEnd(trackCount);

        JPanel session = new JPanel(new GridBagLayout());
        session.setOpaque(false);
        JScrollPane prompt = new JScrollPane(description);
        prompt.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        prompt.setPreferredSize(new Dimension(300, 64));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 8, 0);
        session.add(prompt, c);
        JButton chooseOutput = new JButton("Ordner …");
        chooseOutput.addActionListener(event -> selectDirectory());
        JButton selectTsv = new JButton("Datei …");
        selectTsv.addActionListener(event -> selectArrangement());
        addFileRow(session, 1, "Ordner", directory, chooseOutput);
        addFileRow(session, 2, "Arrangement", arrangementFile, selectTsv);
        DeckWidgets.Module sessionModule = new DeckWidgets.Module("Session · Musikalische Beschreibung", session);

        JButton newSong = new JButton("Neues Stück");
        newSong.addActionListener(event -> createNewComposition());
        JButton demo = new JButton("Demo laden");
        demo.setToolTipText("Das mitgelieferte Stück laden, ohne die KI zu fragen");
        demo.addActionListener(event -> loadDemo());
        proposalButton.setText("KI-Vorschlag");
        proposalButton.setToolTipText("Die KI schlägt Instrumente vor; es werden noch keine Noten erzeugt.");
        proposalButton.addActionListener(event -> proposeInstruments(false));
        generateButton.setText("Komponieren");
        generateButton.setToolTipText("Aus den Spuren des Mixers mit der KI komponieren");
        autopilotButton.setText("Autopilot");
        autopilotButton.setToolTipText("Die KI schlägt Instrumente vor, komponiert alle Spuren und rendert sie.");
        autopilotButton.addActionListener(event -> proposeInstruments(true));
        renderButton.setText("Rendern");
        cancelButton.setText("Abbrechen");
        JButton inspect = new JButton("Plan ansehen");
        inspect.setToolTipText("Den Kompositionsplan und die Antworten der KI ansehen");
        inspect.addActionListener(event -> inspectComposition());
        DeckWidgets.asStep(newSong, 1);
        DeckWidgets.asStep(proposalButton, 2);
        DeckWidgets.asStep(generateButton, 3);
        DeckWidgets.asStep(renderButton, 4);
        getRootPane().setDefaultButton(generateButton);
        JPanel steps = new JPanel(new java.awt.GridLayout(4, 2, 8, 8));
        steps.setOpaque(false);
        steps.add(newSong);
        steps.add(demo);
        steps.add(proposalButton);
        steps.add(autopilotButton);
        steps.add(generateButton);
        steps.add(inspect);
        steps.add(renderButton);
        steps.add(cancelButton);
        DeckWidgets.Module stepsModule = new DeckWidgets.Module("Ablauf", steps);
        stepsModule.setPreferredSize(new Dimension(344, 100));

        JPanel lower = new JPanel(new BorderLayout(14, 0));
        lower.setOpaque(false);
        lower.add(sessionModule, BorderLayout.CENTER);
        lower.add(stepsModule, BorderLayout.EAST);

        // In a low window the page keeps its height and scrolls, so that a channel strip is never cut in half.
        TallPage page = new TallPage(730);
        page.add(output, BorderLayout.NORTH);
        page.add(mixer, BorderLayout.CENTER);
        page.add(lower, BorderLayout.SOUTH);
        JScrollPane scroll = new JScrollPane(page, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(28);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(scroll, BorderLayout.CENTER);
        return holder;
    }

    /** A page that fills the height it gets, but is never lower than it needs to be: then it scrolls. */
    private static final class TallPage extends JPanel implements javax.swing.Scrollable {
        private final int least;

        TallPage(int least) {
            super(new BorderLayout(0, 14));
            this.least = least;
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, Math.max(size.height, least));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 28;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return visible.height - 28;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() >= least;
        }
    }

    private static void addFileRow(JPanel panel, int row, String name, JTextField field, JButton choose) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, row == 1 ? 6 : 0, 10);
        panel.add(DeckWidgets.lettering(name), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, row == 1 ? 6 : 0, 8);
        panel.add(field, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, row == 1 ? 6 : 0, 0);
        panel.add(choose, c);
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
            public Path execute(GemmaMusicComposer.Progress progress) throws Exception {
                try {
                    Path arrangement = new GemmaMusicComposer(client, tokens, parallel)
                            .compose(prompt, output, chosen, progress);
                    return renderArrangement(arrangement, output, settings, progress);
                } finally {
                    activeClient = null;
                }
            }
        });
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
        activeClient = client;
        start(new Operation() {
            @Override
            public Path execute(GemmaMusicComposer.Progress progress) throws Exception {
                try {
                    TrackArrangement proposed = new InstrumentProposalService(client, tokens)
                            .propose(prompt, output, progress);
                    final TrackArrangement selected = withPreferredSoundbanks(proposed);
                    final AtomicReference<RenderSettings> rendering = new AtomicReference<RenderSettings>();
                    SwingUtilities.invokeAndWait(new Runnable() {
                        @Override
                        public void run() {
                            trackTable.setArrangement(selected);
                            if (autopilot) {
                                rendering.set(captureRenderSettings(selected));
                            }
                        }
                    });
                    if (!autopilot) {
                        progress.update("Instrumentenvorschlag bereit. "
                                + "Du kannst die Tabelle ändern und danach auf 'Aus Tabelle komponieren' klicken.");
                        return null;
                    }
                    new TrackArrangementStore().save(output, selected);
                    Path arrangement = new GemmaMusicComposer(client, tokens, parallel)
                            .compose(prompt, output, selected, progress);
                    return renderArrangement(arrangement, output, rendering.get(), progress);
                } finally {
                    activeClient = null;
                }
            }
        });
    }

    private TrackArrangement withPreferredSoundbanks(TrackArrangement original) {
        String preferred = "builtin";
        for (InstrumentLibrary library : registry.installedInstruments()) {
            if (library.id().startsWith("generaluser::") || library.id().equals("generaluser")) {
                preferred = library.id();
                break;
            }
        }
        List<MusicTrack> selected = new ArrayList<MusicTrack>();
        for (MusicTrack track : original.tracks()) {
            selected.add(track.withLibrary(preferred));
        }
        return new TrackArrangement(selected);
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
            public Path execute(GemmaMusicComposer.Progress progress) throws Exception {
                return renderArrangement(arrangement, output, settings, progress);
            }
        });
    }

    /** Combine user overrides with the proxy settings already used by 3d4j. */
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
                                   GemmaMusicComposer.Progress progress) throws Exception {
        return new StudioMusicRenderer(registry).render(source, destination, settings.assignments,
                settings.programs, settings.sf2Engine,
                settings.fluidSynthExecutable, settings.sfizzExecutable, progress);
    }

    private void createNewComposition() {
        Path newDirectory = Paths.get(System.getProperty("user.home"), ".aresstack",
                "audio", "compositions", "song-" + System.currentTimeMillis());
        directory.setText(newDirectory.toString());
        arrangementFile.setText("");
        tracksChanged();
        saveSettings(false);
        append("Neues, unabhängiges Stück angelegt: " + newDirectory);
        append("Klicke 'Mit Gemma komponieren' oder 'Demo laden'.");
    }

    private void loadSettings() {
        try {
            Properties saved = ExistingAiSettings.withDefaults(settingsStore.load());
            preferredKeePassEntry = saved.getProperty("keepassEntry", "");
            endpoint.setText(saved.getProperty("endpoint", ""));
            apiKey.setText(saved.getProperty("apiKey", ""));
            model.setText(saved.getProperty("model", model.getText()));
            tokenLimit.setValue(ExistingAiSettings.maxTokens(saved));
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
            loadArrangementForCurrentDirectory(saved);
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
        generateButton.setEnabled(false);
        proposalButton.setEnabled(false);
        autopilotButton.setEnabled(false);
        renderButton.setEnabled(false);
        trackTable.setEditingEnabled(false);
        cancelButton.setEnabled(true);
        cancelKey.setEnabled(true);
        renderKey.setEnabled(false);
        lcd.setBusy(true);
        worker = new SwingWorker<Path, String>() {
            @Override
            protected Path doInBackground() throws Exception {
                return action.execute(new GemmaMusicComposer.Progress() {
                    @Override
                    public void update(String update) {
                        publish(update);
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
                proposalButton.setEnabled(true);
                autopilotButton.setEnabled(true);
                renderButton.setEnabled(true);
                trackTable.setEditingEnabled(true);
                cancelButton.setEnabled(false);
                cancelKey.setEnabled(false);
                renderKey.setEnabled(true);
                lcd.setBusy(false);
                try {
                    Path result = get();
                    if (result != null) {
                        arrangementFile.setText(result.getParent().resolve("arrangement.tsv").toString());
                        append("Finished: " + result);
                    } else {
                        append("Instrumentenvorschlag bereit.");
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
            directory.setText(chooser.getSelectedFile().toString());
            loadArrangementForCurrentDirectory(new Properties());
            Path song = chooser.getSelectedFile().toPath().resolve("arrangement.tsv");
            arrangementFile.setText(Files.isRegularFile(song) ? song.toString() : "");
        }
    }

    private void selectArrangement() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            arrangementFile.setText(chooser.getSelectedFile().toString());
            Path parent = chooser.getSelectedFile().toPath().toAbsolutePath().getParent();
            directory.setText(parent.toString());
            loadArrangementForCurrentDirectory(new Properties());
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
        Path sampled = folder.resolve(selected + "-sampled.wav");
        return Files.isRegularFile(sampled) ? sampled : folder.resolve(selected + ".wav");
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
        String solo = soloTrack();
        java.awt.Color tint = AudioStudioTheme.ACCENT;
        if (solo != null && trackTable != null) {
            int index = 0;
            for (MusicTrack track : trackTable.tracks()) {
                if (track.id().equals(solo)) {
                    tint = AudioStudioTheme.channel(index);
                }
                index++;
            }
        }
        waveform.setEnvelope(player.envelope(),
                there ? "" : "Noch nichts gerendert · Demo laden oder komponieren, dann Rendern", tint);
        lcd.setSource(selected == null ? "—" : playbackName(selected), there);
        lcd.setTime(0, player.length());
    }

    private static String playbackName(String selected) {
        return "Mixed sampled arrangement".equals(selected) ? "MIX · INSTRUMENTE"
                : "Java reference mix".equals(selected) ? "MIX · JAVA-SYNTHESE"
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
            files.add(playbackFile(id));
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
                    trackTable.setEnvelopes(get());
                } catch (Exception ignored) {
                    // The meters stay dark; playing is not affected.
                }
            }
        }.execute();
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
            waveform.setEnvelope(player.envelope(), "", AudioStudioTheme.ACCENT);
            showTint();
            lcd.setSource(playbackName(selected), true);
            player.play();
        } catch (Exception error) {
            message("Cannot play " + output + ": " + error.getMessage());
        }
    }

    /** Colours the waveform like the channel that plays alone. */
    private void showTint() {
        String solo = soloTrack();
        if (solo == null) {
            return;
        }
        int index = 0;
        for (MusicTrack track : trackTable.tracks()) {
            if (track.id().equals(solo)) {
                waveform.setEnvelope(player.envelope(), "", AudioStudioTheme.channel(index));
            }
            index++;
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
        waveform.setShare(length <= 0 ? 0 : second / length);
        DeckPlayer.Envelope envelope = player.envelope();
        masterLeft.setLevel(playing && envelope != null ? envelope.at(second, false) : 0f);
        masterRight.setLevel(playing && envelope != null ? envelope.at(second, true) : 0f);
        if (trackTable != null) {
            trackTable.follow(playing ? second : -1, soloTrack());
        }
    }

    private void append(String text) {
        log.append(text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
        lcd.setStatus(text);
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
            trackTable.setArrangement(arrangement);
            refreshPlaybackChoices();
        } catch (Exception invalid) {
            append("WARN: Gespeicherte Spuren konnten nicht geladen werden: "
                    + invalid.getMessage());
            trackTable.setArrangement(TrackArrangement.defaults());
        } finally {
            loadingTracks = false;
        }
    }

    private void tracksChanged() {
        refreshPlaybackChoices();
        if (loadingTracks) {
            return;
        }
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
        if (trackTable != null) {
            try {
                int count = trackTable.tracks().size();
                trackCount.setText(count == 0 ? "KEINE SPUR" : count + (count == 1 ? " SPUR" : " SPUREN") + " VON 16");
            } catch (IllegalArgumentException empty) {
                trackCount.setText("KEINE SPUR");
            }
        }
        showPlaybackChoice();
        refreshStems();
    }

    private interface Operation {
        Path execute(GemmaMusicComposer.Progress progress) throws Exception;
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
