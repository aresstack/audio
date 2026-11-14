package com.aresstack.audio.instruments;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/** Persist locally installed sound banks and every individual SFZ preset without copying samples. */
public final class InstrumentRegistry {
    private static final String VARIANT_SEPARATOR = "::";
    private static final int MAX_PRESETS_PER_IMPORT = 2000;
    private final Path file;
    private final Map<String, Path> paths = new HashMap<String, Path>();

    public InstrumentRegistry(Path file) throws IOException {
        this.file = file;
        load();
    }

    public static InstrumentRegistry standard() throws IOException {
        return new InstrumentRegistry(Paths.get(System.getProperty("user.home"),
                ".aresstack", "audio", "instruments.properties"));
    }

    public synchronized Path find(String libraryOrInstrumentId) {
        return paths.get(libraryOrInstrumentId);
    }

    public synchronized boolean isInstalled(String libraryId) {
        if (Files.isRegularFile(paths.getOrDefault(libraryId, file.resolveSibling("__missing__")))) {
            return true;
        }
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            if (entry.getKey().startsWith(libraryId + VARIANT_SEPARATOR)
                    && Files.isRegularFile(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Import a sound bank or scan an extracted library for up to 2,000 selectable presets. */
    public synchronized int importLocation(InstrumentLibrary library, Path location) throws IOException {
        Path selected = location.toAbsolutePath().normalize();
        List<Path> supported = discover(selected);
        if (supported.isEmpty()) {
            throw new IllegalArgumentException("No SF2, SF3 or SFZ instruments found at " + selected);
        }
        for (Path candidate : supported) {
            String variantId = library.id() + VARIANT_SEPARATOR
                    + UUID.nameUUIDFromBytes(candidate.toString().getBytes(StandardCharsets.UTF_8));
            paths.put(variantId, candidate);
        }
        save();
        return supported.size();
    }

    /** Describe every installed preset as its own selectable instrument. */
    public synchronized List<InstrumentLibrary> installedInstruments() {
        List<InstrumentLibrary> instruments = new ArrayList<InstrumentLibrary>();
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            if (!Files.isRegularFile(entry.getValue())) {
                continue;
            }
            String id = entry.getKey();
            String familyId = id.contains(VARIANT_SEPARATOR)
                    ? id.substring(0, id.indexOf(VARIANT_SEPARATOR)) : id;
            for (InstrumentLibrary parent : InstrumentCatalog.standard()) {
                if (parent.id().equals(familyId)) {
                    instruments.add(new InstrumentLibrary(id,
                            parent.name() + " / " + entry.getValue().getFileName(),
                            parent.family(), detectFormat(entry.getValue()), parent.license(),
                            parent.sourceUrl(), parent.notes()));
                    break;
                }
            }
        }
        Collections.sort(instruments, new Comparator<InstrumentLibrary>() {
            @Override
            public int compare(InstrumentLibrary first, InstrumentLibrary second) {
                return first.name().compareToIgnoreCase(second.name());
            }
        });
        return Collections.unmodifiableList(instruments);
    }

    /** Preserve compatibility with existing single-bank registrations. */
    public synchronized void register(InstrumentLibrary library, Path selected) throws IOException {
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

    public synchronized void unregister(String instrumentId) throws IOException {
        paths.remove(instrumentId);
        save();
    }

    private static List<Path> discover(Path location) throws IOException {
        if (Files.isRegularFile(location)) {
            detectFormat(location);
            return Collections.singletonList(location);
        }
        if (!Files.isDirectory(location)) {
            throw new IllegalArgumentException("Not an existing file or directory: " + location);
        }
        List<Path> result = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(location, 10)) {
            walk.filter(Files::isRegularFile).filter(InstrumentRegistry::supported)
                    .limit(MAX_PRESETS_PER_IMPORT + 1).forEach(result::add);
        }
        if (result.size() > MAX_PRESETS_PER_IMPORT) {
            throw new IllegalArgumentException("More than 2,000 soundbanks in this folder; import a subfolder.");
        }
        Collections.sort(result);
        return result;
    }

    private static boolean supported(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".sf2") || lower.endsWith(".sf3") || lower.endsWith(".sfz");
    }

    private static InstrumentLibrary.Format detectFormat(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".sf2")) {
            return InstrumentLibrary.Format.SF2;
        }
        if (lower.endsWith(".sf3")) {
            return InstrumentLibrary.Format.SF3;
        }
        if (lower.endsWith(".sfz")) {
            return InstrumentLibrary.Format.SFZ;
        }
        throw new IllegalArgumentException("Unsupported soundbank file: " + path);
    }

    private synchronized void load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        for (String key : properties.stringPropertyNames()) {
            paths.put(key, Paths.get(properties.getProperty(key)));
        }
    }

    private synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue().toString());
        }
        Path temp = Files.createTempFile(file.getParent(), "instrument-registry-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temp)) {
                properties.store(output, "Local instrument paths only; never store API credentials");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
