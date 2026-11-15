package com.aresstack.audio.studio.visual;

/**
 * One track as a picture sees it at the heard moment: where it stands on the stage and how loud it is.
 *
 * @param trackId the id of the track
 * @param index   its place in the list of tracks, which gives its colour
 * @param x       the place between -1 (left) and +1 (right)
 * @param depth   the place between 0 (front edge) and 1 (back wall)
 * @param height  the height above the ears, 0 to 1
 * @param level   the peak of the track now, 0 for silence, 1 for full scale
 */
public record TrackLight(String trackId, int index, double x, double depth, double height, float level) {
}
