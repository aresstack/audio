package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Role;
import com.aresstack.audio.studio.stage.Performer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Define one user-editable musical voice independently of MIDI channel allocation. */
public final class MusicTrack implements Performer {
    public static final String MELODY = Role.MELODY;
    public static final String HARMONY = Role.HARMONY;
    public static final String BASS = Role.BASS;
    public static final String DRUMS = Role.DRUMS;
    public static final String TEXTURE = Role.TEXTURE;
    public static final List<String> ROLES = Role.ALL;

    private final String id;
    private final String role;
    private final String instrument;
    private final int program;
    private final String libraryId;

    public MusicTrack(String id, String role, String instrument, int program, String libraryId) {
        this.id = requireId(id);
        String normalizedRole = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (!ROLES.contains(normalizedRole)) {
            throw new IllegalArgumentException("Unknown track role: " + role);
        }
        if (instrument == null || instrument.trim().isEmpty() || instrument.length() > 100
                || instrument.contains("\n") || instrument.contains("\t")) {
            throw new IllegalArgumentException("Enter an instrument description of 1–100 characters.");
        }
        if (program < 0 || program > 127) {
            throw new IllegalArgumentException("GM instrument program must be between 0 and 127.");
        }
        this.role = normalizedRole;
        this.instrument = instrument.trim();
        this.program = program;
        this.libraryId = libraryId == null ? "builtin" : libraryId.trim();
    }

    public static List<MusicTrack> defaults() {
        return Arrays.asList(
                new MusicTrack("melody", MELODY, "Music box", 10, "builtin"),
                new MusicTrack("harmony", HARMONY, "String ensemble", 48, "builtin"),
                new MusicTrack("bass", BASS, "Acoustic bass", 32, "builtin"),
                new MusicTrack("drums", DRUMS, "Soft drum kit", 0, "builtin"));
    }

    public MusicTrack withLibrary(String selectedId) {
        return new MusicTrack(id, role, instrument, program, selectedId);
    }

    @Override
    public String id() { return id; }
    @Override
    public String role() { return role; }
    public String instrument() { return instrument; }
    public int program() { return program; }
    public String libraryId() { return libraryId; }

    public boolean hasLegacyCacheIdentity() {
        List<MusicTrack> originals = defaults();
        for (MusicTrack original : originals) {
            if (original.id.equals(id) && original.role.equals(role)
                    && original.program == program && original.instrument.equals(instrument)) {
                return true;
            }
        }
        return false;
    }

    private static String requireId(String id) {
        String clean = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (!clean.matches("[a-z][a-z0-9_-]{0,28}")) {
            throw new IllegalArgumentException("Track ID must start with a letter and use a-z, 0-9, - or _ (up to 29 characters): " + id);
        }
        return clean;
    }

    @Override
    public String toString() {
        return id + " — " + instrument;
    }
}
