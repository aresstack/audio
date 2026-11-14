package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.KipitzChatClient;
import com.aresstack.audio.studio.application.GemmaMusicComposer;
import com.aresstack.audio.studio.application.StudioMusicRenderer;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
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
    private final InstrumentRegistry registry;
    private final java.util.List<InstrumentLibrary> libraries = InstrumentCatalog.standard();
    private final InstrumentLibrary builtin = new InstrumentLibrary("builtin", "Java synthesis (built in)",
            "Fallback", InstrumentLibrary.Format.SF2, "Built into audio", "", "No sample bank required.");
    private final JTextField endpoint = new JTextField(38);
    private final JPasswordField apiKey = new JPasswordField(38);
    private final JTextField model = new JTextField("google/gemma-4-31B-it", 23);
    private final JTextField proxyHost = new JTextField(16);
    private final JTextField proxyPort = new JTextField("8080", 5);
    private final JTextField directory = new JTextField(Paths.get(System.getProperty("user.home"),
            ".aresstack", "audio", "compositions", "district-dawn").toString(), 38);
    private final JTextField arrangementFile = new JTextField(38);
    private final JTextField fluidSynth = new JTextField("fluidsynth", 17);
    private final JTextField sfizz = new JTextField("sfizz_render", 17);
    private final JTextArea description = new JTextArea(
            "A charming pastel city slowly grows; compose a playful, cozy, uplifting orchestral theme.", 3, 54);
    private final JTextArea log = new JTextArea(10, 70);
    private final JComboBox<InstrumentLibrary>[] selections;
    private final LibraryTableModel tableModel = new LibraryTableModel();
    private final JTable libraryTable = new JTable(tableModel);
    private final JComboBox<String> playbackSelection = new JComboBox<String>(new String[]{
            "Mixed sampled arrangement", "Java reference mix", "melody", "harmony", "bass", "drums"
    });
    private final JButton generateButton = new JButton("Mit Gemma komponieren");
    private final JButton renderButton = new JButton("Rendern");
    private final JButton cancelButton = new JButton("Abbrechen");
    private SwingWorker<Path, String> worker;
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
        cancelButton.setEnabled(false);
        generateButton.addActionListener(event -> generate());
        renderButton.addActionListener(event -> render());
        cancelButton.addActionListener(event -> cancel());
    }

    private void initializeContents() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Komposition und Wiedergabe", compositionPanel());
        tabs.addTab("Instrumentenbibliotheken", librariesPanel());
        add(tabs, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        footer.add(new JScrollPane(log), BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    private JPanel compositionPanel() {
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        int row = 0;
        addRow(fields, row++, "KIPITZ Completions URL:", endpoint);
        addRow(fields, row++, "API-Key (nur Arbeitsspeicher):", apiKey);
        addRow(fields, row++, "Modell:", model);
        JPanel proxy = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        proxy.add(new JLabel("HTTP-Proxy-Host (optional):"));
        proxy.add(proxyHost);
        proxy.add(new JLabel("Port:"));
        proxy.add(proxyPort);
        addRow(fields, row++, "Proxy:", proxy);
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
        JButton useDemo = new JButton("Demo laden");
        useDemo.addActionListener(event -> loadDemo());
        JPanel chooseButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        chooseButtons.add(selectTsv);
        chooseButtons.add(useDemo);
        tsv.add(chooseButtons, BorderLayout.EAST);
        addRow(fields, row++, "Arrangement:", tsv);

        JPanel engines = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        engines.add(new JLabel("FluidSynth:"));
        engines.add(fluidSynth);
        engines.add(new JLabel("sfizz:"));
        engines.add(sfizz);
        addRow(fields, row++, "Sampler:", engines);

        for (int index = 0; index < PART_NAMES.length; index++) {
            JComboBox<InstrumentLibrary> choice = new JComboBox<InstrumentLibrary>();
            choice.addItem(builtin);
            for (InstrumentLibrary installed : registry.installedInstruments()) {
                choice.addItem(installed);
            }
            selections[index] = choice;
            addRow(fields, row++, PART_NAMES[index] + ":", choice);
        }
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(generateButton);
        actions.add(renderButton);
        actions.add(cancelButton);
        actions.add(playbackSelection);
        JButton playButton = new JButton("Abspielen");
        playButton.addActionListener(event -> play());
        actions.add(playButton);
        JButton stopButton = new JButton("Stopp");
        stopButton.addActionListener(event -> stopPlayback());
        actions.add(stopButton);
        addRow(fields, row++, "Aktionen:", actions);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(fields), BorderLayout.CENTER);
        return panel;
    }

    private JPanel librariesPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        libraryTable.setRowHeight(25);
        libraryTable.setAutoCreateRowSorter(true);
        panel.add(new JScrollPane(libraryTable), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton register = new JButton("Soundbank oder ganzen Ordner importieren …");
        register.addActionListener(event -> registerSelectedLibrary());
        actions.add(register);
        JButton source = new JButton("Quellseite öffnen");
        source.addActionListener(event -> openSelectedSource());
        actions.add(source);
        JButton details = new JButton("Lizenz und Hinweise");
        details.addActionListener(event -> showDetails());
        actions.add(details);
        panel.add(actions, BorderLayout.SOUTH);
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
        final String host = proxyHost.getText().trim();
        final int port;
        try {
            port = Integer.parseInt(proxyPort.getText().trim());
        } catch (NumberFormatException invalidPort) {
            message("Proxy port must be a number.");
            return;
        }
        final String prompt = description.getText().trim();
        final Path output = Paths.get(directory.getText().trim());
        start(new Operation() {
            @Override
            public Path execute(GemmaMusicComposer.Progress progress) throws Exception {
                KipitzChatClient client = new KipitzChatClient(selectedUrl, key, selectedModel, host, port);
                Path tsv = new GemmaMusicComposer(client).compose(prompt, output, progress);
                return renderArrangement(tsv, output, progress);
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
        start(new Operation() {
            @Override
            public Path execute(GemmaMusicComposer.Progress progress) throws Exception {
                return renderArrangement(arrangement, output, progress);
            }
        });
    }

    private Path renderArrangement(Path source, Path destination, GemmaMusicComposer.Progress progress)
            throws Exception {
        Map<String, InstrumentLibrary> assignments = new HashMap<String, InstrumentLibrary>();
        for (int index = 0; index < PART_NAMES.length; index++) {
            InstrumentLibrary library = (InstrumentLibrary) selections[index].getSelectedItem();
            if (library != null && !"builtin".equals(library.id())) {
                assignments.put(PART_NAMES[index], library);
            }
        }
        return new StudioMusicRenderer(registry).render(source, destination, assignments,
                fluidSynth.getText().trim(), sfizz.getText().trim(), progress);
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
                }
            }
        };
        worker.execute();
    }

    private void cancel() {
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

    private void registerSelectedLibrary() {
        InstrumentLibrary library = selectedLibrary();
        if (library == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            int count = registry.importLocation(library, chooser.getSelectedFile().toPath());
            tableModel.fireTableDataChanged();
            refreshInstrumentChoices();
            append("Imported " + count + " instrument preset(s) from " + library.name());
        } catch (Exception error) {
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

    private InstrumentLibrary selectedLibrary() {
        int viewIndex = libraryTable.getSelectedRow();
        if (viewIndex < 0) {
            message("Select a library in the table first.");
            return null;
        }
        return libraries.get(libraryTable.convertRowIndexToModel(viewIndex));
    }

    private void openSelectedSource() {
        InstrumentLibrary library = selectedLibrary();
        if (library == null) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(library.sourceUrl()));
        } catch (Exception error) {
            message("Could not open source URL: " + error.getMessage());
        }
    }

    private void showDetails() {
        InstrumentLibrary library = selectedLibrary();
        if (library != null) {
            JOptionPane.showMessageDialog(this, library.notes() + "\nLicense: "
                    + library.license() + "\nSource: " + library.sourceUrl(),
                    library.name(), JOptionPane.INFORMATION_MESSAGE);
        }
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

    private final class LibraryTableModel extends AbstractTableModel {
        private final String[] headings = {"Bibliothek", "Instrumente", "Format", "Lizenz", "Lokal verfügbar"};

        @Override
        public int getRowCount() {
            return libraries.size();
        }

        @Override
        public int getColumnCount() {
            return headings.length;
        }

        @Override
        public String getColumnName(int column) {
            return headings[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            InstrumentLibrary library = libraries.get(row);
            switch (column) {
                case 0: return library.name();
                case 1: return library.family();
                case 2: return library.format().name();
                case 3: return library.license();
                default: return registry.isInstalled(library.id()) ? "Ja" : "Nein";
            }
        }
    }
}
