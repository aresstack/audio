package com.aresstack.audio.studio.render;

import com.aresstack.audio.composition.Articulation;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The samples a sound bank has for articulations, and how they are reached: by a keyswitch (a
 * key outside the range of the instrument that selects a set of samples, as sample libraries in
 * SFZ do) or by another program of the bank (a "Violin Staccato" preset beside the "Violin" one,
 * as many SF2 banks have it). A General MIDI bank has no such samples; there an articulation
 * sounds through the length and velocity of the note alone ({@code Dynamics.Articulations}).
 *
 * <p>The user says what a bank has in a text file beside it, named like the bank with
 * {@code .articulations} added ({@code Strings.sfz.articulations}), one line per articulation:
 * <pre>
 * normal        = keyswitch 24
 * staccato      = keyswitch 26
 * accent        = keyswitch 28
 * legato        = program 49
 * keyswitch.velocity = 100
 * keyswitch.lead     = 1
 * </pre>
 * {@code normal} is what plays notes without an articulation; without it they play on the
 * program of the part, and a keyswitch bank stays on the last keyswitch. {@code keyswitch.lead} is
 * how many ticks before its note a keyswitch is pressed, {@code keyswitch.velocity} how hard.
 * Unknown lines are left out.
 *
 * @param sounds    the sound of each articulation the bank has; the key {@code null} is the normal sound
 * @param velocity  the velocity a keyswitch is pressed with
 * @param leadTicks how many ticks before its note a keyswitch is pressed
 */
public record ArticulationSounds(Map<Articulation, Sound> sounds, Sound normal, int velocity, int leadTicks) {
    /** The ending of the file beside a sound bank that says its articulations. */
    public static final String SUFFIX = ".articulations";
    /** How hard a keyswitch is pressed when the file does not say. */
    public static final int DEFAULT_KEYSWITCH_VELOCITY = 100;
    /** How many ticks before its note a keyswitch is pressed when the file does not say. */
    public static final int DEFAULT_KEYSWITCH_LEAD = 1;
    /** A bank without articulation samples. */
    public static final ArticulationSounds NONE = new ArticulationSounds(Map.of(), null,
            DEFAULT_KEYSWITCH_VELOCITY, DEFAULT_KEYSWITCH_LEAD);

    /** How a set of samples is reached: a keyswitch key, or a program of the bank. */
    public enum Kind { KEYSWITCH, PROGRAM }

    /** One way to a set of samples. */
    public record Sound(Kind kind, int value) {
        public Sound {
            if (kind == null || value < 0 || value > 127) {
                throw new IllegalArgumentException("A keyswitch or program lies between 0 and 127: " + value);
            }
        }

        static Sound parse(String text) {
            String[] words = text.trim().toLowerCase(Locale.ROOT).split("\\s+");
            if (words.length != 2) {
                throw new IllegalArgumentException("Expected \"keyswitch <key>\" or \"program <number>\": " + text);
            }
            Kind kind = switch (words[0]) {
                case "keyswitch", "key", "ks" -> Kind.KEYSWITCH;
                case "program", "preset" -> Kind.PROGRAM;
                default -> throw new IllegalArgumentException("Unknown kind of sound: " + words[0]);
            };
            return new Sound(kind, Integer.parseInt(words[1]));
        }
    }

    public ArticulationSounds {
        EnumMap<Articulation, Sound> copy = new EnumMap<>(Articulation.class);
        if (sounds != null) {
            copy.putAll(sounds);
        }
        sounds = Collections.unmodifiableMap(copy);
        if (velocity < 1 || velocity > 127 || leadTicks < 0) {
            throw new IllegalArgumentException("A keyswitch has a velocity from 1 to 127 and a lead from 0 ticks.");
        }
    }

    public boolean isEmpty() {
        return sounds.isEmpty() && normal == null;
    }

    /** The sound a note with this articulation needs, {@code null} if the bank has nothing of its own for it. */
    public Sound of(Articulation articulation) {
        return articulation == null ? normal : sounds.get(articulation);
    }

    /** The file beside a sound bank that says its articulations. */
    public static Path fileOf(Path soundbank) {
        return soundbank.resolveSibling(soundbank.getFileName() + SUFFIX);
    }

    /** What the file beside a sound bank says; {@link #NONE} without such a file. */
    public static ArticulationSounds beside(Path soundbank) throws IOException {
        if (soundbank == null || soundbank.getFileName() == null) {
            return NONE;
        }
        Path file = fileOf(soundbank);
        if (!Files.isRegularFile(file)) {
            return NONE;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties lines = new Properties();
            lines.load(reader);
            return of(lines);
        }
    }

    /** What lines as in the file say; a line that cannot be read is left out. */
    public static ArticulationSounds of(Properties lines) {
        Map<Articulation, Sound> sounds = new EnumMap<>(Articulation.class);
        Sound normal = null;
        int velocity = DEFAULT_KEYSWITCH_VELOCITY;
        int lead = DEFAULT_KEYSWITCH_LEAD;
        for (String name : lines.stringPropertyNames()) {
            String value = lines.getProperty(name);
            String key = name.trim().toLowerCase(Locale.ROOT);
            try {
                switch (key) {
                    case "normal" -> normal = Sound.parse(value);
                    case "keyswitch.velocity" -> velocity = Math.max(1, Math.min(127, Integer.parseInt(value.trim())));
                    case "keyswitch.lead" -> lead = Math.max(0, Integer.parseInt(value.trim()));
                    default -> {
                        Articulation articulation = Articulation.parse(key);
                        if (articulation != null) {
                            sounds.put(articulation, Sound.parse(value));
                        }
                    }
                }
            } catch (RuntimeException unreadable) {
                // a line that cannot be read changes nothing
            }
        }
        return new ArticulationSounds(sounds, normal, velocity, lead);
    }

    /** The lines of the file beside a bank that say these sounds, as {@link #beside} reads them. */
    public String text() {
        StringBuilder text = new StringBuilder("# Articulation samples of this sound bank (Audio Studio)\n");
        if (normal != null) {
            text.append("normal = ").append(line(normal)).append('\n');
        }
        for (Map.Entry<Articulation, Sound> entry : sounds.entrySet()) {
            text.append(entry.getKey().token()).append(" = ").append(line(entry.getValue())).append('\n');
        }
        text.append("keyswitch.velocity = ").append(velocity).append('\n');
        text.append("keyswitch.lead = ").append(leadTicks).append('\n');
        return text.toString();
    }

    private static String line(Sound sound) {
        return (sound.kind() == Kind.KEYSWITCH ? "keyswitch " : "program ") + sound.value();
    }

    /**
     * Writes these sounds into the file beside a sound bank; without any sound the file is
     * removed, so that the bank plays as before.
     */
    public void saveBeside(Path soundbank) throws IOException {
        Path file = fileOf(soundbank);
        if (isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.writeString(file, text(), StandardCharsets.UTF_8);
    }

    /** A short text for the cache identity of a stem. */
    public String describe() {
        return isEmpty() ? "none" : "normal=" + normal + " " + sounds + " v" + velocity + " lead" + leadTicks;
    }
}
