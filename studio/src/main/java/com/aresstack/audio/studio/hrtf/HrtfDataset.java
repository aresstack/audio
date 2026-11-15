package com.aresstack.audio.studio.hrtf;

import java.net.URI;
import java.util.Objects;

/**
 * A set of measured head-related responses that the user may download on request (work package
 * D4, decision of the product owner on PR #33): where it comes from, what it may weigh, its
 * checksum, and the terms and citation that go with it. Nothing is downloaded unless the user
 * asks; without a set the stage hears through the spherical head model.
 *
 * @param id         folder name below the root of the downloaded sets
 * @param title      what the user sees
 * @param uri        the original source, HTTPS only
 * @param page       the page that documents the source and its terms
 * @param version    which edition of the data
 * @param maxBytes   a download larger than this is refused
 * @param sha256     the checksum of the archive as published; a download that differs is refused
 * @param terms      the terms of use, word for word as the source states them
 * @param citation   how the authors ask to be cited
 */
public record HrtfDataset(String id, String title, URI uri, URI page, String version, long maxBytes, String sha256,
                          String terms, String citation) {
    public HrtfDataset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTPS sources are downloaded.");
        }
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(version, "version");
        if (maxBytes < 1024) {
            throw new IllegalArgumentException("Invalid size limit.");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A set is only offered with the SHA-256 of its archive.");
        }
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(citation, "citation");
    }

    /**
     * The compact MIT KEMAR set (231 461 bytes, 368 stereo WAV files, checked 2026-11-15 against
     * the original source): 128-tap responses of a KEMAR dummy head at 44.1 kHz, measured
     * on the right side and mirrored for the left, from 40° below to 90° above the ears. The
     * MIT Media Lab grants its use without restriction, asking that the authors be cited; this
     * is a permission for the data, not the MIT software licence.
     */
    public static final HrtfDataset MIT_KEMAR = new HrtfDataset("mit-kemar-compact",
            "MIT KEMAR (kompakt)",
            URI.create("https://sound.media.mit.edu/resources/KEMAR/compact.zip"),
            URI.create("https://sound.media.mit.edu/resources/KEMAR.html"),
            "compact set, MIT Media Lab Perceptual Computing, 1994", 1_000_000L,
            "0bcd69f8e8760cf8eacff4a89594ceca7d44fb94f28ecf0bf19970003bbfb3e0",
            "This data is Copyright 1994 by the MIT Media Laboratory. It is provided free with no "
                    + "restrictions on use, provided the authors are cited when the data is used in any research "
                    + "or commercial application.",
            "Bill Gardner and Keith Martin, \"HRTF Measurements of a KEMAR Dummy-Head Microphone\", "
                    + "MIT Media Lab Perceptual Computing Technical Report #280, May 1994 (revised July 2000).");
}
