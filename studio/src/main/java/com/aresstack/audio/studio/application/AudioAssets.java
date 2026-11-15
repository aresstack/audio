package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Asset;
import com.aresstack.audio.composition.AudioClip;
import com.aresstack.audio.core.stream.LinearResampler;
import com.aresstack.audio.core.wav.WavFileSource;
import com.aresstack.audio.sounds.SoundDesign;
import com.aresstack.audio.sounds.SoundPlanReader;
import com.aresstack.audio.sounds.SoundSynthesizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The audio files a piece uses as source material (work package B3): they lie in the folder
 * {@code assets/} of the piece, and the model names them as {@link Asset}s with a path relative to
 * the piece, so that the folder can be moved. A file the user picks is copied there; a sound effect
 * of the module {@code sounds} is rendered there from its sound plan.
 */
public final class AudioAssets {
    /** The folder of the files, within the folder of the piece. */
    public static final String FOLDER = "assets";
    private static final String PLAN = "/com/aresstack/audio/studio/sfx/sound-plan.json";

    /** A file ready to be placed: the asset and how many samples it lasts at {@link AudioClip#SAMPLE_RATE}. */
    public record Ready(Asset asset, long lengthSamples) {
    }

    private AudioAssets() {
    }

    /** Copies a WAV file into the piece and names it as a new asset. */
    public static Ready copy(Path folder, Path source, Supplier<String> ids) throws IOException {
        String id = ids.get();
        String name = source.getFileName().toString();
        String base = name.replaceFirst("\\.[^.]*$", "");
        Path target = folder.resolve(FOLDER).resolve(id + "-" + safe(base) + ".wav");
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        try {
            return new Ready(new Asset(id, base, FOLDER + "/" + target.getFileName()), lengthOf(target));
        } catch (IOException unreadable) {
            Files.deleteIfExists(target);
            throw new IOException("Keine lesbare WAV-Datei: " + name, unreadable);
        }
    }

    /** The sound effects the studio offers, from the sound plan of the module {@code sounds}. */
    public static List<SoundDesign> effects() throws IOException {
        Path plan = Files.createTempFile("sound-plan", ".json");
        try (InputStream in = AudioAssets.class.getResourceAsStream(PLAN)) {
            if (in == null) {
                return List.of();
            }
            Files.copy(in, plan, StandardCopyOption.REPLACE_EXISTING);
            return new SoundPlanReader().read(plan);
        } finally {
            Files.deleteIfExists(plan);
        }
    }

    /** Renders a sound effect into the piece and names it as a new asset. */
    public static Ready render(Path folder, SoundDesign effect, Supplier<String> ids) throws IOException {
        String id = ids.get();
        Path target = folder.resolve(FOLDER).resolve(id + "-" + safe(effect.id()) + ".wav");
        Files.createDirectories(target.getParent());
        new SoundSynthesizer().render(effect, target);
        return new Ready(new Asset(id, effect.id(), FOLDER + "/" + target.getFileName()), lengthOf(target));
    }

    /** How many samples a WAV file lasts at the rate of audio clips. */
    public static long lengthOf(Path wav) throws IOException {
        float rate;
        try (WavFileSource source = new WavFileSource(wav)) {
            rate = source.sampleRate();
        }
        return LinearResampler.outputFrames(WavFileSource.countFrames(wav), rate, AudioClip.SAMPLE_RATE);
    }

    private static String safe(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "klang" : cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
