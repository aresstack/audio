package com.aresstack.audio.instruments.download;

import java.net.URI;
import java.util.Objects;

/** Describe a verified download target and the packaging required to install it. */
public final class InstrumentDownload {
    public enum Packaging { RAW_SF2, RAW_SF3, SEVEN_Z_SF2 }

    private final String libraryId;
    private final URI uri;
    private final String fileName;
    private final Packaging packaging;
    private final long maximumBytes;
    private final long estimatedBytes;
    private final String sha256;

    public InstrumentDownload(String libraryId, String url, String fileName, Packaging packaging,
                              long maximumBytes, long estimatedBytes, String sha256) {
        this.libraryId = Objects.requireNonNull(libraryId, "libraryId");
        this.uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only verified HTTPS sources are supported.");
        }
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.packaging = Objects.requireNonNull(packaging, "packaging");
        if (maximumBytes < 1024 || estimatedBytes < 0) {
            throw new IllegalArgumentException("Invalid source size.");
        }
        this.maximumBytes = maximumBytes;
        this.estimatedBytes = estimatedBytes;
        this.sha256 = sha256 == null ? "" : sha256;
    }

    public String libraryId() { return libraryId; }
    public URI uri() { return uri; }
    public String fileName() { return fileName; }
    public Packaging packaging() { return packaging; }
    public long maximumBytes() { return maximumBytes; }
    public long estimatedBytes() { return estimatedBytes; }
    public String sha256() { return sha256; }
}
