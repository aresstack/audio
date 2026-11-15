package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.project.StemManifest;
import com.aresstack.audio.studio.render.BuiltinStemRenderer;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine;
import com.aresstack.audio.studio.render.PartMidi;
import com.aresstack.audio.studio.render.RenderLimits;
import com.aresstack.audio.studio.render.SamplerUnavailableException;
import com.aresstack.audio.studio.render.StemCache;
import com.aresstack.audio.studio.render.StemJob;
import com.aresstack.audio.studio.render.StemRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Renders the stems of a piece from its model (architecture review 2.5, E7; work package R1):
 * each part becomes its own MIDI file, is played by the engine its sound bank asks for, and
 * goes into the stem cache of the piece. A part is rendered only if the cache has no stem for
 * its key yet, so after a change to one part exactly that part is rendered again, and a change
 * to the mix renders nothing.
 *
 * <p>Afterwards {@code render/manifest.json} says which file holds the sound of each part, and
 * {@code stems.tsv} says the same for the mix on the stage, which still reads it.
 */
public final class RenderService {
    /**
     * How a part is played.
     *
     * @param renderer  the engine
     * @param soundbank the sound bank file, or {@code null} for the built-in sound
     * @param program   the program to write to the part's MIDI, or {@link PartMidi#OWN_PROGRAM}
     */
    public record Voicing(StemRenderer renderer, Path soundbank, int program) {
    }

    /** Decides how each part is played. */
    @FunctionalInterface
    public interface Instrumentation {
        Voicing of(Part part) throws Exception;
    }

    /**
     * What one rendering did.
     *
     * @param stems    the file of each part, in the order of the parts
     * @param rendered the parts that were rendered now
     * @param reused   the parts whose stem the cache already had
     * @param warnings what did not go as asked (a sampler that could not be started, say)
     */
    public record Result(Map<String, Path> stems, List<String> rendered, List<String> reused, List<String> warnings) {
    }

    private static final StemRenderer BUILTIN = new BuiltinStemRenderer();

    private final Instrumentation instrumentation;
    private final RenderLimits limits;

    public RenderService(Instrumentation instrumentation, RenderLimits limits) {
        this.instrumentation = instrumentation;
        this.limits = limits;
    }

    /**
     * The usual instrumentation: the sound bank a part names ({@link Part#libraryId}) from the
     * registry, played by the engine its format needs; "builtin" is the Java sound. A General
     * MIDI bank plays the part's program (or the one given for it), any other bank program 0.
     */
    public static Instrumentation instrumentation(InstrumentRegistry registry, Sf2Engine sf2Engine,
                                                  String fluidSynthExecutable, String sfizzExecutable,
                                                  Map<String, Integer> programOverrides) {
        InstrumentSampleRenderer samplers = new InstrumentSampleRenderer();
        Map<String, Integer> programs = programOverrides == null ? Map.of() : Map.copyOf(programOverrides);
        return part -> {
            if ("builtin".equals(part.libraryId())) {
                return new Voicing(BUILTIN, null, PartMidi.OWN_PROGRAM);
            }
            InstrumentLibrary library = null;
            for (InstrumentLibrary installed : registry.installedInstruments()) {
                if (installed.id().equals(part.libraryId())) {
                    library = installed;
                }
            }
            Path bank = library == null ? null : registry.find(library.id());
            if (bank == null || !Files.isRegularFile(bank)) {
                throw new IllegalArgumentException("Soundbank nicht installiert: " + part.libraryId()
                        + " (Spur " + part.id() + ")");
            }
            int program = InstrumentSampleRenderer.generalMidi(library)
                    ? programs.getOrDefault(part.id(), PartMidi.OWN_PROGRAM) : 0;
            return new Voicing(samplers.select(library, sf2Engine, fluidSynthExecutable, sfizzExecutable), bank,
                    program);
        };
    }

    /** Renders the stems of the open piece that the cache does not have yet. */
    public Result render(ProjectSession session, Consumer<String> progress) throws Exception {
        return render(session.folder(), session.composition(), progress);
    }

    /** Renders the stems of a piece that the cache of its folder does not have yet. */
    public Result render(Path folder, Composition composition, Consumer<String> progress) throws Exception {
        if (composition.parts().isEmpty()) {
            throw new IllegalArgumentException("Das Stück hat keine Spur.");
        }
        limits.checkPiece(StemJob.pieceSeconds(composition), "Das Stück");
        StemCache cache = new StemCache(folder);
        List<Part> parts = composition.parts();
        Planned[] planned = new Planned[parts.size()];
        List<Callable<Void>> jobs = new ArrayList<>();
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            Voicing voicing = instrumentation.of(part);
            Planned plan = plan(cache, composition, part, voicing);
            planned[index] = plan;
            if (!cache.has(part.id(), plan.key)) {
                jobs.add(() -> {
                    renderOne(cache, composition, plan, warnings, progress);
                    return null;
                });
            }
        }
        if (!jobs.isEmpty()) {
            int threads = Math.max(1, Math.min(jobs.size(), Runtime.getRuntime().availableProcessors()));
            progress.accept("Rendere " + jobs.size() + " von " + parts.size() + " Spuren, " + threads
                    + " gleichzeitig ...");
            runAll(jobs, threads);
        } else {
            progress.accept("Alle " + parts.size() + " Spuren sind unverändert; nichts zu rendern.");
        }

        Map<String, Path> stems = new LinkedHashMap<>();
        List<String> rendered = new ArrayList<>();
        List<String> reused = new ArrayList<>();
        List<StemManifest.Entry> entries = new ArrayList<>();
        for (Planned plan : planned) {
            String id = plan.job.part().id();
            Path stem = cache.stem(id, plan.usedKey);
            stems.put(id, stem);
            (plan.rendered ? rendered : reused).add(id);
            entries.add(new StemManifest.Entry(id, relative(folder, stem), plan.usedEngine, StemManifest.RENDERED,
                    StemManifest.digest(composition, id), plan.usedKey));
        }
        StemManifest.save(folder, entries);
        StageMixService.keepStems(folder, stems);
        return new Result(Collections.unmodifiableMap(stems), List.copyOf(rendered), List.copyOf(reused),
                List.copyOf(warnings));
    }

    private Planned plan(StemCache cache, Composition composition, Part part, Voicing voicing) throws Exception {
        byte[] midi = PartMidi.bytes(composition, part, voicing.program());
        Path midiFile = cache.midi(part.id(), midi);
        StemJob job = new StemJob(composition, part, midiFile, voicing.soundbank(), limits);
        StemRenderer renderer = voicing.renderer();
        String key = StemCache.key(renderer.engine(), renderer.identity(job), midi);
        return new Planned(job, renderer, midi, key);
    }

    private static void renderOne(StemCache cache, Composition composition, Planned plan, List<String> warnings,
                                  Consumer<String> progress) throws Exception {
        String id = plan.job.part().id();
        progress.accept("Rendere " + id + " mit " + plan.renderer.engine() + " ...");
        try {
            cache.store(id, plan.key, file -> plan.renderer.render(plan.job, file));
            plan.rendered = true;
        } catch (SamplerUnavailableException unavailable) {
            // As before: without the sampler the part keeps its Java sound, and the user is told.
            String warning = "WARN: " + plan.renderer.engine() + " lässt sich nicht starten ('"
                    + unavailable.executable() + "'); " + id + " klingt mit dem eingebauten Java-Klang. "
                    + "Pfad des Samplers unter Einstellungen setzen.";
            warnings.add(warning);
            progress.accept(warning);
            StemJob job = new StemJob(composition, plan.job.part(), plan.job.midi(), null, plan.job.limits());
            String key = StemCache.key(BUILTIN.engine(), BUILTIN.identity(job), plan.midi);
            if (!cache.has(id, key)) {
                cache.store(id, key, file -> BUILTIN.render(job, file));
                plan.rendered = true;
            }
            plan.usedKey = key;
            plan.usedEngine = BUILTIN.engine();
        }
    }

    private static void runAll(List<Callable<Void>> jobs, int threads) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (Future<Void> job : executor.invokeAll(jobs)) {
                try {
                    job.get();
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw new IllegalStateException(String.valueOf(cause), cause);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static String relative(Path folder, Path file) {
        return folder.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    /** One part on its way through the cache. */
    private static final class Planned {
        private final StemJob job;
        private final StemRenderer renderer;
        private final byte[] midi;
        private final String key;
        private volatile boolean rendered;
        private volatile String usedKey;
        private volatile String usedEngine;

        private Planned(StemJob job, StemRenderer renderer, byte[] midi, String key) {
            this.job = job;
            this.renderer = renderer;
            this.midi = midi;
            this.key = key;
            this.usedKey = key;
            this.usedEngine = renderer.engine();
        }
    }
}
