package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.studio.ai.VoiceNormalizer.ShapedNote;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.aresstack.audio.studio.score.Voicing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Composes a new voice into an existing piece ({@code ADD_TRACK}, architecture review 2.4 and E4;
 * report 6): context of all other parts, optional planning step, one generator call per frame in
 * the order of the piece with the end of the previous frame as connection, normalizer. It never
 * touches the piece: the result is a {@link ComposedVoice} from which the application builds the
 * patch.
 */
public final class VoiceComposer {
    /** Bars before and after the frames whose notes the context gives in full. */
    public static final int AROUND_BARS = 2;
    /** Bars at the end of a frame passed on to the next one as connection. */
    public static final int CONNECTION_BARS = 2;

    private final VoicePlanner planner;
    private final PartGenerator generator;

    public VoiceComposer(VoicePlanner planner, PartGenerator generator) {
        this.planner = planner;
        this.generator = generator;
    }

    /**
     * A composer whose plan comes from the arranging role (follows planning) and whose notes come
     * from the rework role (follows composition).
     */
    public static VoiceComposer with(ModelLineup models) {
        return new VoiceComposer(new ChatVoicePlanner(models.model(ModelRoles.ARRANGING), AnswerRoom.plan()),
                new ChatPartGenerator(models.model(ModelRoles.REWORK), AnswerRoom.notes()));
    }

    /** A composer that asks a model for plan and notes. */
    public static VoiceComposer with(ChatModel model) {
        return new VoiceComposer(new ChatVoicePlanner(model, AnswerRoom.plan()), new ChatPartGenerator(model, AnswerRoom.notes()));
    }

    /** The task of a request on a piece: frames, range, context. Pure; the same piece gives the same task. */
    public static VoiceTask task(Composition piece, AddVoiceRequest request, String partId, int attempt) {
        if (request.motifPartId() != null && piece.part(request.motifPartId()) == null) {
            throw new IllegalArgumentException("Unbekannte Stimme für das Motiv: " + request.motifPartId());
        }
        List<VoiceFrame> frames = VoiceFrame.of(piece, request.sectionIds());
        InstrumentRange range = InstrumentRange.of(request.program(), Role.DRUMS.equals(request.role()));
        MusicalContext context = ContextBuilder.build(piece, frames.get(0).startTick(),
                frames.get(frames.size() - 1).endTick(), AROUND_BARS, request.motifPartId());
        return new VoiceTask(context, request, partId, piece.timeMap(), frames, range, attempt);
    }

    /**
     * Composes the new voice.
     *
     * @param attempt        1 for the first, more to generate it again differently
     * @param cacheDirectory where the answers of the model are kept
     * @throws ForeignPartException if an answer was for another part or section
     * @throws IOException          if the model failed or no playable note came back
     */
    public ComposedVoice compose(Composition piece, AddVoiceRequest request, NewVoiceIds ids, int attempt,
                                 Path cacheDirectory, Progress progress) throws IOException {
        VoiceTask task = task(piece, request, ids.partId(), attempt);
        Files.createDirectories(cacheDirectory);
        VoicePlan plan;
        if (request.plan()) {
            progress.update("Plane die neue Stimme " + request.name() + " ...");
            try (Listening.Scope planning = EditSketch.preparing(progress, "Die KI plant die neue Stimme")) {
                plan = planner.plan(task, cacheDirectory);
            }
        } else {
            plan = VoicePlan.everywhere(task.frames(), task.range(), request.instruction());
        }
        boolean drums = Role.DRUMS.equals(request.role());
        VoiceNormalizer normalizer = new VoiceNormalizer(piece.timeMap(), task.range(), drums);
        List<VoiceFrame> played = new ArrayList<>();
        List<List<ShapedNote>> playedNotes = new ArrayList<>();
        String connection = "";
        final EditSketch sketch = EditSketch.of(piece, progress);
        for (VoiceFrame frame : sketch == null ? List.<VoiceFrame>of() : task.frames()) {
            sketch.expect(ids.partId(), frame);
        }
        for (VoiceFrame frame : task.frames()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Hinzukomponieren abgebrochen.");
            }
            VoicePlan.SectionPlan here = plan.section(frame.sectionId());
            if (here != null && !here.plays()) {
                progress.update(request.name() + " pausiert in " + frame.name() + ".");
                connection = "";
                if (sketch != null) {
                    sketch.rests(ids.partId(), frame);
                }
                continue;
            }
            progress.update("Komponiere " + request.name() + " für " + frame.name() + " ...");
            GeneratedNotes generated;
            try (Listening.Scope watching = Listening.to(sketch == null ? null
                    : sketch.hearing(ids.partId(), request.program(), request.role(), frame))) {
                generated = generator.generate(task, plan, frame, connection, cacheDirectory);
            }
            if (sketch != null) {
                sketch.written(ids.partId(), request.program(), request.role(), frame, generated);
            }
            List<ShapedNote> shaped = normalizer.shape(frame, generated);
            if (shaped.isEmpty()) {
                progress.update("WARN: keine spielbaren Noten für " + frame.name() + ".");
                connection = "";
                continue;
            }
            played.add(frame);
            playedNotes.add(shaped);
            connection = connection(piece.timeMap(), frame, shaped);
        }
        if (played.isEmpty()) {
            throw new IOException("Keine spielbaren Noten für die neue Stimme " + request.name()
                    + "; die Antworten bleiben im Cache zur Diagnose.");
        }
        // The voices (work package S1): as the answers name them, else as the score guesses them.
        List<long[]> written = new ArrayList<>();
        List<String> named = new ArrayList<>();
        for (int f = 0; f < played.size(); f++) {
            for (ShapedNote n : playedNotes.get(f)) {
                written.add(new long[]{played.get(f).startTick() + n.tick(), n.length(), n.pitch()});
                named.add(n.voice());
            }
        }
        boolean grand = task.grand();
        String[] tokenOf = Voicing.assign(written, drums ? null : named.toArray(new String[0]), drums, grand);
        List<String> tokens = new ArrayList<>();
        for (String token : tokenOf) {
            if (!tokens.contains(token)) {
                tokens.add(token);
            }
        }
        tokens.sort(Voicing.ORDER);
        List<Voice> voices = new ArrayList<>();
        Map<String, String> voiceIds = new HashMap<>();
        for (String token : tokens) {
            List<String> sameStaff = tokens.stream().filter(t -> Voicing.staffOf(t) == Voicing.staffOf(token)).toList();
            String id = voices.isEmpty() ? ids.voiceId() : ids.more().get();
            voiceIds.put(token, id);
            voices.add(new Voice(id, tokens.size() == 1 && !grand ? request.name() : Voicing.name(token),
                    Voicing.staffOf(token), tokens.size() == 1 ? null : Voicing.stem(token, sameStaff)));
        }
        List<MidiClip> clips = new ArrayList<>();
        int index = 0;
        for (int f = 0; f < played.size(); f++) {
            List<Note> notes = new ArrayList<>();
            for (ShapedNote n : playedNotes.get(f)) {
                notes.add(new Note(ids.more().get(), n.tick(), n.length(), n.pitch(), n.velocity(),
                        voiceIds.get(tokenOf[index++]), n.articulation()));
            }
            VoiceFrame frame = played.get(f);
            clips.add(new MidiClip(ids.more().get(), frame.startTick(), frame.lengthTicks(), null, notes));
        }
        Part part = new Part(ids.partId(), request.name(), request.role(), request.instrument(), request.program(),
                request.libraryId(), grand ? Part.StaffLayout.GRAND : Part.StaffLayout.SINGLE, voices, null);
        Track track = new Track(ids.trackId(), request.name(), Track.Kind.MIDI, ids.partId(), List.of());
        ComposedVoice voice = new ComposedVoice(part, track, clips, task.frames(), plan, task.context(),
                normalizer.corrections(), attempt);
        progress.update("Neue Stimme " + request.name() + ": " + voice.noteCount() + " Noten in " + clips.size()
                + " Abschnitt(en), " + voice.corrections().size() + " Korrekturen.");
        return voice;
    }

    /** The notes of the last bars of a frame, one line each, in bars of the piece. */
    static String connection(TimeMap time, VoiceFrame frame, List<ShapedNote> notes) {
        int firstBar = Math.max(frame.firstBar(), frame.firstBar() + frame.bars() - CONNECTION_BARS);
        long from = time.tickOfBar(firstBar);
        StringBuilder text = new StringBuilder();
        notes.stream().filter(n -> frame.startTick() + n.tick() >= from)
                .sorted(java.util.Comparator.comparingLong(ShapedNote::tick).thenComparingInt(ShapedNote::pitch))
                .forEach(n -> {
                    long tick = frame.startTick() + n.tick();
                    int bar = time.barOf(tick);
                    text.append("  ").append(bar + 1).append(' ').append(ContextBuilder.quarters(tick - time.tickOfBar(bar)))
                            .append(' ').append(ContextBuilder.quarters(n.length())).append(' ')
                            .append(ContextBuilder.pitch(n.pitch())).append('/').append(n.pitch()).append(' ')
                            .append(n.velocity()).append('\n');
                });
        return text.toString();
    }
}
