package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.studio.ai.VoiceNormalizer.ShapedNote;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Continues a piece ({@code APPEND}, architecture review 2.4 and E4; report 5): tiered context of
 * all parts, one shared plan, then the generators, parts in parallel and the sections of a part
 * one after another with the end of the previous one as connection, and the normalizer. With a
 * revised transition the last bars of the original are written anew first, as a frame of their
 * own. It never touches the piece: the result is a {@link ComposedContinuation} from which the
 * application builds the patch.
 */
public final class ContinuationComposer {
    /** Bars at the end of a frame passed on to the next one as connection. */
    public static final int CONNECTION_BARS = 2;

    private final ContinuationPlanner planner;
    private final SectionGenerator generator;
    private final int threads;

    /** @param threads how many parts are written at the same time, at least 1 */
    public ContinuationComposer(ContinuationPlanner planner, SectionGenerator generator, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("At least one thread: " + threads);
        }
        this.planner = planner;
        this.generator = generator;
        this.threads = threads;
    }

    /** A composer that asks a model for plan and notes, four parts at a time. */
    public static ContinuationComposer with(ChatModel model) {
        return new ContinuationComposer(new ChatContinuationPlanner(model, 4096), new ChatSectionGenerator(model, 16384),
                4);
    }

    /**
     * The frames of a plan: the revised tail of the original if there is one, then the planned
     * sections, in the order of the piece.
     */
    public static List<VoiceFrame> frames(ContinuationTask task, ContinuationPlan plan, Composition piece) {
        TimeMap time = task.time();
        List<VoiceFrame> frames = new ArrayList<>();
        if (task.revisesTail()) {
            ContinuationPlan.PlannedSection next = plan.sections().get(0);
            int bars = task.request().tailBars();
            frames.add(new VoiceFrame(SectionGenerator.TRANSITION, "Übergang", "transition", task.tailStart(),
                    task.newStart(), task.firstTailBar(), bars, chordsOf(piece, task.firstTailBar(), bars), null,
                    next.name() + (next.idea().isEmpty() ? "" : ": " + next.idea())));
        }
        for (ContinuationPlan.PlannedSection section : plan.sections()) {
            int first = task.firstNewBar() + plan.firstBarOf(section);
            frames.add(new VoiceFrame(section.key(), section.name(), section.function(), time.tickOfBar(first),
                    time.tickOfBar(first + section.bars()), first, section.bars(), section.chords(), section.intensity(),
                    section.idea()));
        }
        return frames;
    }

    /**
     * Composes the continuation.
     *
     * @param ids            where the ids of new objects come from; ids the piece uses are skipped
     * @param attempt        1 for the first, more to compose it again differently
     * @param cacheDirectory where the answers of the model are kept
     * @throws ForeignPartException if an answer was for another part or section
     * @throws IOException          if the model failed or no playable note came back
     */
    public ComposedContinuation compose(Composition piece, ContinuationRequest request, Supplier<String> ids,
                                        int attempt, Path cacheDirectory, Progress progress) throws IOException {
        ContinuationTask task = ContinuationTask.of(piece, request, attempt);
        Files.createDirectories(cacheDirectory);
        progress.update("Plane die Fortsetzung (" + request.bars() + " Takte, " + request.preset().label() + ") ...");
        ContinuationPlan plan = planner.plan(task, piece, cacheDirectory);
        if (plan.bars() != request.bars()) {
            throw new IOException("Der Plan deckt " + plan.bars() + " statt " + request.bars() + " Takte ab.");
        }
        for (String repair : plan.repairs()) {
            progress.update("Plan: " + repair);
        }
        List<VoiceFrame> frames = frames(task, plan, piece);
        List<ContinuationVoice> voices = new ArrayList<>();
        task.parts().forEach(part -> voices.add(ContinuationVoice.of(part)));
        plan.newParts().forEach(part -> voices.add(ContinuationVoice.of(part)));

        Map<String, Written> written = write(piece, task, plan, frames, voices, cacheDirectory, progress);
        ComposedContinuation result = assemble(piece, task, plan, frames, voices, written, new Fresh(piece, ids));
        if (result.noteCount() == 0) {
            throw new IOException("Keine spielbaren Noten für die Fortsetzung; die Antworten bleiben im Cache zur "
                    + "Diagnose.");
        }
        progress.update("Fortsetzung: " + result.noteCount() + " Noten in " + result.sections().size()
                + " Abschnitt(en), " + result.newParts().size() + " neue Instrumente, " + result.corrections().size()
                + " Korrekturen.");
        return result;
    }

    /** What the generators wrote for one part: the shaped notes per frame, and the repairs. */
    private record Written(Map<String, List<ShapedNote>> notes, List<String> corrections) {
    }

    private Map<String, Written> write(Composition piece, ContinuationTask task, ContinuationPlan plan,
                                       List<VoiceFrame> frames, List<ContinuationVoice> voices, Path cache,
                                       Progress progress) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, Math.max(1, voices.size())), runnable -> {
            Thread thread = new Thread(runnable, "continuation");
            thread.setDaemon(true);
            return thread;
        });
        Map<String, Future<Written>> running = new LinkedHashMap<>();
        try {
            for (ContinuationVoice voice : voices) {
                running.put(voice.key(), pool.submit(() -> writePart(piece, task, plan, frames, voice, cache, progress)));
            }
            Map<String, Written> written = new HashMap<>();
            for (Map.Entry<String, Future<Written>> entry : running.entrySet()) {
                written.put(entry.getKey(), entry.getValue().get());
            }
            return written;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Verlängern abgebrochen.", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Verlängern fehlgeschlagen: " + cause.getMessage(), cause);
        } finally {
            pool.shutdownNow();
        }
    }

    private Written writePart(Composition piece, ContinuationTask task, ContinuationPlan plan, List<VoiceFrame> frames,
                              ContinuationVoice voice, Path cache, Progress progress) throws IOException {
        VoiceNormalizer normalizer = new VoiceNormalizer(task.time(), voice.range(), voice.drums());
        Map<String, List<ShapedNote>> notes = new LinkedHashMap<>();
        String connection = voice.newPart() ? "" : before(piece, voice.key(), frames.get(0).startTick());
        for (VoiceFrame frame : frames) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Verlängern abgebrochen.");
            }
            boolean transition = SectionGenerator.TRANSITION.equals(frame.sectionId());
            ContinuationPlan.PlannedSection section = transition ? null : section(plan, frame.sectionId());
            if (transition ? voice.newPart() : !section.plays(voice.key())) {
                connection = "";
                continue;
            }
            progress.update("Komponiere " + voice.name() + " für " + frame.name() + " ...");
            GeneratedNotes generated = generator.generate(task, plan, voice, frame, connection, cache);
            List<ShapedNote> shaped = normalizer.shape(frame, generated);
            if (shaped.isEmpty()) {
                progress.update("WARN: keine spielbaren Noten für " + voice.name() + " in " + frame.name() + ".");
            }
            notes.put(frame.sectionId(), shaped);
            connection = VoiceComposer.connection(task.time(), frame, shaped);
        }
        List<String> corrections = new ArrayList<>();
        normalizer.corrections().forEach(line -> corrections.add(voice.key() + "\t" + line));
        return new Written(notes, corrections);
    }

    private static ComposedContinuation assemble(Composition piece, ContinuationTask task, ContinuationPlan plan,
                                                 List<VoiceFrame> frames, List<ContinuationVoice> voices,
                                                 Map<String, Written> written, Fresh fresh) {
        List<String> corrections = new ArrayList<>();
        // The form: what is missing up to the continuation, then the planned sections.
        List<Section> sections = new ArrayList<>();
        long formEnd = piece.sections().isEmpty() ? 0 : piece.sections().get(piece.sections().size() - 1).endTick();
        if (formEnd < task.newStart()) {
            sections.add(new Section(fresh.next(), formEnd == 0 ? "Original" : "Ausklang", null, formEnd,
                    task.newStart() - formEnd, null, List.of(), null));
        }
        List<String> sectionIds = new ArrayList<>();
        plan.sections().forEach(s -> sectionIds.add(fresh.next()));
        sections.addAll(plan.asSections(task, sectionIds));

        List<Part> newParts = new ArrayList<>();
        List<Track> newTracks = new ArrayList<>();
        Map<String, Part> partOf = new HashMap<>();
        Map<String, Track> trackOf = new HashMap<>();
        String library = library(piece);
        for (ContinuationVoice voice : voices) {
            Written part = written.get(voice.key());
            boolean plays = part.notes().values().stream().anyMatch(list -> !list.isEmpty());
            if (voice.newPart()) {
                if (!plays) {
                    corrections.add(voice.key() + "\t-\tpart\tpresent\tskipped\tneues Instrument ohne spielbare Noten");
                    continue;
                }
                Part made = new Part(fresh.next(), voice.name(), voice.role(), voice.instrument(), voice.program(), library,
                        Part.StaffLayout.SINGLE, List.of(new Voice(fresh.next(), voice.name(), null, null)), null);
                Track track = new Track(fresh.next(), voice.name(), Track.Kind.MIDI, made.id(), List.of());
                newParts.add(made);
                newTracks.add(track);
                partOf.put(voice.key(), made);
                trackOf.put(voice.key(), track);
            } else {
                Part existing = piece.part(voice.key());
                partOf.put(voice.key(), existing);
                Track track = trackFor(piece, existing.id());
                if (track == null && plays) {
                    track = new Track(fresh.next(), existing.name(), Track.Kind.MIDI, existing.id(), List.of());
                    newTracks.add(track);
                }
                trackOf.put(voice.key(), track);
            }
        }

        List<ComposedContinuation.PlacedClip> clips = new ArrayList<>();
        List<ComposedContinuation.TailChange> tail = new ArrayList<>();
        for (ContinuationVoice voice : voices) {
            Written part = written.get(voice.key());
            corrections.addAll(part.corrections());
            Part player = partOf.get(voice.key());
            if (player == null) {
                continue;
            }
            String voiceId = player.firstVoice().id();
            for (VoiceFrame frame : frames) {
                List<ShapedNote> shaped = part.notes().get(frame.sectionId());
                if (SectionGenerator.TRANSITION.equals(frame.sectionId())) {
                    if (!voice.newPart()) {
                        tail.addAll(tail(piece, task, player, voiceId, shaped == null ? List.of() : shaped, fresh,
                                corrections));
                    }
                    continue;
                }
                if (shaped == null || shaped.isEmpty()) {
                    continue;
                }
                Track track = trackOf.get(voice.key());
                List<Note> notes = new ArrayList<>();
                for (ShapedNote n : shaped) {
                    notes.add(new Note(fresh.next(), n.tick(), n.length(), n.pitch(), n.velocity(), voiceId));
                }
                String clipPart = player.id().equals(track.defaultPartId()) ? null : player.id();
                clips.add(new ComposedContinuation.PlacedClip(track.id(),
                        new MidiClip(fresh.next(), frame.startTick(), frame.lengthTicks(), clipPart, notes)));
            }
        }
        return new ComposedContinuation(task, plan, sections, newParts, newTracks, clips, tail, corrections);
    }

    /**
     * The changes in the revised bars for an existing part: its notes that begin there leave their
     * clips, the new ones enter the clip of the part that holds their tick, cut at its end. A new
     * note no clip of the part holds is left out and recorded.
     */
    private static List<ComposedContinuation.TailChange> tail(Composition piece, ContinuationTask task, Part part,
                                                             String voiceId, List<ShapedNote> shaped, Fresh fresh,
                                                             List<String> corrections) {
        Map<String, List<String>> removed = new LinkedHashMap<>();
        Map<String, List<Note>> added = new LinkedHashMap<>();
        List<MidiClip> owned = new ArrayList<>();
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi && part.id().equals(track.partOf(midi))) {
                    owned.add(midi);
                    for (Note note : midi.notes()) {
                        long tick = midi.startTick() + note.tick();
                        if (tick >= task.tailStart() && tick < task.newStart()) {
                            removed.computeIfAbsent(midi.id(), id -> new ArrayList<>()).add(note.id());
                        }
                    }
                }
            }
        }
        for (ShapedNote n : shaped) {
            long tick = task.tailStart() + n.tick();
            MidiClip home = null;
            for (MidiClip clip : owned) {
                if (tick >= clip.startTick() && tick < clip.endTick()) {
                    home = clip;
                    break;
                }
            }
            if (home == null) {
                corrections.add(part.id() + "\t" + SectionGenerator.TRANSITION + "\t-\tnote\tpresent\tskipped\tkein Clip "
                        + "der Stimme bei Takt " + (task.time().barOf(tick) + 1));
                continue;
            }
            long length = Math.min(n.length(), home.endTick() - tick);
            if (length != n.length()) {
                corrections.add(part.id() + "\t" + SectionGenerator.TRANSITION + "\t-\tduration\t"
                        + ContextBuilder.quarters(n.length()) + "\t" + ContextBuilder.quarters(length)
                        + "\tam Clipende abgeschnitten");
            }
            added.computeIfAbsent(home.id(), id -> new ArrayList<>()).add(new Note(fresh.next(), tick - home.startTick(),
                    length, n.pitch(), n.velocity(), voiceId));
        }
        List<ComposedContinuation.TailChange> changes = new ArrayList<>();
        Set<String> clips = new java.util.LinkedHashSet<>(removed.keySet());
        clips.addAll(added.keySet());
        for (String clip : clips) {
            changes.add(new ComposedContinuation.TailChange(clip, removed.getOrDefault(clip, List.of()),
                    added.getOrDefault(clip, List.of())));
        }
        return changes;
    }

    /** The track the new clips of an existing part go on: its own, else the one of its last clip. */
    static Track trackFor(Composition piece, String partId) {
        for (Track track : piece.tracks()) {
            if (track.kind() == Track.Kind.MIDI && partId.equals(track.defaultPartId())) {
                return track;
            }
        }
        Track found = null;
        long latest = -1;
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi && partId.equals(track.partOf(midi)) && midi.startTick() > latest) {
                    found = track;
                    latest = midi.startTick();
                }
            }
        }
        return found;
    }

    /** The sound library most parts of the piece use: new instruments sound alike. */
    private static String library(Composition piece) {
        Map<String, Integer> count = new LinkedHashMap<>();
        piece.parts().forEach(part -> count.merge(part.libraryId(), 1, Integer::sum));
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** The notes a part played in the last bars before a tick, as connection. */
    private static String before(Composition piece, String partId, long tick) {
        TimeMap time = piece.timeMap();
        int bar = time.barOf(Math.max(0, tick - 1));
        long from = time.tickOfBar(Math.max(0, bar + 1 - CONNECTION_BARS));
        StringBuilder text = new StringBuilder();
        piece.notesOf(partId).stream().filter(n -> n.tick() >= from && n.tick() < tick)
                .sorted(Comparator.comparingLong(PlacedNote::tick).thenComparingInt(n -> n.note().pitch()))
                .forEach(n -> {
                    int at = time.barOf(n.tick());
                    text.append("  ").append(at + 1).append(' ').append(ContextBuilder.quarters(n.tick() - time.tickOfBar(at)))
                            .append(' ').append(ContextBuilder.quarters(n.note().length())).append(' ')
                            .append(ContextBuilder.pitch(n.note().pitch())).append('/').append(n.note().pitch())
                            .append(' ').append(n.note().velocity()).append('\n');
                });
        return text.toString();
    }

    private static ContinuationPlan.PlannedSection section(ContinuationPlan plan, String key) {
        for (ContinuationPlan.PlannedSection section : plan.sections()) {
            if (section.key().equals(key)) {
                return section;
            }
        }
        throw new IllegalStateException("No planned section " + key);
    }

    /** The chords of the original for some bars, as its sections say them; empty if any is unknown. */
    private static List<String> chordsOf(Composition piece, int firstBar, int bars) {
        TimeMap time = piece.timeMap();
        List<String> chords = new ArrayList<>();
        for (int bar = firstBar; bar < firstBar + bars; bar++) {
            long tick = time.tickOfBar(bar);
            String chord = null;
            for (Section section : piece.sections()) {
                if (tick >= section.startTick() && tick < section.endTick()) {
                    int index = bar - time.barOf(section.startTick());
                    chord = index < section.harmony().size() ? section.harmony().get(index) : null;
                }
            }
            if (chord == null) {
                return List.of();
            }
            chords.add(chord);
        }
        return chords;
    }

    /** Ids for new objects that the piece does not use yet, drawn in a fixed order. */
    private static final class Fresh {
        private final Supplier<String> source;
        private final Set<String> used = new HashSet<>();

        Fresh(Composition piece, Supplier<String> source) {
            this.source = source;
            piece.sections().forEach(s -> used.add(s.id()));
            for (Part part : piece.parts()) {
                used.add(part.id());
                part.voices().forEach(v -> used.add(v.id()));
            }
            for (Track track : piece.tracks()) {
                used.add(track.id());
                for (Clip clip : track.clips()) {
                    used.add(clip.id());
                    if (clip instanceof MidiClip midi) {
                        midi.notes().forEach(n -> used.add(n.id()));
                    }
                }
            }
            piece.assets().forEach(a -> used.add(a.id()));
        }

        String next() {
            String id;
            do {
                id = source.get();
            } while (!used.add(id));
            return id;
        }
    }
}
