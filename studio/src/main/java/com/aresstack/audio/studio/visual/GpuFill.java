package com.aresstack.audio.studio.visual;

import com.aresstack.audio.gpu.GpuFrame;
import com.aresstack.audio.gpu.GpuScreen;
import java.util.function.Supplier;

/**
 * Feeds the full screen on the graphics card: on its render thread it analyses the heard sound
 * with an analyzer of its own and hands bands, waveform and levels to the shaders.
 */
public final class GpuFill implements GpuScreen.Source {
    private final SoundFeed feed;
    private final VisualSettings settings;
    private final Supplier<String> effect;
    private SoundAnalyzer analyzer;

    /** @param effect the key of the effect to draw, asked before every picture */
    public GpuFill(SoundFeed feed, VisualSettings settings, Supplier<String> effect) {
        this.feed = feed == null ? SoundFeed.SILENT : feed;
        this.settings = settings;
        this.effect = effect;
    }

    /** Whether the graphics card draws this effect; the others stay with Java2D. */
    public static boolean drawsOnGpu(String key) {
        return GpuScreen.EFFECTS.contains(key);
    }

    @Override
    public void fill(GpuFrame frame) {
        int rate = feed.sampleRate();
        if (analyzer == null || analyzer.sampleRate() != rate) {
            analyzer = new SoundAnalyzer(settings, rate);
        }
        SoundPicture picture = analyzer.analyze(feed);
        copy(picture, frame);
        frame.effect = effect.get();
    }

    /** Spreads the bands and the waveform of a picture over the points of a frame and copies the levels. */
    static void copy(SoundPicture picture, GpuFrame frame) {
        int points = frame.bands.length;
        float[] bands = picture.bands;
        for (int i = 0; i < points; i++) {
            frame.bands[i] = bands[(int) ((long) i * bands.length / points)];
        }
        int n = picture.left.length;
        for (int i = 0; i < points; i++) {
            int at = (int) ((long) i * n / points);
            frame.wave[i] = Math.max(-1f, Math.min(1f, (picture.left[at] + picture.right[at]) * 0.5f));
        }
        frame.bass = picture.bass;
        frame.middle = picture.middle;
        frame.treble = picture.treble;
        frame.beat = picture.beat;
        frame.loudness = picture.loudness();
    }
}
