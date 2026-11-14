package com.aresstack.audio.music.soundfont;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Render an existing MIDI arrangement using a SoundFont and the FluidSynth CLI. */
public final class SoundFontMidiRenderer {
    private static final long TIMEOUT_MINUTES = 5;

    private SoundFontMidiRenderer() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 3 || arguments.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: java -jar music-soundfont-renderer.jar input.mid soundfont.sf2 output.wav [fluidsynth-command]");
        }

        Path midi = Paths.get(arguments[0]).toAbsolutePath().normalize();
        Path soundFont = Paths.get(arguments[1]).toAbsolutePath().normalize();
        Path output = Paths.get(arguments[2]).toAbsolutePath().normalize();
        String executable = arguments.length == 4 ? arguments[3] : "fluidsynth";
        render(midi, soundFont, output, executable);
    }

    private static void render(Path midi, Path soundFont, Path output, String executable)
            throws IOException, InterruptedException {
        requireRegularFile(midi, "MIDI file");
        requireRegularFile(soundFont, "SoundFont");
        if (!output.toString().toLowerCase(Locale.ROOT).endsWith(".wav")) {
            throw new IllegalArgumentException("Output must be a WAV file: " + output);
        }
        if (midi.equals(output) || soundFont.equals(output)) {
            throw new IllegalArgumentException("Output must differ from input files.");
        }
        Files.createDirectories(output.getParent());

        Process process;
        try {
            process = new ProcessBuilder(command(executable, midi, soundFont, output))
                    .inheritIO()
                    .start();
        } catch (IOException error) {
            throw new IOException("Could not start FluidSynth. Install the FluidSynth CLI or pass its executable path.", error);
        }

        boolean completed = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("FluidSynth timed out rendering: " + midi);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) < 44) {
            Files.deleteIfExists(output);
            throw new IOException("FluidSynth failed to produce a valid WAV (exit code "
                    + process.exitValue() + "): " + output);
        }
        System.out.println("Rendered SoundFont audio: " + output);
    }

    private static List<String> command(String executable, Path midi, Path soundFont, Path output) {
        List<String> arguments = new ArrayList<String>();
        arguments.add(executable);
        arguments.add("-n");
        arguments.add("-i");
        arguments.add("-g");
        arguments.add("0.35");
        arguments.add("-r");
        arguments.add("44100");
        arguments.add("-T");
        arguments.add("wav");
        arguments.add("-F");
        arguments.add(output.toString());
        arguments.add(soundFont.toString());
        arguments.add(midi.toString());
        return arguments;
    }

    private static void requireRegularFile(Path path, String description) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(description + " not found: " + path);
        }
    }
}
