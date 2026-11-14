package com.aresstack.audio.instruments;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Store only user-selected instrument file paths, never samples or API credentials. */
public final class InstrumentRegistry {
    private final Path file;
    private final Map<String, Path> paths = new HashMap<String, Path>();

    public InstrumentRegistry(Path file) throws IOException {
        this.file = file;
        load();
    }

    public static InstrumentRegistry standard() throws IOException {
        return new InstrumentRegistry(java.nio.file.Paths.get(System.getProperty("user.home"),
                ".aresstack", "audio", "instruments.properties"));
    }

    public Path find(String libraryId) {
        return paths.get(libraryId);
    }

    public boolean isInstalled(String libraryId) {
        Path path = find(libraryId);
        return path != null && Files.isRegularFile(path);
    }

    public void register(InstrumentLibrary library, Path selected) throws IOException {
        Path absolute = selected.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalArgumentException("Instrument file not found: " + absolute);
        }
        if (!library.accepts(absolute.getFileName().toString())) {
            throw new IllegalArgumentException("Choose a " + library.format() + " file for " + library.name());
        }
        paths.put(library.id(), absolute);
        save();
    }

    public void unregister(String libraryId) throws IOException {
        paths.remove(libraryId);
        save();
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        for (String key : properties.stringPropertyNames()) {
            paths.put(key, java.nio.file.Paths.get(properties.getProperty(key)));
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue().toString());
        }
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "Local instrument paths only; never include API credentials");
        }
    }
}
