package com.aresstack.audio.studio.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/** Persist the user's opt-in local desktop settings, including the API key, under their profile. */
public final class StudioSettingsStore {
    private final Path file;

    public StudioSettingsStore(Path file) {
        this.file = file;
    }

    public static StudioSettingsStore standard() {
        return new StudioSettingsStore(Paths.get(System.getProperty("user.home"),
                ".aresstack", "audio", "studio.properties"));
    }

    public Properties load() throws IOException {
        Properties settings = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                settings.load(input);
            }
        }
        return settings;
    }

    public void save(Properties settings) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "audio-studio-", ".tmp");
        restrictPermissions(temporary);
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                settings.store(output,
                        "AresStack Audio Studio local settings. API key is stored as plain text.");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            restrictPermissions(file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path location() {
        return file;
    }

    private static void restrictPermissions(Path path) throws IOException {
        Set<PosixFilePermission> ownerOnly = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException ignoredOnWindows) {
            // Rely on the user's Windows profile ACL when POSIX permissions are unavailable.
        }
    }
}
