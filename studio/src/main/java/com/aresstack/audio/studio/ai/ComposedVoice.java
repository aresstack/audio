package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Track;
import java.util.List;

/**
 * A new voice as the AI wrote it, repaired, but not yet part of the piece: the new part, its new
 * (empty) track, one clip per frame it plays in, and what it was made from. Turning it into a
 * patch is the application's business.
 *
 * @param corrections every repair of the normalizer and every note left out, tab-separated
 * @param attempt     which attempt this is, from 1
 */
public record ComposedVoice(Part part, Track track, List<MidiClip> clips, List<VoiceFrame> frames, VoicePlan plan,
                            MusicalContext context, List<String> corrections, int attempt) {
    public ComposedVoice {
        clips = List.copyOf(clips);
        frames = List.copyOf(frames);
        corrections = List.copyOf(corrections);
        if (!track.clips().isEmpty() || !part.id().equals(track.defaultPartId())) {
            throw new IllegalArgumentException("The track of a new voice comes empty and names the new part.");
        }
    }

    /** The first tick the new voice may play at. */
    public long fromTick() {
        return frames.get(0).startTick();
    }

    /** The first tick after the last frame. */
    public long toTick() {
        return frames.get(frames.size() - 1).endTick();
    }

    /** A neutral reference to the run, for the revision: what, which part, which attempt, which context. */
    public String runId() {
        return "add-voice/" + part.id() + "/" + attempt + "/" + context.shortDigest();
    }

    public int noteCount() {
        return clips.stream().mapToInt(c -> c.notes().size()).sum();
    }
}
