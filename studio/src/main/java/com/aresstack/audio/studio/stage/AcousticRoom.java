package com.aresstack.audio.studio.stage;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The room the stage stands in: its size, how long it rings (the time in which the
 * reverberation falls by 60 dB), how much of the ring is heard, how strongly the
 * walls throw the sound back and how much they dull it.
 */
public final class AcousticRoom {
    public static final AcousticRoom STUDIO = new AcousticRoom("studio", "Trockenes Studio", 7, 6, 0.35, 0.18, 0.35, 0.7);
    public static final AcousticRoom CHAMBER = new AcousticRoom("chamber", "Kammermusiksaal", 12, 14, 1.0, 0.30, 0.55, 0.5);
    public static final AcousticRoom HALL = new AcousticRoom("hall", "Konzertsaal", 22, 30, 2.0, 0.38, 0.65, 0.4);
    public static final AcousticRoom CATHEDRAL = new AcousticRoom("cathedral", "Kathedrale", 30, 60, 4.5, 0.50, 0.75, 0.3);
    public static final List<AcousticRoom> PRESETS = Collections.unmodifiableList(
            Arrays.asList(STUDIO, CHAMBER, HALL, CATHEDRAL));

    private final String kind;
    private final String name;
    private final double widthMetres;
    private final double depthMetres;
    private final double reverbSeconds;
    private final double amount;
    private final double reflections;
    private final double damping;

    private AcousticRoom(String kind, String name, double widthMetres, double depthMetres, double reverbSeconds,
                         double amount, double reflections, double damping) {
        this.kind = kind;
        this.name = name;
        this.widthMetres = widthMetres;
        this.depthMetres = depthMetres;
        this.reverbSeconds = SoundSourcePlacement.within(reverbSeconds, 0.1, 8);
        this.amount = SoundSourcePlacement.within(amount, 0, 1);
        this.reflections = SoundSourcePlacement.within(reflections, 0, 1);
        this.damping = SoundSourcePlacement.within(damping, 0, 1);
    }

    /** The preset of a kind; an unknown kind is the concert hall. */
    public static AcousticRoom of(String kind) {
        String wanted = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        for (AcousticRoom room : PRESETS) {
            if (room.kind.equals(wanted)) {
                return room;
            }
        }
        return HALL;
    }

    public static boolean known(String kind) {
        String wanted = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        for (AcousticRoom room : PRESETS) {
            if (room.kind.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    public AcousticRoom withReverbSeconds(double seconds) {
        return new AcousticRoom(kind, name, widthMetres, depthMetres, seconds, amount, reflections, damping);
    }

    public AcousticRoom withAmount(double share) {
        return new AcousticRoom(kind, name, widthMetres, depthMetres, reverbSeconds, share, reflections, damping);
    }

    public String kind() { return kind; }
    public String name() { return name; }
    public double widthMetres() { return widthMetres; }
    public double depthMetres() { return depthMetres; }
    public double reverbSeconds() { return reverbSeconds; }
    /** How much of the reverberation is mixed in, 0 (none) to 1. */
    public double amount() { return amount; }
    /** How strongly the walls throw the sound back, 0 to 1. */
    public double reflections() { return reflections; }
    /** How much the walls dull the sound, 0 (bright) to 1 (dull). */
    public double damping() { return damping; }

    @Override
    public String toString() {
        return name;
    }
}
