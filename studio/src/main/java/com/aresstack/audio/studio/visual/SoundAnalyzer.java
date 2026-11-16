package com.aresstack.audio.studio.visual;

import com.aresstack.audio.core.fft.FourierTransform;
import com.aresstack.audio.core.fft.RadixTwoFourierTransform;

/**
 * Turns the heard frames into a {@link SoundPicture}: levels, a spectrum in bands spaced evenly in
 * pitch, the low, middle and high share, and beats. Uses the FFT of the core (MIT, from AskAI), so
 * the visualizer needs no library of its own.
 *
 * <p>One analyzer belongs to one picture thread (usually the event thread); it allocates only when
 * it is made.
 */
public final class SoundAnalyzer {
    private final VisualSettings settings;
    private final SoundPicture picture;
    private final FourierTransform fft;
    private final double[] real;
    private final double[] imag;
    /** The right side's transform; the left one goes into {@link #real} and {@link #imag}. */
    private final double[] realRight;
    private final double[] imagRight;
    private final double[] hann;
    /** For each band its first and last FFT bin; a band narrower than a bin reads between two bins. */
    private final double[] bandLow;
    private final double[] bandHigh;
    private final int sampleRate;
    private double runningBass = -1;
    private double lastBeatSeconds = Double.NEGATIVE_INFINITY;
    private final long started = System.nanoTime();

    public SoundAnalyzer(VisualSettings settings, int sampleRate) {
        this.settings = settings;
        this.sampleRate = sampleRate;
        int n = settings.windowFrames();
        picture = new SoundPicture(n, settings.bands());
        fft = new RadixTwoFourierTransform(n);
        real = new double[n];
        imag = new double[n];
        realRight = new double[n];
        imagRight = new double[n];
        hann = new double[n];
        for (int i = 0; i < n; i++) {
            hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
        }
        int bands = settings.bands();
        bandLow = new double[bands];
        bandHigh = new double[bands];
        double binHz = (double) sampleRate / n;
        double nyquist = sampleRate / 2.0;
        double low = Math.min(settings.lowestHz(), nyquist / 2);
        double high = Math.min(settings.highestHz(), nyquist);
        double ratio = Math.pow(high / low, 1.0 / bands);
        for (int b = 0; b < bands; b++) {
            bandLow[b] = low * Math.pow(ratio, b) / binHz;
            bandHigh[b] = low * Math.pow(ratio, b + 1) / binHz;
        }
    }

    public VisualSettings settings() {
        return settings;
    }

    public int sampleRate() {
        return sampleRate;
    }

    /** The picture the last {@link #analyze} made; the same object every time. */
    public SoundPicture picture() {
        return picture;
    }

    /** Reads the heard window from the feed and analyses it; silence (falling bands) if nothing is heard. */
    public SoundPicture analyze(SoundFeed feed) {
        int n = picture.left.length;
        boolean heard = feed != null && feed.window(picture.left, picture.right, n);
        if (!heard) {
            java.util.Arrays.fill(picture.left, 0f);
            java.util.Arrays.fill(picture.right, 0f);
        }
        picture.sounding = heard;
        picture.lights = feed == null ? java.util.List.of() : feed.lights();
        picture.lightsComing = feed != null && feed.lightsComing();
        String style = feed == null ? null : feed.style();
        picture.style = style == null ? "" : style;
        final SoundFeed from = feed;
        picture.figures = from == null ? track -> null : from::figure;
        picture.figureHeight = from == null ? 3.0 : from.figureHeight();
        analyzeWindow((System.nanoTime() - started) / 1e9);
        return picture;
    }

    /** Analyses what stands in the window arrays of the picture now; {@code seconds} is the time of the picture. */
    public SoundPicture analyzeWindow(double seconds) {
        int n = picture.left.length;
        picture.seconds = seconds;
        float peakL = 0;
        float peakR = 0;
        double sumL = 0;
        double sumR = 0;
        for (int i = 0; i < n; i++) {
            float l = picture.left[i];
            float r = picture.right[i];
            peakL = Math.max(peakL, Math.abs(l));
            peakR = Math.max(peakR, Math.abs(r));
            sumL += l * l;
            sumR += r * r;
            // Each side on its own: a sum would cancel what the two sides play in opposite phase.
            real[i] = l * hann[i];
            imag[i] = 0;
            realRight[i] = r * hann[i];
            imagRight[i] = 0;
        }
        picture.peakLeft = Math.min(1f, peakL);
        picture.peakRight = Math.min(1f, peakR);
        picture.rmsLeft = (float) Math.min(1, Math.sqrt(sumL / n));
        picture.rmsRight = (float) Math.min(1, Math.sqrt(sumR / n));
        fft.forward(real, imag);
        fft.forward(realRight, imagRight);
        // A full-scale sine under a Hann window peaks at n/4 in its bin: that is 0 dB.
        double fullScale = n / 4.0;
        double floor = settings.floorDb();
        int half = n / 2;
        double bass = 0;
        double middle = 0;
        double treble = 0;
        int bassCount = 0;
        int middleCount = 0;
        int trebleCount = 0;
        double binHz = (double) sampleRate / n;
        for (int b = 0; b < picture.bands.length; b++) {
            double magnitude = bandMagnitude(bandLow[b], bandHigh[b], half) / fullScale;
            double db = 20 * Math.log10(Math.max(magnitude, 1e-12));
            float value = (float) Math.max(0, Math.min(1, (db - floor) / -floor));
            picture.rawBands[b] = value;
            float was = picture.bands[b];
            double share = value > was ? settings.attack() : settings.release();
            picture.bands[b] = (float) (was + (value - was) * share);
            double centreHz = Math.sqrt(bandLow[b] * bandHigh[b]) * binHz;
            if (centreHz < settings.bassHz()) {
                bass += value;
                bassCount++;
            } else if (centreHz < settings.trebleHz()) {
                middle += value;
                middleCount++;
            } else {
                treble += value;
                trebleCount++;
            }
        }
        picture.bass = (float) (bassCount == 0 ? 0 : bass / bassCount);
        picture.middle = (float) (middleCount == 0 ? 0 : middle / middleCount);
        picture.treble = (float) (trebleCount == 0 ? 0 : treble / trebleCount);
        beat(seconds);
        return picture;
    }

    /** A beat: the low end jumps well above its running average, not too soon after the last one. */
    private void beat(double seconds) {
        double low = picture.bass;
        picture.beat *= (float) settings.beatDecay();
        if (runningBass < 0) {
            runningBass = low;
            return;
        }
        if (picture.sounding && low > runningBass * settings.beatRatio()
                && seconds - lastBeatSeconds >= settings.beatGap()) {
            picture.beat = 1f;
            picture.beats++;
            lastBeatSeconds = seconds;
        }
        runningBass += (low - runningBass) * settings.beatAverage();
    }

    /** The largest magnitude between two fractional bins; read between the two nearest bins if the band is narrower. */
    private double bandMagnitude(double low, double high, int half) {
        int first = (int) Math.ceil(low);
        int last = (int) Math.floor(high);
        double best = 0;
        for (int k = Math.max(1, first); k <= Math.min(half - 1, last); k++) {
            best = Math.max(best, magnitude(k));
        }
        if (first > last) {
            double centre = (low + high) / 2;
            int below = Math.max(1, Math.min(half - 2, (int) Math.floor(centre)));
            double t = Math.max(0, Math.min(1, centre - below));
            best = magnitude(below) * (1 - t) + magnitude(below + 1) * t;
        }
        return best;
    }

    /** The magnitude of a bin over both sides: the root of their mean power, so a mono signal reads as one side. */
    private double magnitude(int k) {
        double left = real[k] * real[k] + imag[k] * imag[k];
        double right = realRight[k] * realRight[k] + imagRight[k] * imagRight[k];
        return Math.sqrt((left + right) * 0.5);
    }
}
