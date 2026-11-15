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

    /**
     * A composer whose plan comes from the arranging role (follows planning) and whose notes come
     * from the rework role (follows composition), four parts at a time.
     */
    public static ContinuationComposer with(ModelLineup models) {
        return new ContinuationComposer(new ChatContinuationPlanner(models.model(ModelRoles.ARRANGING), 4096),
                new ChatSectionGenerator(models.model(ModelRoles.REWORK), 16384), 4);
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
        progress.update((task.inserts() ? "Plane " + request.bars() + " eingefügte Takte vor Takt "
                + request.insertBeforeBar() + " (" : "Plane die Fortsetzung (" + request.bars() + " Takte, ")
                + request.preset().label() + ") ...");
        // For an insertion everything is written into the piece with the room already made: the
        // music after the room is where it will be once the patch has shifted it.
        Composition room = task.room(piece);
        ContinuationPlan plan;
        try (Listening.Scope planning = EditSketch.preparing(progress, task.inserts()
                ? "Die KI plant die eingefügten Takte" : "Die KI plant die Fortsetzung")) {
            plan = planner.plan(task, room, cacheDirectory);
        }
        if (plan.bars() != request.bars()) {
            throw new IOException("Der Plan deckt " + plan.bars() + " statt " + request.bars() + " Takte ab.");
        }
        for (String repair : plan.repairs()) {
            progress.update("Plan: " + repair);
        }
        List<VoiceFrame> frames = frames(task, plan, room);
        List<ContinuationVoice> voices = new ArrayList<>();
        task.parts().forEach(part -> voices.add(ContinuationVoice.of(part)));
        plan.newParts().forEach(part -> voices.add(ContinuationVoice.of(part)));

        Map<String, Written> written = write(room, piece, task, plan, frames, voices, cacheDirectory, progress,
                sketchUntil(EditSketch.of(room, progress), frames));
        ComposedContinuation result = assemble(room, task, plan, frames, voices, written, new Fresh(room, ids),
                task.inserts() ? holds(piece, task) : List.of());
        if (result.clips().stream().allMatch(c -> c.clip().notes().isEmpty())) {
            throw new IOException("Keine spielbaren Noten für die Fortsetzung; die Antworten bleiben im Cache zur "
                    + "Diagnose.");
        }
        progress.update((task.inserts() ? "Einfügung: " : "Fortsetzung: ") + result.noteCount() + " Noten in "
                + result.sections().size() + " Abschnitt(en), " + result.newParts().size() + " neue Instrumente, " + result.corrections().size()
                + " Korrekturen.");
        return result;
    }

    private static EditSketch sketchUntil(EditSketch sketch, List<VoiceFrame> frames) {
        return sketch == null ? null : sketch.until(frames.get(frames.size() - 1).endTick());
    }

    /** What the generators wrote for one part: the shaped notes per frame, and the repairs. */
    private record Written(Map<String, List<ShapedNote>> notes, List<String> corrections) {
    }

    private Map<String, Written> write(Composition piece, Composition original, ContinuationTask task,
                                       ContinuationPlan plan,
                                       List<VoiceFrame> frames, List<ContinuationVoice> voices, Path cache,
                                       Progress progress, EditSketch sketch) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, Math.max(1, voices.size())), runnable -> {
            Thread thread = new Thread(runnable, "continuation");
            thread.setDaemon(true);
            return thread;
        });
        Map<String, Future<Written>> running = new LinkedHashMap<>();
        try {
            for (ContinuationVoice voice : voices) {
                running.put(voice.key(), pool.submit(() -> writePart(piece, original, task, plan, frames, voice, cache,
                        progress, sketch)));
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

    private Written writePart(Composition piece, Composition original, ContinuationTask task, ContinuationPlan plan,
                              List<VoiceFrame> frames,
                              ContinuationVoice voice, Path cache, Progress progress, EditSketch sketch)
            throws IOException {
        VoiceNormalizer normalizer = new VoiceNormalizer(task.time(), voice.range(), voice.drums());
        List<String> densities = new ArrayList<>();
        Map<String, List<ShapedNote>> notes = new LinkedHashMap<>();
        // From the original: a note that sounds across an insertion point is told with its full length.
        String connection = voice.newPart() ? "" : before(original, voice.key(), frames.get(0).startTick());
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
            GeneratedNotes generated;
            try (Listening.Scope watching = Listening.to(sketch == null ? null
                    : sketch.hearing(voice.key(), voice.program(), voice.role(), frame))) {
                generated = generator.generate(task, plan, voice, frame, connection, cache);
            }
            if (sketch != null) {
                sketch.written(voice.key(), voice.program(), voice.role(), frame, generated);
            }
            List<ShapedNote> shaped = normalizer.shape(frame, generated, transition ? ringOut(task) : 0);
            if (frame.intensity() != null) {
                List<String> thinned = new ArrayList<>();
                shaped = Dramaturgy.thin(frame, task.time(), frame.intensity(), voice.role(), shaped, thinned);
                thinned.forEach(line -> densities.add(voice.key() + "\t" + line));
            }
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

    /**
     * How far a note of the revised transition may sound into the new bars: one bar. The clip of
     * the original that holds it grows for it (a {@code ResizeClip} in the patch).
     */
    static long ringOut(ContinuationTask task) {
        return task.time().meterAt(task.newStart()).barTicks();
    }

    private static ComposedContinuation assemble(Composition piece, ContinuationTask task, ContinuationPlan plan,
                                                 List<VoiceFrame> frames, List<ContinuationVoice> voices,
                                                 Map<String, Written> written, Fresh fresh,
                                                 List<ComposedContinuation.Hold> holds) {
        List<String> corrections = new ArrayList<>();
        // The form: for a continuation what is missing up to it, then the planned sections; for an
        // insertion the form of the piece with the room, the planned sections in the room.
        List<Section> sections = new ArrayList<>();
        List<Section> form;
        if (task.inserts()) {
            sections.addAll(plan.asSections(task, ids(plan, fresh)));
            form = insertedForm(piece, task, sections, fresh);
        } else {
            long formEnd = piece.sections().isEmpty() ? 0 : piece.sections().get(piece.sections().size() - 1).endTick();
            if (formEnd < task.newStart()) {
                sections.add(new Section(fresh.next(), formEnd == 0 ? "Original" : "Ausklang", null, formEnd,
                        task.newStart() - formEnd, null, List.of(), null));
            }
            sections.addAll(plan.asSections(task, ids(plan, fresh)));
            form = new ArrayList<>(piece.sections());
            form.addAll(sections);
        }

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
        hold(piece, task, holds, tail, corrections);
        joinOverhangs(piece, clips, tail, corrections);
        return new ComposedContinuation(task, plan, sections, form, newParts, newTracks, clips, tail, corrections,
                holds);
    }

    private static List<String> ids(ContinuationPlan plan, Fresh fresh) {
        List<String> ids = new ArrayList<>();
        plan.sections().forEach(s -> ids.add(fresh.next()));
        return ids;
    }

    /**
     * The form after an insertion, on the piece with the room ({@code room}): a section the shift
     * stretched over the room is cut in two at its edges, the second half under a new id with the
     * rest of its chords; the planned sections fill the room; gaps the old form left before the
     * room or behind it (where the original goes on) are filled with a section "Original".
     */
    static List<Section> insertedForm(Composition room, ContinuationTask task, List<Section> planned,
                                      Supplier<String> ids) {
        TimeMap time = task.time();
        long start = task.newStart();
        long end = task.newEnd();
        List<Section> before = new ArrayList<>();
        List<Section> after = new ArrayList<>();
        for (Section s : room.sections()) {
            if (s.endTick() <= start) {
                before.add(s);
            } else if (s.startTick() >= end) {
                after.add(s);
            } else {
                int headBars = time.barOf(start) - time.barOf(s.startTick());
                List<String> headChords = s.harmony().subList(0, Math.min(headBars, s.harmony().size()));
                List<String> tailChords = s.harmony().subList(Math.min(headBars, s.harmony().size()), s.harmony().size());
                if (start > s.startTick()) {
                    before.add(new Section(s.id(), s.name(), s.function(), s.startTick(), start - s.startTick(),
                            s.intensity(), headChords, s.instruction()));
                }
                if (s.endTick() > end) {
                    after.add(new Section(start > s.startTick() ? ids.get() : s.id(), s.name(), s.function(), end,
                            s.endTick() - end, s.intensity(), tailChords, s.instruction()));
                }
            }
        }
        List<Section> form = new ArrayList<>(before);
        long formEnd = before.isEmpty() ? 0 : before.get(before.size() - 1).endTick();
        if (formEnd < start) {
            form.add(new Section(ids.get(), "Original", null, formEnd, start - formEnd, null, List.of(), null));
        }
        form.addAll(planned);
        long goesOn = after.isEmpty() ? time.tickOfBar(Math.max(time.barOf(end), time.barsCovering(room.endTick())))
                : after.get(0).startTick();
        if (goesOn > end) {
            form.add(new Section(ids.get(), "Original", null, end, goesOn - end, null, List.of(), null));
        }
        form.addAll(after);
        return form;
    }

    /**
     * The changes in the revised bars for an existing part: its notes that begin there leave their
     * clips, the new ones enter the clip of the part that holds their tick. A note that sounds past
     * the end of that clip lets the clip grow (up to the next clip on its track, where it is cut);
     * {@link #joinOverhangs} then makes room for it among the new clips. A new note no clip of the
     * part holds is left out and recorded.
     */
    private static List<ComposedContinuation.TailChange> tail(Composition piece, ContinuationTask task, Part part,
                                                             String voiceId, List<ShapedNote> shaped, Fresh fresh,
                                                             List<String> corrections) {
        Map<String, List<String>> removed = new LinkedHashMap<>();
        Map<String, List<Note>> added = new LinkedHashMap<>();
        Map<String, Long> ends = new HashMap<>();
        List<MidiClip> owned = new ArrayList<>();
        Map<String, Long> limits = new HashMap<>();
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi && part.id().equals(track.partOf(midi))) {
                    owned.add(midi);
                    limits.put(midi.id(), nextStart(track, midi));
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
            long length = Math.min(n.length(), limits.get(home.id()) - tick);
            if (length != n.length()) {
                corrections.add(part.id() + "\t" + SectionGenerator.TRANSITION + "\t-\tduration\t"
                        + ContextBuilder.quarters(n.length()) + "\t" + ContextBuilder.quarters(length)
                        + "\tvor dem nächsten Clip der Spur abgeschnitten");
            }
            if (tick + length > home.endTick()) {
                ends.merge(home.id(), tick + length, Math::max);
            }
            added.computeIfAbsent(home.id(), id -> new ArrayList<>()).add(new Note(fresh.next(), tick - home.startTick(),
                    length, n.pitch(), n.velocity(), voiceId));
        }
        List<ComposedContinuation.TailChange> changes = new ArrayList<>();
        Set<String> clips = new java.util.LinkedHashSet<>(removed.keySet());
        clips.addAll(added.keySet());
        for (String clip : clips) {
            long start = owned.stream().filter(c -> c.id().equals(clip)).findFirst().orElseThrow().startTick();
            Long end = ends.get(clip);
            changes.add(new ComposedContinuation.TailChange(clip, removed.getOrDefault(clip, List.of()),
                    added.getOrDefault(clip, List.of()), end == null ? 0 : end - start));
        }
        return changes;
    }

    /**
     * The notes of the original that sound across the insertion point: in a MIDI clip across the
     * point, beginning before it (and before the revised bars, whose notes are written anew) and
     * ending after it. The shift cuts them at the point; {@link #hold} lets them sound on.
     */
    static List<ComposedContinuation.Hold> holds(Composition original, ContinuationTask task) {
        long point = task.newStart();
        List<ComposedContinuation.Hold> holds = new ArrayList<>();
        for (Track track : original.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi && midi.startTick() < point && point < midi.endTick()) {
                    for (Note note : midi.notes()) {
                        long tick = midi.startTick() + note.tick();
                        if (tick < task.tailStart() && tick + note.length() > point) {
                            holds.add(new ComposedContinuation.Hold(track.partOf(midi), midi.id(), note.id(), tick,
                                    note.length()));
                        }
                    }
                }
            }
        }
        return holds;
    }

    /**
     * Lets the held notes of an insertion sound on: in the piece with the room ({@code room}) the
     * first half of their clip ends at the insertion point and the note there; the note gets its
     * length back, at most to the end of the room, and the clip grows with it. A note that would
     * sound beyond the end of the room ends there, because the moved original begins there; that
     * goes into the corrections and {@link ComposedContinuation#shortened()}. This becomes a
     * {@link ComposedContinuation.TailChange} of the clip (merged with one of the revised
     * transition), so that {@link #joinOverhangs} joins the new clips of the part into it.
     */
    static void hold(Composition room, ContinuationTask task, List<ComposedContinuation.Hold> holds,
                     List<ComposedContinuation.TailChange> tail, List<String> corrections) {
        Map<String, List<ComposedContinuation.Hold>> byClip = new LinkedHashMap<>();
        for (ComposedContinuation.Hold hold : holds) {
            byClip.computeIfAbsent(hold.clipId(), id -> new ArrayList<>()).add(hold);
        }
        Map<String, MidiClip> halves = new HashMap<>();
        for (Track track : room.tracks()) {
            for (Clip clip : track.clips()) {
                if (byClip.containsKey(clip.id()) && clip instanceof MidiClip midi) {
                    halves.put(midi.id(), midi);
                }
            }
        }
        for (Map.Entry<String, List<ComposedContinuation.Hold>> entry : byClip.entrySet()) {
            MidiClip half = halves.get(entry.getKey());
            List<String> removed = new ArrayList<>();
            List<Note> added = new ArrayList<>();
            long end = half.endTick();
            for (ComposedContinuation.Hold hold : entry.getValue()) {
                Note cut = half.notes().stream().filter(n -> n.id().equals(hold.noteId())).findFirst().orElseThrow();
                long length = Math.min(hold.length(), task.newEnd() - hold.tick());
                if (length < hold.length()) {
                    corrections.add(hold.partId() + "\t" + SectionGenerator.TRANSITION + "\t" + hold.noteId()
                            + "\tduration\t" + ContextBuilder.quarters(hold.length()) + "\t"
                            + ContextBuilder.quarters(length) + "\tklingt über das Ende der eingefügten Takte hinaus;"
                            + " endet dort, wo das verschobene Original beginnt (Originaldauer nicht erhaltbar)");
                }
                removed.add(cut.id());
                added.add(new Note(cut.id(), cut.tick(), length, cut.pitch(), cut.velocity(), cut.voiceId(), cut.articulation()));
                end = Math.max(end, hold.tick() + length);
            }
            long length = end - half.startTick();
            int at = -1;
            for (int i = 0; i < tail.size(); i++) {
                if (tail.get(i).clipId().equals(half.id())) {
                    at = i;
                }
            }
            if (at < 0) {
                tail.add(new ComposedContinuation.TailChange(half.id(), removed, added, length));
            } else {
                ComposedContinuation.TailChange change = tail.get(at);
                List<String> allRemoved = new ArrayList<>(change.removed());
                allRemoved.addAll(removed);
                List<Note> allAdded = new ArrayList<>(change.added());
                allAdded.addAll(added);
                long base = change.lengthTicks() == 0 ? half.lengthTicks() : change.lengthTicks();
                tail.set(at, new ComposedContinuation.TailChange(half.id(), allRemoved, allAdded,
                        Math.max(base, length)));
            }
        }
    }

    /** Where the next clip after this one on its track begins; {@code Long.MAX_VALUE} if none follows. */
    private static long nextStart(Track track, Clip clip) {
        long next = Long.MAX_VALUE;
        for (Clip other : track.clips()) {
            if (other != clip && other.startTick() > clip.startTick() && other.startTick() < next) {
                next = other.startTick();
            }
        }
        return next;
    }

    /**
     * Makes room for clips of the original that grow in the transition: a new clip on the same
     * track that would overlap such a clip, and belongs to the same part, joins it (its notes move
     * over, the grown clip reaches to its end). A new clip of another part stops the growth at its
     * start, and the transition notes are cut there.
     */
    static void joinOverhangs(Composition piece, List<ComposedContinuation.PlacedClip> clips,
                              List<ComposedContinuation.TailChange> tail, List<String> corrections) {
        for (int i = 0; i < tail.size(); i++) {
            ComposedContinuation.TailChange change = tail.get(i);
            if (change.lengthTicks() == 0) {
                continue;
            }
            Track track = null;
            MidiClip home = null;
            for (Track t : piece.tracks()) {
                for (Clip c : t.clips()) {
                    if (c.id().equals(change.clipId()) && c instanceof MidiClip midi) {
                        track = t;
                        home = midi;
                    }
                }
            }
            String part = track.partOf(home);
            long end = home.startTick() + change.lengthTicks();
            List<Note> added = new ArrayList<>(change.added());
            List<ComposedContinuation.PlacedClip> ordered = new ArrayList<>(clips);
            ordered.sort(Comparator.comparingLong(p -> p.clip().startTick()));
            for (ComposedContinuation.PlacedClip placed : ordered) {
                MidiClip clip = placed.clip();
                if (!placed.trackId().equals(track.id()) || clip.startTick() >= end) {
                    continue;
                }
                String owner = clip.partId() == null ? track.defaultPartId() : clip.partId();
                if (part.equals(owner)) {
                    long offset = clip.startTick() - home.startTick();
                    for (Note note : clip.notes()) {
                        added.add(new Note(note.id(), note.tick() + offset, note.length(), note.pitch(), note.velocity(),
                                note.voiceId(), note.articulation()));
                    }
                    end = Math.max(end, clip.endTick());
                    clips.remove(placed);
                } else {
                    end = clip.startTick();
                    List<Note> cut = new ArrayList<>();
                    for (Note note : added) {
                        long stop = end - home.startTick();
                        if (note.tick() + note.length() > stop) {
                            corrections.add(part + "\t" + SectionGenerator.TRANSITION + "\t-\tduration\t"
                                    + ContextBuilder.quarters(note.length()) + "\t"
                                    + ContextBuilder.quarters(stop - note.tick()) + "\tvor dem Clip von " + owner
                                    + " abgeschnitten");
                            note = new Note(note.id(), note.tick(), stop - note.tick(), note.pitch(), note.velocity(),
                                    note.voiceId(), note.articulation());
                        }
                        cut.add(note);
                    }
                    added = cut;
                    break;
                }
            }
            long length = end - home.startTick();
            tail.set(i, new ComposedContinuation.TailChange(change.clipId(), change.removed(), added,
                    length > home.lengthTicks() ? length : 0));
        }
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
    static String library(Composition piece) {
        Map<String, Integer> count = new LinkedHashMap<>();
        piece.parts().forEach(part -> count.merge(part.libraryId(), 1, Integer::sum));
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** The notes a part played in the last bars before a tick, as connection. */
    static String before(Composition piece, String partId, long tick) {
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
    static final class Fresh implements Supplier<String> {
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

        @Override
        public String get() {
            return next();
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
