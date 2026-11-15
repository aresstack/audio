package com.aresstack.audio.composition;

/**
 * A sound file placed on an audio track. It is anchored in ticks and lasts a number of samples,
 * so it does not stretch when the tempo changes.
 */
public record AudioClip(String id, long startTick, long lengthSamples, String assetId, double gainDb,
                        long fadeInSamples, long fadeOutSamples) implements Clip {
    public AudioClip {
        Ids.require(id, "a clip");
        Ids.require(assetId, "the asset of a clip");
        if (startTick < 0 || lengthSamples < 1 || fadeInSamples < 0 || fadeOutSamples < 0
                || Double.isNaN(gainDb) || Double.isInfinite(gainDb)) {
            throw new IllegalArgumentException("Invalid audio clip: " + id);
        }
    }

    @Override
    public Track.Kind kind() {
        return Track.Kind.AUDIO;
    }
}
