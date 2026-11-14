package com.aresstack.audio.instruments;

import java.util.Locale;
import java.util.Objects;

/** Describe a sample library without distributing its potentially large sound data. */
public final class InstrumentLibrary {
    public enum Format { SF2, SF3, SFZ }

    private final String id;
    private final String name;
    private final String family;
    private final Format format;
    private final String license;
    private final String sourceUrl;
    private final String notes;

    public InstrumentLibrary(String id, String name, String family, Format format,
                             String license, String sourceUrl, String notes) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.family = Objects.requireNonNull(family, "family");
        this.format = Objects.requireNonNull(format, "format");
        this.license = Objects.requireNonNull(license, "license");
        this.sourceUrl = Objects.requireNonNull(sourceUrl, "sourceUrl");
        this.notes = Objects.requireNonNull(notes, "notes");
    }

    public String id() { return id; }
    public String name() { return name; }
    public String family() { return family; }
    public Format format() { return format; }
    public String license() { return license; }
    public String sourceUrl() { return sourceUrl; }
    public String notes() { return notes; }

    public boolean accepts(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith("." + format.name().toLowerCase(Locale.ROOT))
            || (format == Format.SF2 && lower.endsWith(".sf3"));
    }

    @Override
    public String toString() {
        return name + " (" + format + ")";
    }
}
