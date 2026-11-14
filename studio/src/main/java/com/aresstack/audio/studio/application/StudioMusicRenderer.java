package com.aresstack.audio.studio.application;

import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.render.ExternalProcessRunner;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer;
import com.aresstack.audio.studio.render.MidiStemExporter;
import com.aresstack.audio.studio.render.WaveStemMixer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Produce the Java reference mix, then optionally replace stems with registered sample banks. */
public final class StudioMusicRenderer {
    private final InstrumentRegistry registry;
    private final InstrumentSampleRenderer sampler = new InstrumentSampleRenderer();
    private final MidiStemExporter midiExporter = new MidiStemExporter();
    private final WaveStemMixer mixer = new WaveStemMixer();
    private final ExternalProcessRunner runner = new ExternalProcessRunner();

    public StudioMusicRenderer(InstrumentRegistry registry) {
        this.registry = registry;
    }

    public Path render(Path arrangement, Path directory, Map<String, InstrumentLibrary> assignments,
                       String fluidSynthExecutable, String sfizzExecutable,
                       GemmaMusicComposer.Progress progress) throws Exception {
        return render(arrangement, directory, assignments, java.util.Collections.<String, Integer>emptyMap(),
                fluidSynthExecutable, sfizzExecutable, progress);
    }

    public Path render(Path arrangement, Path directory, Map<String, InstrumentLibrary> assignments,
                       Map<String, Integer> programOverrides,
                       String fluidSynthExecutable, String sfizzExecutable,
                       GemmaMusicComposer.Progress progress) throws Exception {
        Files.createDirectories(directory);
        progress.update("Creating reference MIDI and Java-synthesis WAVs ...");
        String executable = javaExecutable();
        List<String> command = Arrays.asList(executable, "-cp",
                System.getProperty("java.class.path"), "MultiTrackMusicRenderer",
                arrangement.toAbsolutePath().toString(), directory.toAbsolutePath().toString());
        runner.execute(command, directory.resolve("arrangement.wav"));

        List<Path> stems = new ArrayList<Path>();
        for (String track : Arrays.asList("melody", "harmony", "bass", "drums")) {
            InstrumentLibrary library = assignments.get(track);
            if (library == null) {
                stems.add(directory.resolve(track + ".wav"));
                continue;
            }
            Path bank = registry.find(library.id());
            if (bank == null || !Files.isRegularFile(bank)) {
                throw new IllegalArgumentException("Register an installed " + library.format()
                        + " file for " + library.name() + " before rendering " + track + ".");
            }
            progress.update("Rendering " + track + " with " + library.name() + " ...");
            boolean generalMidi = library.id().equals("generaluser")
                    || library.id().startsWith("generaluser::")
                    || library.id().equals("musescore-general")
                    || library.id().startsWith("musescore-general::");
            Path trackMidi = directory.resolve(track + "-isolated.mid");
            int selectedProgram = generalMidi
                    ? programOverrides.containsKey(track) ? programOverrides.get(track) : -1
                    : 0;
            midiExporter.isolate(directory.resolve("arrangement.mid"), track,
                    selectedProgram, trackMidi);
            Path stem = directory.resolve(track + "-sampled.wav");
            sampler.render(library, bank, trackMidi, stem, fluidSynthExecutable, sfizzExecutable);
            stems.add(stem);
        }
        Path mix = directory.resolve("arrangement-instruments.wav");
        progress.update("Mixing the instrument tracks ...");
        mixer.mix(stems, mix);
        return mix;
    }

    private static String javaExecutable() {
        Path binary = java.nio.file.Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        return binary.toString();
    }
}
