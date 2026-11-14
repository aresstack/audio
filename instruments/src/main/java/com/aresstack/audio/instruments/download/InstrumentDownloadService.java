package com.aresstack.audio.instruments.download;

import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Install a documented SoundFont into the user profile and register the resulting preset. */
public final class InstrumentDownloadService {
    public interface Progress {
        void update(String message);
    }

    private final Path libraryRoot;
    private final InstrumentRegistry registry;
    private final Proxy proxy;
    private final SoundFontArchiveExtractor extractor = new SoundFontArchiveExtractor();

    public InstrumentDownloadService(Path libraryRoot, InstrumentRegistry registry,
                                     String proxyHost, int proxyPort) {
        this.libraryRoot = libraryRoot;
        this.registry = registry;
        if (proxyHost != null && !proxyHost.trim().isEmpty()) {
            this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        } else {
            this.proxy = null;
        }
    }

    public static Path standardLibraryRoot() {
        return Paths.get(System.getProperty("user.home"), ".aresstack", "audio", "libraries");
    }

    public List<Path> install(InstrumentLibrary library, InstrumentDownload source,
                              Progress progress) throws Exception {
        if (!source.libraryId().equals(library.id())) {
            throw new IllegalArgumentException("Download does not belong to " + library.name());
        }
        if (registry.isInstalled(library.id())) {
            progress.update("Bereits installiert: " + library.name());
            Path installed = registry.find(library.id());
            return installed == null ? Collections.<Path>emptyList()
                    : Collections.singletonList(installed);
        }
        Files.createDirectories(libraryRoot);
        Path temporary = Files.createTempDirectory(libraryRoot, "install-");
        try {
            Path archive = temporary.resolve(source.fileName());
            progress.update("Lade " + library.name() + " ...");
            download(source, archive, progress);
            List<Path> extracted;
            if (source.packaging() == InstrumentDownload.Packaging.SEVEN_Z_SF2) {
                extracted = extractor.extractSevenZip(archive, temporary.resolve("unpacked"));
            } else {
                extracted = Collections.singletonList(archive);
            }
            for (Path file : extracted) {
                verifySoundFont(file);
            }
            Path installedDirectory = libraryRoot.resolve(source.libraryId());
            Files.createDirectories(installedDirectory);
            List<Path> installed = new ArrayList<Path>();
            for (Path file : extracted) {
                Path destination = installedDirectory.resolve(file.getFileName().toString());
                Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
                installed.add(destination);
            }
            for (Path file : installed) {
                registry.importLocation(library, file);
            }
            progress.update("Installiert: " + library.name() + " (" + installed.size() + " Presets)");
            return installed;
        } finally {
            deleteTempDirectory(temporary);
        }
    }

    private void download(InstrumentDownload source, Path target, Progress progress)
            throws Exception {
        URL url = source.uri().toURL();
        HttpURLConnection connection = (HttpURLConnection) (proxy == null
                ? url.openConnection() : url.openConnection(proxy));
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "AresStack-Audio-Studio/0.1");
        connection.setInstanceFollowRedirects(true);
        try {
            int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download returned HTTP " + response + " for " + source.uri());
            }
            long reportedLength = connection.getContentLengthLong();
            if (reportedLength > source.maximumBytes()) {
                throw new IOException("Download exceeds allowed size: " + reportedLength);
            }
            long received = 0;
            long nextProgress = 5 * 1024 * 1024;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(target)) {
                byte[] block = new byte[32768];
                int count;
                while ((count = input.read(block)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled.");
                    }
                    received += count;
                    if (received > source.maximumBytes()) {
                        throw new IOException("Download exceeded allowed size.");
                    }
                    output.write(block, 0, count);
                    if (received >= nextProgress) {
                        progress.update(source.libraryId() + ": " + (received / (1024 * 1024)) + " MiB");
                        nextProgress += 5 * 1024 * 1024;
                    }
                }
            }
            if (received < 1024) {
                throw new IOException("Downloaded file is unexpectedly small.");
            }
            if (!source.sha256().isEmpty() && !source.sha256().equalsIgnoreCase(sha256(target))) {
                throw new IOException("SHA-256 mismatch. The downloaded SoundFont was rejected.");
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Reject HTML error pages and corrupt files before adding them to the instrument registry. */
    public static void verifySoundFont(Path path) throws IOException {
        if (Files.size(path) < 1024) {
            throw new IOException("SoundFont is too small: " + path);
        }
        byte[] prefix = new byte[12];
        try (InputStream stream = Files.newInputStream(path)) {
            int offset = 0;
            while (offset < prefix.length) {
                int read = stream.read(prefix, offset, prefix.length - offset);
                if (read < 1) {
                    throw new IOException("Truncated SoundFont: " + path);
                }
                offset += read;
            }
        }
        if (prefix[0] != 'R' || prefix[1] != 'I' || prefix[2] != 'F' || prefix[3] != 'F'
                || prefix[8] != 's' || prefix[9] != 'f' || prefix[10] != 'b' || prefix[11] != 'k') {
            throw new IOException("Not a SoundFont SF2/SF3 RIFF file: " + path);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] block = new byte[32768];
                int count;
                while ((count = input.read(block)) != -1) {
                    digest.update(block, 0, count);
                }
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.ROOT, "%02x", value & 255));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable.", impossible);
        }
    }

    private static void deleteTempDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            List<Path> targets = new ArrayList<Path>();
            files.forEach(targets::add);
            Collections.sort(targets, Collections.reverseOrder());
            for (Path file : targets) {
                Files.deleteIfExists(file);
            }
        }
    }
}
