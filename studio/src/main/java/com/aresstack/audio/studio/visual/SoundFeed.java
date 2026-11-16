package com.aresstack.audio.studio.visual;

import java.util.List;

/**
 * Where a picture gets its sound: the frames the listener hears right now, straight from the
 * playback chain (never from a microphone), and the tracks of the stage. Any thread may ask; an
 * implementation neither blocks nor allocates more than the lights it returns.
 */
public interface SoundFeed {
    /** Nothing plays: every window is silent. */
    SoundFeed SILENT = new SoundFeed() {
        @Override
        public boolean window(float[] left, float[] right, int count) {
            return false;
        }

        @Override
        public int sampleRate() {
            return 44100;
        }
    };

    /**
     * Copies the {@code count} frames that end at the frame heard now into the arrays.
     *
     * @return {@code false} if nothing plays or the frames are not at hand; the arrays then hold
     *         nothing usable
     */
    boolean window(float[] left, float[] right, int count);

    /** Frames per second of the windows. */
    int sampleRate();

    /** The tracks at the heard moment; empty where they are not known (a file played as a whole). */
    default List<TrackLight> lights() {
        return List.of();
    }

    /**
     * Whether the tracks of what plays are known but still being read: the stage then waits for
     * them instead of showing the sound itself at places the tracks do not have.
     */
    default boolean lightsComing() {
        return false;
    }

    /**
     * What is known in words about the style of what plays (the description of the piece, the
     * style of its album); empty where nothing is known. The stage reads its atmosphere from it.
     */
    default String style() {
        return "";
    }

    /** The figure of the musician of a track as a picture standing on its bottom edge, or {@code null} if it has none. */
    default java.awt.image.BufferedImage figure(String trackId) {
        return null;
    }

    /** How tall a figure stands on the stage, in metres. */
    default double figureHeight() {
        return 3.0;
    }
}
