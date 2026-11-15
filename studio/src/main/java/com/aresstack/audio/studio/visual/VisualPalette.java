package com.aresstack.audio.studio.visual;

import java.awt.Color;
import java.util.function.IntFunction;

/**
 * The colours an effect draws with, handed in by the window so that the pictures match the desk.
 *
 * @param background the ground of the picture
 * @param ink        lettering and lines
 * @param faint      scales and quiet lines
 * @param accent     the colour of the studio
 * @param low        the colour of quiet levels and of the low end
 * @param middle     the colour of middle levels
 * @param high       the colour of loud levels and of the high end
 * @param cool       a second colour for the right side and the high end
 * @param track      the colour of a track by its place in the list
 */
public record VisualPalette(Color background, Color ink, Color faint, Color accent, Color low, Color middle,
                            Color high, Color cool, IntFunction<Color> track) {
}
