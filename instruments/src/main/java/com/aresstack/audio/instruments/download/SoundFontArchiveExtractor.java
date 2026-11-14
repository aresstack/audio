package com.aresstack.audio.instruments.download;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

/** Extract only SoundFont files from a verified 7z archive with strict traversal and size limits. */
public final class SoundFontArchiveExtractor {
    private static final long MAX_TOTAL_UNCOMPRESSED = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;

    public List<Path> extractSevenZip(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        List<Path> soundFonts = new ArrayList<Path>();
        int entries = 0;
        long extractedBytes = 0;
        try (SevenZFile compressed = new SevenZFile(archive.toFile())) {
            SevenZArchiveEntry entry;
            while ((entry = compressed.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("Archive contains too many entries.");
                }
                String originalName = entry.getName().replace('\\', '/');
                Path target = destination.resolve(originalName).normalize();
                if (!target.startsWith(destination.toAbsolutePath().normalize())) {
                    throw new IOException("Unsafe archive entry path: " + originalName);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String lower = target.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".sf2") && !lower.endsWith(".sf3")) {
                    continue;
                }
                long size = entry.getSize();
                if (size < 1024 || size > MAX_TOTAL_UNCOMPRESSED
                        || size > MAX_TOTAL_UNCOMPRESSED - extractedBytes) {
                    throw new IOException("Unacceptable SoundFont size in archive: " + originalName);
                }
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target)) {
                    byte[] block = new byte[32768];
                    long remaining = size;
                    while (remaining > 0) {
                        int count = compressed.read(block, 0, (int) Math.min(block.length, remaining));
                        if (count < 1) {
                            throw new IOException("Truncated SoundFont archive entry: " + originalName);
                        }
                        output.write(block, 0, count);
                        remaining -= count;
                    }
                }
                extractedBytes += size;
                soundFonts.add(target);
            }
        }
        if (soundFonts.isEmpty()) {
            throw new IOException("Archive has no SF2/SF3 soundbank.");
        }
        return Collections.unmodifiableList(soundFonts);
    }
}
