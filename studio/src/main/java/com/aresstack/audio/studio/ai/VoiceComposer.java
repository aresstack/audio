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
import java.util.ArrayList;
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
        return new VoiceComposer(new ChatVoicePlanner(models.model(ModelRoles.ARRANGING), 2048),
                new ChatPartGenerator(models.model(ModelRoles.REWORK), 16384));
    }

    /** A composer that asks a model for plan and notes. */
    public static VoiceComposer with(ChatModel model) {
        return new VoiceComposer(new ChatVoicePlanner(model, 2048), new ChatPartGenerator(model, 16384));
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
            plan = planner.plan(task, cacheDirectory);
        } else {
            plan = VoicePlan.everywhere(task.frames(), task.range(), request.instruction());
        }
        boolean drums = Role.DRUMS.equals(request.role());
        VoiceNormalizer normalizer = new VoiceNormalizer(piece.timeMap(), task.range(), drums);
        List<MidiClip> clips = new ArrayList<>();
        String connection = "";
        for (VoiceFrame frame : task.frames()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Hinzukomponieren abgebrochen.");
            }
            VoicePlan.SectionPlan here = plan.section(frame.sectionId());
            if (here != null && !here.plays()) {
                progress.update(request.name() + " pausiert in " + frame.name() + ".");
                connection = "";
                continue;
            }
            progress.update("Komponiere " + request.name() + " für " + frame.name() + " ...");
            GeneratedNotes generated = generator.generate(task, plan, frame, connection, cacheDirectory);
            List<ShapedNote> shaped = normalizer.shape(frame, generated);
            if (shaped.isEmpty()) {
                progress.update("WARN: keine spielbaren Noten für " + frame.name() + ".");
                connection = "";
                continue;
            }
            List<Note> notes = new ArrayList<>();
            for (ShapedNote n : shaped) {
                notes.add(new Note(ids.more().get(), n.tick(), n.length(), n.pitch(), n.velocity(), ids.voiceId()));
            }
            clips.add(new MidiClip(ids.more().get(), frame.startTick(), frame.lengthTicks(), null, notes));
            connection = connection(piece.timeMap(), frame, shaped);
        }
        if (clips.isEmpty()) {
            throw new IOException("Keine spielbaren Noten für die neue Stimme " + request.name()
                    + "; die Antworten bleiben im Cache zur Diagnose.");
        }
        Part part = new Part(ids.partId(), request.name(), request.role(), request.instrument(), request.program(),
                request.libraryId(), Part.StaffLayout.SINGLE, List.of(new Voice(ids.voiceId(), request.name(), null, null)),
                null);
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
