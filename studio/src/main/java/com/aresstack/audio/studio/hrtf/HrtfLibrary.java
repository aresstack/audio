package com.aresstack.audio.studio.hrtf;

import com.aresstack.audio.core.spatial.HrirMeasurements;
import com.aresstack.audio.core.spatial.HrirSet;
import com.aresstack.audio.instruments.network.AudioWindowsTrust;
import com.aresstack.audio.studio.mix.StageSound;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.HttpsURLConnection;

/**
 * Downloaded sets of measured head-related responses, kept in the user profile (work package
 * D4). The rules of the product owner (PR #33): a set is downloaded only when the user asks
 * for it ({@link #download}); nothing here opens a connection on its own. The archive must
 * match the checksum of its source, and the source, version, terms and citation are written
 * next to the data. Once downloaded a set is used offline; without one, {@link #onStage}
 * leaves the stage on the spherical head model.
 */
public final class HrtfLibrary {
    /** Messages for the user while a download runs. */
    public interface Progress {
        void update(String message);
    }

    /** Which proxy reaches a source; the studio passes its audio proxy routes. */
    public interface Proxies {
        Proxy proxyFor(URI target) throws IOException;
    }

    /** The file next to the data that says where it comes from and on which terms. */
    public static final String SOURCE_FILE = "QUELLE.txt";

    private static final Pattern DATA = Pattern.compile("[HLR]-?\\d+e\\d{3}a\\.dat", Pattern.CASE_INSENSITIVE);
    private static final long MAX_ENTRY = 64 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final int TAPS = 128;

    private final Path root;

    public HrtfLibrary(Path root) {
        this.root = root;
    }

    /** {@code ~/.aresstack/audio/hrtf}. */
    public static Path standardRoot() {
        return Paths.get(System.getProperty("user.home"), ".aresstack", "audio", "hrtf");
    }

    /** Whether the set has been downloaded and checked; asks nothing of the network. */
    public boolean isInstalled(HrtfDataset dataset) {
        Path folder = folder(dataset);
        if (!Files.isRegularFile(folder.resolve(SOURCE_FILE))) {
            return false;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.anyMatch(file -> DATA.matcher(file.getFileName().toString()).matches());
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** The folder of a downloaded set. */
    public Path folder(HrtfDataset dataset) {
        return root.resolve(dataset.id());
    }

    /**
     * Downloads the set from its original source, checks it and installs it. Call only when the
     * user asked for it. A set that is installed already is not downloaded again.
     *
     * @param proxies how to reach the source, or {@code null} for the system settings
     */
    public void download(HrtfDataset dataset, Proxies proxies, Progress progress) throws IOException {
        if (isInstalled(dataset)) {
            progress.update("Bereits geladen: " + dataset.title());
            return;
        }
        Files.createDirectories(root);
        Path archive = Files.createTempFile(root, "download-", ".zip");
        try {
            progress.update("Lade " + dataset.title() + " von " + dataset.uri().getHost() + " ...");
            fetch(dataset, proxies, archive);
            install(dataset, archive, progress);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /**
     * Installs a set from its archive: refused unless the checksum is that of the source; only
     * the response files are unpacked, then source, version, terms and citation are written.
     */
    public void install(HrtfDataset dataset, Path archive, Progress progress) throws IOException {
        String actual = sha256(archive);
        if (!actual.equals(dataset.sha256())) {
            throw new IOException("Prüfsumme stimmt nicht (" + actual + "); " + dataset.title() + " wurde verworfen.");
        }
        Files.createDirectories(root);
        Path unpacked = Files.createTempDirectory(root, "install-");
        try {
            int count = unzip(archive, unpacked);
            if (count == 0) {
                throw new IOException("Im Archiv liegen keine Antworten (.dat).");
            }
            HrirMeasurements.readMitKemar(unpacked, TAPS); // can it be read at all?
            Files.writeString(unpacked.resolve(SOURCE_FILE), source(dataset, actual), StandardCharsets.UTF_8);
            Path folder = folder(dataset);
            delete(folder);
            Files.move(unpacked, folder, StandardCopyOption.ATOMIC_MOVE);
            progress.update("Geladen: " + dataset.title() + " (" + count + " Antworten). Bitte zitieren: "
                    + dataset.citation());
        } finally {
            delete(unpacked);
        }
    }

    /** The responses of a downloaded set, read offline. */
    public HrirSet load(HrtfDataset dataset) throws IOException {
        if (!isInstalled(dataset)) {
            throw new IOException(dataset.title() + " ist nicht geladen.");
        }
        return HrirMeasurements.readMitKemar(folder(dataset), TAPS);
    }

    /**
     * The sound of the stage with the downloaded set as its head, or unchanged (the spherical
     * head model) when the set is not there or cannot be read. Never touches the network.
     */
    public StageSound onStage(StageSound sound, HrtfDataset dataset) {
        if (!isInstalled(dataset)) {
            return sound;
        }
        try {
            return sound.withHrirs(load(dataset));
        } catch (IOException | RuntimeException unreadable) {
            return sound;
        }
    }

    /** What is written next to the data. */
    static String source(HrtfDataset dataset, String sha256) {
        return dataset.title() + "\n\n"
                + "Quelle: " + dataset.uri() + "\n"
                + "Beschreibung: " + dataset.page() + "\n"
                + "Version: " + dataset.version() + "\n"
                + "SHA-256 des Archivs: " + sha256 + "\n\n"
                + "Nutzungsbedingungen (Datensatz-Freigabe, nicht die MIT-Softwarelizenz):\n" + dataset.terms() + "\n\n"
                + "Zitat:\n" + dataset.citation() + "\n";
    }

    private static void fetch(HrtfDataset dataset, Proxies proxies, Path target) throws IOException {
        Proxy proxy = proxies == null ? null : proxies.proxyFor(dataset.uri());
        HttpURLConnection connection = (HttpURLConnection) (proxy == null ? dataset.uri().toURL().openConnection()
                : dataset.uri().toURL().openConnection(proxy));
        if (connection instanceof HttpsURLConnection https) {
            AudioWindowsTrust.configure(https);
        }
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "AresStack-Audio-Studio/0.1");
        // Redirects only within HTTPS: HttpURLConnection never follows a change of protocol.
        connection.setInstanceFollowRedirects(true);
        try {
            int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download antwortete HTTP " + response + " für " + dataset.uri());
            }
            if (connection.getContentLengthLong() > dataset.maxBytes()) {
                throw new IOException("Download ist größer als erlaubt.");
            }
            long received = 0;
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(target)) {
                byte[] block = new byte[32768];
                int count;
                while ((count = input.read(block)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download abgebrochen.");
                    }
                    received += count;
                    if (received > dataset.maxBytes()) {
                        throw new IOException("Download ist größer als erlaubt.");
                    }
                    output.write(block, 0, count);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Unpacks only response files, flat, each under its own name; returns how many. */
    private static int unzip(Path archive, Path into) throws IOException {
        int count = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("Zu viele Einträge im Archiv.");
                }
                String name = entry.getName().replace('\\', '/');
                name = name.substring(name.lastIndexOf('/') + 1);
                if (entry.isDirectory() || !DATA.matcher(name).matches()) {
                    continue;
                }
                byte[] data = zip.readNBytes((int) MAX_ENTRY + 1);
                if (data.length > MAX_ENTRY) {
                    throw new IOException("Eintrag zu groß: " + name);
                }
                Path target = into.resolve(name);
                if (!target.normalize().getParent().equals(into.normalize())) {
                    throw new IOException("Ungültiger Name im Archiv: " + name);
                }
                Files.write(target, data);
                count++;
            }
        }
        return count;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] block = new byte[32768];
                int count;
                while ((count = input.read(block)) != -1) {
                    digest.update(block, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 fehlt.", impossible);
        }
    }

    private static void delete(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        List<Path> targets = new ArrayList<Path>();
        try (Stream<Path> files = Files.walk(directory)) {
            files.forEach(targets::add);
        }
        Collections.sort(targets, Collections.reverseOrder());
        for (Path file : targets) {
            Files.deleteIfExists(file);
        }
    }
}
