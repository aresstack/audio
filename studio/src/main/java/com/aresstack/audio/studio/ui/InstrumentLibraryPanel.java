package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.instruments.network.AudioProxyRoutes;
import com.aresstack.audio.instruments.download.InstrumentDownload;
import com.aresstack.audio.instruments.download.InstrumentDownloadCatalog;
import com.aresstack.audio.instruments.download.InstrumentDownloadService;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.GridLayout;
import java.awt.Dimension;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/** Handle sample-bank installation and download actions without coupling them to music composition. */
public final class InstrumentLibraryPanel extends JPanel {
    private final InstrumentRegistry registry;
    private final List<InstrumentLibrary> libraries = InstrumentCatalog.standard();
    private final Supplier<AudioProxyRoutes> proxyRoutes;
    private final Runnable onRegistryChanged;
    private final Consumer<String> log;
    private final JTable table = new JTable(new LibraryTableModel());
    private final JButton downloadButton = new JButton("Herunterladen");
    private final JButton downloadAllButton = new JButton("Alle verfügbaren herunterladen");
    private final JButton cancelButton = new JButton("Download abbrechen");
    private final JProgressBar progressBar = new JProgressBar();
    private SwingWorker<Integer, String> downloadWorker;

    public InstrumentLibraryPanel(InstrumentRegistry registry,
                                  Supplier<AudioProxyRoutes> proxyRoutes,
                                  Runnable onRegistryChanged, Consumer<String> log) {
        super(new BorderLayout(8, 8));
        this.registry = registry;
        this.proxyRoutes = proxyRoutes;
        this.onRegistryChanged = onRegistryChanged;
        this.log = log;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        table.setRowHeight(26);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(event -> updateDownloadButton());
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        if (!libraries.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
        updateDownloadButton();
    }

    private JPanel actions() {
        JPanel container = new JPanel(new BorderLayout(4, 6));
        progressBar.setStringPainted(true);
        progressBar.setString("Bibliothek wählen und herunterladen – oder vorhandenen Ordner importieren");
        container.add(progressBar, BorderLayout.NORTH);

        JPanel actions = new JPanel(new BorderLayout(6, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton importButton = new JButton("Vorhandene Datei / Ordner importieren");
        importButton.addActionListener(event -> importSelected());
        left.add(importButton);
        JButton sourceButton = new JButton("Quellseite");
        sourceButton.addActionListener(event -> openSelectedSource());
        left.add(sourceButton);
        JButton licenseButton = new JButton("Lizenz und Hinweise");
        licenseButton.addActionListener(event -> showDetails());
        left.add(licenseButton);
        actions.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        downloadButton.addActionListener(event -> downloadSelected());
        right.add(downloadButton);
        downloadAllButton.addActionListener(event -> downloadAll());
        right.add(downloadAllButton);
        cancelButton.addActionListener(event -> cancelDownloads());
        cancelButton.setEnabled(false);
        right.add(cancelButton);
        actions.add(right, BorderLayout.EAST);
        container.add(actions, BorderLayout.SOUTH);
        return container;
    }

    private void updateDownloadButton() {
        InstrumentLibrary library = selectedLibrary(false);
        if (library == null) {
            downloadButton.setEnabled(false);
            return;
        }
        InstrumentDownload direct = InstrumentDownloadCatalog.forLibrary(library.id());
        downloadButton.setText(direct == null ? "Download-Seite öffnen" : "Herunterladen");
        downloadButton.setEnabled(downloadWorker == null || downloadWorker.isDone());
    }

    private InstrumentLibrary selectedLibrary(boolean showMessage) {
        int selection = table.getSelectedRow();
        if (selection < 0) {
            if (showMessage) {
                JOptionPane.showMessageDialog(this, "Wähle zuerst eine Bibliothek in der Tabelle.");
            }
            return null;
        }
        return libraries.get(table.convertRowIndexToModel(selection));
    }

    private void downloadSelected() {
        InstrumentLibrary library = selectedLibrary(true);
        if (library == null) {
            return;
        }
        InstrumentDownload direct = InstrumentDownloadCatalog.forLibrary(library.id());
        if (direct == null) {
            JOptionPane.showMessageDialog(this,
                    "Für diese Bibliothek ist noch kein automatischer, verlässlicher Download eingerichtet. "
                            + "Öffne die Projektseite und importiere anschließend das entpackte Verzeichnis.",
                    "Download erfolgt über Projektseite", JOptionPane.INFORMATION_MESSAGE);
            openSource(library);
            return;
        }
        startDownload(Collections.singletonList(direct));
    }

    private void downloadAll() {
        List<InstrumentDownload> pending = new ArrayList<InstrumentDownload>();
        long bytes = 0;
        for (InstrumentDownload item : InstrumentDownloadCatalog.all()) {
            if (!registry.isInstalled(item.libraryId())) {
                pending.add(item);
                bytes += item.estimatedBytes();
            }
        }
        if (pending.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Alle derzeit automatisch installierbaren Bibliotheken sind vorhanden.");
            return;
        }
        int megabytes = (int) Math.ceil(bytes / (1024.0 * 1024));
        int approval = JOptionPane.showConfirmDialog(this,
                pending.size() + " Bibliotheken herunterladen (etwa " + megabytes + " MiB)?\n"
                        + "VSCO, VPO, Salamander und die Sammlungen benötigen derzeit eine manuelle Auswahl "
                        + "auf der jeweiligen Projektseite und werden nicht stillschweigend heruntergeladen.",
                "Alle direkt verfügbaren SoundFonts herunterladen",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (approval == JOptionPane.YES_OPTION) {
            startDownload(pending);
        }
    }

    private void startDownload(final List<InstrumentDownload> downloads) {
        if (downloadWorker != null && !downloadWorker.isDone()) {
            return;
        }
        final AudioProxyRoutes routes;
        try {
            routes = proxyRoutes.get();
        } catch (RuntimeException invalid) {
            showError("Ungültige Proxykonfiguration: " + invalid.getMessage());
            return;
        }
        log.accept("Downloads verwenden Proxy-Modus: " + routes.describe());
        downloadButton.setEnabled(false);
        downloadAllButton.setEnabled(false);
        cancelButton.setEnabled(true);
        progressBar.setIndeterminate(true);
        progressBar.setString("Download läuft …");

        downloadWorker = new SwingWorker<Integer, String>() {
            @Override
            protected Integer doInBackground() throws Exception {
                InstrumentDownloadService service = new InstrumentDownloadService(
                        InstrumentDownloadService.standardLibraryRoot(), registry, routes);
                int success = 0;
                for (InstrumentDownload item : downloads) {
                    if (isCancelled()) {
                        break;
                    }
                    InstrumentLibrary library = findLibrary(item.libraryId());
                    try {
                        service.install(library, item, new InstrumentDownloadService.Progress() {
                            @Override
                            public void update(String message) {
                                publish(message);
                            }
                        });
                        success++;
                    } catch (Exception failed) {
                        publish("Fehler bei " + library.name() + ": " + failed.getMessage());
                    }
                }
                return success;
            }

            @Override
            protected void process(List<String> updates) {
                for (String update : updates) {
                    log.accept(update);
                    progressBar.setString(update);
                }
                refresh();
            }

            @Override
            protected void done() {
                progressBar.setIndeterminate(false);
                downloadAllButton.setEnabled(true);
                cancelButton.setEnabled(false);
                try {
                    int installed = get();
                    progressBar.setString(installed + " Installationen abgeschlossen");
                    log.accept(installed + " Soundbank-Installationen abgeschlossen.");
                } catch (java.util.concurrent.CancellationException cancelled) {
                    progressBar.setString("Download abgebrochen");
                } catch (Exception failure) {
                    progressBar.setString("Download fehlgeschlagen");
                    showError(failure.getMessage());
                }
                refresh();
                updateDownloadButton();
            }
        };
        downloadWorker.execute();
    }

    private InstrumentLibrary findLibrary(String id) {
        for (InstrumentLibrary item : libraries) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        throw new IllegalArgumentException("Unbekannte Bibliothek: " + id);
    }

    private void cancelDownloads() {
        if (downloadWorker != null) {
            downloadWorker.cancel(true);
        }
    }

    private void importSelected() {
        InstrumentLibrary selected = selectedLibrary(true);
        if (selected == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            int count = registry.importLocation(selected, chooser.getSelectedFile().toPath());
            log.accept(count + " Instrumente für " + selected.name() + " registriert.");
            refresh();
        } catch (Exception error) {
            showError(error.getMessage());
        }
    }

    private void openSelectedSource() {
        InstrumentLibrary selected = selectedLibrary(true);
        if (selected != null) {
            openSource(selected);
        }
    }

    private void openSource(InstrumentLibrary library) {
        try {
            Desktop.getDesktop().browse(URI.create(library.sourceUrl()));
        } catch (Exception error) {
            showError("Konnte Projektseite nicht öffnen: " + library.sourceUrl());
        }
    }

    private void showDetails() {
        InstrumentLibrary selected = selectedLibrary(true);
        if (selected != null) {
            JOptionPane.showMessageDialog(this, selected.notes()
                    + "\nLizenz: " + selected.license()
                    + "\nProjektseite: " + selected.sourceUrl(),
                    selected.name(), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void showError(String message) {
        log.accept(message);
        JOptionPane.showMessageDialog(this, message,
                "Instrumentenbibliothek", JOptionPane.ERROR_MESSAGE);
    }

    private void refresh() {
        ((LibraryTableModel) table.getModel()).fireTableDataChanged();
        onRegistryChanged.run();
    }

    private final class LibraryTableModel extends AbstractTableModel {
        private final String[] columns = {
                "Bibliothek", "Instrumente", "Format", "Downloadgröße", "Status", "Lizenz"
        };

        @Override
        public int getRowCount() { return libraries.size(); }

        @Override
        public int getColumnCount() { return columns.length; }

        @Override
        public String getColumnName(int column) { return columns[column]; }

        @Override
        public Object getValueAt(int row, int column) {
            InstrumentLibrary item = libraries.get(row);
            InstrumentDownload download = InstrumentDownloadCatalog.forLibrary(item.id());
            switch (column) {
                case 0: return item.name();
                case 1: return item.family();
                case 2: return download == null ? item.format().name()
                        : download.packaging() == InstrumentDownload.Packaging.SEVEN_Z_SF2
                        ? "SF2 (7z)" : download.packaging() == InstrumentDownload.Packaging.RAW_SF3
                        ? "SF3" : "SF2";
                case 3: return download == null ? "Projektseite" :
                        (download.estimatedBytes() / (1024 * 1024)) + " MiB";
                case 4: return registry.isInstalled(item.id()) ? "Installiert" :
                        download == null ? "Manuell" : "Download verfügbar";
                default: return item.license();
            }
        }
    }
}
