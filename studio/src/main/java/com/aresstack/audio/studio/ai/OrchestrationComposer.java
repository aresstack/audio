package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.studio.ai.VoiceNormalizer.ShapedNote;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Re-orchestrates a passage ({@code REORCHESTRATE}, architecture review 2.4 and E4; work package
 * C4): context around the passage, one plan of who takes over what in each frame, then the
 * generators, parts in parallel and the frames of a part one after another with the end of the
 * previous one as connection, the normalizer, and the thinning of bars that are far denser than
 * their intensity allows ({@link Dramaturgy#thin}). It never touches the piece: the result is a
 * {@link ReorchestratedRegion} from which the application builds the patch.
 *
 * <p>Where the new notes go: into the clip of the part that holds their tick; where the part has
 * no clip, into a new clip in a gap of the part's track within the passage; a note neither can
 * hold is left out and recorded. New instruments get a track of their own with one clip over the
 * passage. Every note of a chosen part that begins in the passage leaves its clip.
 */
public final class OrchestrationComposer {
    private final OrchestrationPlanner planner;
    private final OrchestrationGenerator generator;
    private final int threads;

    public OrchestrationComposer(OrchestrationPlanner planner, OrchestrationGenerator generator, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("At least one thread: " + threads);
        }
        this.planner = planner;
        this.generator = generator;
        this.threads = threads;
    }

    /**
     * A composer whose plan comes from the arranging role (follows planning) and whose notes come
     * from the rework role (follows composition), four parts at a time.
     */
    public static OrchestrationComposer with(ModelLineup models) {
        return with(models, 4);
    }

    /** As {@link #with(ModelLineup)}, with as many parts at a time as the user allows requests at once. */
    public static OrchestrationComposer with(ModelLineup models, int partsAtOnce) {
        return new OrchestrationComposer(new ChatOrchestrationPlanner(models.model(ModelRoles.ARRANGING), 4096),
                new ChatOrchestrationGenerator(models.model(ModelRoles.REWORK), AnswerRoom.notes()), Math.max(1, partsAtOnce));
    }

    /** A composer that asks a model for plan and notes, four parts at a time. */
    public static OrchestrationComposer with(ChatModel model) {
        return new OrchestrationComposer(new ChatOrchestrationPlanner(model, 4096),
                new ChatOrchestrationGenerator(model, AnswerRoom.notes()), 4);
    }

    /**
     * Re-orchestrates the passage.
     *
     * @throws ForeignPartException if an answer was for another part or frame
     * @throws IOException          if the model failed or no playable note came back
     */
    public ReorchestratedRegion compose(Composition piece, ReorchestrationRequest request, Supplier<String> ids,
                                        int attempt, Path cacheDirectory, Progress progress) throws IOException {
        OrchestrationTask task = OrchestrationTask.of(piece, request, attempt);
        Files.createDirectories(cacheDirectory);
        progress.update("Plane die Umorchestrierung der Takte " + request.firstBar() + "-" + request.lastBar() + " ("
                + request.preset().label() + ") ...");
        OrchestrationPlan plan;
        try (Listening.Scope planning = EditSketch.preparing(progress, "Die KI plant die Umorchestrierung")) {
            plan = planner.plan(task, piece, cacheDirectory);
        }
        if (plan.frames().size() != task.frames().size()) {
            throw new IOException("Der Plan hat " + plan.frames().size() + " statt " + task.frames().size()
                    + " Abschnitte.");
        }
        plan.repairs().forEach(repair -> progress.update("Plan: " + repair));
        Map<String, String> material = new HashMap<>();
        for (VoiceFrame frame : task.frames()) {
            material.put(frame.sectionId(), OrchestrationTask.material(piece, task, frame));
        }
        List<ContinuationVoice> voices = new ArrayList<>();
        task.parts().forEach(part -> voices.add(ContinuationVoice.of(part)));
        plan.newParts().forEach(part -> voices.add(ContinuationVoice.of(part)));
        Map<String, Written> written = write(piece, task, plan, material, voices, cacheDirectory, progress);
        ReorchestratedRegion result = assemble(piece, task, plan, voices, written,
                new ContinuationComposer.Fresh(piece, ids));
        if (result.noteCount() == 0) {
            throw new IOException("Keine spielbaren Noten für die Umorchestrierung; die Antworten bleiben im Cache zur "
                    + "Diagnose.");
        }
        progress.update("Umorchestrierung: " + result.noteCount() + " Noten in " + task.frames().size()
                + " Abschnitt(en), " + result.newParts().size() + " neue Instrumente, " + result.corrections().size()
                + " Korrekturen.");
        return result;
    }

    private record Written(Map<String, List<ShapedNote>> notes, List<String> corrections) {
    }

    private Map<String, Written> write(Composition piece, OrchestrationTask task, OrchestrationPlan plan,
                                       Map<String, String> material, List<ContinuationVoice> voices, Path cache,
                                       Progress progress) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, Math.max(1, voices.size())), runnable -> {
            Thread thread = new Thread(runnable, "reorchestration");
            thread.setDaemon(true);
            return thread;
        });
        Map<String, Future<Written>> running = new LinkedHashMap<>();
        final EditSketch sketch = EditSketch.of(piece, progress);
        int atOnce = Math.min(threads, Math.max(1, voices.size()));
        progress.detail(voices.size() + " Stimmen, " + atOnce + " davon gleichzeitig (Einstellung „Parallel“: höchstens "
                + threads + "); jede Stimme schreibt ihre Abschnitte nacheinander.");
        try {
            for (ContinuationVoice voice : voices) {
                for (VoiceFrame frame : sketch == null ? List.<VoiceFrame>of() : task.frames()) {
                    sketch.expect(voice.key(), frame);
                }
            }
            for (ContinuationVoice voice : voices) {
                running.put(voice.key(), pool.submit(() -> writePart(piece, task, plan, material, voice, cache,
                        progress, sketch)));
            }
            Map<String, Written> written = new HashMap<>();
            for (Map.Entry<String, Future<Written>> entry : running.entrySet()) {
                written.put(entry.getKey(), entry.getValue().get());
            }
            return written;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Umorchestrieren abgebrochen.", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Umorchestrieren fehlgeschlagen: " + cause.getMessage(), cause);
        } finally {
            pool.shutdownNow();
        }
    }

    private Written writePart(Composition piece, OrchestrationTask task, OrchestrationPlan plan,
                              Map<String, String> material, ContinuationVoice voice, Path cache, Progress progress,
                              EditSketch sketch) throws IOException {
        VoiceNormalizer normalizer = new VoiceNormalizer(task.time(), voice.range(), voice.drums());
        List<String> densities = new ArrayList<>();
        Map<String, List<ShapedNote>> notes = new LinkedHashMap<>();
        String connection = voice.newPart() ? "" : ContinuationComposer.before(piece, voice.key(), task.fromTick());
        for (VoiceFrame frame : task.frames()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Umorchestrieren abgebrochen.");
            }
            OrchestrationPlan.Assignment assignment = plan.frame(frame.sectionId()).of(voice.key());
            if (assignment == null) {
                connection = "";
                if (sketch != null) {
                    sketch.rests(voice.key(), frame);
                }
                continue;
            }
            progress.update("Orchestriere " + voice.name() + " für " + frame.name() + " ...");
            long asked = System.currentTimeMillis();
            GeneratedNotes generated;
            try (Listening.Scope watching = Listening.to(sketch == null ? null
                    : sketch.hearing(voice.key(), voice.program(), voice.role(), frame))) {
                generated = generator.generate(task, plan, voice, frame, assignment,
                        material.get(frame.sectionId()), connection, cache);
            }
            if (sketch != null) {
                sketch.written(voice.key(), voice.program(), voice.role(), frame, generated);
            }
            progress.detail("Orchestriert: " + voice.name() + " für " + frame.name() + " (" + generated.notes().size()
                    + " Noten, " + (System.currentTimeMillis() - asked + 500) / 1000 + " s)");
            List<ShapedNote> shaped = normalizer.shape(frame, generated);
            List<String> thinned = new ArrayList<>();
            shaped = Dramaturgy.thin(frame, task.time(), frame.intensity(), voice.role(), shaped, thinned);
            thinned.forEach(line -> densities.add(voice.key() + "\t" + line));
            if (shaped.isEmpty()) {
                progress.update("WARN: keine spielbaren Noten für " + voice.name() + " in " + frame.name() + ".");
            }
            notes.put(frame.sectionId(), shaped);
            connection = VoiceComposer.connection(task.time(), frame, shaped);
        }
        List<String> corrections = new ArrayList<>();
        normalizer.corrections().forEach(line -> corrections.add(voice.key() + "\t" + line));
        corrections.addAll(densities);
        return new Written(notes, corrections);
    }

    /** A stretch of a part's track within the passage that no clip covers, and the clip it may get. */
    private static final class Gap {
        final long start;
        final long end;
        final List<Note> notes = new ArrayList<>();

        Gap(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static ReorchestratedRegion assemble(Composition piece, OrchestrationTask task, OrchestrationPlan plan,
                                                 List<ContinuationVoice> voices, Map<String, Written> written,
                                                 ContinuationComposer.Fresh fresh) {
        List<String> corrections = new ArrayList<>();
        List<Part> newParts = new ArrayList<>();
        List<Track> newTracks = new ArrayList<>();
        Map<String, List<String>> removed = new LinkedHashMap<>();
        Map<String, List<Note>> added = new LinkedHashMap<>();
        List<ComposedContinuation.PlacedClip> clips = new ArrayList<>();
        String library = ContinuationComposer.library(piece);
        long from = task.fromTick();
        long to = task.toTick();
        for (ContinuationVoice voice : voices) {
            Written part = written.get(voice.key());
            corrections.addAll(part.corrections());
            List<Long> ticks = new ArrayList<>();
            List<ShapedNote> shaped = new ArrayList<>();
            for (VoiceFrame frame : task.frames()) {
                for (ShapedNote n : part.notes().getOrDefault(frame.sectionId(), List.of())) {
                    ticks.add(frame.startTick() + n.tick());
                    shaped.add(n);
                }
            }
            if (voice.newPart()) {
                if (shaped.isEmpty()) {
                    corrections.add(voice.key() + "\t-\tpart\tpresent\tskipped\tneues Instrument ohne spielbare Noten");
                    continue;
                }
                Part made = new Part(fresh.next(), voice.name(), voice.role(), voice.instrument(), voice.program(), library,
                        Part.StaffLayout.SINGLE, List.of(new Voice(fresh.next(), voice.name(), null, null)), null);
                Track track = new Track(fresh.next(), voice.name(), Track.Kind.MIDI, made.id(), List.of());
                newParts.add(made);
                newTracks.add(track);
                String voiceId = made.firstVoice().id();
                List<Note> notes = new ArrayList<>();
                for (int i = 0; i < shaped.size(); i++) {
                    ShapedNote n = shaped.get(i);
                    notes.add(new Note(fresh.next(), ticks.get(i) - from, n.length(), n.pitch(), n.velocity(), voiceId,
                            n.articulation()));
                }
                clips.add(new ComposedContinuation.PlacedClip(track.id(),
                        new MidiClip(fresh.next(), from, to - from, null, notes)));
                continue;
            }
            Part player = piece.part(voice.key());
            String voiceId = player.firstVoice().id();
            List<MidiClip> owned = new ArrayList<>();
            for (Track track : piece.tracks()) {
                for (Clip clip : track.clips()) {
                    if (clip instanceof MidiClip midi && player.id().equals(track.partOf(midi))) {
                        owned.add(midi);
                        for (Note note : midi.notes()) {
                            long tick = midi.startTick() + note.tick();
                            if (tick >= from && tick < to) {
                                removed.computeIfAbsent(midi.id(), id -> new ArrayList<>()).add(note.id());
                            }
                        }
                    }
                }
            }
            Track home = ContinuationComposer.trackFor(piece, player.id());
            boolean newHome = home == null;
            if (newHome) {
                home = new Track(fresh.next(), player.name(), Track.Kind.MIDI, player.id(), List.of());
            }
            List<Gap> gaps = gaps(home, from, to);
            for (int i = 0; i < shaped.size(); i++) {
                ShapedNote n = shaped.get(i);
                long tick = ticks.get(i);
                MidiClip holder = null;
                for (MidiClip clip : owned) {
                    if (tick >= clip.startTick() && tick < clip.endTick()) {
                        holder = clip;
                        break;
                    }
                }
                if (holder != null) {
                    long length = Math.min(n.length(), Math.min(holder.endTick(), to) - tick);
                    cut(corrections, player.id(), n.length(), length);
                    added.computeIfAbsent(holder.id(), id -> new ArrayList<>()).add(new Note(fresh.next(),
                            tick - holder.startTick(), length, n.pitch(), n.velocity(), voiceId, n.articulation()));
                    continue;
                }
                Gap gap = gaps.stream().filter(g -> tick >= g.start && tick < g.end).findFirst().orElse(null);
                if (gap == null) {
                    corrections.add(player.id() + "\t-\t-\tnote\tpresent\tskipped\tkein Platz auf der Bahn der Stimme "
                            + "bei Takt " + (task.time().barOf(tick) + 1));
                    continue;
                }
                long length = Math.min(n.length(), gap.end - tick);
                cut(corrections, player.id(), n.length(), length);
                gap.notes.add(new Note(fresh.next(), tick - gap.start, length, n.pitch(), n.velocity(), voiceId,
                        n.articulation()));
            }
            boolean needsHome = false;
            for (Gap gap : gaps) {
                if (gap.notes.isEmpty()) {
                    continue;
                }
                needsHome = true;
                String clipPart = player.id().equals(home.defaultPartId()) ? null : player.id();
                clips.add(new ComposedContinuation.PlacedClip(home.id(),
                        new MidiClip(fresh.next(), gap.start, gap.end - gap.start, clipPart, gap.notes)));
            }
            if (newHome && needsHome) {
                newTracks.add(home);
            }
        }
        Set<String> touched = new LinkedHashSet<>(removed.keySet());
        touched.addAll(added.keySet());
        List<ReorchestratedRegion.ClipChange> changes = new ArrayList<>();
        for (String clip : touched) {
            changes.add(new ReorchestratedRegion.ClipChange(clip, removed.getOrDefault(clip, List.of()),
                    added.getOrDefault(clip, List.of())));
        }
        return new ReorchestratedRegion(task, plan, newParts, newTracks, changes, clips, corrections);
    }

    /** The stretches of a track within {@code from..to} that no clip of it covers. */
    private static List<Gap> gaps(Track track, long from, long to) {
        List<Gap> gaps = new ArrayList<>();
        long at = from;
        List<Clip> sorted = new ArrayList<>(track.clips());
        sorted.sort(java.util.Comparator.comparingLong(Clip::startTick));
        for (Clip clip : sorted) {
            long end = clip instanceof MidiClip midi ? midi.endTick() : clip.startTick() + 1;
            if (end <= at || clip.startTick() >= to) {
                continue;
            }
            if (clip.startTick() > at) {
                gaps.add(new Gap(at, Math.min(clip.startTick(), to)));
            }
            at = Math.max(at, end);
        }
        if (at < to) {
            gaps.add(new Gap(at, to));
        }
        return gaps;
    }

    private static void cut(List<String> corrections, String part, long was, long now) {
        if (was != now) {
            corrections.add(part + "\t-\t-\tduration\t" + ContextBuilder.quarters(was) + "\t"
                    + ContextBuilder.quarters(now) + "\tam Clipende abgeschnitten");
        }
    }
}
