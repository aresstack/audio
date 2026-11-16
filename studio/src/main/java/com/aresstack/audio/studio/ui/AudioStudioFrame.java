package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.instruments.network.AudioProxyRoutes;
import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.aresstack.audio.studio.album.Album;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.album.AlbumFiles;
import com.aresstack.audio.studio.album.AlbumMembership;
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
    /** How the stage is heard (work package D4): the choice kept in the settings, and what was read for it. */
    private com.aresstack.audio.studio.application.StageListening listening =
            com.aresstack.audio.studio.application.StageListening.USUAL;
    private volatile com.aresstack.audio.studio.mix.StageSound stageSound =
            com.aresstack.audio.studio.mix.StageSound.LOUDSPEAKERS;
    private final com.aresstack.audio.studio.hrtf.HrtfLibrary hrtfLibrary =
            new com.aresstack.audio.studio.hrtf.HrtfLibrary(com.aresstack.audio.studio.hrtf.HrtfLibrary.standardRoot());
    private ListeningPanel listeningPanel;
    private int listeningAsked;
    private boolean hrtfLoading;
    private final JTextField endpoint = new JTextField(38);
    private final JPasswordField apiKey = new JPasswordField(38);
    private final JTextField model = new JTextField("google/gemma-4-31B-it", 23);
    private final JSpinner tokenLimit = new JSpinner(
            new SpinnerNumberModel(AiDefaults.MAX_TOKENS, 256, Integer.MAX_VALUE, 256));
    private final JSpinner sectionBars = new JSpinner(new SpinnerNumberModel(8, 2, 64, 1));
    /** How many bars a long piece begins with and grows by per step of the continuation. */
    private final JSpinner templateLines = new JSpinner(new SpinnerNumberModel(
            com.aresstack.audio.studio.template.TextTemplate.DEFAULT_LINES,
            com.aresstack.audio.studio.template.TextTemplate.MIN_LINES,
            com.aresstack.audio.studio.template.TextTemplate.MAX_LINES, 1));
    /** Which notes the open piece follows, if it follows any; beside it the keys to give or drop them. */
    private final JLabel templateShown = new JLabel(" ");
    private final JButton chooseTemplate = new DeckWidgets.HeadKey("Noten vorgeben …");
    private final JButton dropTemplate = new DeckWidgets.HeadKey("Ohne Vorlage");
    /** "Original nachbilden": the wish names an existing work, which the AI rebuilds instead of inventing one. */
    private final javax.swing.JCheckBox originalWork = new javax.swing.JCheckBox("Original nachbilden");
    private final JSpinner growBars = new JSpinner(new SpinnerNumberModel(PieceAutopilot.GROW_BARS,
            PieceAutopilot.MIN_GROW_BARS, PieceAutopilot.MAX_GROW_BARS, 1));
    private final javax.swing.JCheckBox detailedLog = new javax.swing.JCheckBox("Erweitertes Protokoll");
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
    /** Whether the arrangement is fitted into the window; off, every bar has its width and the lanes scroll. */
    private final JCheckBox fitArrangement = new JCheckBox("Ganzes Stück in die Fensterbreite einpassen", true);
    /** The same switch in the head of the arrangement: on fits the piece, off gives each bar its width and a scroll bar. */
    private final JCheckBox fitArrangementHead = new JCheckBox("Einpassen", true);
    private final JComboBox<AppIcon> appIconChoice = new JComboBox<>(AppIcon.values());
    private boolean showingSavedIcon;
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
    /** Length, tempo, key and meter of a new piece. */
    private final PieceSpecFields specFields = new PieceSpecFields();
    private final JTextArea description = new JTextArea(
            "A charming pastel city slowly grows; compose a playful, cozy, uplifting orchestral theme.", 3, 54);
    private final JTextArea log = new JTextArea(10, 70);
    private final JComboBox<String> playbackSelection = new JComboBox<String>(new String[]{
            "Mixed sampled arrangement"
    });
    /** Sums up the lines about AI requests tried again, see {@link TroubleDigest}. */
    private final TroubleDigest troubleDigest = new TroubleDigest(this::append);
    private final JSpinner parallelRequests = new JSpinner(
            new SpinnerNumberModel(32, 1, 1024, 1));
    /** The first pause before an AI request is tried again, in seconds. */
    private final JSpinner retryFirst = new JSpinner(new SpinnerNumberModel(
            com.aresstack.audio.studio.ai.Backoff.FIRST_SECONDS, 1, 600, 1));
    /** The longest pause before an AI request is tried again, in seconds. */
    private final JSpinner retryMost = new JSpinner(new SpinnerNumberModel(
            com.aresstack.audio.studio.ai.Backoff.MOST_SECONDS, 1, 3600, 10));
    /** How long the first text of an AI answer may take, in seconds. */
    private final JSpinner firstText = new JSpinner(new SpinnerNumberModel(KipitzChatClient.FIRST_TEXT_SECONDS,
            10, 3600, 10));
    /** How long an AI answer may stay silent once its text flows, in seconds. */
    private final JSpinner silence = new JSpinner(new SpinnerNumberModel(KipitzChatClient.SILENCE_SECONDS,
            10, 3600, 10));
    private final JButton proposalButton = new JButton("KI-Instrumentvorschlag");
    private final JButton autopilotButton = new JButton("Vollautomatisch");
    private final JButton generateButton = new JButton("Mit Gemma komponieren");
    private final JButton renderButton = new JButton("Rendern");
    private final JButton cancelButton = new JButton("Abbrechen");
    private SwingWorker<Path, String> worker;
    /** The models of the production that runs; cancelling stops all of them. */
    private volatile com.aresstack.audio.studio.ai.ModelLineup activeModels;
    /** Which model answers for which role, as last chosen; taken over unchanged when a production starts. */
    private final ModelChoicePanel modelChoices = new ModelChoicePanel(() -> modelsChosen(false),
            () -> loadModelNames(false));
    /**
     * The same assignment on the album page, with the model that plans the album in front: an
     * album needs that one beside those that plan and compose its pieces.
     */
    private final ModelChoicePanel albumModelChoices = new ModelChoicePanel(ALBUM_MODEL_ROLES,
            () -> modelsChosen(true), () -> loadModelNames(false));

    /**
     * A model was chosen. On the album page it belongs to the album shown alone, kept in its folder;
     * on the settings page it is the standard, and the album page shows it where the album has none.
     */
    private void modelsChosen(boolean onAlbumPage) {
        if (onAlbumPage) {
            keepAlbumModels(albumShown, albumModelChoices.assignment());
        } else {
            saveSettings(false);
            albumModelChoices.setAssignment(albumAssignment(albumShown));
        }
    }

    /** The album the album page shows, whose models it shows. */
    private String albumShown;

    /** The file in the folder of an album with the models chosen for it. */
    private Path albumModelsFile(String albumId) {
        return albumMembers.albums().folder(albumId).resolve("models.properties");
    }

    /** The models of an album: the standard of the settings, with what was chosen for the album over it. */
    private com.aresstack.audio.studio.ai.ModelAssignment albumAssignment(String albumId) {
        com.aresstack.audio.studio.ai.ModelAssignment assignment = currentAssignment();
        if (albumId == null) {
            return assignment;
        }
        Path file = albumModelsFile(albumId);
        if (!Files.isRegularFile(file)) {
            return assignment;
        }
        Properties own = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            own.load(reader);
        } catch (IOException | RuntimeException unreadable) {
            append("Die Modelle des Albums ließen sich nicht lesen: " + unreadable.getMessage());
            return assignment;
        }
        for (String key : own.stringPropertyNames()) {
            boolean thinking = key.endsWith(".thinking");
            com.aresstack.audio.studio.ai.ModelRole role = com.aresstack.audio.studio.ai.ModelRoles.byKey(
                    thinking ? key.substring(0, key.length() - ".thinking".length()) : key);
            String chosen = own.getProperty(key, "").trim();
            if (role == null || chosen.isEmpty()) {
                continue;
            }
            // "Standard" kept for the album means the model decides, also if the settings say otherwise.
            assignment = thinking ? assignment.withEffort(role, chosen) : assignment.with(role, chosen);
        }
        return assignment;
    }

    /** Keeps the models shown on the album page with the album, for its plans and productions. */
    private void keepAlbumModels(String albumId, com.aresstack.audio.studio.ai.ModelAssignment shown) {
        if (albumId == null) {
            return;
        }
        // What differs from the standard of the settings is the album's own; the rest follows the standard.
        com.aresstack.audio.studio.ai.ModelAssignment standard = currentAssignment();
        Properties own = new Properties();
        for (com.aresstack.audio.studio.ai.ModelRole role : com.aresstack.audio.studio.ai.ModelRoles.all()) {
            String model = shown.modelFor(role);
            if (!model.equals(standard.modelFor(role))) {
                own.setProperty(role.key(), model);
            }
            String effort = shown.effortFor(role) == null ? "standard" : shown.effortFor(role);
            String usual = standard.effortFor(role) == null ? "standard" : standard.effortFor(role);
            if (!effort.equals(usual)) {
                own.setProperty(role.key() + ".thinking", effort);
            }
        }
        try (java.io.Writer writer = Files.newBufferedWriter(albumModelsFile(albumId),
                java.nio.charset.StandardCharsets.UTF_8)) {
            own.store(writer, "Models of this album");
        } catch (IOException failed) {
            append("Die Modelle des Albums ließen sich nicht speichern: " + failed.getMessage());
        }
    }

    /** The roles the album page shows models for. */
    private static final java.util.List<com.aresstack.audio.studio.ai.ModelRole> ALBUM_MODEL_ROLES =
            java.util.List.of(com.aresstack.audio.studio.album.AlbumRoles.ALBUM,
                    com.aresstack.audio.studio.ai.ModelRoles.PLANNING,
                    com.aresstack.audio.studio.ai.ModelRoles.COMPOSITION);
    private String preferredKeePassEntry = "";
    // The desk: display, transport, waveform, master meters, and the pages behind the keys under the display.
    private final StageDeck player = new StageDeck();
    /** The factor the last file mix of the stage found (compatibility mode), for which piece and master level. */
    private float mixFactor = Float.NaN;
    private double mixFactorMasterDb;
    private Path mixFactorFolder;
    private final DeckDisplays.Lcd lcd = new DeckDisplays.Lcd();
    /** The line at the lower edge of the window: what the studio is doing or did last. */
    private final JLabel statusLine = new JLabel("Bereit");
    /** The keys under the display that choose the page, by the name of the page. */
    private final DeckWidgets.CornerKey settingsKey = new DeckWidgets.CornerKey(DeckWidgets.CornerKey.Sign.GEAR,
            "Einstellungen", AudioStudioTheme.ACCENT_SOFT);
    private final DeckWidgets.CornerKey libraryKey = new DeckWidgets.CornerKey(DeckWidgets.CornerKey.Sign.BANK,
            "Soundbänke", AudioStudioTheme.AMBER);
    private final javax.swing.JRadioButton accessKeePass = new javax.swing.JRadioButton("KeePass", true);
    private final javax.swing.JRadioButton accessManual = new javax.swing.JRadioButton("Manuell");
    private final javax.swing.JRadioButton accessOllama = new javax.swing.JRadioButton("Ollama");
    private final JLabel accessNote = new JLabel();
    private JPanel keePassRow;
    private JLabel keePassLabel;
    /** How many characters of the answers of the AI have arrived while composing, and how many notes are shown. */
    private long arrived;
    private int sketchNotes;
    private final Map<String, javax.swing.JToggleButton> pageKeys = new java.util.LinkedHashMap<String, javax.swing.JToggleButton>();
    /** Beside the choice of the piece under the name of the studio: new, open, and what is seldom needed. */
    private final JButton songMenuKey = new JButton("▾");
    /** What is done with the piece beside the AI keys: undo, revisions, files, export; on its pages. */
    private final javax.swing.JPopupMenu pieceMenu = new javax.swing.JPopupMenu();
    private final ArrangementView arrangementView = new ArrangementView(new ArrangementView.Listener() {
        @Override
        public void seek(double second) {
            double length = player.length();
            AudioStudioFrame.this.seek(length <= 0 ? 0 : Math.min(1, second / length));
        }

        @Override
        public void edited(String said, boolean changed) {
            notesEdited(said, changed);
        }
    });
    /** The piece written out as a conductor's score, on the page of the composition. */
    private final ScoreView scoreView = new ScoreView();
    /** The form of the piece above the lanes: its sections with name, function, intensity and chords. */
    private final SectionBar sectionBar = new SectionBar(arrangementView);
    /** The projects of the studio; the open one is the library of pieces below. */
    private final com.aresstack.audio.studio.application.Projects projects =
            com.aresstack.audio.studio.application.Projects.standard();
    private final com.aresstack.audio.studio.application.SongLibrary songs =
            new com.aresstack.audio.studio.application.SongLibrary(projects.current());
    /** Every piece belongs to an album; a piece without one lands in the unnamed album. */
    private final AlbumMembership albumMembers = new AlbumMembership(new AlbumFiles(songs.root()), songs);
    /** The id of the album worked in: the one clicked in the tree, or the album of the open piece. */
    private String currentAlbum;
    private AlbumsPanel albumsPanel;
    /** The albums of the project and their titles, at the left of the desk. */
    private AlbumTree albumTree;
    /** One page key lit at a time; none while the page of an album is shown, which the tree opens. */
    private final javax.swing.ButtonGroup pageGroup = new javax.swing.ButtonGroup();
    /** Under the name of the studio: the project, that is the library with all its albums. */
    private final JLabel projectName = new JLabel();
    /** The project's name while it is renamed: in place of the name, Enter keeps it, Esc leaves it. */
    private final javax.swing.JTextField projectNameField = new javax.swing.JTextField();
    private final JPanel projectNameHolder = new JPanel(new java.awt.CardLayout());
    /** The model of the open piece; it follows what the studio still writes the old way. */
    private final com.aresstack.audio.studio.application.PieceModel pieceModel =
            new com.aresstack.audio.studio.application.PieceModel(this::modelSays);

    /**
     * What the model of the open piece says. While the album production makes pieces, it writes
     * their files itself, and the open one may be among them: that its files changed is then no
     * news worth a warning, since the production takes them into its model when the step ends.
     */
    private void modelSays(String line) {
        if (line.startsWith("WARN: Die Dateien des Stücks") && albumsPanel != null && albumsPanel.producing()) {
            return;
        }
        append(line);
    }

    /** How often a cut-off answer is asked for again, and by how much its room grows (studio.properties). */
    private int longerAnswers = KipitzChatClient.LONGER_ANSWERS;
    private int longerAnswerFactor = KipitzChatClient.LONGER_FACTOR;

    /** How many requests to the models are open right now, from whatever part of the studio. */
    private int aiRequests;

    /** The AI lamp: on while the window works or any model is being asked. */
    private void showAiLamp() {
        lcd.setBusy((worker != null && !worker.isDone()) || aiRequests > 0);
    }

    /** The models last named in the protocol; a production asks for them often, they are named once. */
    private String modelsSaid;
    /** The note editor's way into the open piece: every change a patch through the edit core (B1). */
    /** The piece as the kept answers of the AI last followed it; the note editor's undo is compared with it. */
    private com.aresstack.audio.composition.Composition answersState;
    /** Lets the stage of the window follow the mix of the model when a step of the user changed it. */
    private final com.aresstack.audio.studio.application.StageFollowsMix stageFollowsMix =
            new com.aresstack.audio.studio.application.StageFollowsMix();
    private final com.aresstack.audio.studio.application.PieceEditing pieceEditing =
            new com.aresstack.audio.studio.application.PieceEditing(this::pieceToEdit);
    /** What the plan says about the piece: tempo, key, bars, mood. */
    private final JLabel songFacts = new JLabel(" ");
    private final javax.swing.JRadioButton aiChooses = new javax.swing.JRadioButton("die KI wählt sie");
    private final javax.swing.JRadioButton ownTracks = new javax.swing.JRadioButton("meine Spuren aus dem Arrangement");
    /** How many instruments the AI may choose: the fewest and the most; the same twice is an exact number. */
    private final JSpinner voicesMin = new JSpinner(new SpinnerNumberModel(3, InstrumentProposalService.MIN_VOICES,
            InstrumentProposalService.MAX_VOICES, 1));
    private final JSpinner voicesMax = new JSpinner(new SpinnerNumberModel(8, InstrumentProposalService.MIN_VOICES,
            InstrumentProposalService.MAX_VOICES, 1));
    private final JButton composeButton = new DeckWidgets.HeadKey("Komponieren");
    /** The two views of the composition page: what is asked of the AI, and the score that came of it. */
    private final DeckWidgets.SegmentKey draftKey = new DeckWidgets.SegmentKey("Entwurf", true);
    private final DeckWidgets.SegmentKey scoreKey = new DeckWidgets.SegmentKey("Partitur", false);
    private final java.awt.CardLayout songViews = new java.awt.CardLayout();
    private final JPanel songView = new JPanel(songViews);
    /** Whether the piece shown last was composed already; a change of that chooses the view. */
    private Boolean composedShown;
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
    /** Where a proposal of the AI is shown, above the tracks of the arrangement; empty while there is none. */
    private final JPanel proposalHolder = new JPanel(new BorderLayout());
    /** The proposal that waits for the user's answer, or {@code null}. */
    private ProposalPanel openProposal;
    /** The piece a proposal was made for and its notes at that time: a proposal fits only the piece it was made for. */
    private com.aresstack.audio.studio.application.ProjectSession offerPiece;
    private com.aresstack.audio.composition.Composition offerBase;
    private final javax.swing.Timer clock = new javax.swing.Timer(33, event -> tick());
    private boolean choosingPlayback;

    public AudioStudioFrame(InstrumentRegistry registry) {
        super("AresStack Audio Studio");
        this.registry = registry;
        setIconImages(AppIcon.DEFAULT.images());
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
        pageHolder.add(playerPage(), "player");
        // At the left the albums of the project as a tree, beside it the page of what was clicked there.
        JPanel work = new JPanel(new BorderLayout(14, 0));
        work.setOpaque(false);
        DeckWidgets.Module albumsModule = new DeckWidgets.Module("Alben", albumTree());
        albumsModule.setHeadStart(albumTree.newAlbumKey());
        albumsModule.setHeadEnd(albumTree.newPieceKey());
        work.add(albumsModule, BorderLayout.WEST);
        work.add(pageHolder, BorderLayout.CENTER);
        desk.add(work, BorderLayout.CENTER);
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
        // Under the name: the project, and beside it the menu of the projects.
        // Albums and pieces are chosen in the tree at the left.
        projectName.setText(projectTitle());
        projectName.setFont(projectName.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        projectName.setForeground(AudioStudioTheme.TEXT);
        projectName.setToolTipText("Das Projekt: " + songs.root());
        songMenuKey.setToolTipText("Projekte: wechseln, neu anlegen, umbenennen, exportieren, importieren");
        projectName.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) {
                    startRenamingProject();
                }
            }
        });
        projectNameField.setFont(projectName.getFont());
        projectNameField.addActionListener(event -> finishRenamingProject(true));
        projectNameField.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "leave");
        projectNameField.getActionMap().put("leave", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                finishRenamingProject(false);
            }
        });
        projectNameField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                finishRenamingProject(true);
            }
        });
        projectNameHolder.setOpaque(false);
        projectNameHolder.add(projectName, "shown");
        projectNameHolder.add(projectNameField, "editing");
        songMenuKey.addActionListener(event -> projectMenu().show(songMenuKey, 0, songMenuKey.getHeight()));
        songMenuKey.setMargin(new Insets(2, 4, 2, 4));
        songMenuKey.setFocusable(false);
        songMenuKey.setPreferredSize(new Dimension(30, 28));
        JPanel line = new JPanel(new BorderLayout(4, 0));
        line.setOpaque(false);
        line.add(projectNameHolder, BorderLayout.CENTER);
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
        // Under the meters: what the play key plays, the mix on the stage or one track alone.
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.insets = new Insets(10, 0, 5, 0);
        master.add(DeckWidgets.lettering("Wiedergabe"), c);
        c.gridy = 4;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        // As wide as the meters with their letters: the layout stretches it, it asks for no width of its own.
        playbackSelection.setPreferredSize(new Dimension(60, 26));
        playbackSelection.setToolTipText("Wiedergabe: was die Play-Taste abspielt, der Mix auf der Bühne oder eine Spur allein");
        playbackSelection.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focused) {
                return super.getListCellRendererComponent(list, value == null ? "" : playbackLabel(String.valueOf(value)),
                        index, selected, focused);
            }
        });
        master.add(playbackSelection, c);

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
        // Under the display, as on a player: one key per page of the work on the piece. The
        // settings and the sound banks are not among them; they sit behind two round keys in the corner.
        JPanel keysOfPages = new JPanel(new java.awt.GridLayout(1, 5, 6, 0));
        keysOfPages.setOpaque(false);
        javax.swing.ButtonGroup onePage = pageGroup;
        // The albums are in the tree at the left: a click on an album shows its page, a click on a title its piece.
        String[][] entries = {{"song", "Komposition"}, {"deck", "Arrangement"},
                {"stage", "Bühne"},
                {"console", "Protokoll"}, {"player", "Player"}};
        for (final String[] entry : entries) {
            DeckWidgets.PageKey key = new DeckWidgets.PageKey(entry[1]);
            key.addActionListener(event -> showPage(entry[0]));
            onePage.add(key);
            pageKeys.put(entry[0], key);
            keysOfPages.add(key);
        }
        settingsKey.addActionListener(event -> showPage("settings"));
        onePage.add(settingsKey); // one page at a time: the gear lights instead of a page key
        pageKeys.put("settings", settingsKey);
        libraryKey.addActionListener(event -> showPage("library"));
        onePage.add(libraryKey);
        pageKeys.put("library", libraryKey);
        pageKeys.get("song").setSelected(true);

        display.add(keysOfPages, BorderLayout.SOUTH);
        inner.add(display, BorderLayout.CENTER);
        inner.add(right, BorderLayout.EAST);
        DeckWidgets.Module bar = new DeckWidgets.Module("Transport", inner);
        // In the upper corner, above the master meters and away from the pages of the piece: the
        // sound banks and the settings, each behind a round key. The meters stay where they were.
        JPanel corner = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        corner.setOpaque(false);
        corner.add(libraryKey); // needed more often than the settings, so it stands first
        corner.add(settingsKey);
        bar.setHeadEnd(corner);
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
        // The choice of the playback is in the transport, under the master meters; here, beside
        // the menu of the piece, is the key that takes the last change back.
        sourceChoice.add(undoKey);

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
        trackTable.setHeader(sectionBar);
        sectionBar.setListener((section, intensity) -> sectionIntensity(section, intensity));
        sectionBar.setEditing(new SectionBar.Editing() {
            @Override
            public void deleteBars(int firstBar, int endBar, boolean close) {
                editForm(piece -> com.aresstack.audio.studio.application.SectionEdits.deleteBars(piece, firstBar,
                        endBar, close, com.aresstack.audio.composition.Ids.random()));
            }

            @Override
            public void moveSection(SectionMark section, int toBar) {
                editForm(piece -> com.aresstack.audio.studio.application.SectionEdits.moveSection(piece, section.id(),
                        toBar, com.aresstack.audio.composition.Ids.random()));
            }

            @Override
            public void insertEmpty(int beforeBar, int bars) {
                editForm(piece -> com.aresstack.audio.studio.application.SectionEdits.insertEmpty(piece, beforeBar,
                        bars, com.aresstack.audio.composition.Ids.random()));
            }

            @Override
            public void generate(int firstBar, int endBar) {
                generateBars(firstBar, endBar);
            }

            @Override
            public boolean silent(int firstBar, int endBar) {
                java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
                return piece.isPresent() && com.aresstack.audio.studio.application.SectionEdits.isSilent(
                        piece.get().composition(), firstBar, endBar);
            }
        });
        arrangementView.setCompanion(sectionBar);
        arrangementView.setEditing(pieceEditing);
        // What the AI can do with the piece as it is stands in sight in the head of the arrangement,
        // right after its name: flat keys as on a player, not a row of their own above the tracks.
        JPanel pieceActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pieceActions.setOpaque(false);
        pieceActions.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        pieceActions.add(pieceAction("Verlängern",
                "Die KI plant und schreibt weitere Takte ans Ende; das bisherige Stück bleibt erhalten",
                this::extendPiece));
        pieceActions.add(pieceAction("Takte einfügen",
                "Die KI schreibt neue Takte vor einem Takt; alles ab dort rückt unverändert nach hinten",
                this::insertBars));
        pieceActions.add(pieceAction("Stimme hinzu",
                "Die KI schreibt eine neue Stimme zu den vorhandenen; die anderen bleiben unverändert",
                this::addVoice));
        pieceActions.add(pieceAction("Passage neu",
                "Die KI schreibt markierte Takte gewählter Spuren neu; mit Umschalt und Ziehen im Taktlineal markieren",
                this::reworkRegion));
        pieceActions.add(pieceAction("Umorchestrieren",
                "Die KI verteilt das Material markierter Takte neu auf die Stimmen", this::reorchestrate));
        undoKey.setToolTipText("Nimmt die letzte Änderung am Stück zurück (Strg+Z)");
        undoKey.setFocusable(false);
        undoKey.setEnabled(false);
        undoKey.addActionListener(event -> undoPiece(false));
        JPanel arrangementBody = new JPanel(new BorderLayout());
        arrangementBody.setOpaque(false);
        // A proposal of the AI waits here, under the keys that ask for one: part of the page, no dialog.
        proposalHolder.setOpaque(false);
        JPanel aboveTracks = new JPanel(new BorderLayout());
        aboveTracks.setOpaque(false);
        aboveTracks.add(proposalHolder, BorderLayout.CENTER);
        arrangementBody.add(aboveTracks, BorderLayout.NORTH);
        arrangementBody.add(trackTable, BorderLayout.CENTER);
        DeckWidgets.Module output = new DeckWidgets.Module("Arrangement", arrangementBody);
        output.setHeadStart(pieceActions);
        // On: the whole piece fits the window. Off: every bar has its width, a scroll bar below,
        // and Shift with the wheel moves along the piece. The same switch as in the settings.
        fitArrangementHead.setModel(fitArrangement.getModel());
        fitArrangementHead.setOpaque(false);
        fitArrangementHead.setFocusable(false);
        fitArrangementHead.setForeground(AudioStudioTheme.TEXT_DIM);
        fitArrangementHead.setToolTipText("An: das ganze Stück passt ins Fenster. Aus: Strg + Mausrad zoomt,"
                + " unten ein Rollbalken; Umschalt + Mausrad rollt seitwärts.");
        fitArrangementHead.addActionListener(event -> {
            trackTable.setFit(fitArrangement.isSelected());
            saveSettings(false);
        });
        JPanel arrangementHeadEnd = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        arrangementHeadEnd.setOpaque(false);
        arrangementHeadEnd.add(fitArrangementHead);
        arrangementHeadEnd.add(withPieceMenu(sourceChoice));
        output.setHeadEnd(arrangementHeadEnd);

        // The piece: which one, and what it shall be. What is seldom needed is in the menu "Mehr".
        final javax.swing.JPopupMenu moreMenu = pieceMenu;
        // Bearbeiten: the changes of the piece that can be taken back, and its revisions.
        final javax.swing.JMenuItem undoItem = new javax.swing.JMenuItem("Rückgängig");
        undoItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        undoItem.addActionListener(event -> undoPiece(false));
        final javax.swing.JMenuItem redoItem = new javax.swing.JMenuItem("Wiederholen");
        redoItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        redoItem.addActionListener(event -> undoPiece(true));
        final javax.swing.JMenuItem revisionsItem = new javax.swing.JMenuItem("Revisionen …");
        revisionsItem.setToolTipText("Frühere Stände des Stücks ansehen, einen zurückholen oder den jetzigen festhalten");
        revisionsItem.addActionListener(event -> showRevisions());
        moreMenu.add(undoItem);
        moreMenu.add(redoItem);
        moreMenu.add(revisionsItem);
        moreMenu.addSeparator();
        javax.swing.JMenuItem curveItem = new javax.swing.JMenuItem("Intensitätsverlauf setzen …");
        curveItem.setToolTipText("Legt einen Verlauf über die Abschnitte der markierten Takte (oder des ganzen Stücks);"
                + " keine Note ändert sich");
        curveItem.addActionListener(event -> intensityOverBars());
        moreMenu.add(curveItem);
        // The same on the marked passage itself, with the right mouse button.
        javax.swing.JPopupMenu passageMenu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem reworkMarked = new javax.swing.JMenuItem("Passage ersetzen oder variieren …");
        reworkMarked.addActionListener(event -> reworkRegion());
        javax.swing.JMenuItem unmark = new javax.swing.JMenuItem("Markierung aufheben");
        unmark.addActionListener(event -> arrangementView.mark(null));
        passageMenu.add(reworkMarked);
        passageMenu.add(unmark);
        arrangementView.setPassageMenu(passageMenu);
        arrangementView.setToolTipText("Klick: dorthin springen · Umschalt und Ziehen im Taktlineal: Passage markieren · rechte Maustaste: Menü");
        moreMenu.addSeparator();
        moreMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
                java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
                EditMenu.Labels labels = EditMenu.labels(piece.isPresent() && piece.get().canUndo(),
                        piece.isPresent() ? piece.get().undoDescription() : null,
                        piece.isPresent() && piece.get().canRedo(),
                        piece.isPresent() ? piece.get().redoDescription() : null);
                undoItem.setText(labels.undo());
                undoItem.setEnabled(labels.canUndo());
                redoItem.setText(labels.redo());
                redoItem.setEnabled(labels.canRedo());
                revisionsItem.setEnabled(piece.isPresent());
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
            }
        });
        // The same from the keyboard, but not while a text is being written: there the keys belong to the text.
        javax.swing.Action undoKey = new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
                        instanceof javax.swing.text.JTextComponent)) {
                    undoPiece(false);
                }
            }
        };
        javax.swing.Action redoKey = new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
                        instanceof javax.swing.text.JTextComponent)) {
                    undoPiece(true);
                }
            }
        };
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(undoItem.getAccelerator(), "piece-undo");
        getRootPane().getActionMap().put("piece-undo", undoKey);
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoItem.getAccelerator(), "piece-redo");
        getRootPane().getActionMap().put("piece-redo", redoKey);
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
        // Composing: one key, and the choice of who decides on the instruments.
        javax.swing.ButtonGroup who = new javax.swing.ButtonGroup();
        who.add(aiChooses);
        who.add(ownTracks);
        aiChooses.setSelected(true);
        aiChooses.setToolTipText("Die KI schlägt zur Beschreibung passende Instrumente vor und komponiert dann für sie");
        ownTracks.setToolTipText("Für genau die Spuren im Arrangement komponieren; was schon komponiert ist, bleibt");
        composeButton.setToolTipText("Komponieren und gleich rendern");
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
        // The draft is a form like the one of an album: short names at the left (what they mean is
        // their tooltip), the fields in one column, the square keys of the AI help at the right.
        JPanel wish = new JPanel(new BorderLayout(0, 0));
        wish.setOpaque(false);
        JPanel form = new JPanel(new java.awt.GridBagLayout());
        form.setOpaque(false);
        JButton describeHelp = assistant().key("KI-Hilfe für die Beschreibung des Stücks", () -> Assistant.textField(
                "Beschreibung", "piece-" + pieceKey(), description,
                "the description of a piece of music for the AI that composes it: what it is about, mood, style, "
                        + "players, form, how it develops. It may be long.",
                () -> "Stück: " + pieceKey() + "\n" + songFacts.getText() + "\nLänge, Tempo, Tonart, Takt: "
                        + specSaid(), null));
        AlbumsPanel.formRow(form, 0, "Beschreibung", "Was für Musik soll es sein? Das bekommt die KI gesagt: worum es "
                + "geht, Stimmung, Stil, Besetzung, Form, wie es sich entwickelt. Darf lang sein.", prompt, null,
                describeHelp);
        AlbumsPanel.formRow(form, 1, "Vorgaben", "Länge, Tempo, Tonart und Taktart: automatisch wählt die KI sie aus der "
                + "Beschreibung; was du wählst oder eintippst, gilt.", specFields, null, null);
        prompt.setPreferredSize(new Dimension(300, 190));
        modelChoices.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
        wish.add(form, BorderLayout.CENTER);
        JPanel modelsAtTop = new JPanel(new BorderLayout());
        modelsAtTop.setOpaque(false);
        modelsAtTop.add(modelChoices, BorderLayout.NORTH);
        wish.add(modelsAtTop, BorderLayout.EAST);

        // Under the words for the AI: what came of them, as the score a conductor reads.
        JScrollPane sheet = new JScrollPane(scoreView, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sheet.setBorder(BorderFactory.createLineBorder(AudioStudioTheme.STROKE, 1, true));
        sheet.getVerticalScrollBar().setUnitIncrement(40);
        JPanel notes = new JPanel(new BorderLayout(0, 6));
        notes.setOpaque(false);
        // Over the score: how it is played, tempo, dynamics and articulation, to be set by hand.
        JPanel scoreHead = new JPanel(new BorderLayout());
        scoreHead.setOpaque(false);
        scoreHead.add(DeckWidgets.lettering("Partitur · was die Instrumente spielen"), BorderLayout.WEST);
        JButton performance = new DeckWidgets.HeadKey("Vortrag: Tempo, Dynamik, Artikulation");
        performance.setToolTipText("Tempowechsel (auch accelerando, ritardando), Dynamikzeichen und Gabeln, "
                + "Artikulationen und was die Stufen bedeuten");
        performance.addActionListener(event -> performance());
        scoreHead.add(performance, BorderLayout.EAST);
        notes.add(scoreHead, BorderLayout.NORTH);
        notes.add(sheet, BorderLayout.CENTER);

        // One choice of two, shown as what it is: radio buttons behind the question they answer.
        JPanel whoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        whoRow.setOpaque(false);
        aiChooses.setOpaque(false);
        ownTracks.setOpaque(false);
        aiChooses.addActionListener(event -> updateComposeKey());
        ownTracks.addActionListener(event -> updateComposeKey());
        whoRow.add(aiChooses);
        // How many it may choose: from ... to ...; the same number twice asks for exactly that many.
        final JLabel fromVoices = new JLabel("mit");
        final JLabel toVoices = new JLabel("bis");
        final JLabel voicesWord = new JLabel("Stimmen");
        for (JLabel word : new JLabel[]{fromVoices, toVoices, voicesWord}) {
            word.setForeground(AudioStudioTheme.TEXT_DIM);
            word.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        }
        ownTracks.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
        voicesMin.setPreferredSize(new Dimension(58, 28));
        voicesMax.setPreferredSize(new Dimension(58, 28));
        voicesMin.setToolTipText("So viele Instrumente wählt die KI mindestens");
        voicesMax.setToolTipText("So viele Instrumente wählt die KI höchstens; gleich dem Minimum ergibt genau diese Zahl");
        // The two never cross: raising the fewest pulls the most along, lowering the most pulls the fewest.
        voicesMin.addChangeListener(event -> {
            if ((Integer) voicesMin.getValue() > (Integer) voicesMax.getValue()) {
                voicesMax.setValue(voicesMin.getValue());
            }
        });
        voicesMax.addChangeListener(event -> {
            if ((Integer) voicesMax.getValue() < (Integer) voicesMin.getValue()) {
                voicesMin.setValue(voicesMax.getValue());
            }
        });
        Runnable voicesFollow = () -> {
            for (java.awt.Component part : new java.awt.Component[]{fromVoices, voicesMin, toVoices, voicesMax, voicesWord}) {
                part.setEnabled(aiChooses.isSelected());
            }
        };
        aiChooses.addChangeListener(event -> voicesFollow.run());
        voicesFollow.run();
        whoRow.add(fromVoices);
        whoRow.add(voicesMin);
        whoRow.add(toVoices);
        whoRow.add(voicesMax);
        whoRow.add(voicesWord);
        whoRow.add(ownTracks);
        // The page shows one of two things, never both: the draft, what the AI is asked for, or the
        // score that came of it. The switch stands in the head beside the name, then the key that composes.
        JPanel draft = new JPanel(new BorderLayout());
        draft.setOpaque(false);
        JPanel asked = new JPanel(new BorderLayout());
        asked.setOpaque(false);
        asked.add(wish, BorderLayout.CENTER);
        // Or no free composing at all: notes the user gives, which the AI only brings into shape.
        JPanel templateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        templateRow.setOpaque(false);
        templateShown.setForeground(AudioStudioTheme.TEXT_DIM);
        templateShown.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 14));
        chooseTemplate.setToolTipText("Eigene Noten geben (MIDI, MusicXML, ABC …), z. B. ein gemeinfreies Werk aus einer"
                + " Notensammlung. Die KI komponiert dann nicht frei: sie übernimmt die Noten, wählt die Besetzung und"
                + " berichtigt nur Format und offensichtliche Fehler. Es geht gleich los.");
        dropTemplate.setToolTipText("Das Stück folgt keiner Notenvorlage mehr; „Komponieren“ komponiert wieder frei");
        chooseTemplate.addActionListener(event -> chooseTemplate());
        dropTemplate.addActionListener(event -> dropTemplate());
        templateRow.add(templateShown);
        templateRow.add(chooseTemplate);
        JButton scoreHelp = assistant().key("KI-Hilfe für die Notenvorlage: aus einer Beschreibung, Akkorden, einer "
                + "Melodie in Worten oder ABC schreibt die KI mit dir die Noten dieses Stücks", () -> {
                    if (directory.getText().trim().isEmpty()) {
                        append("Erst ein Stück wählen oder anlegen; dann schreibt die KI-Hilfe seine Noten.");
                        return null;
                    }
                    return Assistant.scoreField("Notenvorlage", "piece-" + pieceKey() + "-score", () -> {
                        String folder = directory.getText().trim();
                        return folder.isEmpty() ? null : Paths.get(folder);
                    }, () -> "Stück: " + pieceKey() + "\nBeschreibung: " + description.getText()
                            + "\nLänge, Tempo, Tonart, Takt: " + specSaid(), () -> {
                        showTemplate();
                        updateComposeKey();
                        append("Notenvorlage aus der KI-Hilfe gespeichert; „Komponieren“ macht das Stück daraus.");
                    });
                });
        templateRow.add(javax.swing.Box.createHorizontalStrut(6));
        templateRow.add(dropTemplate);
        originalWork.setOpaque(false);
        originalWork.setToolTipText("Die Beschreibung nennt ein bestehendes Werk oder Quellen: die KI gibt es mit Titel,"
                + " Tonart, Takt, Tempo und seinen bekannten Themen so getreu wie möglich wieder, statt etwas Neues"
                + " darüber zu erfinden. Genau wird es mit „Noten vorgeben …“.");
        originalWork.addActionListener(event -> saveSettings(false));
        originalWork.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
        templateRow.add(originalWork);
        AlbumsPanel.formRow(form, 2, "Instrumente", "Wer spielt: die KI wählt die Instrumente zur Beschreibung, mit so "
                + "vielen Stimmen, wie hier steht, oder sie komponiert für genau die Spuren im Arrangement.", whoRow,
                null, null);
        AlbumsPanel.formRow(form, 3, "Noten", "Eine Notenvorlage: ohne sie komponiert die KI frei; mit ihr übernimmt sie "
                + "die Noten und bringt sie nur in Form.", templateRow, null, scoreHelp);
        draft.add(asked, BorderLayout.NORTH);
        songView.setOpaque(false);
        songView.add(draft, "draft");
        songView.add(notes, "score");
        javax.swing.ButtonGroup oneView = new javax.swing.ButtonGroup();
        oneView.add(draftKey);
        oneView.add(scoreKey);
        draftKey.setToolTipText("Entwurf: was die KI gesagt bekommt, Takte, Tempo, Tonart, Instrumente, Modelle");
        scoreKey.setToolTipText("Partitur: was die Instrumente spielen");
        draftKey.addActionListener(event -> songViews.show(songView, "draft"));
        scoreKey.addActionListener(event -> songViews.show(songView, "score"));
        draftKey.setSelected(true);
        JPanel composing = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        composing.setOpaque(false);
        composing.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        composing.add(draftKey);
        composing.add(scoreKey);
        composing.add(javax.swing.Box.createHorizontalStrut(12));
        composing.add(composeButton);
        JButton inAlbum = new DeckWidgets.HeadKey("Im Album produzieren");
        inAlbum.setToolTipText("Macht dieses Stück neu als Titel seines Albums, mit dem, was das Album ihm sagt;"
                + " die Albumseite zeigt, wie es vorangeht.");
        inAlbum.addActionListener(event -> produceOpenPieceInAlbum());
        composing.add(javax.swing.Box.createHorizontalStrut(8));
        composing.add(inAlbum);

        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        DeckWidgets.Module module = new DeckWidgets.Module("Komposition", songView);
        module.setHeadStart(composing);
        module.setHeadEnd(withPieceMenu(songFacts));
        page.add(module, BorderLayout.CENTER);
        return page;
    }

    /** The stage: where the instruments stand, how loud they are, and the room around them. */
    private JPanel stagePage() {
        remixTimer.setRepeats(false);
        stagePanel = new StagePanel(new StagePanel.Host() {
            @Override
            public Path pieceDirectory() {
                String folder = directory.getText().trim();
                return folder.isEmpty() ? null : Paths.get(folder);
            }

            @Override
            public com.aresstack.audio.studio.figure.FigureArtist figureArtist() {
                try {
                    return new com.aresstack.audio.studio.figure.FigureArtist(
                            startModels().model(com.aresstack.audio.studio.figure.FigureArtist.ROLE));
                } catch (RuntimeException failed) {
                    message("Die KI kann keine Figuren zeichnen: " + failed.getMessage());
                    return null;
                }
            }

            @Override
            public void sceneChanged(com.aresstack.audio.studio.stage.SpatialScene changed, boolean settled) {
                AudioStudioFrame.this.sceneChanged(changed, settled, false);
            }

            @Override
            public void movementChanged(com.aresstack.audio.studio.application.StageMovements movements,
                                        String trackId, String parameter, boolean settled) {
                AudioStudioFrame.this.movementChanged(movements, trackId, parameter, settled);
            }

            @Override
            public void askDirector() {
                AudioStudioFrame.this.askDirector();
            }

            @Override
            public void movementWishChanged(boolean wanted) {
                saveSettings(false);
            }

            @Override
            public boolean takeWithMovements(com.aresstack.audio.studio.stage.SpatialScene before,
                                             com.aresstack.audio.studio.stage.SpatialScene taken,
                                             com.aresstack.audio.studio.application.MovementDirector.Proposal moves) {
                return AudioStudioFrame.this.takeWithMovements(before, taken, moves);
            }
        });
        listeningPanel = new ListeningPanel(new ListeningPanel.Host() {
            @Override
            public void listeningChanged(com.aresstack.audio.studio.application.StageListening wanted) {
                listening = wanted;
                saveSettings(false);
                applyListening(true);
            }

            @Override
            public String chooseImpulseResponse() {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Impulsantwort (WAV) wählen");
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("WAV-Dateien", "wav"));
                return chooser.showOpenDialog(AudioStudioFrame.this) == JFileChooser.APPROVE_OPTION
                        ? chooser.getSelectedFile().getAbsolutePath() : null;
            }

            @Override
            public String chooseHrtfFolder() {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Ordner mit Kopfdaten (Format MIT KEMAR) wählen");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                return chooser.showOpenDialog(AudioStudioFrame.this) == JFileChooser.APPROVE_OPTION
                        ? chooser.getSelectedFile().getAbsolutePath() : null;
            }

            @Override
            public void downloadHrtf(com.aresstack.audio.studio.hrtf.HrtfDataset dataset) {
                AudioStudioFrame.this.downloadHrtf(dataset);
            }
        });
        JPanel page = new JPanel(new BorderLayout(0, 10));
        page.setOpaque(false);
        page.add(stagePanel, BorderLayout.CENTER);
        // How the stage is heard stands in the head of the stage, not in a module of its own under it.
        stagePanel.setListening(listeningPanel.keys(), listeningPanel);
        applyListening(false); // what the settings said, read from the disk in the background
        return page;
    }

    /** The stage mixer of the window: it mixes the way the stage is heard (work package D4). */
    private com.aresstack.audio.studio.application.StageMixService stageMixing() {
        com.aresstack.audio.studio.application.StageMixService mixing =
                new com.aresstack.audio.studio.application.StageMixService();
        mixing.setSound(stageSound);
        return mixing;
    }

    /**
     * Makes the way of hearing the user chose the one of the stage: the files it names are read in
     * the background (from the disk only, never from the network), then the live stage and the
     * mixed file follow. A live stage that plays another way is made anew where playback stands.
     *
     * @param hear whether the stage is to be mixed and played anew now (not at the start, when
     *             nothing has been heard yet)
     */
    private void applyListening(boolean hear) {
        final com.aresstack.audio.studio.application.StageListening wanted = listening;
        final int ticket = ++listeningAsked;
        if (listeningPanel != null) {
            listeningPanel.show(wanted, null, hrtfLibrary.isInstalled(
                    com.aresstack.audio.studio.hrtf.HrtfDataset.MIT_KEMAR), hrtfLoading);
        }
        Thread reader = new Thread(() -> {
            com.aresstack.audio.studio.application.StageListening.Loaded loaded = wanted.load(hrtfLibrary);
            boolean installed = hrtfLibrary.isInstalled(com.aresstack.audio.studio.hrtf.HrtfDataset.MIT_KEMAR);
            SwingUtilities.invokeLater(() -> {
                if (ticket != listeningAsked) {
                    return; // chosen again in the meantime
                }
                for (String problem : loaded.problems()) {
                    append("WARN: " + problem);
                }
                boolean changed = !loaded.sound().equals(stageSound);
                stageSound = loaded.sound();
                player.sound(stageSound);
                if (listeningPanel != null) {
                    listeningPanel.show(wanted, loaded, installed, hrtfLoading);
                }
                if (hear && changed) {
                    append("Hören: " + (wanted.headphones() ? "Kopfhörer (3D), " + loaded.head() : "Lautsprecher")
                            + " · " + loaded.room());
                    hearStage(true);
                    remixTimer.restart();
                }
            });
        }, "stage-listening");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Downloads a set of head responses. Only ever called after the user has seen its source, its
     * terms and its citation and confirmed: the studio asks the network for it at no other time.
     */
    private void downloadHrtf(com.aresstack.audio.studio.hrtf.HrtfDataset dataset) {
        final AudioProxyRoutes routes;
        try {
            routes = configureNetworkRoutes();
        } catch (RuntimeException error) {
            message(error.getMessage());
            applyListening(false);
            return;
        }
        hrtfLoading = true;
        applyListening(false);
        Thread loader = new Thread(() -> {
            String problem = null;
            try {
                hrtfLibrary.download(dataset, routes::proxyFor,
                        line -> SwingUtilities.invokeLater(() -> append(line)));
            } catch (Exception failed) {
                problem = String.valueOf(failed.getMessage());
            }
            final String said = problem;
            SwingUtilities.invokeLater(() -> {
                hrtfLoading = false;
                if (said != null) {
                    message(dataset.title() + " ließ sich nicht laden: " + said);
                }
                applyListening(true);
            });
        }, "hrtf-download");
        loader.setDaemon(true);
        loader.start();
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
        hearStage(settled);
        if (settled) {
            saveScene();
            remixTimer.restart();
        }
    }

    private void showStage() {
        if (stagePanel != null && trackTable != null) {
            stagePanel.setScene(scene, trackTable.tracks());
        }
        hearStage(true); // a whole scene from elsewhere: a proposal taken, a step undone
        showMovements();
    }

    /**
     * The stage as it stands is heard at once where it is played live, also in the middle of a
     * drag. Another room (or another way of setting the level) needs a new live stage: that is
     * made in the background once the change has settled, and goes on where playback stands.
     */
    private void hearStage(boolean settled) {
        if (!player.stage(scene, soloTrack()) && settled) {
            openLive(Double.NaN, false);
        }
    }

    /** Whether a choice of the playback is heard on the stage, and so can be played live. */
    private boolean onStage(String selected) {
        return StageDeck.LIVE_WANTED && selected != null && !"Java reference mix".equals(selected)
                && writtenArrangement() != null;
    }

    /**
     * Plays the stage live from now on: the tracks are read in the background, then playback goes
     * on at a second ({@code at}), or where it stands by then if {@code at} is not a number. If the
     * stage cannot be played live (no sound card for it, a track not rendered), the file is played
     * as before.
     */
    private void openLive(final double at, final boolean play) {
        final String selected = (String) playbackSelection.getSelectedItem();
        final Path folder = Paths.get(directory.getText().trim());
        // The factor of the whole mix is not that of a track alone: that one the live stage measures itself.
        float factor = soloTrack() == null && folder.equals(mixFactorFolder)
                && mixFactorMasterDb == scene.masterDb() ? mixFactor : Float.NaN;
        player.openLive(pieceToMix(), folder, scene, soloTrack(), factor, at, play, problem -> {
            if (problem == null) {
                hearStage(true); // what moved while the tracks were read
                return;
            }
            append("WARN: Die Bühne lässt sich nicht live spielen, es spielt die gemischte Datei: "
                    + problem.getMessage());
            if (selected == null || !selected.equals(playbackSelection.getSelectedItem())) {
                return;
            }
            if (Double.isNaN(at)) {
                // A live stage made anew (another room) failed: the old one must not go on in the old
                // room. The file takes over where playback stands; the mix that follows is swapped in.
                playFile(selected, player.seconds(), player.playing());
            } else {
                playFile(selected, at, play);
            }
        });
    }

    /** Shows on the stage page how the instruments move over the bars, as the model of the piece says. */
    private void showMovements() {
        if (stagePanel != null) {
            stagePanel.setMovements(pieceModel.movements());
        }
    }

    /**
     * A curve of the place or of the room share of a track was drawn on the stage page (work
     * package D3). When the change has settled, it is kept in the mix document of the piece and
     * the rendered tracks are mixed again; the notes and the rendered sound stay as they are.
     */
    private void movementChanged(com.aresstack.audio.studio.application.StageMovements movements, String trackId,
                                 String parameter, boolean settled) {
        if (!settled) {
            return;
        }
        if (!pieceModel.move(movements, trackId, parameter)) {
            showMovements(); // not kept: the page shows again what the piece says
            return;
        }
        player.movementsChanged(); // heard at once where the stage is played live
        remixTimer.restart();
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

    /**
     * What mixing, listening to one track and saving the mix work from: the model of the open
     * piece, with its parts and its time map. A written arrangement is read only for a piece
     * without a model, or when the user chose an arrangement from elsewhere. {@code null} if
     * nothing is written yet.
     */
    private com.aresstack.audio.studio.application.StageMixService.Piece pieceToMix() {
        try {
            com.aresstack.audio.composition.Composition model = modelToMix();
            if (model != null) {
                return com.aresstack.audio.studio.application.StageMixService.Piece.of(model);
            }
            Path written = writtenArrangement();
            return written == null ? null : com.aresstack.audio.studio.application.StageMixService.Piece.written(written);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The model of the open piece as it is mixed; {@code null} without one, or with an arrangement from elsewhere. */
    private com.aresstack.audio.composition.Composition modelToMix() {
        Path folder = Paths.get(directory.getText().trim()).toAbsolutePath().normalize();
        String named = arrangementFile.getText().trim();
        boolean elsewhere = !named.isEmpty() && !Paths.get(named).toAbsolutePath().normalize()
                .equals(folder.resolve("arrangement.tsv"));
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> model = pieceToEdit();
        return !elsewhere && model.isPresent() && model.get().folder().toAbsolutePath().normalize().equals(folder)
                ? model.get().composition() : null;
    }

    /** Mixes the rendered tracks again on the stage as it stands, in the background. */
    private void remix() {
        if (stagePanel == null || (worker != null && !worker.isDone())) {
            return; // what is running mixes on the saved stage when it gets there
        }
        final com.aresstack.audio.studio.application.StageMixService.Piece arrangement = pieceToMix();
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
            com.aresstack.audio.studio.application.StageMixService mixing = stageMixing();
            try {
                mixing.mix(arrangement, folder, mixed, message -> { });
            } catch (Exception failed) {
                problem = String.valueOf(failed.getMessage());
            }
            final String said = problem;
            final float factor = mixing.legacyFactor();
            if (problem == null && factor > 0 && Float.isFinite(factor)) {
                SwingUtilities.invokeLater(() -> mixFactorFound(folder, ticket, factor, mixed.masterDb()));
            }
            final double seconds = (System.nanoTime() - began) / 1e9;
            SwingUtilities.invokeLater(() -> mixedAgain(folder, ticket, said, seconds));
        });
    }

    /**
     * The file mix of the stage found the factor it brought the mix to its peak with. Live
     * playback takes it, so that it is as loud as the file; kept for the next time the stage is
     * played live.
     */
    private void mixFactorFound(Path folder, int ticket, float factor, double masterDb) {
        if (ticket != mixWanted || !folder.equals(Paths.get(directory.getText().trim()))) {
            return;
        }
        mixFactor = factor;
        mixFactorMasterDb = masterDb;
        mixFactorFolder = folder;
        if (soloTrack() == null) {
            player.legacyFactor(factor, masterDb);
        }
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
        if (selected == null) {
            return;
        }
        // What plays goes on at the same place with the new mix; a track that plays alone is mixed anew too.
        Path file = playbackFile(selected);
        if (player.ready()) {
            try {
                player.swap(file); // goes on at the same place, without a gap
                if (!player.live()) {
                    lightsOfFile(soloTrack()); // the stage lights follow the stage that was mixed
                }
            } catch (Exception noSound) {
                player.show(file);
            }
        } else {
            player.show(file);
        }
        arrangementView.setMix(player.envelope(), soloTrack() == null ? "Mix" : "Solo", soloTrack(), player.length());
        lcd.setSource(playbackName(selected), true);
    }

    /** Writes the mix on the stage to a WAV file of the user's choice, with 24 bits, as loud as the mixed file. */
    private void exportMix() {
        final com.aresstack.audio.studio.application.StageMixService.Piece arrangement = pieceToMix();
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
            com.aresstack.audio.studio.application.StageMixService mixing = stageMixing();
            mixing.export(arrangement, Paths.get(directory.getText().trim()), scene, target, 24);
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
        final com.aresstack.audio.composition.Composition model = modelToMix();
        final Path arrangement = writtenArrangement();
        if (model == null && arrangement == null) {
            message("Erst komponieren oder die Demo laden: die KI mischt ab, was geschrieben ist.");
            return;
        }
        final com.aresstack.audio.studio.ai.ModelLineup models;
        try {
            models = startModels();
        } catch (RuntimeException invalid) {
            message(invalid.getMessage());
            return;
        }
        final Path output = Paths.get(directory.getText().trim());
        final String prompt = wishForAi();
        final int tokens = (Integer) tokenLimit.getValue();
        final com.aresstack.audio.studio.stage.SpatialScene current = scene;
        // Movements are a request of their own, only when the user switched them on (off by default).
        final boolean withMovements = stagePanel.movementsWanted();
        final com.aresstack.audio.studio.application.StageMovements movements = pieceModel.movements();
        if (!saveSettings(false)) {
            return;
        }
        activeModels = models;
        stagePanel.setProposal(null);
        stagePanel.setStatus("Die KI schlägt eine Abmischung vor …");
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                try {
                    final com.aresstack.audio.studio.stage.SpatialScene proposed =
                            model != null
                                    ? new com.aresstack.audio.studio.application.MixDirector(
                                            models.model(com.aresstack.audio.studio.ai.ModelRoles.MIX), tokens)
                                            .watching(seen -> SwingUtilities.invokeLater(() -> showMixArriving(seen)))
                                            .direct(prompt, model, current, output, progress)
                                    : new com.aresstack.audio.studio.application.MixDirector(
                                            models.model(com.aresstack.audio.studio.ai.ModelRoles.MIX), tokens)
                                            .watching(seen -> SwingUtilities.invokeLater(() -> showMixArriving(seen)))
                                            .direct(prompt, arrangement, current, output, progress);
                    final com.aresstack.audio.studio.application.MovementDirector.Proposal moves =
                            withMovements ? proposeMovements(
                                    models.model(com.aresstack.audio.studio.ai.ModelRoles.MOVEMENTS), tokens, prompt, model, arrangement, current,
                                    proposed, movements, output, progress)
                                    : com.aresstack.audio.studio.application.MovementDirector.Proposal.NONE;
                    SwingUtilities.invokeLater(() -> {
                        mixArriving = false;
                        stagePanel.setProposal(proposed, moves);
                    });
                    progress.update("Abmischung vorgeschlagen: auf der Bühne ansehen, dann übernehmen oder verwerfen.");
                    return null;
                } finally {
                    activeModels = null;
                }
            }
        });
    }

    /**
     * The second request of "KI mischt ab", when the user wants movements: how tracks that are not
     * protected move, starting from where the mix proposal puts them. Like the mix proposal it reads
     * the model of the piece where there is one, else the written arrangement. If it fails, the mix proposal
     * stands without movements and the reason is said; the mix is not lost for it.
     */
    private com.aresstack.audio.studio.application.MovementDirector.Proposal proposeMovements(
            com.aresstack.audio.studio.ai.ChatModel client, int tokens, String prompt,
            com.aresstack.audio.composition.Composition model, Path arrangement,
            com.aresstack.audio.studio.stage.SpatialScene current,
            com.aresstack.audio.studio.stage.SpatialScene proposed,
            com.aresstack.audio.studio.application.StageMovements movements, Path output, Progress progress)
            throws IOException {
        // From the model where the piece has one, as the mix proposal (no fall-back to the file, #14).
        com.aresstack.audio.studio.application.MovementDirector.Piece piece = model != null
                ? com.aresstack.audio.studio.application.MovementDirector.of(model)
                : com.aresstack.audio.studio.application.MovementDirector.of(arrangement);
        java.util.Set<String> movable = com.aresstack.audio.studio.application.MovementDirector.movable(
                current, movements, piece);
        if (movable.isEmpty()) {
            append("Bewegungen: keine Spur kann sich bewegen (alle geschützt, oder das Stück hat noch kein Werkmodell).");
            return com.aresstack.audio.studio.application.MovementDirector.Proposal.NONE;
        }
        try {
            com.aresstack.audio.studio.application.MovementDirector.Proposal moves =
                    new com.aresstack.audio.studio.application.MovementDirector(client, tokens)
                            .watching(seen -> SwingUtilities.invokeLater(() -> showMovesArriving(proposed, seen)))
                            .propose(prompt, piece, current.taking(proposed), movable, output, progress);
            append(moves.empty() ? "Bewegungen: die KI lässt alle Spuren stehen."
                    : "Bewegungen vorgeschlagen für " + String.join(", ", moves.curves().keySet()) + ".");
            return moves;
        } catch (IOException failed) {
            if (Thread.currentThread().isInterrupted()) {
                throw failed;
            }
            append("WARN: Bewegungen nicht vorgeschlagen, die Abmischung gilt ohne sie: " + failed.getMessage());
            return com.aresstack.audio.studio.application.MovementDirector.Proposal.NONE;
        }
    }

    /**
     * The user took a mix proposal with movements: stage file and model take it together, as one
     * step that one undo takes back ({@link com.aresstack.audio.studio.application.MixProposalTaking}).
     * If it cannot be kept, nothing of it is taken and the proposal stays.
     */
    private boolean takeWithMovements(com.aresstack.audio.studio.stage.SpatialScene before,
                                      com.aresstack.audio.studio.stage.SpatialScene taken,
                                      com.aresstack.audio.studio.application.MovementDirector.Proposal moves) {
        int kept;
        try {
            kept = com.aresstack.audio.studio.application.MixProposalTaking.take(pieceModel, stageFollowsMix,
                    sceneStore, before, taken, moves);
        } catch (IOException | RuntimeException failed) {
            message("Der Vorschlag ließ sich nicht übernehmen, die Bühne bleibt, wie sie war: " + failed.getMessage());
            return false;
        }
        scene = taken;
        trackTable.refreshStage();
        showStage();
        player.movementsChanged(); // heard at once where the stage is played live
        remixTimer.restart();
        append("Abmischung der KI übernommen, mit " + kept + (kept == 1 ? " Bewegung." : " Bewegungen.")
                + " Rückgängig nimmt beides zurück.");
        return true;
    }

    /** The AI help of the fields, made once. */
    private Assistant assistantOfFields;

    private Assistant assistant() {
        if (assistantOfFields == null) {
            assistantOfFields = new Assistant(new Assistant.Connection() {
                @Override
                public com.aresstack.audio.studio.ai.ToolChat connect(String chosen) {
                    String name = chosen == null || chosen.isBlank() ? currentAssignment().base() : chosen.trim();
                    com.aresstack.audio.studio.ai.ChatModel made = startModels().modelNamed(name);
                    if (made instanceof com.aresstack.audio.studio.ai.ToolChat tools) {
                        return tools;
                    }
                    throw new IllegalArgumentException("Dieses Modell kann keine Werkzeuge aufrufen.");
                }

                @Override
                public List<String> models() {
                    List<String> known = modelChoices.knownModels();
                    if (!known.isEmpty()) {
                        return known;
                    }
                    java.util.LinkedHashSet<String> chosen = new java.util.LinkedHashSet<String>();
                    com.aresstack.audio.studio.ai.ModelAssignment assignment = currentAssignment();
                    chosen.add(assignment.base());
                    for (com.aresstack.audio.studio.ai.ModelRole role : com.aresstack.audio.studio.ai.ModelRoles.all()) {
                        chosen.add(assignment.modelFor(role));
                    }
                    return new ArrayList<String>(chosen);
                }

                @Override
                public String defaultModel() {
                    return currentAssignment().modelFor(com.aresstack.audio.studio.album.AlbumRoles.ALBUM);
                }

                @Override
                public int maxTokens() {
                    return (Integer) tokenLimit.getValue();
                }

                @Override
                public Path folder() {
                    return settingsStore.location().toAbsolutePath().getParent().resolve("assist");
                }

                @Override
                public void log(String line) {
                    append(line);
                }
            });
        }
        return assistantOfFields;
    }

    /** What the fields of length, tempo, key and meter say, or why they say nothing. */
    private String specSaid() {
        try {
            return String.valueOf(specFields.spec());
        } catch (IllegalArgumentException unclear) {
            return unclear.getMessage();
        }
    }

    /** The name of the open piece's folder, for the conversation of its description. */
    private String pieceKey() {
        String folder = directory.getText().trim();
        Path name = folder.isEmpty() ? null : Paths.get(folder).getFileName();
        return name == null ? "neu" : name.toString();
    }

    /** The page of the albums: several pieces planned and made together, beside the single piece. */
    private JPanel albumsPage() {
        AlbumsPanel albums = new AlbumsPanel(new AlbumsPanel.Host() {
            @Override
            public void albumShown(String id) {
                albumShown = id;
                albumModelChoices.setAssignment(albumAssignment(id));
            }

            @Override
            public void albumChosen(String id) {
                currentAlbum = id;
                refreshSongs();
                if (albumTree != null) {
                    albumTree.selectAlbum(id);
                }
            }

            @Override
            public void albumChanged() {
                refreshSongs(); // a title, a new title or a plan: the tree shows it
            }

            @Override
            public com.aresstack.audio.studio.ai.ModelLineup models() {
                return startModels(albumAssignment(albumShown)); // the models of the album shown
            }

            @Override
            public Assistant assistant() {
                return AudioStudioFrame.this.assistant();
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
            public int sectionBars() {
                return (Integer) sectionBars.getValue();
            }

            @Override
            public int growBars() {
                return (Integer) growBars.getValue();
            }

            @Override
            public int templateLines() {
                return (Integer) templateLines.getValue();
            }

            @Override
            public List<Path> chooseScores(String title) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(title);
                chooser.setMultiSelectionEnabled(true);
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "Noten (MIDI, MusicXML, ABC, LilyPond …)",
                        com.aresstack.audio.studio.template.TemplateReader.ENDINGS));
                if (chooser.showOpenDialog(AudioStudioFrame.this) != JFileChooser.APPROVE_OPTION) {
                    return List.of();
                }
                List<Path> chosen = new ArrayList<Path>();
                for (java.io.File file : chooser.getSelectedFiles()) {
                    chosen.add(file.toPath());
                }
                return chosen;
            }

            @Override
            public int[] voices() {
                int fewest = (Integer) voicesMin.getValue();
                return new int[]{fewest, Math.max(fewest, (Integer) voicesMax.getValue())};
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

            @Override
            public com.aresstack.audio.studio.progress.SketchProgress pieceWatch(Path piece) {
                return albumPieceWatch(piece);
            }

            @Override
            public void pieceProduced(Path piece) {
                albumPieceProduced(piece);
            }
        }, songs, registry);
        albumsPanel = albums;
        albums.listInTree(); // the albums are chosen in the tree at the left
        albums.setBesideConcept(albumModelChoices);
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        DeckWidgets.Module module = new DeckWidgets.Module("Album", albums);
        module.setHeadStart(albums.headKeys());
        page.add(module, BorderLayout.CENTER);
        return page;
    }

    private JPanel libraryPage() {
        InstrumentLibraryPanel libraries = new InstrumentLibraryPanel(registry, this::configureNetworkRoutes,
                this::refreshInstrumentChoices, this::append);
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        page.add(new DeckWidgets.Module("Soundbänke · SF2, SF3 und SFZ", libraries), BorderLayout.CENTER);
        ArticulationSoundsPanel articulations = new ArticulationSoundsPanel(this::append);
        libraries.whenSelected(articulations::display);
        page.add(new DeckWidgets.Module("Artikulationen der gewählten Soundbank · Keyswitch oder Programm",
                articulations), BorderLayout.SOUTH);
        return page;
    }

    private JPanel consolePage() {
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createLineBorder(new java.awt.Color(0x24352C), 1, true));
        DeckWidgets.Module module = new DeckWidgets.Module("Protokoll", scroll);
        JButton clear = new DeckWidgets.HeadKey("Leeren");
        clear.setToolTipText("Das Protokoll leeren");
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
        // Where the access comes from, as in 3D4J: KeePass first, typed in by hand, or Ollama.
        javax.swing.ButtonGroup accessKinds = new javax.swing.ButtonGroup();
        for (javax.swing.JRadioButton kind : new javax.swing.JRadioButton[] {accessKeePass, accessManual, accessOllama}) {
            kind.setOpaque(false);
            accessKinds.add(kind);
            kind.addActionListener(event -> showAccess());
        }
        accessKeePass.setToolTipText("Adresse und API-Key kommen aus einem Eintrag in KeePass");
        accessManual.setToolTipText("OpenAI-kompatibler Dienst: Adresse und API-Key von Hand eintragen");
        accessNote.setForeground(AudioStudioTheme.TEXT_FAINT);
        accessNote.setFont(accessNote.getFont().deriveFont(12f));
        accessOllama.setEnabled(false);
        accessOllama.setToolTipText("Ollama wird noch nicht unterstützt und kommt später");
        showAccess(); // the note has its words before the row is laid out
        addRow(ai, row++, "Zugang", line(accessKeePass, accessManual, accessOllama, accessNote));
        JButton keepassList = new DeckWidgets.HeadKey("KeePass verbinden");
        keepassList.setToolTipText("Mit KeePassRPC verbinden und die Einträge laden");
        keepassList.addActionListener(event -> loadKeePassEntries());
        keePassEntries.setPreferredSize(new Dimension(260, 30));
        JButton keepassUse = new DeckWidgets.HeadKey("Eintrag übernehmen");
        keepassUse.addActionListener(event -> useKeePassEntry());
        JPanel credentialButtons = line(keepassList, keePassEntries, keepassUse);
        keePassRow = credentialButtons;
        keePassLabel = addRow(ai, row++, "KeePass", credentialButtons);
        addRow(ai, row++, "Completions-URL", endpoint);
        addRow(ai, row++, "API-Key", apiKey);
        showAccess();
        // The base model has no field any more: every task has its model on the pages of the
        // album and of the composition. The value is kept as what a task without one falls back to.
        model.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void follow() {
                modelChoices.setBase(model.getText());
                albumModelChoices.setBase(model.getText());
            }

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                follow();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                follow();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                follow();
            }
        });
        tokenLimit.setPreferredSize(new Dimension(110, 30));
        parallelRequests.setPreferredSize(new Dimension(110, 30));
        addRow(ai, row++, "Antwortlänge",
                line(tokenLimit, note("Tokens je Antwort, höchstens; auch der Plan eines langen Stücks bekommt so viel Platz.")));
        sectionBars.setPreferredSize(new Dimension(110, 30));
        sectionBars.setToolTipText("So viele Takte schreibt die KI je Anfrage und Spur, 2 bis 64");
        addRow(ai, row++, "Takte je Abschnitt",
                line(sectionBars, note("Takte je Anfrage und Spur; Stücke bis zur doppelten Länge entstehen in einem Zug.")));
        growBars.setPreferredSize(new Dimension(110, 30));
        growBars.setToolTipText("So lang ist ein neues Stück, so viel fügt „Verlängern“ an, und in Schritten dieser Größe wächst ein langes Stück, "
                + PieceAutopilot.MIN_GROW_BARS + " bis " + PieceAutopilot.MAX_GROW_BARS);
        addRow(ai, row++, "Takte je Stück und Schritt",
                line(growBars, note("Vorgabe für neue Stücke, für „Verlängern“ und für jeden Schritt, um den lange Stücke wachsen.")));
        growBars.addChangeListener(event -> specFields.suggestBars((Integer) growBars.getValue()));
        templateLines.setPreferredSize(new Dimension(110, 30));
        templateLines.setToolTipText("So viele Zeilen einer Notenvorlage in Textform (ABC, LilyPond …) überträgt die KI je Anfrage, "
                + com.aresstack.audio.studio.template.TextTemplate.MIN_LINES + " bis "
                + com.aresstack.audio.studio.template.TextTemplate.MAX_LINES);
        addRow(ai, row++, "Zeilen der Vorlage je Anfrage",
                line(templateLines, note("Für Notenvorlagen in Textform; MIDI und MusicXML liest das Studio selbst.")));
        addRow(ai, row++, "Parallel",
                line(parallelRequests, note("Anfragen gleichzeitig; höchstens so viele wie Spuren.")));
        retryFirst.setPreferredSize(new Dimension(110, 30));
        retryMost.setPreferredSize(new Dimension(110, 30));
        addRow(ai, row++, "Wiederholen",
                line(retryFirst, note("s erste Pause, verdoppelt bis höchstens"), retryMost,
                        note("s. Abgerissene Verbindungen und unbrauchbare Antworten werden ohne Ende wiederholt; "
                                + "nur „Abbrechen“ beendet das.")));
        javax.swing.event.ChangeListener retry = event -> com.aresstack.audio.studio.ai.Backoff.configure(
                (Integer) retryFirst.getValue(), (Integer) retryMost.getValue());
        retryFirst.addChangeListener(retry);
        retryMost.addChangeListener(retry);
        firstText.setPreferredSize(new Dimension(110, 30));
        silence.setPreferredSize(new Dimension(110, 30));
        addRow(ai, row++, "Erste Antwort",
                line(firstText, note("s Warten auf den ersten Text; danach darf die Antwort höchstens"), silence,
                        note("s schweigen („Stille“). Wer länger schweigt, gilt als abgerissen und wird neu gefragt.")));
        javax.swing.event.ChangeListener waits = event -> KipitzChatClient.waits((Integer) firstText.getValue(),
                (Integer) silence.getValue());
        firstText.addChangeListener(waits);
        silence.addChangeListener(waits);
        com.aresstack.audio.studio.ai.Backoff.tellTo(line -> SwingUtilities.invokeLater(() -> troubleDigest.add(line)));
        // Many requests that break off together are said in one line per model and cause.
        new javax.swing.Timer(TroubleDigest.EVERY_MILLIS, event -> troubleDigest.flush()).start();
        // As many requests at once per model as the connections hold, up to "Parallel".
        parallelRequests.addChangeListener(event -> com.aresstack.audio.studio.ai.ConnectionGate.configure(
                (Integer) parallelRequests.getValue()));
        com.aresstack.audio.studio.ai.ConnectionGate.configure((Integer) parallelRequests.getValue());
        // A window that stops answering leaves a note of where it hung, next to the settings.
        new EdtWatch(settingsStore.location().toAbsolutePath().getParent()
                .resolve("logs"), this::append).start();
        detailedLog.setOpaque(false);
        detailedLog.setToolTipText("Zeigt im Protokoll zusätzlich, wie viele Stimmen gleichzeitig laufen und wann welche"
                + " Antwort mit wie vielen Noten nach wie vielen Sekunden kam");
        addRow(ai, row++, "Protokoll", detailedLog);
        addRow(ai, row, "", note("Der API-Key wird lokal im Klartext gespeichert (studio.properties im Benutzerordner)."));

        JPanel network = form();
        row = 0;
        addRow(network, row++, "Proxy-Modus", proxyMode);
        JButton testProxy = new DeckWidgets.HeadKey("Proxyweg prüfen");
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
        JButton fluidHelp = new DeckWidgets.HeadKey("Installieren");
        fluidHelp.addActionListener(event -> openEngineWebsite("https://www.fluidsynth.org/download/"));
        fluidSynth.setPreferredSize(new Dimension(260, 30));
        addRow(sound, row++, "FluidSynth", line(fluidSynth, fluidHelp));
        JButton sfizzHelp = new DeckWidgets.HeadKey("Installieren");
        sfizzHelp.addActionListener(event -> openEngineWebsite("https://github.com/sfztools/sfizz/releases"));
        sfizz.setPreferredSize(new Dimension(260, 30));
        addRow(sound, row++, "sfizz", line(sfizz, sfizzHelp));
        addRow(sound, row, "", note("SF2: Java/Gervill ohne Installation · SF3: FluidSynth · SFZ: sfizz."));

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new javax.swing.BoxLayout(column, javax.swing.BoxLayout.Y_AXIS));
        DeckWidgets.Module aiModule = new DeckWidgets.Module("KI-Zugang", ai);
        JButton save = new DeckWidgets.HeadKey("Einstellungen speichern");
        save.addActionListener(event -> saveSettings(true));
        aiModule.setHeadEnd(save);
        column.add(aiModule);
        column.add(javax.swing.Box.createVerticalStrut(14));
        column.add(new DeckWidgets.Module("Netzwerk · Proxy", network));
        column.add(javax.swing.Box.createVerticalStrut(14));
        column.add(new DeckWidgets.Module("Klangerzeugung", sound));
        JPanel looks = form();
        fitArrangement.setOpaque(false);
        fitArrangement.setToolTipText("An: das ganze Stück ist immer zu sehen. Aus: Strg + Mausrad zoomt, "
                + "unten erscheint ein Rollbalken; Umschalt und Mausrad rollen seitwärts.");
        fitArrangement.addActionListener(event -> {
            trackTable.setFit(fitArrangement.isSelected());
            saveSettings(false);
        });
        addRow(looks, 0, "Arrangement", fitArrangement);
        addRow(looks, 1, "", note("Aus: Strg + Mausrad zoomt; unten ein Rollbalken, Umschalt + Mausrad rollt seitwärts."));
        appIconChoice.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof AppIcon) {
                    AppIcon icon = (AppIcon) value;
                    // the open list shows the icons large enough to tell them apart, the closed box small
                    setIcon(icon.preview(index < 0 ? 24 : 48));
                    setText(icon.label());
                    setIconTextGap(10);
                }
                return this;
            }
        });
        appIconChoice.setToolTipText("Das Icon von Fenster und Taskleiste; wirkt sofort");
        appIconChoice.addActionListener(event -> {
            ((AppIcon) appIconChoice.getSelectedItem()).applyToAllWindows();
            if (!showingSavedIcon) {
                saveSettings(false);
            }
        });
        addRow(looks, 2, "App-Icon", line(appIconChoice));
        column.add(javax.swing.Box.createVerticalStrut(14));
        column.add(new DeckWidgets.Module("Ansicht", looks));
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

    /**
     * Shows what the chosen kind of access needs: with KeePass the row to connect and to take an
     * entry, whose address and key are then shown but not typed; by hand the two fields alone.
     */
    private void showAccess() {
        boolean keePass = accessKeePass.isSelected();
        if (keePassRow != null) {
            keePassRow.setVisible(keePass);
            keePassLabel.setVisible(keePass);
        }
        endpoint.setEditable(!keePass);
        apiKey.setEditable(!keePass);
        accessNote.setText(keePass ? "Adresse und API-Key kommen aus dem KeePass-Eintrag "
                : "OpenAI-kompatibler Dienst, Adresse und API-Key von Hand ");
        accessNote.revalidate();
    }

    private JLabel addRow(JPanel panel, int row, String name, java.awt.Component component) {
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
        return lettering;
    }

    /**
     * Names the compose key after what it does with the piece that is open: a piece that is not
     * composed yet is composed; one that is composed is either begun anew (the AI chooses the
     * instruments: a new piece beside it, or this one replaced, as the studio then asks) or gets
     * the tracks composed that have no notes yet.
     */
    private void updateComposeKey() {
        boolean template = showTemplate();
        // A piece that is composed opens on its score, one that is not on its draft; after that the
        // switch in the head is the user's.
        if (composedShown == null || composedShown.booleanValue() != specFields.locked()) {
            composedShown = Boolean.valueOf(specFields.locked());
            showSongView(specFields.locked());
        }
        if (template) {
            composeButton.setText(specFields.locked() ? "Aus Noten neu machen …" : "Aus Noten übernehmen");
            composeButton.setToolTipText("Die Notenvorlage wird gelesen, die KI wählt die Besetzung und berichtigt nur"
                    + " offensichtliche Fehler; dann wird gerendert.");
        } else if (!specFields.locked()) {
            composeButton.setText("Komponieren");
            composeButton.setToolTipText("Komponieren und gleich rendern");
        } else if (aiChooses.isSelected()) {
            composeButton.setText("Neu komponieren …");
            composeButton.setToolTipText("Dieses Stück ist schon komponiert. Hier entsteht ein neues Stück daneben,"
                    + " oder dieses wird ersetzt; das wird gleich gefragt.");
        } else {
            composeButton.setText("Fehlende Spuren komponieren");
            composeButton.setToolTipText("Komponiert für die Spuren im Arrangement, die noch keine Noten haben;"
                    + " was schon komponiert ist, bleibt.");
        }
    }

    /** The one key: the AI chooses the instruments first, or the tracks of the mixer are composed as they are. */
    /** Shows the draft or the score on the composition page. */
    private void showSongView(boolean score) {
        (score ? scoreKey : draftKey).setSelected(true);
        songViews.show(songView, score ? "score" : "draft");
    }

    private void compose() {
        // Notes given as a template: the piece is made from them; the AI chooses no instruments of its own.
        Path folderBefore = Paths.get(directory.getText().trim());
        java.util.Optional<Path> template = com.aresstack.audio.studio.application.ScoreTemplates.of(folderBefore);
        if (template.isPresent()) {
            if (startAfresh()) {
                Path folder = Paths.get(directory.getText().trim());
                if (!folder.toAbsolutePath().normalize().equals(folderBefore.toAbsolutePath().normalize())) {
                    try {
                        com.aresstack.audio.studio.application.ScoreTemplates.put(folder, template.get());
                    } catch (IOException failed) {
                        message("Die Notenvorlage ließ sich nicht ins neue Stück legen: " + failed.getMessage());
                        return;
                    }
                }
                showSongView(true);
                proposeInstruments(true);
            }
            return;
        }
        // The draft is said: the page turns to the score, where the notes appear as they are written.
        if (aiChooses.isSelected()) {
            if (startAfresh()) {
                showSongView(true);
                proposeInstruments(true);
            }
        } else if (planStillFits()) {
            showSongView(true);
            generate();
        }
    }

    /**
     * Shows which notes the open piece follows, and lets the choice of instruments rest while it
     * follows any: the template names who plays.
     *
     * @return whether the open piece follows a template
     */
    private boolean showTemplate() {
        String folderText = directory.getText().trim();
        java.util.Optional<Path> template = folderText.isEmpty() ? java.util.Optional.<Path>empty()
                : com.aresstack.audio.studio.application.ScoreTemplates.of(Paths.get(folderText));
        templateShown.setText(template.isPresent() ? "„" + template.get().getFileName() + "“ · die KI bringt sie nur in Form"
                : "keine, die KI komponiert frei");
        templateShown.setForeground(template.isPresent() ? AudioStudioTheme.TEXT : AudioStudioTheme.TEXT_DIM);
        dropTemplate.setVisible(template.isPresent());
        aiChooses.setEnabled(!template.isPresent());
        ownTracks.setEnabled(!template.isPresent());
        return template.isPresent();
    }

    /**
     * Notes the user gives for the open piece: kept with it, and made into the piece at once
     * (a piece that is composed already asks first whether beside it or in its place).
     */
    private void chooseTemplate() {
        if (busy()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Noten als Vorlage wählen");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Noten (MIDI, MusicXML, ABC, LilyPond …)", com.aresstack.audio.studio.template.TemplateReader.ENDINGS));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        // The notes go where the piece is made: a composed piece first asks whether beside it or in its
        // place, and cancelling leaves it, and the template it follows, as it was.
        if (!startAfresh()) {
            return;
        }
        Path folder = Paths.get(directory.getText().trim());
        try {
            com.aresstack.audio.studio.application.ScoreTemplates.put(folder, chooser.getSelectedFile().toPath());
        } catch (IOException failed) {
            message("Die Notenvorlage ließ sich nicht übernehmen: " + failed.getMessage());
            return;
        }
        append("Notenvorlage für dieses Stück: " + chooser.getSelectedFile().getName());
        updateComposeKey();
        showSongView(true);
        proposeInstruments(true);
    }

    private void dropTemplate() {
        try {
            com.aresstack.audio.studio.application.ScoreTemplates.remove(Paths.get(directory.getText().trim()));
            append("Das Stück folgt keiner Notenvorlage mehr; „Komponieren“ komponiert wieder frei.");
        } catch (IOException failed) {
            message("Die Notenvorlage ließ sich nicht entfernen: " + failed.getMessage());
        }
        updateComposeKey();
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
        // Both ways plan anew, so length, tempo, key and meter can be chosen anew here.
        PieceSpecFields wanted = new PieceSpecFields();
        final com.aresstack.audio.studio.application.CompositionSpec chosenSpec;
        java.util.Set<String> chosenFixed = java.util.Set.of();
        int choice;
        try {
            wanted.choose(specFields.spec(), specFields.fixed());
            choice = JOptionPane.showOptionDialog(this, new Object[]{
                    "„" + songs.name(folder) + "“ ist schon komponiert.\n\n"
                            + "Neues Stück: es kommt als eigenes Stück in die Liste, dieses bleibt, wie es ist.\n"
                            + "Dieses ersetzen: Instrumente und Noten werden neu erfunden; "
                            + "die bisherige Fassung liegt danach im Ordner „earlier“.\n\n"
                            + "Vorgaben für die neue Komposition:", wanted},
                    "Komponieren", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
            chosenSpec = choice == 0 || choice == 1 ? wanted.spec() : null;
            chosenFixed = wanted.fixed();
        } catch (RuntimeException invalid) {
            message(invalid.getMessage());
            return false;
        }
        try {
            if (choice == 0) {
                keepSong();
                stopPlayback();
                Path fresh = songs.createUntitled(description.getText());
                intoAlbum(fresh, null);
                directory.setText(fresh.toString());
                arrangementFile.setText("");
                loadArrangementForCurrentDirectory(new Properties());
                saveSettings(false);
                refreshSongs();
                append("Neues Stück angelegt; seinen Namen bekommt es vom Plan der KI: " + fresh.getFileName());
                specFields.show(null);
                updateComposeKey();
                specFields.choose(chosenSpec, chosenFixed);
                return true;
            }
            if (choice == 1) {
                int put = putAnswersAside(folder, null);
                append("Das Stück wird neu komponiert; " + put + " bisherige Antworten liegen im Ordner „earlier“.");
                specFields.show(null);
                updateComposeKey();
                specFields.choose(chosenSpec, chosenFixed);
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
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (piece.isPresent() && piece.get().composition().part(trackId) != null) {
            if (!busy()) {
                proposeAgain(trackId, piece.get()); // a piece with a model is changed only by a proposal that is taken
            }
            return;
        }
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
        String now = wishForAi();
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
        final com.aresstack.audio.studio.ai.ModelLineup models;
        try {
            chosen = trackTable.arrangement();
            models = startModels();
        } catch (RuntimeException invalid) {
            message(invalid.getMessage());
            return;
        }
        final String prompt = wishForAi();
        final Path output = Paths.get(directory.getText().trim());
        final com.aresstack.audio.studio.application.CompositionSpec spec;
        final java.util.Set<String> fixed = specFields.fixed();
        try {
            spec = specFields.spec();
        } catch (RuntimeException invalid) {
            message(invalid.getMessage());
            return;
        }
        final int tokens = (Integer) tokenLimit.getValue();
        final int parallel = (Integer) parallelRequests.getValue();
        final int sections = (Integer) sectionBars.getValue();
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
        activeModels = models;
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                try {
                    recordModels(output, "Komposition", models);
                    Path arrangement = composeIntoPiece(new GemmaMusicComposer(models, tokens, parallel).sectionBars(sections),
                            prompt, output, chosen, plannedSpec(models, tokens, prompt, spec, fixed, output, progress),
                            progress);
                    return renderArrangement(arrangement, output, settings, progress);
                } finally {
                    activeModels = null;
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
                                  final TrackArrangement tracks,
                                  final com.aresstack.audio.studio.application.CompositionSpec spec,
                                  final Progress progress) throws Exception {
        return com.aresstack.audio.studio.application.ComposingIntoPiece.run(
                () -> composer.compose(prompt, output, tracks, spec, progress), output, prompt, songs, pieceModel);
    }

    /** First ask the AI only for a proposal; optionally continue automatically. */
    private void proposeInstruments(final boolean autopilot) {
        final com.aresstack.audio.studio.ai.ModelLineup models;
        try {
            models = startModels();
        } catch (RuntimeException invalid) {
            message(invalid.getMessage());
            return;
        }
        final Path output = Paths.get(directory.getText().trim());
        final String prompt = wishForAi();
        final com.aresstack.audio.studio.application.CompositionSpec spec;
        final java.util.Set<String> fixed = specFields.fixed();
        try {
            spec = specFields.spec();
        } catch (RuntimeException invalid) {
            message(invalid.getMessage());
            return;
        }
        final int tokens = (Integer) tokenLimit.getValue();
        final int parallel = (Integer) parallelRequests.getValue();
        final int sections = (Integer) sectionBars.getValue();
        final int grow = (Integer) growBars.getValue();
        final int linesPerRequest = (Integer) templateLines.getValue();
        final int fewest = (Integer) voicesMin.getValue();
        final int most = Math.max(fewest, (Integer) voicesMax.getValue());
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
        activeModels = models;
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                try {
                    recordModels(output, autopilot ? "Autopilot" : "Instrumentenvorschlag", models);
                    if (autopilot) {
                        // The AI decides everything: the same steps the production of an album runs.
                        PieceAutopilot pilot = new PieceAutopilot(registry, models, tokens, parallel, rendering).sectionBars(sections).growBars(grow)
                                .voices(fewest, most).templateLines(linesPerRequest);
                        return composeIntoPiece(pilot, prompt, output,
                                plannedSpec(models, tokens, prompt, spec, fixed, output, progress), stageBefore, progress);
                    }
                    final InstrumentProposalService.Staged staged = new InstrumentProposalService(
                            models.model(com.aresstack.audio.studio.ai.ModelRoles.INSTRUMENTS), tokens)
                            .voices(fewest, most).proposeStaged(prompt, output, progress);
                    final TrackArrangement selected = withPreferredSoundbanks(staged.tracks());
                    SwingUtilities.invokeAndWait(new Runnable() {
                        @Override
                        public void run() {
                            endChoosing(true);
                            trackTable.setArrangement(selected);
                            stagePanel.setProposal(staged.scene());
                        }
                    });
                    progress.update("Instrumentenvorschlag bereit. "
                            + "Du kannst die Tabelle ändern und danach auf 'Aus Tabelle komponieren' klicken.");
                    return null;
                } finally {
                    activeModels = null;
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
                                  com.aresstack.audio.studio.application.CompositionSpec spec,
                                  com.aresstack.audio.studio.stage.SpatialScene stageBefore,
                                  Progress progress) throws Exception {
        return pilot.run(prompt, output, spec, stageBefore, songs, pieceModel, progress,
                (selected, staged) -> SwingUtilities.invokeAndWait(() -> {
                    endChoosing(true);
                    trackTable.setArrangement(selected);
                    scene = staged;
                    showStage();
                    trackTable.refreshStage();
                })).rendered();
    }

    /**
     * The spec a piece is composed with: the advanced settings the user set, and for those left
     * automatic what the AI chooses from the description (the planning of an album, for one
     * title). What it chose is shown in the fields.
     */
    private com.aresstack.audio.studio.application.CompositionSpec plannedSpec(
            com.aresstack.audio.studio.ai.ModelLineup models, int tokens, String prompt,
            com.aresstack.audio.studio.application.CompositionSpec shown, java.util.Set<String> fixed, Path output,
            Progress progress) throws IOException {
        final com.aresstack.audio.studio.application.CompositionSpec used =
                com.aresstack.audio.studio.album.PieceSpecPlanner.plan(
                        models.model(com.aresstack.audio.studio.ai.ModelRoles.PLANNING), tokens, prompt, shown, fixed,
                        output, progress);
        if (!used.equals(shown)) {
            progress.update("Gewählt: " + used.bars() + " Takte, " + used.bpm() + " BPM, " + used.key().germanName()
                    + ", " + used.numerator() + "/" + used.denominator() + ".");
        }
        SwingUtilities.invokeLater(() -> specFields.showChosen(used));
        return used;
    }

    private TrackArrangement withPreferredSoundbanks(TrackArrangement original) {
        return PieceAutopilot.withPreferredSoundbanks(original, registry);
    }

    /**
     * The models of a production that starts now: the current choice is taken over unchanged, so
     * that choosing other models while it runs only concerns the next one. One client per model;
     * answers kept before origins were written down count as the base model's.
     */
    private com.aresstack.audio.studio.ai.ModelLineup startModels() {
        return startModels(currentAssignment());
    }

    /** The models of this assignment, each with the connection of the settings. */
    private com.aresstack.audio.studio.ai.ModelLineup startModels(
            final com.aresstack.audio.studio.ai.ModelAssignment fixed) {
        final String url = endpoint.getText().trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Trage unter 3 · Einstellungen die KIPITZ-URL ein.");
        }
        char[] secret = apiKey.getPassword();
        final String key = new String(secret);
        Arrays.fill(secret, (char) 0);
        final AudioProxyRoutes routes = configureNetworkRoutes();
        if (!String.valueOf(fixed).equals(modelsSaid)) {
            modelsSaid = String.valueOf(fixed);
            append("KI-Modelle: " + fixed);
        }
        return new com.aresstack.audio.studio.ai.ModelLineup(fixed,
                name -> new KipitzChatClient(url, key,
                        com.aresstack.audio.studio.ai.ModelLineup.modelIn(name), routes)
                        .reasoningEffort(com.aresstack.audio.studio.ai.ModelLineup.effortIn(name))
                        .legacyModel(fixed.base())
                        .longerAnswers(longerAnswers, longerAnswerFactor)
                        // Every request to a model lights the AI lamp, whoever asks (an album too).
                        .activity(change -> SwingUtilities.invokeLater(() -> {
                            aiRequests += change;
                            showAiLamp();
                        }))
                        // A connection that breaks off or is tried again is said at once, in the protocol and below.
                        .troubles(line -> SwingUtilities.invokeLater(() -> troubleDigest.add(line))));
    }

    /** The assignment as chosen now: the roles as set, the base model from the settings page. */
    private com.aresstack.audio.studio.ai.ModelAssignment currentAssignment() {
        String base = model.getText().trim();
        return modelChoices.assignment().withBase(base.isEmpty() ? AiDefaults.MODEL : base);
    }

    /** Writes down in the piece which model each role has for this production. */
    private void recordModels(Path folder, String what, com.aresstack.audio.studio.ai.ModelLineup models) {
        try {
            com.aresstack.audio.studio.application.ModelsUsed.record(folder, what, models.assignment());
        } catch (IOException failed) {
            append("Die verwendeten Modelle ließen sich nicht vermerken: " + failed.getMessage());
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

    /**
     * Asks the service for its models and offers them in the model choices.
     *
     * @param ask whether the user then picks the base model, as on the settings page
     */
    private void loadModelNames(final boolean ask) {
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
                    modelChoices.setModels(models);
                    albumModelChoices.setModels(models);
                    append(models.size() + " Modelle geladen; sie stehen in der Modellauswahl der Komposition und der Alben.");
                    if (!ask) {
                        return;
                    }
                    Object choice = JOptionPane.showInputDialog(AudioStudioFrame.this,
                            "Basismodell wählen (gilt für alle Rollen ohne eigenes Modell):", "KIPITZ-Modelle",
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

    /**
     * Renders the piece and mixes its stage. A piece with a model is rendered from the model as
     * it is saved (rendering never brings the model in step with the old files; only composing
     * and renaming do), with the instruments chosen in the table: only the parts that changed
     * are rendered, the others come from the stem cache. An arrangement that is
     * not the open piece (a file chosen from elsewhere) or a piece whose model cannot be used is
     * rendered from its file as before.
     */
    private Path renderArrangement(Path source, Path destination, RenderSettings settings,
                                   Progress progress) throws Exception {
        if (source.toAbsolutePath().normalize()
                .equals(destination.resolve("arrangement.tsv").toAbsolutePath().normalize())) {
            java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece =
                    pieceModel.of(destination);
            if (piece.isPresent()) {
                return new com.aresstack.audio.studio.application.PieceRendering(registry).withSound(stageSound).render(piece.get(),
                        librariesOf(settings), settings.sf2Engine, settings.fluidSynthExecutable, settings.sfizzExecutable,
                        settings.programs, progress).mix();
            }
        }
        progress.update("Dieses Arrangement wird aus seiner Datei gerendert (kein Werkmodell).");
        return new StudioMusicRenderer(registry).render(source, destination, settings.assignments,
                settings.programs, settings.sf2Engine,
                settings.fluidSynthExecutable, settings.sfizzExecutable, progress);
    }

    /** The sound bank chosen in the table for each track; "builtin" is the Java sound. */
    private static Map<String, String> librariesOf(RenderSettings settings) {
        Map<String, String> libraries = new HashMap<String, String>();
        for (String track : settings.programs.keySet()) {
            InstrumentLibrary bank = settings.assignments.get(track);
            libraries.put(track, bank == null ? "builtin" : bank.id());
        }
        return libraries;
    }

    /**
     * The saved model of the open piece, for the changes the user asks for by name: taking a
     * proposal, undo, redo, and the revisions. Empty for a piece without a model.
     */
    private java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> savedPiece() {
        String chosen = directory.getText().trim();
        return chosen.isEmpty()
                ? java.util.Optional.<com.aresstack.audio.studio.application.ProjectSession>empty()
                : pieceModel.of(Paths.get(chosen));
    }

    private boolean busy() {
        if (openProposal != null) {
            showPage("deck");
            message("Erst den Vorschlag der KI über den Spuren übernehmen oder verwerfen.");
            return true;
        }
        if (worker != null && !worker.isDone()) {
            message("Erst den laufenden Vorgang beenden oder abbrechen.");
            return true;
        }
        return false;
    }

    /**
     * Has the AI compose one track anew as a proposal: the composer writes into a folder of its
     * own, the result is rendered there to be heard, and the piece changes only if the user takes it.
     */
    private void proposeAgain(final String trackId,
                              final com.aresstack.audio.studio.application.ProjectSession piece) {
        offerPiece = piece;
        final TrackArrangement chosen;
        final com.aresstack.audio.studio.ai.ModelLineup models;
        final RenderSettings settings;
        try {
            chosen = trackTable.arrangement();
            models = startModels();
            settings = captureRenderSettings(chosen);
        } catch (Exception invalid) {
            message(invalid.getMessage());
            return;
        }
        final String prompt = wishForAi();
        final int tokens = (Integer) tokenLimit.getValue();
        final int parallel = (Integer) parallelRequests.getValue();
        final int sections = (Integer) sectionBars.getValue();
        if (!saveSettings(false)) {
            return;
        }
        final String runId = "ki-" + Long.toString(System.currentTimeMillis(), 36);
        append("Spur " + trackId + " wird neu komponiert.");
        activeModels = models;
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                com.aresstack.audio.studio.application.RecompositionOffer offer = null;
                try {
                    offer = com.aresstack.audio.studio.application.RecompositionOffer.stage(piece, trackId, runId);
                    composeAside(new GemmaMusicComposer(models, tokens, parallel).sectionBars(sections), prompt, offer.staging(), chosen,
                            progress);
                    offer.propose(runId);
                    final com.aresstack.audio.studio.application.RecompositionOffer ready = offer;
                    afterWork = () -> takeRecomposition(ready);
                    return null;
                } catch (com.aresstack.audio.studio.application.EditRefusedException refused) {
                    offer.discard();
                    throw new IOException("Der Vorschlag wurde nicht angenommen, weil er mehr als die Spur "
                            + trackId + " geändert hätte: " + refused.violations());
                } catch (Exception failed) {
                    if (offer != null) {
                        offer.discard();
                    }
                    throw failed;
                } finally {
                    activeModels = null;
                }
            }
        });
    }

    /**
     * Composes into a folder beside the piece, for a proposal. Nothing of the piece is written
     * and its model is not touched; see {@link #composeIntoPiece} for composing the piece itself.
     */
    private Path composeAside(GemmaMusicComposer composer, String prompt, Path staging, TrackArrangement tracks,
                              Progress progress) throws Exception {
        return composer.compose(prompt, staging, tracks, progress);
    }

    /** The track the AI wrote anew is taken at once; "Rückgängig" brings the old one back. */
    private void takeRecomposition(com.aresstack.audio.studio.application.RecompositionOffer offer) {
        try {
            if (!offer.changesSomething()) {
                offer.discard();
                append("Die KI hat die Spur " + offer.trackId() + " so geschrieben, wie sie schon ist.");
                return;
            }
            pieceChanged("Spur " + offer.trackId() + " neu komponiert. Zurücknehmen mit „Rückgängig“ (Strg+Z).",
                    offer.accept());
        } catch (Exception failed) {
            message("Die neue Spur ließ sich nicht übernehmen: " + failed.getMessage());
        }
    }

    /** The proposal is ready: the user hears it and takes it or throws it away. */
    private void decide(final com.aresstack.audio.studio.application.RecompositionOffer offer, final Path heard) {
        try {
            if (!offer.changesSomething()) {
                offer.discard();
                append("Die KI hat die Spur " + offer.trackId() + " so geschrieben, wie sie schon ist; "
                        + "es gibt nichts zu übernehmen.");
                return;
            }
            showProposal(new ProposalPanel("Die KI hat die Spur „" + offer.trackId() + "“ neu komponiert.",
                    java.util.Collections.<String>emptyList(), java.util.Collections.<String>emptyList(), null, false,
                    new ProposalPanel.Listening() {
                        @Override
                        public void proposal() {
                            listenTo(heard, "VORSCHLAG · " + offer.trackId().toUpperCase(java.util.Locale.ROOT), 0);
                        }

                        @Override
                        public void current() {
                            playbackSelection.setSelectedItem("Mixed sampled arrangement");
                            play();
                        }

                        @Override
                        public void stop() {
                            stopPlayback();
                        }
                    }, verdict -> {
                        boolean fits = closeProposal();
                        try {
                            if (verdict == ProposalPanel.Verdict.TAKE && fits) {
                                com.aresstack.audio.composition.edit.ChangeSet changes = offer.accept();
                                pieceChanged("Vorschlag übernommen: Spur " + offer.trackId() + " neu komponiert.", changes);
                            } else {
                                offer.discard();
                                append(verdict == ProposalPanel.Verdict.TAKE ? STALE_PROPOSAL
                                        : "Vorschlag verworfen; das Stück ist unverändert.");
                            }
                        } catch (Exception failed) {
                            message("Der Vorschlag ließ sich nicht abschließen: " + failed.getMessage());
                        }
                    }));
        } catch (Exception failed) {
            message("Der Vorschlag ließ sich nicht abschließen: " + failed.getMessage());
        }
    }

    /** Asks which voice the AI shall add to the piece, and has it composed as a proposal. */
    private void addVoice() {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent()) {
            message("Erst ein Stück komponieren: eine Stimme kommt zu den vorhandenen hinzu.");
            return;
        }
        if (busy()) {
            return;
        }
        final com.aresstack.audio.studio.ai.AddVoiceRequest request =
                AddVoiceForm.ask(this, piece.get().composition());
        if (request == null) {
            return;
        }
        offerFromAi(piece.get(), "Die Stimme „" + request.name() + "“ wird hinzukomponiert.", (session, models, progress) ->
                com.aresstack.audio.studio.application.VoiceOffer.start(session,
                        com.aresstack.audio.studio.ai.VoiceComposer.with(models), request, progress));
    }

    /** Asks how the AI shall write the marked passage anew, and has it written as a proposal. */
    private void reworkRegion() {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent()) {
            message("Erst ein Stück komponieren: dann lässt sich eine Passage neu schreiben.");
            return;
        }
        if (busy()) {
            return;
        }
        final RegionForm.Asked asked = RegionForm.ask(this, piece.get().composition(), arrangementView.bars(),
                arrangementView.marked());
        if (asked == null) {
            return;
        }
        offerFromAi(piece.get(), (asked.firstBar() == asked.lastBar() ? "Takt " + asked.firstBar()
                : "Takt " + asked.firstBar() + " bis " + asked.lastBar()) + " wird neu geschrieben.", (session, model, progress) ->
                com.aresstack.audio.studio.application.RegionOffer.start(session,
                        com.aresstack.audio.studio.ai.RegionComposer.with(model), asked.request(), asked.partIds(),
                        asked.firstBar(), asked.lastBar(), progress));
    }

    /** A key above the tracks for something the AI does with the piece; every such change is a proposal first. */
    /** Takes the last change of the piece back, in sight beside the keys of the AI. */
    private final JButton undoKey = new DeckWidgets.HeadKey("↶ Rückgängig");

    private static JButton pieceAction(String label, String hint, Runnable action) {
        JButton key = new DeckWidgets.HeadKey(label);
        key.setToolTipText("KI: " + hint);
        key.addActionListener(event -> action.run());
        return key;
    }

    /** Asks how the AI shall make the piece longer, and has the new bars planned and written as a proposal. */
    private void extendPiece() {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent()) {
            message("Erst ein Stück komponieren: dann lässt es sich verlängern.");
            return;
        }
        if (busy()) {
            return;
        }
        final com.aresstack.audio.studio.ai.ContinuationRequest request =
                ExtendForm.ask(this, piece.get().composition(), arrangementView.bars(), (Integer) growBars.getValue());
        if (request == null) {
            return;
        }
        offerFromAi(piece.get(), "Das Stück wird um " + request.bars()
                + (request.bars() == 1 ? " Takt" : " Takte") + " verlängert ("
                + request.preset().label() + ").",
                (session, model, progress) -> com.aresstack.audio.studio.application.ExtensionOffer.start(session,
                        com.aresstack.audio.studio.ai.ContinuationComposer.with(model, partsAtOnce()), request, progress));
    }

    /** Asks which bars the AI shall put into the piece and where, and has them written as a proposal (C4). */
    private void insertBars() {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent()) {
            message("Erst ein Stück komponieren: dann lassen sich Takte einfügen.");
            return;
        }
        if (busy()) {
            return;
        }
        ArrangementView.Range marked = arrangementView.marked();
        final com.aresstack.audio.studio.ai.ContinuationRequest request = ExtendForm.askInsert(this,
                piece.get().composition(), arrangementView.bars(), marked == null ? 1 : marked.firstBar());
        if (request == null) {
            return;
        }
        offerFromAi(piece.get(), "Vor Takt " + request.insertBeforeBar() + " werden " + request.bars()
                + (request.bars() == 1 ? " Takt" : " Takte") + " eingefügt.",
                (session, model, progress) -> com.aresstack.audio.studio.application.ExtensionOffer.start(session,
                        com.aresstack.audio.studio.ai.ContinuationComposer.with(model, partsAtOnce()), request, progress));
    }

    /** Asks how the AI shall orchestrate the marked bars anew, and has it done as a proposal (C4). */
    private void reorchestrate() {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent()) {
            message("Erst ein Stück komponieren: dann lässt es sich umorchestrieren.");
            return;
        }
        if (busy()) {
            return;
        }
        final com.aresstack.audio.studio.ai.ReorchestrationRequest request = ReorchestrateForm.ask(this,
                piece.get().composition(), arrangementView.bars(), arrangementView.marked());
        if (request == null) {
            return;
        }
        offerFromAi(piece.get(), (request.firstBar() == request.lastBar() ? "Takt " + request.firstBar()
                : "Takt " + request.firstBar() + " bis " + request.lastBar()) + " wird umorchestriert ("
                + request.preset().label() + ").",
                (session, model, progress) -> com.aresstack.audio.studio.application.ReorchestrationOffer.start(
                        session, com.aresstack.audio.studio.ai.OrchestrationComposer.with(model, partsAtOnce()), request, progress));
    }

    /**
     * The user set the intensity of a section in the section bar: a change of the form only, by
     * the edit core, undoable. No note changes and nothing is rendered; the AI follows the
     * intensity the next time it continues, inserts or orchestrates.
     */
    private void sectionIntensity(SectionMark section, double intensity) {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent() || busy()) {
            return;
        }
        try {
            com.aresstack.audio.studio.application.IntensityEdit.apply(piece.get(),
                    java.util.Map.of(section.id(), intensity));
            append("Intensität von „" + section.name() + "“ auf " + Math.round(intensity * 100)
                    + " % gesetzt (unter „Rückgängig“ zurückzunehmen). Die Noten sind unverändert.");
        } catch (Exception failed) {
            message("Die Intensität ließ sich nicht setzen: " + failed.getMessage());
        }
        showSections();
    }

    /**
     * The user changed the form in the section bar: bars deleted, a section dragged, empty bars put
     * in. One undoable step by the edit core; what sounds different is rendered again. What cannot
     * be done is said in the status line, without a dialog.
     */
    private void editForm(java.util.function.Function<com.aresstack.audio.composition.Composition,
            com.aresstack.audio.studio.application.SectionEdits.SectionEdit> making) {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent() || busy()) {
            return;
        }
        com.aresstack.audio.studio.application.SectionEdits.SectionEdit edit = making.apply(piece.get().composition());
        if (edit.refusal() != null) {
            append("WARN " + edit.refusal());
            return;
        }
        if (edit.isEmpty()) {
            return;
        }
        try {
            stopPlayback();
            com.aresstack.audio.composition.edit.ChangeSet changes = piece.get().apply(edit.patch(), edit.scope(), edit.mix());
            arrangementView.mark(null);
            pieceChanged(edit.patch().description() + ". Zurücknehmen mit „Rückgängig“ (Strg+Z).", changes);
        } catch (com.aresstack.audio.studio.application.EditRefusedException refused) {
            append("WARN Nicht möglich: " + refused.violations());
        } catch (Exception failed) {
            append("WARN Die Änderung ließ sich nicht speichern: " + failed.getMessage());
        }
        showSections();
    }

    /**
     * Has the AI write the marked bars of every part: a gap is filled, music is written anew. It
     * hears what comes before and after them and builds a transition that fits both ends; a gap
     * becomes a section "Übergang" in the same step. Taken at once, "Rückgängig" takes it back.
     */
    private void generateBars(int firstBar, int endBar) {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent() || busy()) {
            return;
        }
        com.aresstack.audio.composition.Composition shown = piece.get().composition();
        final List<String> parts = com.aresstack.audio.studio.application.SectionEdits.playingParts(shown);
        if (parts.isEmpty()) {
            append("WARN Das Stück hat keine Stimme, die die KI hier schreiben könnte.");
            return;
        }
        final com.aresstack.audio.studio.ai.RegionRequest request = com.aresstack.audio.studio.ai.RegionRequest.replace(
                com.aresstack.audio.composition.edit.Selection.bars(shown, parts, firstBar + 1, endBar),
                com.aresstack.audio.studio.application.SectionEdits.transitionInstruction(shown, firstBar, endBar));
        final List<com.aresstack.audio.composition.Section> form =
                com.aresstack.audio.studio.application.SectionEdits.filledForm(shown, firstBar, endBar,
                        com.aresstack.audio.composition.Ids.random());
        boolean silent = com.aresstack.audio.studio.application.SectionEdits.isSilent(shown, firstBar, endBar);
        String bars = endBar - firstBar == 1 ? "Takt " + (firstBar + 1) : "Takt " + (firstBar + 1) + " bis " + endBar;
        offerFromAi(piece.get(), bars + (silent ? " wird von der KI gefüllt" : " wird von der KI neu geschrieben")
                        + ": ein Übergang von dem, was davor klingt, zu dem, was danach kommt.",
                (session, model, progress) -> com.aresstack.audio.studio.application.RegionOffer.start(session,
                        com.aresstack.audio.studio.ai.RegionComposer.with(model), request, parts, firstBar + 1, endBar,
                        form, progress));
    }

    /**
     * The user sets how the piece is played: tempo with jumps and glides, dynamic marks and
     * hairpins, what the levels and articulations stand for, and articulations of notes. Each
     * confirmed change is one undoable step; what sounds different is rendered again.
     */
    private void performance() {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent()) {
            message("Erst ein Stück komponieren: dann hat es Noten, deren Vortrag sich einstellen lässt.");
            return;
        }
        if (busy()) {
            return;
        }
        final com.aresstack.audio.studio.application.ProjectSession session = piece.get();
        PerformanceDialog.Result chosen = PerformanceDialog.show(this, session.composition(),
                (partId, first, last, articulation) -> {
                    com.aresstack.audio.composition.edit.ChangeSet changes =
                            com.aresstack.audio.studio.application.PerformanceEdit.articulate(session, partId, first,
                                    last, articulation);
                    if (!changes.isEmpty()) {
                        pieceChanged((articulation == null ? "Artikulation entfernt" : articulation.germanName())
                                + " für " + partId + ", Takt " + first + " bis " + last
                                + " (unter „Rückgängig“ zurückzunehmen).", changes);
                    }
                });
        if (chosen == null) {
            return;
        }
        try {
            com.aresstack.audio.composition.edit.ChangeSet changes =
                    com.aresstack.audio.studio.application.PerformanceEdit.apply(session, chosen.tempos(),
                            chosen.dynamics());
            if (!changes.isEmpty()) {
                pieceChanged("Tempo und Dynamik gesetzt (unter „Rückgängig“ zurückzunehmen); was anders klingt, wird neu "
                        + "gerendert.", changes);
            }
        } catch (Exception failed) {
            message("Tempo und Dynamik ließen sich nicht setzen: " + failed.getMessage());
        }
    }

    /** Lays an intensity curve over the sections of the marked bars, or of the whole piece; the form only. */
    private void intensityOverBars() {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent()) {
            message("Erst ein Stück komponieren: dann hat es Abschnitte, über die ein Verlauf gelegt werden kann.");
            return;
        }
        if (busy()) {
            return;
        }
        ArrangementView.Range marked = arrangementView.marked();
        int first = marked == null ? 1 : marked.firstBar();
        int last = marked == null ? Math.max(1, arrangementView.bars()) : marked.lastBar();
        String[] names = ExtendForm.curveNames();
        Object chosen = JOptionPane.showInputDialog(this, "Intensitätsverlauf über Takt " + first + " bis " + last
                        + ".\nNur die Form ändert sich: keine Note, nichts wird neu gerendert.", "Intensitätsverlauf",
                JOptionPane.PLAIN_MESSAGE, null, java.util.Arrays.copyOfRange(names, 1, names.length), names[1]);
        if (chosen == null) {
            return;
        }
        try {
            com.aresstack.audio.studio.application.IntensityEdit.apply(piece.get(), first, last,
                    ExtendForm.curveOf(java.util.Arrays.asList(names).indexOf(chosen)));
            append("Intensitätsverlauf „" + chosen + "“ über Takt " + first + " bis " + last
                    + " gelegt (unter „Rückgängig“ zurückzunehmen). Die Noten sind unverändert.");
        } catch (Exception failed) {
            message("Der Verlauf ließ sich nicht setzen: " + failed.getMessage());
        }
        showSections();
    }

    /**
     * Shows the form of the saved piece above the lanes and in the score: its sections with name,
     * function, intensity and chords. It only reads the model; a piece without one shows no form.
     */
    private void showSections() {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        List<SectionMark> marks = SectionMark.of(piece.isPresent() ? piece.get().composition() : null);
        sectionBar.setSections(marks);
        scoreView.setSections(marks);
        undoKey.setEnabled(piece.isPresent() && piece.get().canUndo());
        undoKey.setToolTipText(piece.isPresent() && piece.get().canUndo()
                ? "Rückgängig: " + piece.get().undoDescription() + " (Strg+Z)"
                : "Nimmt die letzte Änderung am Stück zurück (Strg+Z)");
    }

    /** How many parts the AI writes at the same time when it works on a piece: the setting "Parallel". */
    private int partsAtOnce() {
        Object allowed = parallelRequests.getValue();
        return allowed instanceof Integer ? Math.max(1, (Integer) allowed) : 4;
    }

    /** Has the AI make a proposal for the saved piece; what was asked for decides how. */
    private interface OfferStart {
        com.aresstack.audio.studio.application.AiOffer start(
                com.aresstack.audio.studio.application.ProjectSession piece,
                com.aresstack.audio.studio.ai.ModelLineup models, Progress progress) throws Exception;
    }

    /**
     * Runs a request to the AI in the background and offers what comes of it: the proposal is
     * rendered in its own folder to be heard, and the piece changes only if the user takes it.
     */
    private void offerFromAi(final com.aresstack.audio.studio.application.ProjectSession piece, String said,
                             final OfferStart starter) {
        offerPiece = piece;
        final com.aresstack.audio.studio.ai.ModelLineup models;
        final RenderSettings settings;
        try {
            models = startModels();
            settings = captureRenderSettings();
        } catch (Exception invalid) {
            message(invalid.getMessage());
            return;
        }
        if (!saveSettings(false)) {
            return;
        }
        append(said);
        activeModels = models;
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                com.aresstack.audio.studio.application.AiOffer offer = null;
                try {
                    recordModels(piece.folder(), "KI-Vorschlag", models);
                    offer = starter.start(piece, models, progress);
                    // What the AI wrote is taken at once (PO 2026-11-15): no box to decide in, the
                    // step can be taken back with "Rückgängig".
                    final com.aresstack.audio.studio.application.AiOffer ready = offer;
                    afterWork = () -> takeOffer(ready);
                    return null;
                } catch (com.aresstack.audio.studio.application.EditRefusedException refused) {
                    throw new IOException("Der Vorschlag wurde nicht angenommen, weil er mehr geändert hätte als"
                            + " verlangt: " + refused.violations());
                } catch (Exception failed) {
                    if (offer != null) {
                        offer.discard();
                    }
                    throw failed;
                } finally {
                    activeModels = null;
                }
            }
        });
    }

    /** Takes what the AI wrote into the piece: one step that "Rückgängig" takes back, the plan goes to the protocol. */
    private void takeOffer(com.aresstack.audio.studio.application.AiOffer offer) {
        try {
            String what = offer.what();
            for (String note : offer.notes()) {
                append("  " + note);
            }
            for (String lost : offer.toConfirm()) {
                append("  Das Original verliert dabei: " + lost);
            }
            offer.confirm();
            pieceChanged(what + " Zurücknehmen mit „Rückgängig“ (Strg+Z).", offer.accept());
        } catch (Exception failed) {
            try {
                offer.discard();
            } catch (Exception left) {
                // the piece is as it was either way
            }
            message("Das Ergebnis der KI ließ sich nicht übernehmen: " + failed.getMessage());
        }
    }

    /** Renders the preview of an offer (only what is new; the rest comes from the stems of the piece) and then asks. */
    private void hearOffer(final com.aresstack.audio.studio.application.AiOffer offer,
                           final com.aresstack.audio.studio.ai.ModelLineup models,
                           final RenderSettings settings, Progress progress) throws Exception {
        Path preview = offer.preview();
        progress.update("Der Vorschlag wird zum Anhören gerendert ...");
        Map<String, Integer> programs = new HashMap<String, Integer>(settings.programs);
        programs.putAll(offer.newPrograms());
        com.aresstack.audio.studio.application.PieceRendering.Outcome heard =
                new com.aresstack.audio.studio.application.PieceRendering(registry).render(preview, offer.proposed(),
                        librariesOf(settings), settings.sf2Engine, settings.fluidSynthExecutable,
                        settings.sfizzExecutable, programs, progress);
        final Path whole = heard.mix();
        final Path alone = offer.soloPartId() == null ? null : heard.stems().stems().get(offer.soloPartId());
        afterWork = () -> decideOffer(offer, whole, alone, models, settings);
    }

    /** The offer is ready: the user hears it, has it made again, takes it or throws it away. */
    private void decideOffer(final com.aresstack.audio.studio.application.AiOffer offer, final Path whole,
                             final Path alone, final com.aresstack.audio.studio.ai.ModelLineup models,
                             final RenderSettings settings) {
        try {
            showProposal(new ProposalPanel(offer.what(), offer.notes(), offer.toConfirm(),
                    alone == null ? null : offer.soloName(), true, new ProposalPanel.Listening() {
                        @Override
                        public void proposal() {
                            listenTo(whole, "VORSCHLAG", offer.listenFromSecond());
                        }

                        @Override
                        public void alone() {
                            listenTo(alone, "VORSCHLAG · NUR DAS NEUE", offer.listenFromSecond());
                        }

                        @Override
                        public void current() {
                            playbackSelection.setSelectedItem("Mixed sampled arrangement");
                            play();
                        }

                        @Override
                        public void stop() {
                            stopPlayback();
                        }
                    }, verdict -> offerDecided(offer, verdict, models, settings)));
        } catch (Exception failed) {
            message("Der Vorschlag ließ sich nicht abschließen: " + failed.getMessage());
        }
    }

    private static final String STALE_PROPOSAL = "Das Stück wurde geändert, seit die KI den Vorschlag gemacht hat;"
            + " er passt nicht mehr und wurde verworfen. Das Stück ist, wie es war.";

    /** The user answered the proposal above the tracks: it is taken, made again or thrown away. */
    private void offerDecided(final com.aresstack.audio.studio.application.AiOffer offer, ProposalPanel.Verdict verdict,
                              final com.aresstack.audio.studio.ai.ModelLineup models, final RenderSettings settings) {
        boolean fits = closeProposal();
        try {
            if (verdict == ProposalPanel.Verdict.TAKE && fits) {
                String what = offer.what();
                offer.confirm(); // the panel lets "Übernehmen" through only after the user ticked what the original loses
                pieceChanged("Vorschlag übernommen. " + what, offer.accept());
            } else if (verdict == ProposalPanel.Verdict.AGAIN && fits) {
                append("Die KI schreibt den Vorschlag noch einmal, anders.");
                activeModels = models;
                start(new Operation() {
                    @Override
                    public Path execute(Progress progress) throws Exception {
                        try {
                            offer.again(progress);
                            hearOffer(offer, models, settings, progress);
                            return null;
                        } catch (Exception failed) {
                            offer.discard();
                            throw failed;
                        } finally {
                            activeModels = null;
                        }
                    }
                });
            } else {
                offer.discard();
                append(verdict == ProposalPanel.Verdict.DROP ? "Vorschlag verworfen; das Stück ist unverändert."
                        : STALE_PROPOSAL);
            }
        } catch (Exception failed) {
            message("Der Vorschlag ließ sich nicht abschließen: " + failed.getMessage());
        }
    }

    /** Shows a proposal above the tracks of the arrangement and brings that page to the front. */
    private void showProposal(ProposalPanel proposal) {
        openProposal = proposal;
        offerBase = offerPiece == null ? null : offerPiece.composition();
        proposalHolder.removeAll();
        proposalHolder.add(proposal, BorderLayout.CENTER);
        proposalHolder.revalidate();
        proposalHolder.repaint();
        showPage("deck");
        append("Der Vorschlag der KI steht auf der Seite Arrangement über den Spuren: anhören, übernehmen oder verwerfen.");
    }

    /**
     * Takes the answered proposal from the page and lets the player go of its preview.
     *
     * @return whether the piece still has the notes the proposal was made for
     */
    private boolean closeProposal() {
        openProposal = null;
        proposalHolder.removeAll();
        proposalHolder.revalidate();
        proposalHolder.repaint();
        stopPlayback();
        showPlaybackChoice(); // the player lets go of the preview before its folder goes
        boolean fits = offerPiece == null || offerBase == null || offerBase.equals(offerPiece.composition());
        offerBase = null;
        return fits;
    }

    /** Plays a file that is not one of the playback choices: the preview of a proposal. */
    private void listenTo(Path file, String name, double fromSecond) {
        try {
            player.open(file);
            arrangementView.setMix(player.envelope(), "Vorschlag", null, player.length());
            lcd.setSource(name, true);
            if (fromSecond > 0 && player.length() > fromSecond) {
                player.seek(fromSecond / player.length()); // where the new bars begin, a little before
            }
            player.play();
        } catch (Exception error) {
            message("Der Vorschlag lässt sich nicht abspielen: " + error.getMessage());
        }
    }

    /**
     * The saved piece was changed on the user's word (a proposal taken, undo, redo, a revision
     * brought back): the tracks, the notes and the sound follow. Only the parts that sound
     * different are rendered; the others come from the stem cache.
     */
    private void pieceChanged(String said, com.aresstack.audio.composition.edit.ChangeSet changes) {
        append(said);
        setStatus(said);
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent()) {
            return;
        }
        final Path folder = piece.get().folder();
        showTracksOf(piece.get().composition());
        arrangementFile.setText(folder.resolve("arrangement.tsv").toString());
        showPlaybackChoice();
        answersFollow(piece.get(), changes);
        answersState = piece.get().composition();
        // A step may change nothing but the mix (a revision that differs in the mix alone): the
        // stage follows, and the tracks that are rendered are mixed again on it.
        boolean mixMoved = stageFollows(piece.get());
        if (changes == null || changes.isEmpty()) {
            if (mixMoved) {
                remix();
            }
            return;
        }
        final RenderSettings settings;
        try {
            settings = captureRenderSettings();
        } catch (RuntimeException invalid) {
            append("WARN: Nicht neu gerendert: " + invalid.getMessage());
            return;
        }
        start(new Operation() {
            @Override
            public Path execute(Progress progress) throws Exception {
                return renderArrangement(folder.resolve("arrangement.tsv"), folder, settings, progress);
            }
        });
    }

    /**
     * The stage of the window takes what a step of the user changed in the mix of the model, and
     * is saved with the piece ({@code spatial-scene.json}), so that the next mix is the mix of
     * the state that was brought back.
     *
     * @return whether the mix of the model changed
     */
    private boolean stageFollows(com.aresstack.audio.studio.application.ProjectSession piece) {
        java.util.Optional<com.aresstack.audio.studio.stage.SpatialScene> moved =
                stageFollowsMix.follow(piece, scene);
        if (!moved.isPresent()) {
            return false;
        }
        scene = moved.get();
        saveScene();
        trackTable.refreshStage();
        showStage();
        player.movementsChanged(); // the movements of the mix that came back are heard live too
        return true;
    }

    /** The kept answers of the AI go with the state of the piece: undo of a taken proposal brings the earlier answer back. */
    private void answersFollow(com.aresstack.audio.studio.application.ProjectSession piece,
                               com.aresstack.audio.composition.edit.ChangeSet changes) {
        try {
            for (String track : com.aresstack.audio.studio.application.AnswerShelf.follow(piece.folder(),
                    piece.composition(), changes)) {
                append("Die Antwort der KI für die Spur " + track + " gehört wieder zu diesem Stand des Stücks.");
            }
        } catch (IOException failed) {
            append("WARN: Die Antworten der KI ließen sich nicht nachführen: " + failed.getMessage());
        }
    }

    /**
     * Shows the tracks the model has, if they are not the ones in the table: a track the table
     * knows keeps what was chosen for it there, a track it does not know comes as the model has it.
     */
    private void showTracksOf(com.aresstack.audio.composition.Composition composition) {
        Map<String, MusicTrack> shown = new HashMap<String, MusicTrack>();
        List<String> ids = new ArrayList<String>();
        for (MusicTrack track : trackTable.tracks()) {
            shown.put(track.id(), track);
            ids.add(track.id());
        }
        List<MusicTrack> wanted = new ArrayList<MusicTrack>();
        List<String> wantedIds = new ArrayList<String>();
        for (com.aresstack.audio.composition.Part part : composition.parts()) {
            MusicTrack known = shown.get(part.id());
            wanted.add(known != null ? known : new MusicTrack(part.id(), part.role(), part.instrument(),
                    part.program(), part.libraryId() == null ? "builtin" : part.libraryId()));
            wantedIds.add(part.id());
        }
        if (wanted.isEmpty() || wantedIds.equals(ids)) {
            return;
        }
        try {
            trackTable.setArrangement(new TrackArrangement(wanted));
            tracksChanged();
        } catch (RuntimeException invalid) {
            append("WARN: Die Spuren des Stücks ließen sich nicht anzeigen: " + invalid.getMessage());
        }
    }

    /** Takes back the last change of the piece, or does it again. */
    private void undoPiece(boolean again) {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent() || !(again ? piece.get().canRedo() : piece.get().canUndo()) || busy()) {
            return;
        }
        try {
            String what = again ? piece.get().redoDescription() : piece.get().undoDescription();
            stopPlayback();
            pieceChanged((again ? "Wiederholt: " : "Rückgängig: ") + what,
                    again ? piece.get().redo() : piece.get().undo());
        } catch (Exception failed) {
            message((again ? "Das ließ sich nicht wiederholen: " : "Das ließ sich nicht zurücknehmen: ")
                    + failed.getMessage());
        }
    }

    /** The list of revisions: one can be brought back, the piece as it is can be kept as a new one. */
    private void showRevisions() {
        final java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> piece = savedPiece();
        if (!piece.isPresent()) {
            message("Dieses Stück hat noch keine Revisionen: erst komponieren.");
            return;
        }
        if (busy()) {
            return;
        }
        com.aresstack.audio.composition.edit.Revision chosen = new RevisionsDialog(this, new RevisionsDialog.Piece() {
            @Override
            public List<com.aresstack.audio.composition.edit.Revision> revisions() throws Exception {
                return piece.get().revisions();
            }

            @Override
            public void mark(String words) throws Exception {
                piece.get().markRevision(words);
                append("Stand gemerkt: " + words);
            }
        }).ask();
        if (chosen == null) {
            return;
        }
        try {
            stopPlayback();
            pieceChanged("Revision " + chosen.sequence() + " wiederhergestellt (" + chosen.description() + ").",
                    piece.get().restoreRevision(chosen.id()));
        } catch (Exception failed) {
            message("Die Revision ließ sich nicht wiederherstellen: " + failed.getMessage());
        }
    }

    private void createNewComposition() {
        // The new piece goes into an album: the one chosen above, another one, or a new one by its title.
        // Left empty, it lands in the unnamed album.
        // Left empty, the piece gets its name from the plan of the AI, as before albums.
        javax.swing.JTextField name = new javax.swing.JTextField("", 24);
        JComboBox<String> album = new JComboBox<String>();
        album.setEditable(true);
        // Two unnamed albums are told apart by a number, as in the tree.
        java.util.Map<String, String> labels = AlbumMembership.labels(albumMembers.albums().albums());
        for (String label : labels.values()) {
            album.addItem(label);
        }
        album.setSelectedItem(currentAlbum == null || !labels.containsKey(currentAlbum) ? "" : labels.get(currentAlbum));
        int answer = JOptionPane.showConfirmDialog(this, new Object[]{
                        "Wie soll das neue Stück heißen? Leer: den Namen gibt ihm der Plan der KI.", name,
                        "In welches Album? Ein neuer Titel legt ein neues Album an; leer: ein neues unbenanntes Album.", album},
                "Neues Stück", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        Object typed = album.getEditor().getItem();
        String albumTitle = typed == null ? "" : typed.toString().trim();
        try {
            keepSong();
            String wanted = name.getText().trim();
            Path folder = wanted.isEmpty() ? songs.createUntitled("") : songs.create(wanted);
            String target = intoAlbum(folder, albumTitle);
            directory.setText(folder.toString());
            arrangementFile.setText("");
            loadArrangementForCurrentDirectory(new Properties());
            saveSettings(false);
            refreshSongs();
            append("Neues Stück angelegt: " + (wanted.isEmpty() ? "seinen Namen gibt ihm der Plan der KI"
                    : songs.name(folder)) + (target == null ? "" : " (Album „" + target + "“)"));
        } catch (IOException failed) {
            message("Das Stück ließ sich nicht anlegen: " + failed.getMessage());
        }
    }

    /**
     * Puts a new piece into an album and makes that album the chosen one: the album with this
     * title, a new album if no album has it, the unnamed album for an empty title, and for
     * {@code null} the album chosen above. Returns the title of the album, or {@code null} if the
     * piece could not be put into one; it then lands in the unnamed album at the next look.
     */
    private String intoAlbum(Path piece, String title) {
        try {
            Album target = null;
            java.util.List<Album> all = albumMembers.albums().albums();
            if (title == null) {
                target = currentAlbum == null ? null : albumMembers.albums().album(currentAlbum).orElse(null);
            } else if (!title.isEmpty()) {
                // A name as the list shows it ("Unbenanntes Album 2") names exactly that album.
                for (java.util.Map.Entry<String, String> label : AlbumMembership.labels(all).entrySet()) {
                    if (label.getValue().equals(title)) {
                        target = albumMembers.albums().album(label.getKey()).orElse(null);
                    }
                }
                for (Album each : target != null ? java.util.List.<Album>of() : all) {
                    if (each.title().equalsIgnoreCase(title)) {
                        target = each;
                        break;
                    }
                }
                if (target == null) {
                    target = albumMembers.albums().create(title,
                            com.aresstack.audio.studio.album.AlbumConcept.standard("", "", 12));
                }
            }
            if (target == null) {
                // No album named: an unnamed album of its own when the user left the album empty,
                // the first unnamed one when nothing was chosen at all.
                target = title != null ? albumMembers.albums().create(AlbumMembership.UNTITLED,
                        com.aresstack.audio.studio.album.AlbumConcept.standard("", "", 12)) : albumMembers.untitled();
            }
            Album now = albumMembers.add(target.id(), piece);
            currentAlbum = now.id();
            if (albumsPanel != null) {
                albumsPanel.follow(now.id()); // the page must not write back an album without the new piece
            }
            return now.title();
        } catch (IOException | RuntimeException failed) {
            append("Das Stück ließ sich keinem Album zuordnen: " + failed.getMessage());
            return null;
        }
    }

    /**
     * Produces one title of an album now, with the album as its context: the album page shows how
     * it goes, as its "Titel neu produzieren" does.
     */
    private void produceInAlbum(String albumId, String trackId) {
        keepSong();
        showAlbum(albumId);
        if (albumsPanel != null && albumsPanel.album() != null && albumsPanel.album().id().equals(albumId)) {
            albumsPanel.produceTitle(trackId);
        }
    }

    /** The key in the head of the composition: the open piece made anew as the title of its album. */
    private void produceOpenPieceInAlbum() {
        Path open = Paths.get(directory.getText().trim());
        Album album = albumMembers.albumOf(open).orElse(null);
        String name = open.getFileName() == null ? "" : open.getFileName().toString();
        AlbumTrack track = album == null ? null : album.tracks().stream()
                .filter(each -> name.equals(each.piece())).findFirst().orElse(null);
        if (track == null) {
            message("Dieses Stück steht in keinem Album; im Album produzieren geht nur mit einem Titel eines Albums.");
            return;
        }
        produceInAlbum(album.id(), track.id());
    }

    /** Shows the page of an album: its settings, its titles and its production. */
    private void showAlbum(String id) {
        if (id == null || !albumMembers.albums().album(id).isPresent()) {
            refreshSongs();
            return;
        }
        currentAlbum = id;
        if (albumsPanel != null) {
            albumsPanel.follow(id);
        }
        showPage("albums");
    }

    /** The tree at the left: the albums of the project with their titles. */
    private AlbumTree albumTree() {
        albumTree = new AlbumTree(new AlbumTree.Host() {
            @Override
            public void albumClicked(String albumId) {
                if ("player".equals(shownPage)) {
                    playerAlbumClicked(albumId); // the player stays: this album plays from now on
                    return;
                }
                showAlbum(albumId);
            }

            @Override
            public void pieceClicked(String albumId, Path folder) {
                if ("player".equals(shownPage)) {
                    playerTitleClicked(albumId, folder); // the player stays and plays the title
                    return;
                }
                currentAlbum = albumId;
                openSong(folder);
                if (Paths.get(directory.getText().trim()).equals(folder)) {
                    showPiecePage(); // Arrangement, Bühne or Protokoll stay when another title is chosen
                }
            }

            @Override
            public void plannedClicked(String albumId, String trackId) {
                if ("player".equals(shownPage)) {
                    playerAlbumClicked(albumId); // nothing to hear yet
                    return;
                }
                if (albumsPanel != null && albumsPanel.producing()) {
                    showAlbum(albumId); // the production makes the piece itself
                    return;
                }
                try {
                    Path piece = albumMembers.pieceFor(albumId, trackId);
                    if (albumsPanel != null) {
                        albumsPanel.follow(albumId); // the page must not write back the title without its piece
                    }
                    if (piece != null) {
                        pieceClicked(albumId, piece);
                    }
                } catch (IOException | RuntimeException failed) {
                    message("Die Komposition ließ sich nicht anlegen: " + failed.getMessage());
                }
                refreshSongs();
            }

            @Override
            public void newCompositionIn(String albumId) {
                currentAlbum = albumId;
                newComposition();
            }

            @Override
            public void produceTitle(String albumId, String trackId) {
                produceInAlbum(albumId, trackId);
            }

            @Override
            public void produceAlbum(String albumId) {
                showAlbum(albumId);
                if (albumsPanel != null && albumsPanel.album() != null && albumsPanel.album().id().equals(albumId)) {
                    albumsPanel.produceAlbum();
                }
            }

            @Override
            public void renameAlbum(String albumId) {
                Album album = albumMembers.albums().album(albumId).orElse(null);
                if (album == null || refuseWhileProducing()) {
                    return;
                }
                Object typed = JOptionPane.showInputDialog(AudioStudioFrame.this,
                        "Neuer Name des Albums (leer: „" + AlbumMembership.UNTITLED + "“):", "Album umbenennen",
                        JOptionPane.PLAIN_MESSAGE, null, null, AlbumMembership.isUntitled(album) ? "" : album.title());
                if (typed == null) {
                    return;
                }
                try {
                    albumMembers.renameAlbum(albumId, typed.toString());
                    if (albumsPanel != null && currentAlbum != null) {
                        albumsPanel.follow(currentAlbum);
                    }
                } catch (IOException | RuntimeException failed) {
                    message("Das Album ließ sich nicht umbenennen: " + failed.getMessage());
                }
                refreshSongs();
            }

            @Override
            public void renameTitle(String albumId, String trackId) {
                Album album = albumMembers.albums().album(albumId).orElse(null);
                com.aresstack.audio.studio.album.AlbumTrack track = album == null ? null : album.track(trackId);
                if (track == null || refuseWhileProducing()) {
                    return;
                }
                Path folder = track.hasPiece() ? songs.root().resolve(track.piece()) : null;
                String now = folder != null && Files.isDirectory(folder) ? songs.name(folder) : track.title();
                Object typed = JOptionPane.showInputDialog(AudioStudioFrame.this, "Neuer Name des Titels:",
                        "Titel umbenennen", JOptionPane.PLAIN_MESSAGE, null, null, now);
                if (typed == null || typed.toString().isBlank()) {
                    return;
                }
                try {
                    Path renamed = albumMembers.renameTitle(albumId, trackId, typed.toString());
                    if (renamed != null) {
                        titleRenamed(renamed);
                    }
                    if (albumsPanel != null && currentAlbum != null) {
                        albumsPanel.follow(currentAlbum);
                    }
                } catch (IOException | RuntimeException failed) {
                    message("Der Titel ließ sich nicht umbenennen: " + failed.getMessage());
                }
                refreshSongs();
            }

            @Override
            public void deleteTitle(String albumId, String trackId) {
                Album album = albumMembers.albums().album(albumId).orElse(null);
                com.aresstack.audio.studio.album.AlbumTrack track = album == null ? null : album.track(trackId);
                if (track == null || refuseWhileProducing()) {
                    return;
                }
                Path folder = track.hasPiece() ? songs.root().resolve(track.piece()) : null;
                boolean piece = folder != null && Files.isDirectory(folder);
                Path open = Paths.get(directory.getText().trim());
                if (piece && folder.equals(open) && worker != null && !worker.isDone()) {
                    message("Erst den laufenden Vorgang beenden oder abbrechen.");
                    return;
                }
                String name = piece ? songs.name(folder) : track.title();
                String question = piece
                        ? "„" + name + "“ löschen?\n\nDie Komposition kommt in den Papierkorb der Bibliothek "
                        + "(Ordner „.papierkorb“) und lässt sich von dort zurückholen."
                        : "Den geplanten Titel „" + name + "“ aus dem Album löschen?";
                if (JOptionPane.showConfirmDialog(AudioStudioFrame.this, question, "Titel löschen",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                    return;
                }
                try {
                    if (piece && folder.equals(open)) {
                        stopPlayback();
                    }
                    Path gone = albumMembers.removeTitle(albumId, trackId);
                    if (gone != null) {
                        songs.discard(gone);
                        append("„" + name + "“ liegt jetzt im Papierkorb der Bibliothek.");
                    }
                    if (albumsPanel != null && currentAlbum != null) {
                        albumsPanel.follow(currentAlbum);
                    }
                    if (gone != null && gone.equals(open)) {
                        java.util.List<com.aresstack.audio.studio.application.SongLibrary.Song> left = songs.songs();
                        Path next = left.isEmpty() ? songs.create("Neues Stück") : left.get(0).folder();
                        openSong(next);
                    }
                } catch (IOException | RuntimeException failed) {
                    message("Der Titel ließ sich nicht löschen: " + failed.getMessage());
                }
                refreshSongs();
            }

            private boolean refuseWhileProducing() {
                if (albumsPanel != null && (albumsPanel.producing() || albumsPanel.busy())) {
                    message("Während ein Album geplant oder produziert wird, bleiben Alben und Titel, wie sie sind.");
                    return true;
                }
                return false;
            }

            @Override
            public void moveTitle(String fromAlbum, String trackId, String toAlbum, int index) {
                if (albumsPanel != null && albumsPanel.producing()) {
                    message("Während ein Album produziert wird, werden keine Titel verschoben.");
                    return;
                }
                try {
                    if (albumMembers.move(fromAlbum, trackId, toAlbum, index)) {
                        if (albumsPanel != null) {
                            albumsPanel.follow(currentAlbum == null ? toAlbum : currentAlbum);
                        }
                    }
                } catch (IOException | RuntimeException failed) {
                    message("Der Titel ließ sich nicht verschieben: " + failed.getMessage());
                }
                refreshSongs();
            }

            @Override
            public void newAlbum() {
                if (albumsPanel != null) {
                    String before = currentAlbum;
                    albumsPanel.createAlbum(); // a new album becomes the current one
                    if (currentAlbum != null && !currentAlbum.equals(before)) {
                        showAlbum(currentAlbum);
                    }
                }
            }

            @Override
            public void newComposition() {
                createNewComposition();
                showPage("song");
            }

            @Override
            public void deleteAlbum(String albumId) {
                if (albumsPanel == null) {
                    return;
                }
                if (albumsPanel.producing() || albumsPanel.busy()) {
                    message("Während ein Album geplant oder produziert wird, wird keines gelöscht.");
                    return;
                }
                albumsPanel.follow(albumId);
                // Only the album asked for, never another one the page happens to show.
                if (albumsPanel.album() != null && albumsPanel.album().id().equals(albumId)) {
                    albumsPanel.deleteAlbum();
                }
                refreshSongs();
            }
        });
        albumTree.setNewTitles(this::newInTree); // marked on the player page only
        return albumTree;
    }

    /**
     * A piece got another name from the album tree: its model follows it to the new name, as
     * after a renamed track. A piece that is not open is brought in step on its own.
     */
    private void titleRenamed(Path renamed) {
        if (renamed.equals(Paths.get(directory.getText().trim()))) {
            pieceModel.synchronize();
        } else {
            com.aresstack.audio.studio.application.PieceModel other =
                    new com.aresstack.audio.studio.application.PieceModel(line -> { });
            other.open(renamed);
            other.synchronize();
        }
    }

    /** What the open project is called. */
    private String projectTitle() {
        return projects.name(songs.root());
    }

    /** A head of a page with the key of the piece's menu beside what it shows already. */
    private javax.swing.JComponent withPieceMenu(javax.swing.JComponent shown) {
        JButton key = new DeckWidgets.HeadKey("Stück ▾");
        key.setToolTipText("Rückgängig, Revisionen, Intensitätsverlauf, Dateien öffnen, Plan der KI, Export, Demo");
        key.addActionListener(event -> pieceMenu.show(key, 0, key.getHeight()));
        JPanel line = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        line.setOpaque(false);
        line.add(shown);
        line.add(key);
        return line;
    }

    /** The menu of the projects: the open one checked, the others to switch to, and new or rename. */
    private javax.swing.JPopupMenu projectMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        Path open = songs.root().toAbsolutePath().normalize();
        for (com.aresstack.audio.studio.application.Projects.Project project : projects.all()) {
            javax.swing.JCheckBoxMenuItem item = new javax.swing.JCheckBoxMenuItem(project.name(),
                    project.root().toAbsolutePath().normalize().equals(open));
            item.addActionListener(event -> switchProject(project.root()));
            menu.add(item);
        }
        menu.addSeparator();
        javax.swing.JMenuItem create = new javax.swing.JMenuItem("Neues Projekt");
        create.setToolTipText("Legt ein leeres Projekt an und öffnet es; den Namen dann oben ändern");
        create.addActionListener(event -> {
            try {
                switchProject(projects.create(com.aresstack.audio.studio.application.Projects.NEW_NAME));
            } catch (IOException failed) {
                message("Das Projekt ließ sich nicht anlegen: " + failed.getMessage());
            }
        });
        menu.add(create);
        javax.swing.JMenuItem rename = new javax.swing.JMenuItem("Projekt umbenennen");
        rename.setToolTipText("Den Namen oben direkt ändern: Enter übernimmt, Esc lässt ihn (auch per Doppelklick)");
        rename.addActionListener(event -> startRenamingProject());
        menu.add(rename);
        menu.addSeparator();
        javax.swing.JMenuItem export = new javax.swing.JMenuItem("Projekt exportieren …");
        export.setToolTipText("Alben, Stücke, Revisionen und Einstellungen (ohne API-Schlüssel) als ZIP, ohne Renders");
        export.addActionListener(event -> exportProject(false));
        menu.add(export);
        javax.swing.JMenuItem exportAll = new javax.swing.JMenuItem("Projekt mit Renders exportieren …");
        exportAll.setToolTipText("Wie Exportieren, dazu alle gerenderten Klänge (WAV): größer, aber sofort abspielbar");
        exportAll.addActionListener(event -> exportProject(true));
        menu.add(exportAll);
        javax.swing.JMenuItem importing = new javax.swing.JMenuItem("Projekt importieren …");
        importing.setToolTipText("Liest eine Projekt-ZIP als neues Projekt ein; vorhandene Projekte bleiben, wie sie sind");
        importing.addActionListener(event -> importProject());
        menu.add(importing);
        menu.addSeparator();
        javax.swing.JMenuItem folder = new javax.swing.JMenuItem("Projektordner zeigen");
        folder.setToolTipText(songs.root().toString());
        folder.addActionListener(event -> {
            try {
                Files.createDirectories(songs.root());
                java.awt.Desktop.getDesktop().open(songs.root().toFile());
            } catch (Exception failed) {
                message("Der Ordner ließ sich nicht öffnen: " + songs.root());
            }
        });
        menu.add(folder);
        return menu;
    }

    /** Shows the project's name as a field to change it in place. */
    private void startRenamingProject() {
        projectNameField.setText(projectTitle());
        ((java.awt.CardLayout) projectNameHolder.getLayout()).show(projectNameHolder, "editing");
        projectNameField.selectAll();
        projectNameField.requestFocusInWindow();
    }

    /** Ends renaming: keeps the typed name, or leaves the old one. */
    private void finishRenamingProject(boolean keep) {
        if (!projectNameField.isShowing()) {
            return;
        }
        String typed = projectNameField.getText().trim();
        ((java.awt.CardLayout) projectNameHolder.getLayout()).show(projectNameHolder, "shown");
        if (!keep || typed.isEmpty() || typed.equals(projectTitle())) {
            return;
        }
        try {
            projects.rename(songs.root(), typed);
            projectName.setText(projectTitle());
            append("Projekt umbenannt: " + projectTitle());
        } catch (IOException failed) {
            message("Das Projekt ließ sich nicht umbenennen: " + failed.getMessage());
        }
    }

    /** Writes the open project into a ZIP file of the user's choice, in the background. */
    private void exportProject(boolean renders) {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle(renders ? "Projekt mit Renders exportieren (ZIP)" : "Projekt exportieren (ZIP)");
        chooser.setSelectedFile(new java.io.File(projectTitle().replaceAll("[^\\p{L}\\p{N} ()_-]", "").trim()
                + com.aresstack.audio.studio.application.ProjectArchive.ENDING));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path chosen = chooser.getSelectedFile().toPath();
        String ending = com.aresstack.audio.studio.application.ProjectArchive.ENDING;
        final Path target = chosen.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(ending)
                ? chosen : chosen.resolveSibling(chosen.getFileName() + ending);
        try {
            keepSong();
            saveSettings(false);
        } catch (Exception failed) {
            message("Das offene Stück ließ sich vor dem Export nicht sichern: " + failed.getMessage());
            return;
        }
        final Path root = songs.root();
        final String name = projectTitle();
        setStatus("Projekt wird exportiert …");
        new SwingWorker<com.aresstack.audio.studio.application.ProjectArchive.Written, Void>() {
            @Override
            protected com.aresstack.audio.studio.application.ProjectArchive.Written doInBackground() throws Exception {
                Properties settings = settingsStore.load();
                return com.aresstack.audio.studio.application.ProjectArchive.export(root, name, settings, target,
                        renders);
            }

            @Override
            protected void done() {
                try {
                    com.aresstack.audio.studio.application.ProjectArchive.Written written = get();
                    String line = "Projekt exportiert: " + written.file() + " (" + written.files() + " Dateien, "
                            + (written.bytes() / 1024) + " KB"
                            + (renders || written.skippedRenders() == 0 ? ""
                            : ", " + written.skippedRenders() + " Renders weggelassen") + ")";
                    append(line);
                    setStatus(line);
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                    message("Das Projekt ließ sich nicht exportieren: " + cause.getMessage());
                }
            }
        }.execute();
    }

    /** Reads a project ZIP as a new project beside the others and opens it. */
    private void importProject() {
        if (busy() || (albumsPanel != null && (albumsPanel.producing() || albumsPanel.busy()))) {
            message("Erst den laufenden Vorgang beenden oder abbrechen, dann ein Projekt importieren.");
            return;
        }
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Projekt importieren (ZIP)");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Projekt-ZIP", "zip"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        final Path archive = chooser.getSelectedFile().toPath();
        setStatus("Projekt wird importiert …");
        new SwingWorker<com.aresstack.audio.studio.application.ProjectArchive.Imported, Void>() {
            @Override
            protected com.aresstack.audio.studio.application.ProjectArchive.Imported doInBackground() throws Exception {
                return com.aresstack.audio.studio.application.ProjectArchive.importInto(projects, archive);
            }

            @Override
            protected void done() {
                try {
                    com.aresstack.audio.studio.application.ProjectArchive.Imported made = get();
                    append("Projekt importiert als „" + made.name() + "“" + (made.renamed()
                            ? " (der Name war schon vergeben)" : "") + ": " + made.files() + " Dateien");
                    switchProject(made.root());
                } catch (Exception failed) {
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                    message("Das Projekt ließ sich nicht importieren: " + cause.getMessage());
                }
            }
        }.execute();
    }

    /**
     * Opens another project: what is open is kept, and the window starts again on the other
     * library, with the piece last open there.
     */
    private void switchProject(Path root) {
        if (root.toAbsolutePath().normalize().equals(songs.root().toAbsolutePath().normalize())) {
            return;
        }
        if (busy() || (albumsPanel != null && (albumsPanel.producing() || albumsPanel.busy()))) {
            message("Erst den laufenden Vorgang beenden oder abbrechen, dann das Projekt wechseln.");
            return;
        }
        try {
            keepSong();
            saveSettings(false);
            stopPlayback();
            projects.choose(root);
        } catch (IOException failed) {
            message("Das Projekt ließ sich nicht öffnen: " + failed.getMessage());
            return;
        }
        clock.stop();
        remixTimer.stop();
        preparingClock.stop();
        java.awt.Rectangle where = getBounds();
        int state = getExtendedState();
        dispose();
        AudioStudioFrame other = new AudioStudioFrame(registry);
        other.setBounds(where);
        other.setExtendedState(state);
        other.setVisible(true);
    }

    /**
     * The words the AI composes from: the description of the piece, and for a piece of a named
     * album what it is told about that album. The description itself shows only the piece's own words.
     */
    private String wishForAi() {
        String own = com.aresstack.audio.studio.application.OriginalWork.wish(description.getText().trim(),
                originalWork.isSelected());
        String context = albumMembers.albumContext(Paths.get(directory.getText().trim()));
        return context.isEmpty() ? own : own + "\n" + context;
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
        String kept = Album.withoutBrief(songs.description(folder)); // the album is told to the AI beside it
        if (kept != null) {
            description.setText(kept);
            description.setCaretPosition(0);
        }
        loadArrangementForCurrentDirectory(new Properties());
        saveSettings(false);
        followPiece(folder);
        refreshSongs();
    }

    /** The album chosen above follows a piece that is opened, if the piece is not in it. */
    private void followPiece(Path folder) {
        Album own = albumMembers.albumOf(folder).orElse(null);
        if (own == null || own.id().equals(currentAlbum)) {
            return;
        }
        Album chosen = currentAlbum == null ? null : albumMembers.albums().album(currentAlbum).orElse(null);
        if (chosen == null || albumMembers.pieces(chosen).stream().noneMatch(song -> song.folder().equals(folder))) {
            currentAlbum = own.id();
        }
    }

    /** Keeps the description with the piece it belongs to. */
    private void keepSong() {
        Path folder = Paths.get(directory.getText().trim());
        if (Files.isDirectory(folder)) {
            String kept = songs.description(folder);
            if (description.getText().equals(kept == null ? "" : Album.withoutBrief(kept))) {
                return; // nothing new: the file stays untouched, so the work model sees no change
            }
            try {
                songs.save(folder, null, description.getText());
            } catch (IOException ignored) {
                // The description stays in the studio's settings; the piece is not affected.
            }
        }
    }

    /**
     * Shows the albums of the project with their titles in the tree, and the piece in use. Before
     * that, every piece that no album names is put into the unnamed album, so that each piece
     * belongs to one, and a new piece takes its title from its plan.
     */
    private void refreshSongs() {
        Path current = Paths.get(directory.getText().trim());
        try {
            int adopted = albumMembers.adoptLoosePieces();
            if (adopted > 0) {
                append(adopted == 1 ? "Ein Stück ohne Album liegt jetzt im Album „" + AlbumMembership.UNTITLED + "“."
                        : adopted + " Stücke ohne Album liegen jetzt im Album „" + AlbumMembership.UNTITLED + "“.");
            }
            albumMembers.refresh(current); // the plan may have given the piece its title, tempo and key
        } catch (IOException failed) {
            append("Die Stücke ohne Album ließen sich nicht ins unbenannte Album legen: " + failed.getMessage());
        }
        java.util.List<Album> all = albumMembers.albums().albums();
        if (currentAlbum == null || all.stream().noneMatch(album -> album.id().equals(currentAlbum))) {
            currentAlbum = albumMembers.albumOf(current).map(Album::id).orElse(null);
        }
        if (albumTree != null) {
            albumTree.show(all, songs, currentAlbum, current);
            markAlbumInTree();
        }
        Album chosen = currentAlbum == null ? null : albumMembers.albums().album(currentAlbum).orElse(null);
        setTitle("AresStack Audio Studio — " + (chosen == null ? "" : chosen.title() + " — ") + songs.name(current));
        songFacts.setText(songFacts(current, modelBars(current)));
    }

    /** One line about the piece from its plan: tempo, key, metre, length and mood; a hint when there is no plan. */
    /**
     * The bars of the piece as its model has them, or 0 without a model: after "Verlängern" or a
     * production in portions the plan still names the length it was first planned with.
     */
    private int modelBars(Path folder) {
        // Only reads the open session, and only if it is this piece.
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> model = pieceToEdit();
        if (!model.isPresent() || !model.get().folder().toAbsolutePath().normalize()
                .equals(folder.toAbsolutePath().normalize())) {
            return 0;
        }
        com.aresstack.audio.composition.Composition piece = model.get().composition();
        long end = piece.endTick();
        return end <= 0 ? 0 : piece.timeMap().barOf(end - 1) + 1;
    }

    private static String songFacts(Path folder, int modelBars) {
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
                if ("bars".equals(part[0]) && modelBars > 0) {
                    facts.append(facts.length() > 0 ? "  ·  " : "").append(modelBars).append(part[1]);
                } else if (json.has(part[0]) && json.get(part[0]).isJsonPrimitive()) {
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

    /**
     * The piece to open in this project: the one given if it is one of its pieces, else the one
     * last open in it, else its newest, and in an empty project a new one.
     */
    private Path pieceOfThisProject(Path wanted) {
        Path root = songs.root().toAbsolutePath().normalize();
        try {
            if (wanted != null && Files.isDirectory(wanted)
                    && root.equals(wanted.toAbsolutePath().normalize().getParent())) {
                return wanted;
            }
            Path last = projects.lastPiece(songs.root());
            if (last != null) {
                return last;
            }
            java.util.List<com.aresstack.audio.studio.application.SongLibrary.Song> all = songs.songs();
            return all.isEmpty() ? songs.create("Neues Stück") : all.get(0).folder();
        } catch (IOException | RuntimeException failed) {
            return wanted == null ? songs.root() : wanted;
        }
    }

    private void loadSettings() {
        try {
            Properties saved = AiDefaults.withDefaults(settingsStore.load());
            preferredKeePassEntry = saved.getProperty("keepassEntry", "");
            endpoint.setText(saved.getProperty("endpoint", ""));
            apiKey.setText(saved.getProperty("apiKey", ""));
            // Settings from before the choice: a key that was saved keeps being typed by hand.
            String access = saved.getProperty("access",
                    saved.getProperty("apiKey", "").isEmpty() ? "keepass" : "manual");
            (("manual".equals(access)) ? accessManual : accessKeePass).setSelected(true);
            showAccess();
            model.setText(saved.getProperty("model", model.getText()));
            modelChoices.setAssignment(com.aresstack.audio.studio.settings.ModelSettings.read(saved));
            albumModelChoices.setAssignment(albumAssignment(albumShown));
            tokenLimit.setValue(AiDefaults.maxTokens(saved));
            sectionBars.setValue(AiDefaults.sectionBars(saved));
            detailedLog.setSelected(Boolean.parseBoolean(saved.getProperty("detailedLog", "false")));
            growBars.setValue(AiDefaults.growBars(saved));
            templateLines.setValue(AiDefaults.templateLines(saved));
            originalWork.setSelected(Boolean.parseBoolean(saved.getProperty("originalWork", "false")));
            longerAnswers = AiDefaults.longerAnswers(saved);
            longerAnswerFactor = AiDefaults.longerAnswerFactor(saved);
            specFields.suggestBars((Integer) growBars.getValue());
            proxyHost.setText(saved.getProperty("proxyHost", ""));
            proxyPort.setText(saved.getProperty("proxyPort", ""));
            proxyPacUrl.setText(saved.getProperty("proxyPacUrl", ""));
            proxyNonProxyHosts.setText(saved.getProperty("proxyNonProxyHosts", ""));
            proxyBasic.setSelected("BASIC".equalsIgnoreCase(saved.getProperty("proxyAuthMode", "")));
            proxyCredentialRef.setText(saved.getProperty("proxyCredentialRef", ""));
            fitArrangement.setSelected(!"false".equalsIgnoreCase(saved.getProperty("arrangementFit", "true")));
            stagePanel.setFigureLook(com.aresstack.audio.studio.figure.FigureSettings.from(saved),
                    com.aresstack.audio.studio.figure.FigureSettings.bob(saved));
            // The player of the album page: when a title counts as heard, only new titles, overlap.
            albumHeardPercent = (int) Math.round(number(saved, "albumHeardPercent",
                    com.aresstack.audio.studio.application.HeardTitles.DEFAULT_PERCENT));
            albumCrossfadeSeconds = number(saved, "albumCrossfadeSeconds", 0.0);
            albumOnlyNew = Boolean.parseBoolean(saved.getProperty("albumOnlyNew", "false"));
            playerPanel.setOptions(albumHeardPercent, albumCrossfadeSeconds, albumOnlyNew);
            trackTable.setFit(fitArrangement.isSelected());
            // Zoom of the arrangement when it is not fitted (Strg + Mausrad): step per notch and the most.
            trackTable.setZoomLimits(number(saved, "arrangementZoomStep", ArrangementView.ZOOM_STEP),
                    number(saved, "arrangementZoomMost", ArrangementView.ZOOM_MOST));
            AppIcon savedIcon = AppIcon.fromKey(saved.getProperty(AppIcon.SETTING));
            showingSavedIcon = true;
            try {
                appIconChoice.setSelectedItem(savedIcon);
            } finally {
                showingSavedIcon = false;
            }
            savedIcon.applyToAllWindows();
            String savedMode = saved.getProperty("proxyMode", "");
            if (!savedMode.isEmpty()) {
                proxyMode.setSelectedItem(savedMode);
            }
            append("Proxy: " + new AudioProxyRoutes(networkSettings()).describe());
            directory.setText(saved.getProperty("outputDirectory", directory.getText()));
            directory.setText(pieceOfThisProject(Paths.get(directory.getText().trim())).toString());
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
            firstText.setValue(AiDefaults.firstTextSeconds(saved));
            silence.setValue(AiDefaults.silenceSeconds(saved));
            KipitzChatClient.waits((Integer) firstText.getValue(), (Integer) silence.getValue());
            retryFirst.setValue(AiDefaults.retryFirstSeconds(saved));
            retryMost.setValue(AiDefaults.retryMostSeconds(saved));
            com.aresstack.audio.studio.ai.Backoff.configure((Integer) retryFirst.getValue(),
                    (Integer) retryMost.getValue());
            String keptDescription = Album.withoutBrief(songs.description(Paths.get(directory.getText().trim())));
            if (keptDescription != null) {
                description.setText(keptDescription);
            }
            ownTracks.setSelected("own".equals(saved.getProperty("instrumentMode")));
            try {
                int fewestSaved = Integer.parseInt(saved.getProperty("voicesMin", "3").trim());
                int mostSaved = Integer.parseInt(saved.getProperty("voicesMax", "8").trim());
                voicesMax.setValue(Math.max(InstrumentProposalService.MIN_VOICES,
                        Math.min(InstrumentProposalService.MAX_VOICES, Math.max(fewestSaved, mostSaved))));
                voicesMin.setValue(Math.max(InstrumentProposalService.MIN_VOICES,
                        Math.min((Integer) voicesMax.getValue(), fewestSaved)));
            } catch (NumberFormatException unreadable) {
                // the fields keep 3 to 8
            }
            updateComposeKey();
            listening = com.aresstack.audio.studio.application.StageListening.from(saved);
            if (stagePanel != null) {
                stagePanel.setMovementsWanted(com.aresstack.audio.studio.application.MovementDirector.wanted(saved));
            }
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
            saved.setProperty("access", accessManual.isSelected() ? "manual" : "keepass");
            saved.setProperty("keepassEntry", preferredKeePassEntry);
            char[] password = apiKey.getPassword();
            try {
                saved.setProperty("apiKey", new String(password));
            } finally {
                Arrays.fill(password, '\0');
            }
            com.aresstack.audio.studio.settings.ModelSettings.write(saved, currentAssignment());
            saved.setProperty("maxTokens", tokenLimit.getValue().toString());
            saved.setProperty("sectionBars", sectionBars.getValue().toString());
            saved.setProperty("detailedLog", Boolean.toString(detailedLog.isSelected()));
            saved.setProperty("portionBars", growBars.getValue().toString());
            saved.setProperty("templateLines", templateLines.getValue().toString());
            saved.setProperty("originalWork", Boolean.toString(originalWork.isSelected()));
            saved.setProperty("longerAnswers", Integer.toString(longerAnswers));
            saved.remove("albumPiecesAtOnce"); // no more: the parallel requests alone set how much runs at once
            saved.setProperty("longerAnswerFactor", Integer.toString(longerAnswerFactor));
            saved.setProperty("proxyHost", proxyHost.getText().trim());
            saved.setProperty("proxyPort", proxyPort.getText().trim());
            String selectedProxyMode = proxyMode.getSelectedIndex() == 0 ? ""
                    : String.valueOf(proxyMode.getSelectedItem());
            saved.setProperty("proxyMode", selectedProxyMode);
            saved.setProperty("proxyPacUrl", proxyPacUrl.getText().trim());
            saved.setProperty("proxyNonProxyHosts", proxyNonProxyHosts.getText().trim());
            saved.setProperty("proxyAuthMode", proxyBasic.isSelected() ? "BASIC" : "");
            saved.setProperty("proxyCredentialRef", proxyCredentialRef.getText().trim());
            saved.setProperty("arrangementFit", String.valueOf(fitArrangement.isSelected()));
            saved.setProperty("albumHeardPercent", Integer.toString(albumHeardPercent));
            saved.setProperty("albumCrossfadeSeconds", Double.toString(albumCrossfadeSeconds));
            saved.setProperty("albumOnlyNew", Boolean.toString(albumOnlyNew));
            saved.setProperty(AppIcon.SETTING, ((AppIcon) appIconChoice.getSelectedItem()).key());
            saved.setProperty("outputDirectory", directory.getText().trim());
            try {
                projects.lastPiece(songs.root(), Paths.get(directory.getText().trim()));
            } catch (IOException | RuntimeException ignored) {
                // then the project opens with another of its pieces next time
            }
            saved.setProperty("sf2Engine", sf2Engine.getSelectedIndex() == 1
                    ? "FLUIDSYNTH" : "GERVILL");
            saved.setProperty("fluidSynth", fluidSynth.getText().trim());
            saved.setProperty("sfizz", sfizz.getText().trim());
            saved.setProperty("description", description.getText());
            saved.setProperty("parallel", parallelRequests.getValue().toString());
            saved.setProperty("firstTextSeconds", firstText.getValue().toString());
            saved.setProperty("silenceSeconds", silence.getValue().toString());
            saved.setProperty("retryFirstSeconds", retryFirst.getValue().toString());
            saved.setProperty("retryMostSeconds", retryMost.getValue().toString());
            saved.setProperty("instrumentMode", ownTracks.isSelected() ? "own" : "ai");
            saved.setProperty("voicesMin", voicesMin.getValue().toString());
            saved.setProperty("voicesMax", voicesMax.getValue().toString());
            listening.into(saved);
            if (stagePanel != null) {
                com.aresstack.audio.studio.application.MovementDirector.into(saved, stagePanel.movementsWanted());
            }
            if (trackTable != null && !loadingTracks && trackTable.arrangement() != null) {
                trackStore.save(Paths.get(directory.getText().trim()), trackTable.currentArrangement());
            }
            if (visualizer != null) {
                visualizer.settings().into(saved); // the settings file is written whole: the visualizer's numbers stay in it
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
        lcd.setBusy(true); // the work of the window itself; the requests of an album light it as well
        trackStates.clear();
        trackTable.clearWork();
        arrived = 0;
        sketchNotes = 0;
        worker = new SwingWorker<Path, String>() {
            @Override
            protected Path doInBackground() throws Exception {
                return action.execute(new com.aresstack.audio.studio.progress.SketchProgress() {
                    @Override
                    public void sketch(final List<String> arrangement) {
                        SwingUtilities.invokeLater(() -> showSketch(arrangement));
                    }

                    @Override
                    public void preparing(final String what, final long characters) {
                        SwingUtilities.invokeLater(() -> showPreparing(what, characters));
                    }

                    @Override
                    public void form(final List<com.aresstack.audio.studio.progress.FormSection> sections) {
                        SwingUtilities.invokeLater(() -> showForm(sections));
                    }

                    @Override
                    public void instruments(final List<String[]> chosen) {
                        SwingUtilities.invokeLater(() -> showChosenInstruments(chosen));
                    }

                    @Override
                    public void planned(final List<String> chords) {
                        SwingUtilities.invokeLater(() -> showPlanned(chords));
                    }

                    @Override
                    public void arriving(final long characters) {
                        SwingUtilities.invokeLater(() -> {
                            if (worker == null || worker.isDone()) {
                                return; // a late word after the work ended
                            }
                            arrived = characters;
                            showTrackWork();
                        });
                    }


                    @Override
                    public void update(String update) {
                        publish(update);
                    }

                    @Override
                    public void detail(String line) {
                        if (detailedLog.isSelected()) {
                            publish(line);
                        }
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
                showAiLamp();
                trackStates.clear();
                trackTable.clearWork();
                endChoosing(false); // a choice of instruments that did not get to its end
                if (mixArriving) {
                    mixArriving = false;
                    stagePanel.setProposal(null); // a mix that did not get to its end is no proposal
                }
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
                    Runnable next = afterWork;
                    afterWork = null;
                    if (next != null) {
                        next.run(); // what the work asked to be shown once it is over: a proposal to decide on
                    }
                }
            }
        };
        worker.execute();
    }

    private void cancel() {
        com.aresstack.audio.studio.ai.ModelLineup running = activeModels;
        if (running != null) {
            running.cancel();
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

    /**
     * Loads the demo into the folder of the open piece. A piece with a model is rendered from its
     * model, so the model takes the demo over here, on the user's word and as a step that can be
     * taken back; a model that would lose something is not replaced.
     */
    private void loadDemo() {
        if (busy()) {
            return;
        }
        Path folder = Paths.get(directory.getText().trim());
        com.aresstack.audio.studio.application.DemoIntoPiece.Effect effect =
                com.aresstack.audio.studio.application.DemoIntoPiece.effect(folder, pieceModel);
        if (effect == com.aresstack.audio.studio.application.DemoIntoPiece.Effect.REPLACES_PIECE
                && JOptionPane.showConfirmDialog(this, "Die Demo ersetzt das Stück in diesem Ordner."
                        + " Hat das Stück ein Werkmodell, lässt es sich mit „Rückgängig“ zurückholen.\n"
                        + "Demo laden?", "Demo laden", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        try (java.io.InputStream input = getClass().getResourceAsStream("/district-dawn.tsv")) {
            if (input == null) {
                throw new IOException("Bundled sample arrangement is missing.");
            }
            stopPlayback();
            Path destination = com.aresstack.audio.studio.application.DemoIntoPiece.load(input, folder, pieceModel);
            arrangementFile.setText(destination.toString());
            trackTable.setArrangement(TrackArrangement.defaults());
            append("Loaded original Gemma composition (no API call): " + destination);
            if (pieceToEdit().isPresent()) {
                // The model is the demo now: tracks, lanes and score show it, and the next rendering plays it.
                showTracksOf(pieceToEdit().get().composition());
                stageFollowsMix.remember(pieceToEdit().get());
                answersState = pieceToEdit().get().composition();
            }
            showPlaybackChoice();
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
        // One track alone is heard as it stands on the stage: with its level, its place and the room.
        com.aresstack.audio.studio.application.StageMixService.Piece arrangement = pieceToMix();
        Path raw = com.aresstack.audio.studio.application.StageMixService.stem(folder, selected);
        if (arrangement != null && Files.isRegularFile(raw)) {
            try {
                return stageMixing().solo(arrangement, folder, scene, selected);
            } catch (Exception failed) {
                return raw; // then the track as it was rendered
            }
        }
        return raw;
    }

    /** The track that plays alone, or {@code null} when a mix is chosen. */
    private String soloTrack() {
        String selected = (String) playbackSelection.getSelectedItem();
        return selected == null || "Mixed sampled arrangement".equals(selected) ? null : selected;
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
        // A planned piece shows what it was planned with; only a new one can still be given its length, tempo and key.
        specFields.show(GemmaMusicComposer.specOf(folder));
        updateComposeKey();
        arrangementView.setArrangement(shownArrangement(folder), withAudioLanes(ids),
                there ? "" : "Noch nichts gerendert · Komponieren oder Demo laden");
        arrangementView.setMix(player.envelope(), soloTrack() == null ? "Mix" : "Solo", soloTrack(), player.length());
        trackTable.setAlone(soloTrack());
        com.aresstack.audio.studio.score.Score written = scoreOf(folder);
        if (stagePanel != null) {
            stagePanel.setBars(written == null ? 8 : written.bars());
            showMovements(); // after composing, the model of the piece may be new
        }
        scoreView.setScore(written, ids,
                "Noch keine Noten: erst komponieren, dann steht hier die Partitur");
        showSections();
        lcd.setSource(selected == null ? "—" : playbackName(selected), there);
        lcd.setTime(0, player.length());
    }

    /**
     * The score of the piece in a folder: from its model, which says the voices and the staff of
     * every part, or from the arrangement file for a piece that has no model (yet). Only reads.
     */
    private com.aresstack.audio.studio.score.Score scoreOf(Path folder) {
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> model = pieceModel.of(folder);
        if (model.isPresent()) {
            try {
                return com.aresstack.audio.studio.score.Score.of(model.get().composition());
            } catch (RuntimeException unreadable) {
                append("WARN: Die Partitur ließ sich nicht aus dem Werkmodell lesen, sie kommt aus arrangement.tsv: "
                        + unreadable.getMessage());
            }
        }
        return com.aresstack.audio.studio.score.Score.read(folder.resolve("arrangement.tsv"));
    }

    /**
     * The lanes as the arrangement shows them: from the model if the piece has one, so that its
     * notes can be edited, else from the arrangement file as before.
     */
    private ArrangementView.Arrangement shownArrangement(Path folder) {
        java.util.Optional<com.aresstack.audio.composition.Composition> model = pieceEditing.composition();
        return model.isPresent() ? ArrangementView.Arrangement.of(model.get())
                : ArrangementView.Arrangement.read(folder.resolve("arrangement.tsv"));
    }

    /**
     * The rows of the arrangement: the tracks, and below them the audio lanes of the model (work
     * package B3), whose heads the lanes show as well.
     */
    private List<String> withAudioLanes(List<String> trackIds) {
        List<String> rows = new ArrayList<String>(trackIds);
        List<String[]> audio = new ArrayList<String[]>();
        java.util.Optional<com.aresstack.audio.composition.Composition> model = pieceEditing.composition();
        if (model.isPresent()) {
            for (com.aresstack.audio.composition.Track track : model.get().tracks()) {
                if (track.kind() == com.aresstack.audio.composition.Track.Kind.AUDIO && !rows.contains(track.id())) {
                    rows.add(track.id());
                    audio.add(new String[]{track.id(), track.name()});
                }
            }
        }
        trackTable.setAudioLanes(audio, model.isPresent() ? () -> {
            com.aresstack.audio.studio.application.PieceEditing.Outcome added =
                    pieceEditing.addAudioTrack("Audio " + (audio.size() + 1));
            notesEdited(added.said(), added.changed());
        } : null);
        return rows;
    }

    /** The open piece's model for the note editor; reading only, the editor changes it through the edit core. */
    private java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> pieceToEdit() {
        return pieceModel.session();
    }

    /**
     * The note editor changed the piece (or says why it did not). The model has saved the change
     * and written {@code arrangement.tsv} from it; lanes and score show it now, and the next
     * rendering renders the parts that sound different. The playback keeps playing what it plays.
     */
    private void notesEdited(String said, boolean changed) {
        if (said != null && !said.isEmpty()) {
            statusLine.setText(changed ? said + " · neu rendern, um es zu hören" : said);
        }
        if (!changed) {
            return;
        }
        // Undo in the note editor can take back a step that changed the mix or took a proposal.
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> edited = pieceToEdit();
        if (edited.isPresent()) {
            answersFollow(edited.get(), com.aresstack.audio.composition.edit.ChangeSet.between(
                    answersState == null ? edited.get().composition() : answersState, edited.get().composition()));
            answersState = edited.get().composition();
            if (stageFollows(edited.get())) {
                remixTimer.restart();
            }
        }
        Path folder = Paths.get(directory.getText().trim());
        List<String> ids = new ArrayList<String>();
        for (MusicTrack track : trackTable.tracks()) {
            ids.add(track.id());
        }
        arrangementView.setArrangement(shownArrangement(folder), withAudioLanes(ids), "");
        scoreView.setScore(scoreOf(folder), ids,
                "Noch keine Noten: erst komponieren, dann steht hier die Partitur");
    }

    /** What a choice of the playback is called in the list. */
    private static String playbackLabel(String selected) {
        return "Mixed sampled arrangement".equals(selected) ? "Mix · Bühne" : "Solo · " + selected;
    }

    private static String playbackName(String selected) {
        return "Mixed sampled arrangement".equals(selected) ? "MIX · BÜHNE"
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

    /**
     * The open piece is made by the production of its album: what the AI streams for it is shown
     * as while the window composes it itself. {@code null} while no such piece is open.
     */
    private Path albumLive;

    /** Whether pictures of a piece on its way are shown: the window composes, or the album makes the open piece. */
    private boolean livePicture() {
        return (worker != null && !worker.isDone())
                || (albumLive != null && albumLive.equals(Paths.get(directory.getText().trim())));
    }

    /**
     * What the album production streams for a piece, shown in the score, the arrangement, the
     * track strips and the sections, if that piece is open (now or once the user opens it).
     */
    private com.aresstack.audio.studio.progress.SketchProgress albumPieceWatch(Path piece) {
        java.util.function.Consumer<Runnable> onOpen = shown -> SwingUtilities.invokeLater(() -> {
            if (!piece.equals(Paths.get(directory.getText().trim()))
                    || (worker != null && !worker.isDone())) {
                return; // another piece is open, or the window works on its own
            }
            albumLive = piece;
            shown.run();
        });
        return new com.aresstack.audio.studio.progress.SketchProgress() {
            @Override
            public void update(String message) {
            }

            @Override
            public void track(String trackId, TrackProgress.Stage stage, TrackProgress.State state, String detail) {
                onOpen.accept(() -> trackWork(trackId, stage, state, detail));
            }

            @Override
            public void sketch(List<String> arrangement) {
                onOpen.accept(() -> showSketch(arrangement));
            }

            @Override
            public void arriving(long characters) {
                onOpen.accept(() -> {
                    arrived = characters;
                    showTrackWork();
                });
            }

            @Override
            public void preparing(String what, long characters) {
                onOpen.accept(() -> showPreparing(what, characters));
            }

            @Override
            public void planned(List<String> chords) {
                onOpen.accept(() -> showPlanned(chords));
            }

            @Override
            public void form(List<com.aresstack.audio.studio.progress.FormSection> sections) {
                onOpen.accept(() -> showForm(sections));
            }

            @Override
            public void instruments(List<String[]> chosen) {
                onOpen.accept(() -> showChosenInstruments(chosen));
            }
        };
    }

    /** The album production of a piece ended: if it is open, it is shown as it is now, from its files. */
    private void albumPieceProduced(Path piece) {
        try {
            com.aresstack.audio.studio.application.HeardTitles.producedAgain(piece); // a new mix is new again
            if (!com.aresstack.audio.studio.application.HeardTitles.heard(piece)) {
                hearing.reset(); // the new mix is counted from its start
            }
        } catch (IOException kept) {
            append("WARN: Ob „" + piece.getFileName() + "“ neu ist, ließ sich nicht vermerken: " + kept.getMessage());
        }
        heardChanged();
        boolean shown = piece.equals(albumLive);
        if (shown) {
            albumLive = null;
            preparing = null;
            preparingClock.stop();
            trackStates.clear();
            trackTable.clearWork();
            endChoosing(true); // the instruments the production chose are the piece's now
        }
        if (piece.equals(Paths.get(directory.getText().trim())) && (worker == null || worker.isDone())) {
            Path song = piece.resolve("arrangement.tsv");
            arrangementFile.setText(Files.isRegularFile(song) ? song.toString() : "");
            loadArrangementForCurrentDirectory(new Properties());
        }
        refreshSongs();
    }

    /** A track reports from the workers: its strip shows it, and the display counts all of them. */
    private void trackWork(String trackId, TrackProgress.Stage stage, TrackProgress.State state,
                           String detail) {
        if (!livePicture()) {
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

    /** What the AI does before the first note, or {@code null}; shown with the seconds it takes. */
    private String preparing;
    private long preparingSince;
    private long preparingArrived;
    private final javax.swing.Timer preparingClock = new javax.swing.Timer(1000, event -> sayPreparing());

    /**
     * The steps before the first note (instruments, plan) take a while and were silent: the
     * empty paper and the status line now say which step runs, for how long, and how much of its
     * answer is there.
     */
    private void showPreparing(String what, long characters) {
        if (!livePicture()) {
            return;
        }
        if (!what.equals(preparing)) {
            preparing = what;
            preparingSince = System.currentTimeMillis();
        }
        preparingArrived = characters;
        if (!preparingClock.isRunning()) {
            preparingClock.start();
        }
        sayPreparing();
    }

    private void sayPreparing() {
        if (preparing == null || !livePicture()) {
            preparing = null;
            preparingClock.stop();
            return;
        }
        long seconds = (System.currentTimeMillis() - preparingSince) / 1000;
        String line = preparing + " · " + seconds + " s · " + (preparingArrived == 0
                ? "noch kein Text angekommen (das Modell überlegt oder wartet)"
                : preparingArrived + " Zeichen angekommen");
        setStatus(line);
        sayOnPaper(line);
    }

    /** The plan is there: the chords are said until the first notes arrive. */
    private void showPlanned(List<String> chords) {
        if (!livePicture()) {
            return;
        }
        preparing = null;
        preparingClock.stop();
        StringBuilder said = new StringBuilder("Plan steht · Akkorde: ");
        for (int i = 0; i < chords.size() && i < 16; i++) {
            said.append(i == 0 ? "" : " | ").append(chords.get(i));
        }
        if (chords.size() > 16) {
            said.append(" … (").append(chords.size()).append(" Takte)");
        }
        append(said.toString());
        setStatus(said.toString());
        sayOnPaper(said + " · gleich kommen die Noten");
    }

    /**
     * The form while the AI plans it: the sections appear above the lanes and in the score as the
     * plan names them, with the chords that have arrived. Only shown; the form of the saved piece
     * is shown again when the work is over.
     */
    private void showForm(List<com.aresstack.audio.studio.progress.FormSection> sections) {
        if (!livePicture()) {
            return;
        }
        try {
            List<SectionMark> marks = new ArrayList<SectionMark>();
            int bars = 0;
            for (com.aresstack.audio.studio.progress.FormSection section : sections) {
                marks.add(new SectionMark("plan" + marks.size(), section.firstBar(), section.bars(),
                        section.name() == null ? "…" : section.name(), section.function(), section.intensity(),
                        section.chords()));
                bars = Math.max(bars, section.firstBar() + section.bars());
            }
            if (arrangementView.bars() < bars) {
                // The lanes get the bars of the plan before the first note, so that the sections have their place.
                List<String> lines = new ArrayList<String>();
                lines.add("bars\t" + bars);
                List<String> ids = new ArrayList<String>();
                int melodic = 0;
                for (MusicTrack track : trackTable.tracks()) {
                    boolean drums = MusicTrack.DRUMS.equals(track.role());
                    int next = melodic % 15;
                    if (!drums) {
                        melodic++;
                    }
                    lines.add("track\t" + track.id() + "\t" + track.program() + "\t"
                            + (drums ? 9 : next >= 9 ? next + 1 : next));
                    ids.add(track.id());
                }
                ArrangementView.Arrangement lanes = ArrangementView.Arrangement.of(lines);
                if (lanes != null) {
                    arrangementView.setArrangement(lanes, withAudioLanes(ids), "");
                }
            }
            sectionBar.setSections(marks);
            scoreView.setSections(marks);
        } catch (RuntimeException undrawn) {
            // a look at the plan on its way; the status line says the step as well
        }
    }

    /** The players and the stage before the AI began to choose, to come back to if the choice fails. */
    private TrackArrangement tracksBeforeChoice;
    private com.aresstack.audio.studio.stage.SpatialScene sceneBeforeChoice;

    /**
     * The instruments while the AI chooses them: each appears as a lane and on the stage as soon
     * as it is named. Only shown and saved nowhere; the whole proposal replaces it when it is
     * there, and a choice that fails gives the lanes back as they were.
     */
    private void showChosenInstruments(List<String[]> chosen) {
        if (!livePicture() || chosen.isEmpty()) {
            return;
        }
        boolean loading = loadingTracks;
        try {
            List<MusicTrack> tracks = new ArrayList<MusicTrack>();
            for (String[] one : chosen) {
                tracks.add(new MusicTrack(one[0], one[1], one[2], Integer.parseInt(one[3]), "builtin"));
            }
            if (sceneBeforeChoice == null) {
                sceneBeforeChoice = scene;
                try {
                    tracksBeforeChoice = trackTable.currentArrangement();
                } catch (RuntimeException none) {
                    tracksBeforeChoice = null; // no lanes yet: nothing to come back to
                }
            }
            loadingTracks = true; // shown, not saved
            trackTable.setArrangement(new TrackArrangement(tracks));
            setStatus("Die KI wählt die Instrumente · bisher " + tracks.size() + ": " + chosen.get(chosen.size() - 1)[2]);
        } catch (RuntimeException undrawn) {
            // a look at the choice on its way; the proposal itself does not depend on it
        } finally {
            loadingTracks = loading;
        }
    }

    /** The choice of the AI is there or has failed: {@code taken} keeps what the lanes show now. */
    private void endChoosing(boolean taken) {
        TrackArrangement before = tracksBeforeChoice;
        com.aresstack.audio.studio.stage.SpatialScene stage = sceneBeforeChoice;
        tracksBeforeChoice = null;
        sceneBeforeChoice = null;
        if (taken || before == null) {
            return;
        }
        boolean loading = loadingTracks;
        try {
            loadingTracks = true;
            scene = stage;
            trackTable.setArrangement(before);
        } catch (RuntimeException undrawn) {
            // then the lanes stay as they are
        } finally {
            loadingTracks = loading;
        }
    }

    /** Whether the stage shows a mix that is still being written and has not become a proposal yet. */
    private boolean mixArriving;

    /** The mix while the AI writes it: the players move on the stage, dashed, as their places arrive. */
    private void showMixArriving(com.aresstack.audio.studio.stage.SpatialScene seen) {
        if (!livePicture()) {
            return;
        }
        try {
            mixArriving = true;
            stagePanel.setProposal(seen);
            stagePanel.setStatus("Die KI mischt ab · die Plätze erscheinen gestrichelt, sobald sie ankommen");
        } catch (RuntimeException undrawn) {
            // a look at the mix on its way; the proposal itself does not depend on it
        }
    }

    /** The movements while the AI writes them: the curves appear dashed under the sliders, track by track. */
    private void showMovesArriving(com.aresstack.audio.studio.stage.SpatialScene proposed,
                                   com.aresstack.audio.studio.application.MovementDirector.Proposal seen) {
        if (!livePicture()) {
            return;
        }
        try {
            mixArriving = true;
            stagePanel.setProposal(proposed, seen);
            stagePanel.setStatus("Die KI schlägt Bewegungen vor · bisher für " + seen.tracks()
                    + (seen.tracks() == 1 ? " Spur" : " Spuren"));
        } catch (RuntimeException undrawn) {
            // a look at the movements on their way; the proposal itself does not depend on it
        }
    }

    /** A line on the paper of the score while it has no notes yet. */
    private void sayOnPaper(String line) {
        try {
            List<String> ids = new ArrayList<String>();
            for (MusicTrack track : trackTable.tracks()) {
                ids.add(track.id());
            }
            scoreView.setScore(null, ids, line);
        } catch (RuntimeException undrawn) {
            // the status line says it as well
        }
    }

    /**
     * The piece while the AI writes it: the score on the page of the composition grows with every
     * note that arrives. Only shown; the saved piece is shown again when the work is over.
     */
    private void showSketch(List<String> arrangement) {
        if (!livePicture()) {
            return; // a late picture after the work ended: the score shows the piece itself again
        }
        preparing = null;
        preparingClock.stop();
        try {
            List<String> ids = new ArrayList<String>();
            for (MusicTrack track : trackTable.tracks()) {
                ids.add(track.id());
            }
            for (String line : arrangement) {
                String[] cells = line.split("\t");
                if (cells.length > 1 && "track".equals(cells[0]) && !ids.contains(cells[1])) {
                    ids.add(cells[1]);
                }
            }
            int notes = 0;
            for (String line : arrangement) {
                if (line.startsWith("note\t")) {
                    notes++;
                }
            }
            sketchNotes = notes;
            scoreView.setScore(com.aresstack.audio.studio.score.Score.of(arrangement), ids,
                    "Die KI schreibt · die ersten Noten erscheinen gleich");
            // The lanes of the arrangement grow as well: there the user asked for most of it.
            ArrangementView.Arrangement lanes = ArrangementView.Arrangement.of(arrangement);
            if (lanes != null) {
                arrangementView.setArrangement(lanes, withAudioLanes(ids), "");
            }
        } catch (RuntimeException unreadable) {
            // a picture on the way that cannot be drawn is left out; the next one comes at once
        }
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
    private String saidWaiting;

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
        if (trackStage == TrackProgress.Stage.COMPOSE && sketchNotes == 0 && arrived == 0 && done == 0) {
            // Between the plan and the first note: the requests are out, the service has sent nothing yet.
            String waitingFor = "Noten angefragt: " + working + " Spuren gleichzeitig · " + seconds
                    + " s · der Dienst hat noch keinen Text geschickt";
            if (!waitingFor.equals(saidWaiting)) {
                saidWaiting = waitingFor;
                sayOnPaper(waitingFor);
            }
        }
        setStatus((trackStage == TrackProgress.Stage.COMPOSE ? "KI komponiert" : "Rendern") + ": "
                + working + " gleichzeitig · " + done + " von " + trackStates.size() + " fertig"
                + (waiting > 0 ? " · " + waiting + " warten" : "") + (failed > 0 ? " · " + failed + " Fehler" : "")
                + " · " + seconds + " s"
                // Whether the service answers at all: nothing arrives while it lets the requests wait.
                + (trackStage != TrackProgress.Stage.COMPOSE ? ""
                : arrived == 0 ? (done == 0 ? " · noch kein Text von der KI angekommen" : "")
                : " · " + arrived + " Zeichen von der KI" + (sketchNotes > 0 ? ", " + sketchNotes + " Noten" : "")));
    }

    private void listenAlone(String trackId) {
        playbackSelection.setSelectedItem(trackId);
        play();
    }

    private void togglePlayback() {
        if (player.playing()) {
            player.pause();
            player.dropOutgoing();
            albumPlay = false; // paused by the user: no next title
            return;
        }
        Path current = Paths.get(directory.getText().trim());
        if ("player".equals(shownPage)) {
            playOnPlayerPage(current);
            return;
        }
        // On the album page Play plays the title selected in the album's table, not the one
        // that happens to be open; only to check it: no title follows there.
        Path chosen = "albums".equals(shownPage) && albumsPanel != null ? albumsPanel.selectedPiece() : null;
        if (chosen != null && !same(chosen, current)) {
            openSong(chosen);
            if (!chosen.equals(Paths.get(directory.getText().trim()))) {
                return; // could not be opened (said)
            }
            play();
        } else if (player.ready() && !("albums".equals(shownPage) && player.live())) {
            player.play();
        } else {
            play(); // on the album page the finished mix, not a live stage paused elsewhere
        }
    }

    /** Play on the player page: the open title, or with "Nur neue" a title not heard yet; the album goes on. */
    private void playOnPlayerPage(Path current) {
        String album = playerAlbum(current);
        Path chosen = current;
        if (album != null && !inAlbum(album, current)) {
            // Another album was chosen in the tree: it begins with its first title that has a mix.
            chosen = nextAlbumPiece(album, current);
            if (chosen == null && !playerPanel.onlyNew()) {
                message("In diesem Album ist noch kein Titel fertig gemischt.");
                return;
            }
        }
        if (playerPanel.onlyNew()) {
            java.util.List<Path> fresh = newPieces(album);
            chosen = among(fresh, current) ? current : fresh.isEmpty() ? null : fresh.get(0);
            if (chosen == null) {
                startAlbumWaiting();
                return;
            }
            stopAlbumWaiting();
        }
        if (!same(chosen, current)) {
            openSong(chosen);
            if (!chosen.equals(Paths.get(directory.getText().trim()))) {
                return; // could not be opened (said)
            }
            playAlbumTitle(0);
        } else if (player.ready() && !player.live()) {
            player.play(); // goes on where it was paused
        } else {
            playAlbumTitle(0);
        }
        armAlbumPlay();
    }

    /** Whether the piece is a title of the album. */
    private boolean inAlbum(String albumId, Path piece) {
        Album album = albumMembers.albums().album(albumId).orElse(null);
        return album != null && albumMembers.pieces(album).stream().anyMatch(song -> same(song.folder(), piece));
    }

    private static boolean same(Path one, Path other) {
        return one != null && other != null
                && one.toAbsolutePath().normalize().equals(other.toAbsolutePath().normalize());
    }

    private static boolean among(java.util.List<Path> pieces, Path piece) {
        for (Path each : pieces) {
            if (same(each, piece)) {
                return true;
            }
        }
        return false;
    }

    /** The page "Player": the titles of an album one after the other, chosen in the tree. */
    private final PlayerPanel playerPanel = new PlayerPanel();
    /** The album the player plays; {@code null}: the album of the open title. */
    private String playerAlbumId;

    /**
     * Play pressed on the player page: the next title of the album follows when this one has
     * played to its end. Only while the player page stays open; pause or stop ends it.
     */
    private boolean albumPlay;

    /** Whether the title armed for the album has been heard playing: only then its silence is its end. */
    private boolean albumPlaySounded;

    /** The settings of the player, kept in the settings file. */
    private int albumHeardPercent = com.aresstack.audio.studio.application.HeardTitles.DEFAULT_PERCENT;
    private double albumCrossfadeSeconds;
    private boolean albumOnlyNew;

    /** "Nur neue" and no new title left: the player waits on its page until one is finished. */
    private boolean albumWaiting;
    private long albumWaitingLooked;
    /** How often the waiting player looks for a new title, in milliseconds. */
    private static final long ALBUM_WAITING_LOOK_MILLIS = 1000;

    /** While the next title is opened during an overlap: the title that fades out must go on. */
    private boolean keepFading;

    /** Counts how long the title that plays has been heard. */
    private final com.aresstack.audio.studio.application.HeardTitles.Counter hearing =
            new com.aresstack.audio.studio.application.HeardTitles.Counter();
    private long hearingNanos;
    /** A longer pause between two ticks than this (a busy window) is not counted as heard. */
    private static final double HEARING_GAP_SECONDS = 0.5;

    /** Which pieces are new, as last read for the tree; read again after {@link #NEW_READ_MILLIS}. */
    private final Map<Path, Boolean> newShown = new HashMap<Path, Boolean>();
    private long newShownRead;
    private static final long NEW_READ_MILLIS = 1000;

    /** The visualizer on the player page; made with the page. */
    private VisualizerPanel visualizer;

    /** The page of the player, with the settings it was saved with. */
    private JPanel playerPage() {
        playerPanel.setOptions(albumHeardPercent, albumCrossfadeSeconds, albumOnlyNew);
        playerPanel.onOptions(() -> {
            albumHeardPercent = playerPanel.heardPercent();
            albumCrossfadeSeconds = playerPanel.crossfadeSeconds();
            albumOnlyNew = playerPanel.onlyNew();
            if (!albumOnlyNew) {
                stopAlbumWaiting(); // waiting for new titles belongs to "Nur neue"
            }
            saveSettings(false);
        });
        // The area right of the tree is the visualizer: the heard sound as a picture, or the score to read along.
        visualizer = new VisualizerPanel(player.feed(), scoreView, settingsStore);
        playerPanel.setVisualizer(visualizer);
        playerPanel.addHeadKeys(visualizer.headKeys());
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        DeckWidgets.Module module = new DeckWidgets.Module("Player", playerPanel);
        module.setHeadStart(playerPanel.headKeys());
        page.add(module, BorderLayout.CENTER);
        return page;
    }

    /** The album the player plays: the one chosen in the tree, else the album of the open title. */
    private String playerAlbum(Path current) {
        if (playerAlbumId != null && albumMembers.albums().album(playerAlbumId).isPresent()) {
            return playerAlbumId;
        }
        Album own = albumMembers.albumOf(current).orElse(null);
        return own == null ? null : own.id();
    }

    /** A title was clicked in the tree while the player page is open: it plays now, the album goes on. */
    private void playerTitleClicked(String albumId, Path folder) {
        playerAlbumId = albumId;
        currentAlbum = albumId;
        stopAlbumWaiting();
        openSong(folder);
        if (!same(folder, Paths.get(directory.getText().trim()))) {
            return; // could not be opened (said)
        }
        playAlbumTitle(0);
        armAlbumPlay();
    }

    /** An album was clicked in the tree while the player page is open: Play plays it from now on. */
    private void playerAlbumClicked(String albumId) {
        playerAlbumId = albumId;
        currentAlbum = albumId;
        Album album = albumMembers.albums().album(albumId).orElse(null);
        if (!player.playing() && album != null) {
            playerPanel.setNowPlaying(album.title(), null);
        }
    }

    /**
     * The finished titles of an album that nobody has heard yet, in the order of the album; a
     * title that is being made right now is not finished.
     */
    private java.util.List<Path> newPieces(String albumId) {
        java.util.List<Path> found = new java.util.ArrayList<Path>();
        Album album = albumId == null ? null : albumMembers.albums().album(albumId).orElse(null);
        if (album == null) {
            return found;
        }
        com.aresstack.audio.studio.album.AlbumProductionState state =
                com.aresstack.audio.studio.album.AlbumProductionState.load(albumMembers.albums().folder(album.id()));
        for (com.aresstack.audio.studio.album.AlbumTrack track : album.tracks()) {
            if (!track.hasPiece()) {
                continue;
            }
            com.aresstack.audio.studio.album.AlbumProductionState.Status status = state.status(track.id());
            if (status == com.aresstack.audio.studio.album.AlbumProductionState.Status.RUNNING
                    || status == com.aresstack.audio.studio.album.AlbumProductionState.Status.QUEUED) {
                continue; // being made: not finished yet
            }
            Path piece = songs.root().resolve(track.piece());
            if (com.aresstack.audio.studio.application.HeardTitles.isNew(piece)) {
                found.add(piece);
            }
        }
        return found;
    }

    /** Whether the tree marks this title as not heard yet: only while the player page is open. */
    private boolean newInTree(Path piece) {
        if (!"player".equals(shownPage) || piece == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - newShownRead > NEW_READ_MILLIS) {
            newShown.clear();
            newShownRead = now;
        }
        return newShown.computeIfAbsent(piece, com.aresstack.audio.studio.application.HeardTitles::isNew);
    }

    /** A title was heard or became new: the tree shows it at once. */
    private void heardChanged() {
        newShownRead = 0;
        if (albumTree != null) {
            albumTree.repaint();
        }
    }

    private void armAlbumPlay() {
        albumPlay = "player".equals(shownPage);
        albumPlaySounded = false;
        showNowPlaying();
    }

    /** Shows on the player page which title of which album plays. */
    private void showNowPlaying() {
        Path current = Paths.get(directory.getText().trim());
        Album album = albumMembers.albumOf(current).orElse(null);
        String title = current.getFileName() == null ? "" : current.getFileName().toString();
        playerPanel.setNowPlaying(album == null ? null : album.title(), title);
    }

    /** Whether the player page is open and free to go on with the next title. */
    private boolean playerPageFree() {
        return "player".equals(shownPage) && (worker == null || worker.isDone());
    }

    /** Called thirty times a second: a title that played to its end on the player page is followed by the next one. */
    private void followAlbumPlay(boolean playing) {
        player.settle();
        if (albumWaiting) {
            lookForNewTitle(playing);
            return;
        }
        if (!albumPlay) {
            return;
        }
        if (playing) {
            albumPlaySounded = true;
            overlapNextTitle();
            return;
        }
        if (!albumPlaySounded) {
            return; // still getting ready to play
        }
        albumPlay = false;
        if (!playerPageFree()) {
            return; // the player page was closed meanwhile: the playback ends with this title
        }
        Path next = nextForAlbum();
        if (next == null) {
            if (playerPanel.onlyNew()) {
                startAlbumWaiting(); // the next title that is finished plays
            }
            return; // the last title of the album
        }
        goToAlbumTitle(next, 0);
    }

    /** Near the end of a title the next one comes in while this one fades out, if the setting says so. */
    private void overlapNextTitle() {
        double fade = playerPanel.crossfadeSeconds();
        if (!(fade > 0) || player.live() || player.fading() || !playerPageFree()) {
            return;
        }
        double length = player.length();
        double left = length - player.seconds();
        if (!(length > 0) || left > fade || left <= 0) {
            return;
        }
        Path next = nextForAlbum();
        Path fading = heardPiece();
        if (next == null || !player.fadeOut(left)) {
            return; // nothing follows: this title plays to its end as it is
        }
        // The tail that fades out is heard beside the next title: it counts for this one now.
        if (fading != null && hearing.heard(fading, left, length, albumHeardPercent)
                && !com.aresstack.audio.studio.application.HeardTitles.heard(fading)) {
            try {
                com.aresstack.audio.studio.application.HeardTitles.markHeard(fading);
            } catch (IOException failed) {
                append("WARN: Dass „" + fading.getFileName() + "“ gehört ist, ließ sich nicht vermerken: "
                        + failed.getMessage());
            }
            heardChanged();
        }
        albumPlay = false;
        goToAlbumTitle(next, left);
    }

    /** The title that follows: the next one of the album with a mix, or with "Nur neue" a new one. */
    private Path nextForAlbum() {
        Path current = Paths.get(directory.getText().trim());
        String album = playerAlbum(current);
        if (album == null) {
            return null;
        }
        if (playerPanel.onlyNew()) {
            for (Path each : newPieces(album)) {
                if (!same(each, current)) {
                    return each;
                }
            }
            return null;
        }
        return nextAlbumPiece(album, current);
    }

    /** Opens the next title of the album and plays it; with an overlap at once, coming in over {@code fade}. */
    private void goToAlbumTitle(Path next, double fade) {
        String album = playerAlbum(Paths.get(directory.getText().trim()));
        if (album != null) {
            currentAlbum = album;
        }
        keepFading = fade > 0;
        try {
            openSong(next);
        } finally {
            keepFading = false;
        }
        if (!next.equals(Paths.get(directory.getText().trim()))) {
            player.dropOutgoing();
            return; // the piece could not be opened (said)
        }
        if (fade > 0) {
            playAlbumTitle(fade); // the overlap begins now, not after the window has caught up
            armAlbumPlay();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!"player".equals(shownPage)) {
                return;
            }
            playAlbumTitle(0);
            armAlbumPlay();
        });
    }

    private void startAlbumWaiting() {
        albumWaiting = true;
        albumWaitingLooked = System.currentTimeMillis();
        playerPanel.setNote("Wartet auf einen neuen Titel …");
    }

    private void stopAlbumWaiting() {
        if (albumWaiting) {
            playerPanel.setNote("");
        }
        albumWaiting = false;
    }

    /** Waiting with "Nur neue": the first title that is finished and not heard plays. */
    private void lookForNewTitle(boolean playing) {
        if (playing || !"player".equals(shownPage) || !playerPanel.onlyNew()) {
            stopAlbumWaiting(); // something else plays, or the page was left
            return;
        }
        long now = System.currentTimeMillis();
        if (now - albumWaitingLooked < ALBUM_WAITING_LOOK_MILLIS || !playerPageFree()) {
            return;
        }
        albumWaitingLooked = now;
        java.util.List<Path> fresh = newPieces(playerAlbum(Paths.get(directory.getText().trim())));
        if (!fresh.isEmpty()) {
            stopAlbumWaiting();
            goToAlbumTitle(fresh.get(0), 0);
        }
    }

    /** The finished mix of the open title, read from the disk while it plays: it starts at once. */
    private void playAlbumTitle(double fadeIn) {
        Path mix = Paths.get(directory.getText().trim()).resolve(com.aresstack.audio.studio.application.HeardTitles.MIX);
        if (!Files.isRegularFile(mix)) {
            player.dropOutgoing();
            message("Dieser Titel ist noch nicht fertig gemischt: " + mix.getFileName());
            return;
        }
        try {
            player.open(mix);
            lightsOfFile(null);
            arrangementView.setMix(player.envelope(), "Mix", null, player.length());
            lcd.setSource(playbackName("Mixed sampled arrangement"), true);
            if (fadeIn > 0) {
                player.fadeIn(fadeIn);
            }
            player.play();
        } catch (Exception error) {
            player.dropOutgoing();
            message("Cannot play " + mix + ": " + error.getMessage());
        }
    }

    /**
     * While the mix of the open piece plays as one file, the stage lights of the visualizer still
     * know its tracks: places and movements from its stage, levels from its rendered tracks or notes.
     */
    private void lightsOfFile(String only) {
        player.lightsOf(pieceToMix(), modelToMix(), Paths.get(directory.getText().trim()), scene, only);
    }

    /** Counts what is heard of the title that plays; once enough is heard it is no longer new. */
    private void countHearing(boolean playing) {
        long now = System.nanoTime();
        double more = hearingNanos == 0 ? 0 : (now - hearingNanos) / 1e9;
        hearingNanos = playing ? now : 0;
        if (!playing || more > HEARING_GAP_SECONDS) {
            return;
        }
        Path piece = heardPiece();
        if (piece == null || !hearing.heard(piece, more, player.length(), albumHeardPercent)
                || com.aresstack.audio.studio.application.HeardTitles.heard(piece)) {
            return;
        }
        try {
            com.aresstack.audio.studio.application.HeardTitles.markHeard(piece);
        } catch (IOException failed) {
            append("WARN: Dass „" + piece.getFileName() + "“ gehört ist, ließ sich nicht vermerken: "
                    + failed.getMessage());
        }
        heardChanged();
    }

    /** The piece whose mix plays now; {@code null} while a track plays alone. */
    private Path heardPiece() {
        if (player.live()) {
            return soloTrack() == null ? Paths.get(directory.getText().trim()) : null;
        }
        Path file = player.file();
        return file != null && file.getFileName() != null
                && com.aresstack.audio.studio.application.HeardTitles.MIX.equals(file.getFileName().toString())
                ? file.getParent() : null;
    }

    /**
     * The tree at the left says what the page shows: while the album page is open its album is
     * selected there, not a title (the title in use is selected again when a piece page shows).
     */
    private void markAlbumInTree() {
        if (albumTree == null) {
            return;
        }
        if ("albums".equals(shownPage)) {
            if (albumsPanel != null && albumsPanel.album() != null) {
                albumTree.selectAlbum(albumsPanel.album().id());
            }
        } else if (PIECE_PAGES.contains(shownPage) && albumMarked) {
            albumMarked = false;
            refreshSongs(); // the title in use is selected again
            return;
        }
        albumMarked = "albums".equals(shownPage);
    }

    /** Whether the tree shows the album of the album page instead of the title in use. */
    private boolean albumMarked;

    /** The title after this piece in the album that has a mix to play; {@code null} if none follows. */
    private Path nextAlbumPiece(String albumId, Path current) {
        Album album = albumMembers.albums().album(albumId).orElse(null);
        if (album == null) {
            return null;
        }
        java.util.List<com.aresstack.audio.studio.application.SongLibrary.Song> pieces = albumMembers.pieces(album);
        int at = -1;
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).folder().toAbsolutePath().normalize().equals(current.toAbsolutePath().normalize())) {
                at = i;
            }
        }
        for (int i = at + 1; i < pieces.size(); i++) {
            Path folder = pieces.get(i).folder();
            if (Files.isRegularFile(folder.resolve("arrangement-instruments.wav"))) {
                return folder;
            }
        }
        return null;
    }

    private void play() {
        if ("albums".equals(shownPage) || "player".equals(shownPage)) {
            playAlbumTitle(0); // a title is listened to: its finished mix, at once
            return;
        }
        String selected = (String) playbackSelection.getSelectedItem();
        Path output = playbackFile(selected);
        if (output == null || !Files.isRegularFile(output)) {
            message("Dazu gibt es noch keine Aufnahme: " + (output == null ? "" : output.getFileName())
                    + "\nErst „Demo laden“ oder komponieren, dann „Rendern“.");
            return;
        }
        if (onStage(selected)) {
            // The stage is played live: what is moved on it is heard while it plays.
            if (!output.equals(player.file())) {
                player.show(output);
                arrangementView.setMix(player.envelope(), soloTrack() == null ? "Mix" : "Solo", soloTrack(),
                        player.length());
            }
            lcd.setSource(playbackName(selected), true);
            openLive(player.seconds(), true);
            return;
        }
        playFile(selected, 0, true);
    }

    /** Plays the file behind a choice of the playback, as before the stage could be played live. */
    private void playFile(String selected, double at, boolean play) {
        Path output = playbackFile(selected);
        try {
            player.open(output);
            lightsOfFile(soloTrack());
            arrangementView.setMix(player.envelope(), soloTrack() == null ? "Mix" : "Solo", soloTrack(), player.length());
            lcd.setSource(playbackName(selected), true);
            if (at > 0 && player.length() > 0) {
                player.seek(at / player.length());
            }
            if (play) {
                player.play();
            }
        } catch (Exception error) {
            message("Cannot play " + output + ": " + error.getMessage());
        }
    }

    private void stopPlayback() {
        albumPlay = false;
        if (!keepFading) {
            player.dropOutgoing(); // Stop silences a title that still fades out too
            stopAlbumWaiting();
        }
        player.pause();
        player.seek(0);
    }

    private void seek(double share) {
        if (!player.ready() && player.file() != null) {
            String selected = (String) playbackSelection.getSelectedItem();
            if (onStage(selected)) {
                // A jump before anything played: the stage is got ready live, paused at that place.
                DeckPlayer.Envelope shown = player.envelope();
                if (shown != null) {
                    openLive(Math.max(0, Math.min(1, share)) * shown.seconds, false);
                }
                return;
            }
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
    private final com.aresstack.audio.studio.application.LimiterReadout limiterReadout =
            new com.aresstack.audio.studio.application.LimiterReadout();

    private void tick() {
        boolean playing = player.playing();
        countHearing(playing);
        followAlbumPlay(playing);
        double second = player.seconds();
        double length = player.length();
        lcd.setPlaying(playing);
        lcd.setTime(second, length);
        lcd.tick();
        arrangementView.setSecond(second);
        scoreView.setSecond(playing || second > 0 ? second : -1);
        masterLeft.setLevel(player.level(false));
        masterRight.setLevel(player.level(true));
        if (trackTable != null) {
            trackTable.pulse();
        }
        if (stagePanel != null) {
            // Live only: a file and an export are mixed as before and have no limiter to show.
            stagePanel.setLimiter(limiterReadout.read(player.limiterGain(), System.nanoTime()));
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

    /** The page shown now, so that a click on another title keeps the view the user chose. */
    private String shownPage = "song";

    /** The pages that show the open piece; switching titles stays on them. */
    private static final java.util.Set<String> PIECE_PAGES = java.util.Set.of("song", "deck", "stage", "console");

    /** Shows the open piece: on the page of the piece the user looks at, else on the composition page. */
    private void showPiecePage() {
        showPage(PIECE_PAGES.contains(shownPage) ? shownPage : "song");
    }

    private void showPage(String page) {
        shownPage = page;
        pages.show(pageHolder, page);
        markAlbumInTree();
        heardChanged(); // titles not heard yet are marked in the tree on the player page only
        javax.swing.JToggleButton key = pageKeys.get(page);
        if (key != null) {
            key.setSelected(true);
        } else {
            pageGroup.clearSelection();
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

    /** A notice for the user: in the status line and the log, never as a dialog that has to be clicked away. */
    private void message(String text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> message(text));
            return;
        }
        String line = text == null ? "" : text.trim();
        String said = line.startsWith("WARN") || line.startsWith("ERROR") ? line : "WARN: " + line;
        append(said);
        setStatus(said); // also while tracks are at work: the notice answers what the user just did
    }

    /** A number from the settings, or the default if it is missing or not a number. */
    private static double number(Properties saved, String key, double fallback) {
        try {
            return Double.parseDouble(saved.getProperty(key, Double.toString(fallback)).trim());
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }

    private int parseParallel(String configured) {
        try {
            return Math.max(1, Math.min(1024, Integer.parseInt(configured)));
        } catch (NumberFormatException invalid) {
            return 32;
        }
    }

    private void loadArrangementForCurrentDirectory(Properties saved) {
        // The piece as a model first: lanes, score, sections and the stage are drawn from it right
        // below, and before they showed the model of the piece opened before (or the old file at
        // the start, which for a longer piece with tempo changes still says its first eight bars).
        // An old piece is taken into the model here, its old files stay as they are.
        String chosen = directory.getText().trim();
        pieceModel.open(chosen.isEmpty() ? null : Paths.get(chosen));
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
        showMovements();
        stageFollowsMix.remember(pieceToEdit().orElse(null)); // the stage as it stands belongs to this mix
        answersState = pieceToEdit().map(com.aresstack.audio.studio.application.ProjectSession::composition)
                .orElse(null);
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

    /** What a background operation wants done in the window once it has ended. */
    private volatile Runnable afterWork;

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
