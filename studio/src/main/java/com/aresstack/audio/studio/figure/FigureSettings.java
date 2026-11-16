package com.aresstack.audio.studio.figure;

import java.util.Properties;

/**
 * How the figures stand on the stage, kept in the settings of the studio: whether they are shown,
 * and how tall a figure is drawn, in metres of the stage (the stage is 12 m wide).
 *
 * @param shown  whether the figures stand on the stage
 * @param height the height of a figure in metres of the stage
 */
public record FigureSettings(boolean shown, double height) {
    public static final String SHOWN = "stageFigures";
    public static final String HEIGHT = "stageFigureHeight";
    public static final FigureSettings DEFAULT = new FigureSettings(true, 2.0);
    /** The bounds of the height; outside them a figure is lost or covers the stage. */
    public static final double LOWEST = 0.5;
    public static final double HIGHEST = 6.0;
    /** How much taller a figure grows at full level while its track sounds: it bobs with the music. */
    public static final String BOB = "stageFigureBob";
    public static final double DEFAULT_BOB = 0.06;

    public FigureSettings {
        height = Double.isFinite(height) ? Math.max(LOWEST, Math.min(HIGHEST, height)) : DEFAULT.height;
    }

    public static FigureSettings from(Properties settings) {
        boolean shown = !"false".equals(settings.getProperty(SHOWN, "true").trim());
        double height = DEFAULT.height;
        try {
            height = Double.parseDouble(settings.getProperty(HEIGHT, String.valueOf(DEFAULT.height)).trim());
        } catch (NumberFormatException unreadable) {
            // the default height
        }
        return new FigureSettings(shown, height);
    }

    /** How much a figure bobs with the music, from the settings: 0 keeps it still. */
    public static double bob(Properties settings) {
        try {
            double bob = Double.parseDouble(settings.getProperty(BOB, String.valueOf(DEFAULT_BOB)).trim());
            return Double.isFinite(bob) ? Math.max(0, Math.min(0.5, bob)) : DEFAULT_BOB;
        } catch (NumberFormatException unreadable) {
            return DEFAULT_BOB;
        }
    }

    public void into(Properties settings) {
        settings.setProperty(SHOWN, String.valueOf(shown));
        settings.setProperty(HEIGHT, String.valueOf(height));
    }
}
