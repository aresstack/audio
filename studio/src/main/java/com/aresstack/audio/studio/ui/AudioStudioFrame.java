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
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
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
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/** Keep UI state and interactions here; delegate model calls, rendering and persistence to services. */
public final class AudioStudioFrame extends JFrame {
    private static final String[] PART_NAMES = {"melody", "harmony", "bass", "drums"};
    private static final int[] DEFAULT_PROGRAMS = {10, 48, 32, 0};
    private final InstrumentRegistry registry;
    private final StudioSettingsStore settingsStore = StudioSettingsStore.standard();
    private final InstrumentLibrary builtin = new InstrumentLibrary("builtin", "Java synthesis (built in)",
            "Fallback", InstrumentLibrary.Format.SF2, "Built into audio", "", "No sample bank required.");
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
    private final JTextField fluidSynth = new JTextField("fluidsynth", 17);
    private final JTextField sfizz = new JTextField("sfizz_render", 17);
    private final JTextArea description = new JTextArea(
            "A charming pastel city slowly grows; compose a playful, cozy, uplifting orchestral theme.", 3, 54);
    private final JTextArea log = new JTextArea(10, 70);
    private final JComboBox<InstrumentLibrary>[] selections;
    private final JSpinner[] midiPrograms = new JSpinner[PART_NAMES.length];
    private final JComboBox<String> playbackSelection = new JComboBox<String>(new String[]{
            "Mixed sampled arrangement", "Java reference mix", "melody", "harmony", "bass", "drums"
    });
    private final JButton generateButton = new JButton("Mit Gemma komponieren");
    private final JButton renderButton = new JButton("Rendern");
    private final JButton cancelButton = new JButton("Abbrechen");
    private SwingWorker<Path, String> worker;
    private volatile KipitzChatClient activeClient;
    private String preferredKeePassEntry = "";
    private Clip currentPlayback;

    @SuppressWarnings("unchecked")
    public AudioStudioFrame(InstrumentRegistry registry) {
        super("AresStack Audio Studio – Gemma, MIDI, SoundFonts und SFZ");
        this.registry = registry;
        this.selections = (JComboBox<InstrumentLibrary>[]) new JComboBox<?>[PART_NAMES.length];
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(880, 660));
        setSize(1100, 790);
        setLocationRelativeTo(null);
        log.setEditable(false);
        log.setLineWrap(true);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        initializeContents();
        loadSettings();
        cancelButton.setEnabled(false);
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
    }

    private void initializeContents() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("1 · Bibliotheken installieren",
                new InstrumentLibraryPanel(registry, this::configureNetworkRoutes,
                        this::refreshInstrumentChoices, this::append));
        tabs.addTab("2 · Komponieren und abspielen", compositionPanel());
        tabs.addTab("3 · Einstellungen", settingsPanel());
        add(tabs, BorderLayout.CENTER);
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));
        JLabel headline = new JLabel("AresStack  |  Audio Studio");
        headline.setFont(headline.getFont().deriveFont(java.awt.Font.BOLD, 21f));
        header.add(headline, BorderLayout.NORTH);
        JLabel subtitle = new JLabel("Komponieren mit KI  ·  Instrumente verwalten  ·  WAV / MIDI rendern");
        subtitle.setForeground(new java.awt.Color(0x526D7C));
        header.add(subtitle, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        footer.add(new JScrollPane(log), BorderLayout.CENTER);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Arbeitsprotokoll"),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        add(footer, BorderLayout.SOUTH);
    }

    private JPanel compositionPanel() {
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBorder(AudioStudioTheme.cardBorder());
        int row = 0;
        addRow(fields, row++, "Musikalische Beschreibung:", new JScrollPane(description));
        JPanel output = new JPanel(new BorderLayout(4, 0));
        output.add(directory, BorderLayout.CENTER);
        JButton chooseOutput = new JButton("Ordner …");
        chooseOutput.addActionListener(event -> selectDirectory());
        output.add(chooseOutput, BorderLayout.EAST);
        addRow(fields, row++, "Ausgabe / Cache:", output);
        JPanel tsv = new JPanel(new BorderLayout(4, 0));
        tsv.add(arrangementFile, BorderLayout.CENTER);
        JButton selectTsv = new JButton("TSV …");
        selectTsv.addActionListener(event -> selectArrangement());
        JPanel chooseButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        chooseButtons.add(selectTsv);
        tsv.add(chooseButtons, BorderLayout.EAST);
        addRow(fields, row++, "Arrangement:", tsv);

        for (int index = 0; index < PART_NAMES.length; index++) {
            JComboBox<InstrumentLibrary> choice = new JComboBox<InstrumentLibrary>();
            choice.addItem(builtin);
            for (InstrumentLibrary installed : registry.installedInstruments()) {
                choice.addItem(installed);
            }
            selections[index] = choice;
            JSpinner program = new JSpinner(new SpinnerNumberModel(DEFAULT_PROGRAMS[index], 0, 127, 1));
            program.setToolTipText("Zero-based General MIDI program (0–127); ignored for SFZ presets.");
            program.setEnabled(index != 3);
            midiPrograms[index] = program;
            JPanel trackControls = new JPanel(new BorderLayout(8, 0));
            trackControls.add(choice, BorderLayout.CENTER);
            JPanel programControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
            programControls.add(new JLabel("GM patch (0–127):"));
            programControls.add(program);
            trackControls.add(programControls, BorderLayout.EAST);
            addRow(fields, row++, PART_NAMES[index] + ":", trackControls);
        }
        JPanel steps = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton newSong = new JButton("1 · Neues Stück");
        newSong.addActionListener(event -> createNewComposition());
        steps.add(newSong);
        JButton demo = new JButton("Demo laden (ohne KI)");
        demo.addActionListener(event -> loadDemo());
        steps.add(demo);
        generateButton.setText("2 · Mit Gemma komponieren");
        steps.add(generateButton);
        renderButton.setText("3 · Rendern");
        steps.add(renderButton);
        JButton listen = new JButton("4 · Anhören");
        listen.addActionListener(event -> play());
        steps.add(listen);

        JPanel secondary = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        secondary.add(new JLabel("Wiedergabe:"));
        secondary.add(playbackSelection);
        JButton stop = new JButton("Stopp");
        stop.addActionListener(event -> stopPlayback());
        secondary.add(stop);
        secondary.add(cancelButton);
        JButton inspect = new JButton("Plan / Spuren");
        inspect.addActionListener(event -> inspectComposition());
        secondary.add(inspect);
        JPanel lower = new JPanel(new BorderLayout());
        lower.add(secondary, BorderLayout.WEST);
        JPanel toolbar = new JPanel(new BorderLayout(5, 2));
        toolbar.add(steps, BorderLayout.NORTH);
        toolbar.add(lower, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(4, 8));
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(new JScrollPane(fields), BorderLayout.CENTER);
        return panel;
    }

    /** Group external AI, KeePass, network and sampler configuration in a dedicated tab. */
    private JPanel settingsPanel() {
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBorder(AudioStudioTheme.cardBorder());
        int row = 0;
        addRow(fields, row++, "KI-Anbieter:", new JLabel("OpenAI-kompatible KIPITZ-API"));
        addRow(fields, row++, "KIPITZ Completions URL:", endpoint);
        JPanel credential = new JPanel(new BorderLayout(6, 0));
        credential.add(apiKey, BorderLayout.CENTER);
        JPanel credentialButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        JButton keepassList = new JButton("KeePass verbinden / Einträge laden");
        keepassList.addActionListener(event -> loadKeePassEntries());
        credentialButtons.add(keepassList);
        JButton keepassUse = new JButton("Eintrag übernehmen");
        keepassUse.addActionListener(event -> useKeePassEntry());
        credentialButtons.add(keePassEntries);
        credentialButtons.add(keepassUse);
        credential.add(credentialButtons, BorderLayout.EAST);
        addRow(fields, row++, "API-Key (lokal gespeichert):", credential);
        JPanel modelControls = new JPanel(new BorderLayout(6, 0));
        modelControls.add(model, BorderLayout.CENTER);
        JButton listModels = new JButton("Modelle laden");
        listModels.addActionListener(event -> loadModelNames());
        modelControls.add(listModels, BorderLayout.EAST);
        addRow(fields, row++, "Modell:", modelControls);
        addRow(fields, row++, "Proxy-Modus:", proxyMode);
        JPanel proxyHostAndPort = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        proxyHostAndPort.add(new JLabel("Host:"));
        proxyHostAndPort.add(proxyHost);
        proxyHostAndPort.add(new JLabel("Port:"));
        proxyHostAndPort.add(proxyPort);
        JButton testProxy = new JButton("Proxyweg prüfen");
        testProxy.addActionListener(event -> testProxyRoute());
        proxyHostAndPort.add(testProxy);
        addRow(fields, row++, "Manueller Proxy:", proxyHostAndPort);
        addRow(fields, row++, "PAC-Adresse (optional):", proxyPacUrl);
        addRow(fields, row++, "Ohne Proxy für:", proxyNonProxyHosts);
        JPanel proxyAuthentication = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        proxyAuthentication.add(proxyBasic);
        proxyAuthentication.add(new JLabel("KeePass-Eintrag:"));
        proxyAuthentication.add(proxyCredentialRef);
        addRow(fields, row++, "Proxy-Anmeldung:", proxyAuthentication);
        addRow(fields, row++, "Token-Limit pro Antwort:", tokenLimit);
        addRow(fields, row++, "Token-Hinweis:",
                new JLabel("Maximale Antwortlänge; der kurze Kompositionsplan nutzt höchstens 2.048 Tokens."));
        addRow(fields, row++, "Audio-Renderer:", new JLabel("Externe Instrumenten-Engines"));
        JPanel engines = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        engines.add(new JLabel("FluidSynth:"));
        engines.add(fluidSynth);
        engines.add(new JLabel("sfizz:"));
        engines.add(sfizz);
        JButton fluidHelp = new JButton("FluidSynth installieren …");
        fluidHelp.addActionListener(event -> openEngineWebsite("https://www.fluidsynth.org/download/"));
        engines.add(fluidHelp);
        JButton sfizzHelp = new JButton("sfizz installieren …");
        sfizzHelp.addActionListener(event -> openEngineWebsite("https://github.com/sfztools/sfizz/releases"));
        engines.add(sfizzHelp);
        addRow(fields, row++, "Sampler:", engines);
        addRow(fields, row++, "Hinweis:",
                new JLabel("SF2/SF3 benötigen FluidSynth, SFZ benötigt sfizz; ohne Engine funktioniert die Java-Synthese."));


        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        JButton save = new JButton("Einstellungen speichern");
        save.addActionListener(event -> saveSettings(true));
        actionBar.add(save);

        JPanel panel = new JPanel(new BorderLayout(4, 8));
        panel.add(new JScrollPane(fields), BorderLayout.CENTER);
        panel.add(actionBar, BorderLayout.SOUTH);
        return panel;
    }

    private void addRow(JPanel panel, int row, String name, java.awt.Component component) {
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.gridy = row;
        label.insets = new Insets(5, 5, 5, 10);
        label.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel(name), label);
        GridBagConstraints field = new GridBagConstraints();
        field.gridx = 1;
        field.gridy = row;
        field.weightx = 1;
        field.fill = GridBagConstraints.HORIZONTAL;
        field.anchor = GridBagConstraints.NORTHWEST;
        field.insets = new Insets(4, 4, 4, 4);
        panel.add(component, field);
    }

    private void generate() {
        String url = endpoint.getText().trim();
        if (url.isEmpty()) {
            message("Enter the exact KIPITZ Completions URL first.");
            return;
        }
        char[] password = apiKey.getPassword();
        final String key = new String(password);
        Arrays.fill(password, '\0');
        final String selectedUrl = url;
        final String selectedModel = model.getText().trim();
        final int selectedTokenLimit = (Integer) tokenLimit.getValue();
        final AudioProxyRoutes routes;
        try {
            routes = configureNetworkRoutes();
        } catch (RuntimeException invalidProxy) {
            message("Ungültige Proxy-Einstellung: " + invalidProxy.getMessage());
            return;
        }
        final String prompt = description.getText().trim();
        final Path output = Paths.get(directory.getText().trim());
        final RenderSettings settings = captureRenderSettings();
        if (!saveSettings(false)) {
            return;
        }
        start(new Operation() {
            @Override
            public Path execute(GemmaMusicComposer.Progress progress) throws Exception {
                KipitzChatClient client = new KipitzChatClient(selectedUrl, key, selectedModel, routes);
                activeClient = client;
                try {
                    Path tsv = new GemmaMusicComposer(client, selectedTokenLimit)
                            .compose(prompt, output, progress);
                    return renderArrangement(tsv, output, settings, progress);
                } finally {
                    activeClient = null;
                }
            }
        });
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
        Map<String, InstrumentLibrary> assignments = new HashMap<String, InstrumentLibrary>();
        Map<String, Integer> programs = new HashMap<String, Integer>();
        for (int index = 0; index < PART_NAMES.length; index++) {
            InstrumentLibrary library = (InstrumentLibrary) selections[index].getSelectedItem();
            if (library != null && !"builtin".equals(library.id())) {
                assignments.put(PART_NAMES[index], library);
            }
            programs.put(PART_NAMES[index], (Integer) midiPrograms[index].getValue());
        }
        return new RenderSettings(assignments, programs, fluidSynth.getText().trim(),
                sfizz.getText().trim());
    }

    private Path renderArrangement(Path source, Path destination, RenderSettings settings,
                                   GemmaMusicComposer.Progress progress) throws Exception {
        return new StudioMusicRenderer(registry).render(source, destination, settings.assignments,
                settings.programs, settings.fluidSynthExecutable, settings.sfizzExecutable, progress);
    }

    private void createNewComposition() {
        Path newDirectory = Paths.get(System.getProperty("user.home"), ".aresstack",
                "audio", "compositions", "song-" + System.currentTimeMillis());
        directory.setText(newDirectory.toString());
        arrangementFile.setText("");
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
            fluidSynth.setText(saved.getProperty("fluidSynth", fluidSynth.getText()));
            sfizz.setText(saved.getProperty("sfizz", sfizz.getText()));
            description.setText(saved.getProperty("description", description.getText()));
            for (int index = 0; index < PART_NAMES.length; index++) {
                String part = PART_NAMES[index];
                String program = saved.getProperty("program." + part);
                if (program != null) {
                    try {
                        midiPrograms[index].setValue(Integer.valueOf(program));
                    } catch (IllegalArgumentException invalidSetting) {
                        append("Ungültige MIDI-Programmnummer für " + part + " ignoriert.");
                    }
                }
                String selectionId = saved.getProperty("instrument." + part, "builtin");
                for (int item = 0; item < selections[index].getItemCount(); item++) {
                    InstrumentLibrary library = selections[index].getItemAt(item);
                    if (library.id().equals(selectionId)) {
                        selections[index].setSelectedIndex(item);
                        break;
                    }
                }
            }
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
            saved.setProperty("fluidSynth", fluidSynth.getText().trim());
            saved.setProperty("sfizz", sfizz.getText().trim());
            saved.setProperty("description", description.getText());
            for (int index = 0; index < PART_NAMES.length; index++) {
                String part = PART_NAMES[index];
                InstrumentLibrary selection = (InstrumentLibrary) selections[index].getSelectedItem();
                saved.setProperty("instrument." + part,
                        selection == null ? "builtin" : selection.id());
                saved.setProperty("program." + part, midiPrograms[index].getValue().toString());
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
        renderButton.setEnabled(false);
        cancelButton.setEnabled(true);
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
                renderButton.setEnabled(true);
                cancelButton.setEnabled(false);
                try {
                    Path result = get();
                    arrangementFile.setText(result.getParent().resolve("arrangement.tsv").toString());
                    append("Finished: " + result);
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
        }
    }

    private void selectArrangement() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            arrangementFile.setText(chooser.getSelectedFile().toString());
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
            append("Loaded original Gemma composition (no API call): " + destination);
        } catch (IOException error) {
            message(error.getMessage());
        }
    }

    private void refreshInstrumentChoices() {
        java.util.List<InstrumentLibrary> installed = registry.installedInstruments();
        for (JComboBox<InstrumentLibrary> choice : selections) {
            InstrumentLibrary previous = (InstrumentLibrary) choice.getSelectedItem();
            String previousId = previous == null ? "" : previous.id();
            choice.removeAllItems();
            choice.addItem(builtin);
            for (InstrumentLibrary library : installed) {
                choice.addItem(library);
                if (library.id().equals(previousId)) {
                    choice.setSelectedItem(library);
                }
            }
        }
    }

    private void inspectComposition() {
        StringBuilder document = new StringBuilder();
        Path folder = Paths.get(directory.getText().trim());
        for (String file : new String[]{"composition-plan.json", "melody.json", "harmony.json",
                "bass.json", "drums.json"}) {
            Path current = folder.resolve(file);
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

    private void play() {
        String selected = (String) playbackSelection.getSelectedItem();
        String file = "Mixed sampled arrangement".equals(selected) ? "arrangement-instruments.wav"
                : "Java reference mix".equals(selected) ? "arrangement.wav"
                : selected + "-sampled.wav";
        Path output = Paths.get(directory.getText().trim(), file);
        if (!Files.isRegularFile(output) && !selected.endsWith("mix")
                && !"Mixed sampled arrangement".equals(selected)) {
            output = Paths.get(directory.getText().trim(), selected + ".wav");
        }
        try {
            stopPlayback();
            try (AudioInputStream audio = AudioSystem.getAudioInputStream(output.toFile())) {
                currentPlayback = AudioSystem.getClip();
                currentPlayback.open(audio);
                currentPlayback.start();
            }
        } catch (Exception error) {
            message("Cannot play " + output + ": " + error.getMessage());
        }
    }

    private void stopPlayback() {
        if (currentPlayback != null) {
            currentPlayback.stop();
            currentPlayback.close();
            currentPlayback = null;
        }
    }

    private void append(String text) {
        log.append(text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void message(String text) {
        JOptionPane.showMessageDialog(this, text, "Audio Studio", JOptionPane.WARNING_MESSAGE);
    }

    private interface Operation {
        Path execute(GemmaMusicComposer.Progress progress) throws Exception;
    }

    private static final class RenderSettings {
        private final Map<String, InstrumentLibrary> assignments;
        private final Map<String, Integer> programs;
        private final String fluidSynthExecutable;
        private final String sfizzExecutable;

        private RenderSettings(Map<String, InstrumentLibrary> assignments,
                               Map<String, Integer> programs, String fluidSynthExecutable,
                               String sfizzExecutable) {
            this.assignments = assignments;
            this.programs = programs;
            this.fluidSynthExecutable = fluidSynthExecutable;
            this.sfizzExecutable = sfizzExecutable;
        }
    }

}
