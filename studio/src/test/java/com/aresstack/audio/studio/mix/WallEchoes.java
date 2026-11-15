package com.aresstack.audio.studio.mix;

/**
 * For tests outside this package that compare with the mixer from before the wall echoes were
 * halved (decision of the product owner on #22): sets the level of the wall echoes and gives back
 * the one before, so that the test can restore it.
 */
public final class WallEchoes {
    private WallEchoes() {
    }

    /** The level of the wall echoes as the old mixer had it. */
    public static final double LEGACY = 1.0;

    public static double level(double level) {
        double before = SpatialStemMixer.wallEchoLevel;
        SpatialStemMixer.wallEchoLevel = level;
        return before;
    }
}
