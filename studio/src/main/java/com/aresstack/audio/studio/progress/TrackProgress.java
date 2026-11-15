package com.aresstack.audio.studio.progress;

/**
 * A listener that also wants to know how each single track is getting on, e.g. to show it on
 * the track's channel strip while several tracks are worked on at the same time. Called from
 * the worker threads.
 */
public interface TrackProgress extends Progress {
    /** What is being done to a track. */
    enum Stage { COMPOSE, RENDER }

    /** How far that is. */
    enum State { WAITING, WORKING, DONE, FAILED }

    void track(String trackId, Stage stage, State state, String detail);

    /** Tells a listener about a track, if it wants to know. */
    static void tell(Progress progress, String trackId, Stage stage, State state, String detail) {
        if (progress instanceof TrackProgress) {
            ((TrackProgress) progress).track(trackId, stage, state, detail);
        }
    }
}
