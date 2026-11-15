package com.aresstack.audio.studio.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The content-addressed stem cache of a piece (architecture review E6, E7.3), under
 * {@code render/} in its folder: {@code midi/} holds the MIDI file of each part, {@code stems/}
 * the rendered sound. A file name is the part id and the start of a hash of what decides the
 * content, so a file is never rewritten with other content: if the file for a key exists, it
 * is the stem for that key. The whole folder is derived and may be deleted at any time.
 *
 * <p>The key of a stem is a hash of the cache version, the engine, everything the engine says
 * decides its sound ({@link StemRenderer#identity}: settings, sound bank, sample rate, for the
 * built-in sound the role and the length of the piece) and the bytes of the part's MIDI file
 * (notes, program, channel, tempo and meter). The mix ({@code mix.json}) is in none of them.
 */
public final class StemCache {
    public static final String MIDI = "midi";
    public static final String STEMS = "stems";
    /** Changes when the way keys are made changes; every stem is then rendered again once. */
    static final String VERSION = "stem-cache-1";

    private final Path render;

    /** The cache of the piece in this folder. */
    public StemCache(Path folder) {
        this.render = folder.resolve(com.aresstack.audio.studio.project.ProjectFolder.RENDER);
    }

    /** The cache key of a stem. */
    public static String key(String engine, String identity, byte[] midi) {
        return sha256((VERSION + "\n" + engine + "\n" + identity + "\nmidi\t" + sha256(midi))
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Where the stem for a key lies (whether or not it exists yet). */
    public Path stem(String partId, String key) {
        return render.resolve(STEMS).resolve(partId + "-" + key.substring(0, 16) + ".wav");
    }

    /** Writes the MIDI file of a part unless the same content is there already, and returns it. */
    public Path midi(String partId, byte[] bytes) throws IOException {
        Path file = render.resolve(MIDI).resolve(partId + "-" + sha256(bytes).substring(0, 16) + ".mid");
        if (!Files.isRegularFile(file)) {
            Files.createDirectories(file.getParent());
            Path partial = Files.createTempFile(file.getParent(), partId + "-", ".part");
            try {
                Files.write(partial, bytes);
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(partial);
            }
        }
        return file;
    }

    /** Whether the stem for a key has been rendered. */
    public boolean has(String partId, String key) {
        return Files.isRegularFile(stem(partId, key));
    }

    /**
     * Renders the stem for a key into a file of its own first and moves it in place only when
     * the renderer succeeded, so that a stem in the cache is always complete.
     */
    public Path store(String partId, String key, Rendering rendering) throws Exception {
        Path target = stem(partId, key);
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + "." + System.nanoTime() + ".part.wav");
        try {
            rendering.into(partial);
            if (!Files.isRegularFile(partial) || Files.size(partial) < 44) {
                throw new IOException("The renderer wrote no WAV for " + partId);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // The log of a sampler that succeeded says nothing; that of one that failed is kept.
            Files.deleteIfExists(partial.resolveSibling(partial.getFileName() + ".log"));
        } finally {
            Files.deleteIfExists(partial);
        }
        return target;
    }

    /** Writes a stem to the file it is given. */
    @FunctionalInterface
    public interface Rendering {
        void into(Path file) throws Exception;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
