/**
 * The visualizer: pictures of the sound that is heard right now. A {@link
 * com.aresstack.audio.studio.visual.SoundFeed} hands over the heard frames (from the live stage or
 * from a played file) and the tracks with their places on the stage; the {@link
 * com.aresstack.audio.studio.visual.SoundAnalyzer} turns them into a {@link
 * com.aresstack.audio.studio.visual.SoundPicture} (bands, waveform, levels, beat); an {@link
 * com.aresstack.audio.studio.visual.Effect} draws it with Java2D. All numbers are in {@link
 * com.aresstack.audio.studio.visual.VisualSettings}.
 *
 * <p>This package draws on a {@code Graphics2D} and knows no window, no AI, no use case and no
 * project files; the window (page, keys, full screen) lives in {@code studio.ui}.
 */
package com.aresstack.audio.studio.visual;
