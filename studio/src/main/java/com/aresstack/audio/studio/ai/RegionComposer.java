package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.ai.RewrittenRegion.NewNote;
import com.aresstack.audio.studio.ai.VoiceNormalizer.ShapedNote;
import com.aresstack.audio.studio.progress.Progress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Writes a marked passage anew ({@code REPLACE_REGION}, {@code VARIATE_REGION}; work package C2,
 * architecture review 2.4 and E4): one context of the piece with the region left out and the bars
 * before <em>and</em> after it given in full; for every selected part one generator call per
 * section of the region, in the order of the piece, each with the end of the previous one as
 * connection; then the same normalizer as for a new voice. It never touches the piece: the result
 * is a {@link RewrittenRegion} from which the application builds the patch.
 *
 * <p>The building blocks are those of {@link VoiceComposer} ({@link ContextBuilder},
 * {@link GeneratedNotes}, {@link VoiceNormalizer}, {@link InstrumentRange}, {@link VoiceFrame});
 * only the task differs.
 */
public final class RegionComposer {
    /** Bars before and after the region whose notes of all parts the context gives in full. */
    public static final int AROUND_BARS = VoiceComposer.AROUND_BARS;

    private final RegionGenerator generator;

    public RegionComposer(RegionGenerator generator) {
        this.generator = generator;
    }

    /** A composer whose notes come from the rework role (follows composition). */
    public static RegionComposer with(ModelLineup models) {
        return new RegionComposer(new ChatRegionGenerator(models.model(ModelRoles.REWORK), AnswerRoom.notes()));
    }

    /** A composer that asks a model for the notes. */
    public static RegionComposer with(ChatModel model) {
        return new RegionComposer(new ChatRegionGenerator(model, AnswerRoom.notes()));
    }

    /** The context of a request: the piece around the region, the selected notes left out. Pure. */
    public static MusicalContext context(Composition piece, RegionRequest request) {
        Selection selection = request.selection();
        List<String> parts = selection.parts(piece);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Die Auswahl trifft keinen Part.");
        }
        return ContextBuilder.build(piece, new ContextBuilder.Region(selection.fromTick(), selection.toTick(),
                new HashSet<>(parts), selection::selects), AROUND_BARS);
    }

    /**
     * The parts a mark for all parts of a passage holds for: all parts of the piece when the
     * passage has them all (then such a mark stays one for all), else only its own.
     */
    private static List<String> playing(Composition piece, List<String> passage) {
        return passage.size() >= piece.parts().size() ? List.of() : passage;
    }

    /**
     * Writes the passage anew.
     *
     * @param attempt        1 for the first, more to write it again differently
     * @param cacheDirectory where the answers of the model are kept
     * @throws ForeignPartException if an answer was for another part or section
     * @throws IOException          if the model failed
     */
    public RewrittenRegion compose(Composition piece, RegionRequest request, int attempt, Path cacheDirectory,
                                   Progress progress) throws IOException {
        MusicalContext context = context(piece, request);
        Files.createDirectories(cacheDirectory);
        Map<String, List<NewNote>> passages = new LinkedHashMap<>();
        List<String> corrections = new ArrayList<>();
        final EditSketch sketch = EditSketch.of(piece, progress);
        for (String partId : sketch == null ? List.<String>of() : request.selection().parts(piece)) {
            for (VoiceFrame frame : RegionTask.of(piece, request, context, partId, attempt).frames()) {
                sketch.expect(partId, frame);
            }
        }
        ContinuationPerformance performance = ContinuationPerformance.NONE;
        for (String partId : request.selection().parts(piece)) {
            RegionTask task = RegionTask.of(piece, request, context, partId, attempt);
            VoiceNormalizer normalizer = new VoiceNormalizer(piece.timeMap(), task.range(), task.part().drums());
            List<NewNote> notes = new ArrayList<>();
            String connection = task.before();
            List<VoiceFrame> frames = task.frames();
            for (int i = 0; i < frames.size(); i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Neuschreiben der Passage abgebrochen.");
                }
                VoiceFrame frame = frames.get(i);
                progress.update((request.mode() == RegionRequest.Mode.VARIATE ? "Variiere " : "Schreibe neu: ")
                        + task.part().name() + ", " + frame.name() + " ...");
                GeneratedNotes generated;
                try (Listening.Scope watching = Listening.to(sketch == null ? null
                        : sketch.hearing(partId, task.part().program(), task.part().role(), frame))) {
                    generated = generator.generate(task, frame, i == frames.size() - 1, connection,
                            cacheDirectory);
                }
                if (sketch != null) {
                    sketch.written(partId, task.part().program(), task.part().role(), frame, generated);
                }
                if (task.plansPerformance(frame)) {
                    List<String> said = new ArrayList<>();
                    performance = ContinuationPerformance.read(generated.answer(), piece.timeMap(),
                            request.selection().fromTick(), request.selection().toTick(),
                            new HashSet<>(task.performs()), false, said)
                            .forParts(playing(piece, task.performs()));
                    said.forEach(line -> corrections.add(partId + "\t" + line));
                }
                List<ShapedNote> shaped = normalizer.shape(frame, generated);
                for (ShapedNote n : shaped) {
                    notes.add(new NewNote(frame.startTick() + n.tick(), n.length(), n.pitch(), n.velocity(), n.articulation()));
                }
                connection = VoiceComposer.connection(piece.timeMap(), frame, shaped);
            }
            for (String line : normalizer.corrections()) {
                corrections.add(partId + "\t" + line);
            }
            if (notes.isEmpty()) {
                progress.update(task.part().name() + " pausiert in der Auswahl.");
            }
            passages.put(partId, notes);
        }
        RewrittenRegion region = new RewrittenRegion(request, passages, context, corrections, attempt, performance);
        progress.update("Passage neu geschrieben: " + region.noteCount() + " Noten in " + passages.size()
                + " Part(s), " + corrections.size() + " Korrekturen.");
        return region;
    }
}
