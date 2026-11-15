package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.wav.WavFileSource;
import com.aresstack.audio.studio.mix.SpatialStemMixer;
import com.aresstack.audio.studio.mix.StageAutomation;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.visual.TrackLight;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The tracks of a piece for the stage lights while its finished mix plays as one file: every
 * track at its place as the stage and its movements have it at the heard second, glowing with the
 * level it has there. The level comes from the rendered track (its peak block by block, as a live
 * channel measures it); a track that is not rendered glows with its notes (velocity while a note
 * sounds). Made once per title off the event thread; asking for a second allocates only the lights.
 */
public final class StageLights {
    /** How long one step of a level curve lasts: one block of the mix, as a live channel keeps its peaks. */
    static final double STEP_SECONDS = SpatialStemMixer.BLOCK / (double) SpatialStemMixer.RATE;
    /**
     * How much of its velocity a note still shows at its end: a note glows brightest when it is
     * struck and fades while it is held, as a struck or plucked sound does.
     */
    static final float NOTE_END = 0.5f;

    private final List<String> ids;
    private final List<SoundSourcePlacement> placements;
    private final StageAutomation moving;
    /** Per track the peak of every step, 0 to 1. */
    private final List<float[]> levels;

    StageLights(List<String> ids, List<SoundSourcePlacement> placements, StageAutomation moving, List<float[]> levels) {
        this.ids = List.copyOf(ids);
        this.placements = List.copyOf(placements);
        this.moving = moving == null ? StageAutomation.NONE : moving;
        this.levels = List.copyOf(levels);
    }

    /**
     * Reads what the lights of a piece need: its stage, its movements, and the level of every track
     * from its rendered file, or from the notes of the model where the file is missing.
     *
     * @param model the model of the piece for tracks without a file; {@code null} if there is none
     * @param only  the track that is heard alone, or {@code null} for the whole mix
     */
    public static StageLights read(StageMixService.Piece piece, Composition model, Path directory,
                                   SpatialScene scene, String only) {
        List<MusicTrack> tracks = piece.tracks();
        SpatialScene fitting = scene.fitting(tracks);
        List<String> ids = new ArrayList<String>();
        List<SoundSourcePlacement> placements = new ArrayList<SoundSourcePlacement>();
        List<float[]> levels = new ArrayList<float[]>();
        for (MusicTrack track : tracks) {
            SoundSourcePlacement placement = fitting.placement(track.id());
            if (only != null) {
                placement = placement.withMuted(!only.equals(track.id()));
            }
            ids.add(track.id());
            placements.add(placement);
            float[] level = fromFile(StageMixService.stem(directory, track.id()));
            if (level == null) {
                level = fromNotes(model, track.id());
            }
            levels.add(level);
        }
        // The audio lanes the mix adds beside the parts, from the stems it wrote for them.
        for (SoundSourcePlacement lane : StageMixService.audioLanes(model, directory)) {
            SoundSourcePlacement placement = only == null ? lane : lane.withMuted(!only.equals(lane.trackId()));
            float[] level = fromFile(StageMixService.audioStem(directory, lane.trackId()));
            ids.add(lane.trackId());
            placements.add(placement);
            levels.add(level == null ? new float[0] : level);
        }
        StageAutomation moving;
        try {
            moving = piece.levels(placements).movingAs(piece.movements(directory, message -> { }));
        } catch (RuntimeException unreadable) {
            moving = StageAutomation.NONE; // the lights stand at their places
        }
        return new StageLights(ids, placements, moving, levels);
    }

    /** The peak of every step of a rendered track; {@code null} if there is no file or it cannot be read. */
    static float[] fromFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (WavFileSource source = new WavFileSource(file)) {
            int rate = (int) source.sampleRate();
            int step = Math.max(1, (int) Math.round(STEP_SECONDS * rate));
            AudioBlock block = new AudioBlock(step);
            source.prepare(rate, step);
            float[] peaks = new float[1024];
            int count = 0;
            int got;
            do {
                block.setFrames(step);
                got = source.read(block);
                float peak = 0f;
                for (int i = 0; i < got; i++) {
                    peak = Math.max(peak, Math.max(Math.abs(block.left[i]), Math.abs(block.right[i])));
                }
                if (got > 0) {
                    if (count == peaks.length) {
                        peaks = java.util.Arrays.copyOf(peaks, peaks.length * 2);
                    }
                    peaks[count++] = Math.min(1f, peak);
                }
            } while (got == step);
            return java.util.Arrays.copyOf(peaks, count);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** The level of a part from its notes: the velocity of what sounds, fading over each note. */
    static float[] fromNotes(Composition model, String partId) {
        if (model == null) {
            return new float[0];
        }
        Part part = model.part(partId);
        if (part == null) {
            return new float[0];
        }
        List<Composition.PlacedNote> notes = model.notesOf(partId);
        double end = 0;
        for (Composition.PlacedNote placed : notes) {
            end = Math.max(end, model.timeMap().seconds(model.playedEnd(placed)));
        }
        float[] steps = new float[(int) Math.ceil(end / STEP_SECONDS)];
        for (Composition.PlacedNote placed : notes) {
            double from = model.timeMap().seconds(placed.tick());
            double to = model.timeMap().seconds(model.playedEnd(placed));
            float velocity = Math.max(0, Math.min(127, model.playedVelocity(placed))) / 127f;
            int first = (int) Math.floor(from / STEP_SECONDS);
            int last = Math.min(steps.length - 1, Math.max(first, (int) Math.ceil(to / STEP_SECONDS) - 1));
            for (int s = Math.max(0, first); s <= last; s++) {
                float share = last == first ? 0f : (s - first) / (float) (last - first);
                steps[s] = Math.max(steps[s], velocity * (1 - (1 - NOTE_END) * share));
            }
        }
        return steps;
    }

    /** Whether there are tracks to show. */
    public boolean isEmpty() {
        return ids.isEmpty();
    }

    /** The tracks at a second of the piece: their places as the movements have them then, their levels then. */
    public List<TrackLight> at(double second) {
        long frame = Math.max(0, Math.round(second * SpatialStemMixer.RATE));
        int step = (int) Math.max(0, Math.floor(second / STEP_SECONDS));
        List<TrackLight> lights = new ArrayList<TrackLight>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            SoundSourcePlacement placement = placements.get(i);
            StageAutomation.Lanes lanes = moving.of(id);
            double x = lanes.x() != null ? lanes.x().valueAt(frame) : placement.x();
            double depth = lanes.depth() != null ? lanes.depth().valueAt(frame) : placement.depth();
            double height = lanes.height() != null ? lanes.height().valueAt(frame) : placement.height();
            float[] curve = levels.get(i);
            float level = 0f;
            if (!placement.muted() && step < curve.length) {
                double db = placement.levelDb() + lanes.trimDb() + (lanes.level() != null ? lanes.level().valueAt(frame) : 0);
                level = (float) Math.min(1, curve[step] * Math.pow(10, db / 20));
            }
            lights.add(new TrackLight(id, i, x, depth, height, level));
        }
        return lights;
    }
}
