package com.aresstack.audio.studio.render;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.music.synth.BuiltinSynthesis;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The plain Java sound, in this process, straight from the model. The stem is as long as the
 * piece plus the tail, whatever the length of the piece; it is the same, sample for sample,
 * as the stem the reference renderer of the music module writes for the same part.
 *
 * <p>The role of the part decides wave, envelope, ring-out and level here (E7.3), so it is part
 * of the identity; so is the length of the piece, because the stem runs to its end.
 */
public final class BuiltinStemRenderer implements StemRenderer {
    @Override
    public String engine() {
        return "builtin";
    }

    @Override
    public String identity(StemJob job) {
        return BuiltinSynthesis.VERSION + "\t" + BuiltinSynthesis.SAMPLE_RATE + " Hz mono\trole " + role(job.part())
                + "\tframes " + BuiltinSynthesis.frames(job.pieceSeconds());
    }

    @Override
    public void render(StemJob job, Path output) throws Exception {
        double seconds = job.pieceSeconds();
        job.limits().checkPiece(seconds, "The piece");
        BuiltinSynthesis.writeWave(output, samples(job.composition(), job.part(), BuiltinSynthesis.frames(seconds)));
    }

    /** The samples of the part, its level applied, before the WAV is written. */
    public static double[] samples(Composition composition, Part part, int frames) {
        TimeMap time = composition.timeMap();
        List<BuiltinSynthesis.Tone> tones = new ArrayList<>();
        for (Composition.PlacedNote placed : composition.notesOf(part.id())) {
            long tick = placed.tick();
            tones.add(new BuiltinSynthesis.Tone(time.seconds(tick), heldSeconds(time, tick, placed.endTick()),
                    placed.note().pitch(), placed.note().velocity(), tick));
        }
        return BuiltinSynthesis.part(role(part), tones, frames);
    }

    /**
     * How long a note is held, in seconds. Across a change of tempo it is the time between its
     * start and its end; at one tempo it is computed as the reference renderer does, so that the
     * samples stay the same to the last bit.
     */
    static double heldSeconds(TimeMap time, long tick, long endTick) {
        for (TimeMap.Tempo tempo : time.tempos()) {
            if (tempo.tick() > tick && tempo.tick() < endTick) {
                return time.seconds(endTick) - time.seconds(tick);
            }
        }
        return (endTick - tick) / (double) TimeMap.PPQ * 60.0 / time.tempoAt(tick);
    }

    /** The role the sound is shaped by; a part without one sounds by its id, as in the old renderer. */
    static String role(Part part) {
        return part.role() != null ? part.role() : part.id();
    }
}
