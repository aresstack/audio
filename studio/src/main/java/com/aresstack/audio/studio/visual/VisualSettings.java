package com.aresstack.audio.studio.visual;

import java.util.Locale;
import java.util.Properties;

/**
 * Every number of the visualizer, kept with the settings of the studio under {@code visualizer.*}.
 * A missing or unreadable value takes its default; values out of range are brought into it.
 *
 * @param framesPerSecond pictures per second
 * @param windowFrames    frames of sound one picture analyses (a power of two: the FFT length)
 * @param bands           bands of the spectrum, spaced evenly in pitch
 * @param lowestHz        the lowest frequency shown
 * @param highestHz       the highest frequency shown
 * @param floorDb         the level shown as nothing; the top is full scale (0 dB)
 * @param attack          how fast a band rises, the share of the way to a louder value per picture
 * @param release         how fast a band falls, the share of the way to a quieter value per picture
 * @param beatRatio       how much louder than its running average the low end must get to count as a beat
 * @param beatAverage     the share of a picture's low end that goes into the running average
 * @param beatDecay       how much of a beat's flash is left after one picture
 * @param beatGap         the shortest time between two beats, in seconds
 * @param bassHz          the upper edge of the low end (bass, and what a beat is heard in)
 * @param trebleHz        the lower edge of the high end
 * @param particles       how many particles the particle effect keeps
 * @param tunnelRings     how many rings the tunnel shows
 * @param trail           how much of the last picture stays behind in the trailing effects, 0 to 1
 * @param effect          the key of the effect shown ({@link Effect#key()})
 * @param gpu             whether the full screen uses the graphics card where it can
 * @param gpuBackend      auto, d3d11 or opengl: which graphics API the full screen uses
 * @param gpuVsync        whether the full screen waits for the refresh of the screen
 * @param gpuOpenSeconds  how long the graphics card may take to open the full screen
 * @param doubleClickMs   the longest time between the two clicks of a double-click on the full screen
 * @param atmosphere      the surprise of the stage: auto (fire for rock, lasers for techno, in their wildest moments), fire, laser or off
 * @param laserBpm        from how many beats per minute on music counts as fast
 * @param fireBass        how heavy the low end must be (0 to 1) for a beat to set off the fire
 * @param moodSeconds     how long a wild moment must last before the lasers come, and how long they stay after it
 * @param wildRatio       how much louder than the piece is as a rule a moment must be to count as wild (1.25 = a quarter louder)
 * @param atmosphereStrength how bright the atmosphere is drawn, 0 (not at all) to 1
 */
public record VisualSettings(int framesPerSecond, int windowFrames, int bands, double lowestHz, double highestHz,
                             double floorDb, double attack, double release, double beatRatio, double beatAverage,
                             double beatDecay, double beatGap, double bassHz, double trebleHz, int particles, int tunnelRings, double trail, String effect,
                             boolean gpu, String gpuBackend, boolean gpuVsync, double gpuOpenSeconds,
                             int doubleClickMs, String atmosphere, double laserBpm, double fireBass,
                             double moodSeconds, double wildRatio, double atmosphereStrength) {
    public static final String PREFIX = "visualizer.";

    public static final VisualSettings DEFAULTS = new VisualSettings(30, 2048, 48, 30, 16000, -72, 0.6, 0.12,
            1.45, 0.05, 0.86, 0.18, 250, 4000, 700, 22, 0.82, "spectrum", true, "auto", true, 6, 450,
            StageMood.AUTO, 124, 0.7, 4, 1.25, 0.9);

    public VisualSettings {
        framesPerSecond = clamp(framesPerSecond, 5, 120);
        windowFrames = powerOfTwo(clamp(windowFrames, 256, 16384));
        bands = clamp(bands, 4, 256);
        lowestHz = clamp(lowestHz, 10, 2000);
        highestHz = clamp(highestHz, lowestHz * 2, 22000);
        floorDb = clamp(floorDb, -140, -12);
        attack = clamp(attack, 0.01, 1);
        release = clamp(release, 0.01, 1);
        beatRatio = clamp(beatRatio, 1.01, 10);
        beatAverage = clamp(beatAverage, 0.001, 1);
        beatDecay = clamp(beatDecay, 0, 0.999);
        beatGap = clamp(beatGap, 0, 5);
        bassHz = clamp(bassHz, lowestHz, highestHz);
        trebleHz = clamp(trebleHz, bassHz, highestHz);
        particles = clamp(particles, 0, 20000);
        tunnelRings = clamp(tunnelRings, 2, 200);
        trail = clamp(trail, 0, 0.99);
        effect = effect == null || effect.isBlank() ? "spectrum" : effect.trim();
        gpuBackend = gpuBackend == null || !gpuBackend.trim().matches("auto|d3d11|opengl") ? "auto" : gpuBackend.trim();
        gpuOpenSeconds = clamp(gpuOpenSeconds, 1, 60);
        doubleClickMs = clamp(doubleClickMs, 100, 2000);
        atmosphere = StageMood.normal(atmosphere);
        laserBpm = clamp(laserBpm, 40, 400);
        fireBass = clamp(fireBass, 0, 1);
        moodSeconds = clamp(moodSeconds, 0, 60);
        wildRatio = clamp(wildRatio, 1, 4);
        atmosphereStrength = clamp(atmosphereStrength, 0, 1);
    }

    /** The settings kept in these properties, defaults where none are. */
    public static VisualSettings from(Properties saved) {
        VisualSettings d = DEFAULTS;
        if (saved == null) {
            return d;
        }
        return new VisualSettings(
                integer(saved, "framesPerSecond", d.framesPerSecond),
                integer(saved, "windowFrames", d.windowFrames),
                integer(saved, "bands", d.bands),
                number(saved, "lowestHz", d.lowestHz),
                number(saved, "highestHz", d.highestHz),
                number(saved, "floorDb", d.floorDb),
                number(saved, "attack", d.attack),
                number(saved, "release", d.release),
                number(saved, "beatRatio", d.beatRatio),
                number(saved, "beatAverage", d.beatAverage),
                number(saved, "beatDecay", d.beatDecay),
                number(saved, "beatGap", d.beatGap),
                number(saved, "bassHz", d.bassHz),
                number(saved, "trebleHz", d.trebleHz),
                integer(saved, "particles", d.particles),
                integer(saved, "tunnelRings", d.tunnelRings),
                number(saved, "trail", d.trail),
                saved.getProperty(PREFIX + "effect", d.effect),
                !"false".equalsIgnoreCase(saved.getProperty(PREFIX + "gpu", String.valueOf(d.gpu))),
                saved.getProperty(PREFIX + "gpuBackend", d.gpuBackend),
                !"false".equalsIgnoreCase(saved.getProperty(PREFIX + "gpuVsync", String.valueOf(d.gpuVsync))),
                number(saved, "gpuOpenSeconds", d.gpuOpenSeconds),
                integer(saved, "doubleClickMs", d.doubleClickMs),
                saved.getProperty(PREFIX + "atmosphere", d.atmosphere),
                number(saved, "laserBpm", d.laserBpm),
                number(saved, "fireBass", d.fireBass),
                number(saved, "moodSeconds", d.moodSeconds),
                number(saved, "wildRatio", d.wildRatio),
                number(saved, "atmosphereStrength", d.atmosphereStrength));
    }

    /** Writes every value into the properties, so that a user finds them all in the file. */
    public void into(Properties saved) {
        saved.setProperty(PREFIX + "framesPerSecond", String.valueOf(framesPerSecond));
        saved.setProperty(PREFIX + "windowFrames", String.valueOf(windowFrames));
        saved.setProperty(PREFIX + "bands", String.valueOf(bands));
        saved.setProperty(PREFIX + "lowestHz", text(lowestHz));
        saved.setProperty(PREFIX + "highestHz", text(highestHz));
        saved.setProperty(PREFIX + "floorDb", text(floorDb));
        saved.setProperty(PREFIX + "attack", text(attack));
        saved.setProperty(PREFIX + "release", text(release));
        saved.setProperty(PREFIX + "beatRatio", text(beatRatio));
        saved.setProperty(PREFIX + "beatAverage", text(beatAverage));
        saved.setProperty(PREFIX + "beatDecay", text(beatDecay));
        saved.setProperty(PREFIX + "beatGap", text(beatGap));
        saved.setProperty(PREFIX + "bassHz", text(bassHz));
        saved.setProperty(PREFIX + "trebleHz", text(trebleHz));
        saved.setProperty(PREFIX + "particles", String.valueOf(particles));
        saved.setProperty(PREFIX + "tunnelRings", String.valueOf(tunnelRings));
        saved.setProperty(PREFIX + "trail", text(trail));
        saved.setProperty(PREFIX + "effect", effect);
        saved.setProperty(PREFIX + "gpu", String.valueOf(gpu));
        saved.setProperty(PREFIX + "gpuBackend", gpuBackend);
        saved.setProperty(PREFIX + "gpuVsync", String.valueOf(gpuVsync));
        saved.setProperty(PREFIX + "gpuOpenSeconds", text(gpuOpenSeconds));
        saved.setProperty(PREFIX + "doubleClickMs", String.valueOf(doubleClickMs));
        saved.setProperty(PREFIX + "atmosphere", atmosphere);
        saved.setProperty(PREFIX + "laserBpm", text(laserBpm));
        saved.setProperty(PREFIX + "fireBass", text(fireBass));
        saved.setProperty(PREFIX + "moodSeconds", text(moodSeconds));
        saved.setProperty(PREFIX + "wildRatio", text(wildRatio));
        saved.setProperty(PREFIX + "atmosphereStrength", text(atmosphereStrength));
    }

    /** The same settings showing another effect. */
    public VisualSettings showing(String key) {
        return new VisualSettings(framesPerSecond, windowFrames, bands, lowestHz, highestHz, floorDb, attack,
                release, beatRatio, beatAverage, beatDecay, beatGap, bassHz, trebleHz, particles, tunnelRings, trail, key, gpu, gpuBackend,
                gpuVsync, gpuOpenSeconds, doubleClickMs, atmosphere, laserBpm, fireBass, moodSeconds,
                wildRatio, atmosphereStrength);
    }

    /** The same settings with another atmosphere of the stage. */
    public VisualSettings withAtmosphere(String choice) {
        return new VisualSettings(framesPerSecond, windowFrames, bands, lowestHz, highestHz, floorDb, attack,
                release, beatRatio, beatAverage, beatDecay, beatGap, bassHz, trebleHz, particles, tunnelRings, trail, effect, gpu,
                gpuBackend, gpuVsync, gpuOpenSeconds, doubleClickMs, choice, laserBpm, fireBass, moodSeconds,
                wildRatio, atmosphereStrength);
    }

    private static int integer(Properties saved, String key, int fallback) {
        try {
            return Integer.parseInt(saved.getProperty(PREFIX + key, "").trim());
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }

    private static double number(Properties saved, String key, double fallback) {
        try {
            double value = Double.parseDouble(saved.getProperty(PREFIX + key, "").trim().replace(',', '.'));
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }

    private static String text(double value) {
        return String.format(Locale.ROOT, "%s", value);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static int powerOfTwo(int value) {
        int below = Integer.highestOneBit(value);
        return below == value ? value : below << 1 <= 16384 ? below << 1 : below;
    }
}
