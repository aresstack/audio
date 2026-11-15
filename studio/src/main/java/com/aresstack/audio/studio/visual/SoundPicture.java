package com.aresstack.audio.studio.visual;

import java.util.List;

/**
 * What one picture shows of the sound: made anew by the {@link SoundAnalyzer} for every picture and
 * read by the effects on the same thread. The arrays keep their length while the settings stay.
 */
public final class SoundPicture {
    /** The heard frames of the window, left and right, -1 to 1. */
    public final float[] left;
    public final float[] right;
    /** The bands of the spectrum from low to high, smoothed, 0 (floor) to 1 (full scale). */
    public final float[] bands;
    /** The bands before smoothing, for effects that want the raw jumps. */
    public final float[] rawBands;
    /** The peak of each side in the window, 0 to 1. */
    public float peakLeft;
    public float peakRight;
    /** The RMS of each side in the window, 0 to 1. */
    public float rmsLeft;
    public float rmsRight;
    /** The low, middle and high share of the spectrum, 0 to 1. */
    public float bass;
    public float middle;
    public float treble;
    /** The flash of the last beat: 1 on the picture of the beat, fading by {@link VisualSettings#beatDecay()}. */
    public float beat;
    /** How many beats were counted since the picture began. */
    public long beats;
    /** Whether sound is heard at all (something plays and the window could be read). */
    public boolean sounding;
    /** Seconds since the visualizer started, for animation that runs on in silence. */
    public double seconds;
    /** The tracks of the stage at the heard moment. */
    public List<TrackLight> lights = List.of();

    public SoundPicture(int windowFrames, int bandCount) {
        left = new float[windowFrames];
        right = new float[windowFrames];
        bands = new float[bandCount];
        rawBands = new float[bandCount];
    }

    /** The overall loudness, 0 to 1: the louder of the two RMS values. */
    public float loudness() {
        return Math.max(rmsLeft, rmsRight);
    }
}
