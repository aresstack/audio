package com.aresstack.audio.studio.application;

import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.render.ExternalProcessRunner;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine;
import com.aresstack.audio.studio.render.MidiStemExporter;
import com.aresstack.audio.studio.render.SamplerUnavailableException;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Produce the Java reference mix, then optionally replace stems with registered sample banks,
 * and mix the tracks on the stage of the piece.
 */
public final class StudioMusicRenderer {
    private final InstrumentRegistry registry;
    private final InstrumentSampleRenderer sampler = new InstrumentSampleRenderer();
    private final MidiStemExporter midiExporter = new MidiStemExporter();
    private final StageMixService stage = new StageMixService();
    private final ExternalProcessRunner runner = new ExternalProcessRunner();

    public StudioMusicRenderer(InstrumentRegistry registry) {
        this.registry = registry;
    }

    public Path render(Path arrangement, Path directory, Map<String, InstrumentLibrary> assignments,
                       String fluidSynthExecutable, String sfizzExecutable,
                       GemmaMusicComposer.Progress progress) throws Exception {
        return render(arrangement, directory, assignments, java.util.Collections.<String, Integer>emptyMap(),
                Sf2Engine.GERVILL, fluidSynthExecutable, sfizzExecutable, progress);
    }

    public Path render(Path arrangement, Path directory, Map<String, InstrumentLibrary> assignments,
                       Map<String, Integer> programOverrides,
                       String fluidSynthExecutable, String sfizzExecutable,
                       GemmaMusicComposer.Progress progress) throws Exception {
        return render(arrangement, directory, assignments, programOverrides,
                Sf2Engine.GERVILL, fluidSynthExecutable, sfizzExecutable, progress);
    }

    public Path render(Path arrangement, Path directory, Map<String, InstrumentLibrary> assignments,
                       Map<String, Integer> programOverrides, Sf2Engine sf2Engine,
                       String fluidSynthExecutable, String sfizzExecutable,
                       GemmaMusicComposer.Progress progress) throws Exception {
        Files.createDirectories(directory);
        progress.update("Creating reference MIDI and Java-synthesis WAVs ...");
        String executable = javaExecutable();
        List<String> command = Arrays.asList(executable, "-cp",
                System.getProperty("java.class.path"), "MultiTrackMusicRenderer",
                arrangement.toAbsolutePath().toString(), directory.toAbsolutePath().toString());
        runner.execute(command, directory.resolve("arrangement.wav"));

        // The tracks with a sound bank are rendered side by side: each one is a process of its own
        // that loads its bank, and none depends on another. The mix keeps the order of the tracks.
        final List<String> trackIds = readTrackIds(arrangement);
        final Path[] stems = new Path[trackIds.size()];
        List<java.util.concurrent.Callable<Void>> jobs = new ArrayList<java.util.concurrent.Callable<Void>>();
        for (int index = 0; index < trackIds.size(); index++) {
            final int place = index;
            final String track = trackIds.get(index);
            final InstrumentLibrary library = assignments.get(track);
            if (library == null) {
                stems[place] = directory.resolve(track + ".wav");
                continue;
            }
            final Path bank = registry.find(library.id());
            if (bank == null || !Files.isRegularFile(bank)) {
                throw new IllegalArgumentException("Register an installed " + library.format()
                        + " file for " + library.name() + " before rendering " + track + ".");
            }
            GemmaMusicComposer.tell(progress, track, GemmaMusicComposer.Stage.RENDER,
                    GemmaMusicComposer.State.WAITING, "");
            jobs.add(new java.util.concurrent.Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    stems[place] = renderStem(track, library, bank, directory, programOverrides, sf2Engine,
                            fluidSynthExecutable, sfizzExecutable, progress);
                    return null;
                }
            });
        }
        if (!jobs.isEmpty()) {
            int threads = Math.max(1, Math.min(jobs.size(), Runtime.getRuntime().availableProcessors()));
            progress.update("Rendering " + jobs.size() + " tracks with their sound banks, " + threads
                    + " at the same time ...");
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                for (java.util.concurrent.Future<Void> job : executor.invokeAll(jobs)) {
                    try {
                        job.get();
                    } catch (java.util.concurrent.ExecutionException failed) {
                        Throwable cause = failed.getCause();
                        if (cause instanceof Exception) {
                            throw (Exception) cause;
                        }
                        throw new IllegalStateException(String.valueOf(cause), cause);
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }
        // The mix is made on the stage of the piece; which file holds each track is noted, so that
        // moving an instrument later only mixes again.
        StageMixService.keepStems(directory, trackIds, stems);
        return stage.mix(arrangement, directory, progress);
    }

    /** Renders one track with its sound bank; if the sampler is missing, the Java sound of the track is kept. */
    private Path renderStem(String track, InstrumentLibrary library, Path bank, Path directory,
                            Map<String, Integer> programOverrides, Sf2Engine sf2Engine,
                            String fluidSynthExecutable, String sfizzExecutable,
                            GemmaMusicComposer.Progress progress) throws Exception {
        String engineName = library.format() == InstrumentLibrary.Format.SF2
                && sf2Engine == Sf2Engine.GERVILL ? "Java/Gervill"
                : library.format() == InstrumentLibrary.Format.SFZ ? "sfizz" : "FluidSynth";
        progress.update("Rendering " + track + " with " + library.name()
                + " using " + engineName + " ...");
        GemmaMusicComposer.tell(progress, track, GemmaMusicComposer.Stage.RENDER,
                GemmaMusicComposer.State.WORKING, engineName);
        try {
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
            try {
                sampler.render(library, bank, trackMidi, stem, sf2Engine,
                        fluidSynthExecutable, sfizzExecutable);
                GemmaMusicComposer.tell(progress, track, GemmaMusicComposer.Stage.RENDER,
                        GemmaMusicComposer.State.DONE, library.name());
                return stem;
            } catch (SamplerUnavailableException unavailable) {
                String engine = library.format() == InstrumentLibrary.Format.SFZ
                        ? "sfizz_render" : library.format() == InstrumentLibrary.Format.SF2
                        && sf2Engine == Sf2Engine.GERVILL ? "Java/Gervill" : "FluidSynth";
                progress.update("WARN: " + engine + " is not installed or cannot be launched ('"
                        + unavailable.executable() + "'). Using the built-in Java sound for "
                        + track + " instead. Install the sampler and configure its path under "
                        + "Einstellungen to hear " + library.name() + ".");
                GemmaMusicComposer.tell(progress, track, GemmaMusicComposer.Stage.RENDER,
                        GemmaMusicComposer.State.DONE, "Java sound");
                return directory.resolve(track + ".wav");
            }
        } catch (Exception failed) {
            GemmaMusicComposer.tell(progress, track, GemmaMusicComposer.Stage.RENDER,
                    GemmaMusicComposer.State.FAILED, String.valueOf(failed.getMessage()));
            throw failed;
        }
    }

    /** Trust the written TSV arrangement as the source of truth for output track order. */
    private static List<String> readTrackIds(Path arrangement) throws Exception {
        List<String> tracks = new ArrayList<String>();
        for (String line : Files.readAllLines(arrangement, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\\t");
            if (fields.length >= 4 && "track".equals(fields[0])) {
                tracks.add(fields[1]);
            }
        }
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("Arrangement must contain at least one track.");
        }
        return tracks;
    }

    private static String javaExecutable() {
        Path binary = java.nio.file.Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        return binary.toString();
    }
}
