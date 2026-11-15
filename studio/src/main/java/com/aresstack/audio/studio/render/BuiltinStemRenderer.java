package com.aresstack.audio.studio.render;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
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
            // As it sounds: the dynamics of the piece and the articulation of the note shape velocity and length.
            tones.add(new BuiltinSynthesis.Tone(time.seconds(tick), heldSeconds(time, tick, composition.playedEnd(placed)),
                    placed.note().pitch(), composition.playedVelocity(placed), tick));
        }
        double[] samples = BuiltinSynthesis.part(role(part), tones, frames);
        swell(samples, time, composition.expression(part.id()));
        return samples;
    }

    /**
     * Lets the samples follow the expression curve of the part, as a sampler follows controller
     * 11: held notes swell and fade within a hairpin. Between two points of the curve the gain
     * moves evenly, so that it does not jump in steps; without a curve nothing changes.
     */
    static void swell(double[] samples, TimeMap time, List<Dynamics.ExpressionPoint> curve) {
        if (curve.isEmpty()) {
            return;
        }
        int rate = BuiltinSynthesis.SAMPLE_RATE;
        double gain = 1.0;
        int at = 0;
        for (int i = 0; i <= curve.size(); i++) {
            int until = i < curve.size()
                    ? (int) Math.min(samples.length, Math.round(time.seconds(curve.get(i).tick()) * rate))
                    : samples.length;
            double next = i < curve.size() ? curve.get(i).value() / 127.0 : gain;
            // A point that follows the one before at the next step is reached gradually, the first
            // point of a hairpin and the return to full expression at its end take effect at once.
            boolean glides = i > 0 && i < curve.size()
                    && curve.get(i).tick() - curve.get(i - 1).tick() <= TimeMap.GLIDE_STEP_TICKS;
            for (int f = Math.max(0, at); f < until; f++) {
                double share = glides && until > at ? (f - at) / (double) (until - at) : 0;
                samples[f] *= gain + (next - gain) * share;
            }
            gain = next;
            at = Math.max(at, until);
        }
    }

    /**
     * How long a note is held, in seconds. Across a change of tempo or within an accelerando or ritardando it is the time between its
     * start and its end; at one tempo it is computed as the reference renderer does, so that the
     * samples stay the same to the last bit.
     */
    static double heldSeconds(TimeMap time, long tick, long endTick) {
        if (!time.steadyBetween(tick, endTick)) {
            return time.seconds(endTick) - time.seconds(tick);
        }
        return (endTick - tick) / (double) TimeMap.PPQ * 60.0 / time.tempoAt(tick);
    }

    /** The role the sound is shaped by; a part without one sounds by its id, as in the old renderer. */
    static String role(Part part) {
        return part.role() != null ? part.role() : part.id();
    }
}
