package com.aresstack.audio.core.conv;

import java.util.SplittableRandom;

/**
 * An impulse response made from the numbers that describe a room, without a recording: noise,
 * different on both ears, that falls by 60 dB in the wanted time, whose heights die away sooner
 * the more the walls damp, after a pre-delay and a short build-up. The same numbers always give
 * the same response (fixed seed), so a mix stays reproducible.
 *
 * <p>A measured response of a real room sounds more real still; it is read with
 * {@link com.aresstack.audio.core.wav.ImpulseResponseFile}. Free collections are listed in the
 * documentation of work package D4 together with their licences.
 */
public final class SyntheticRoom {
    private SyntheticRoom() {
    }

    /**
     * @param seconds         time to fall by 60 dB
     * @param damping         0 (bright) to 1 (dull walls)
     * @param preDelaySeconds silence before the first reflection of the ring
     * @param sampleRate      rate of the response
     * @param seed            the noise; the same seed gives the same response
     */
    public static ImpulseResponse generate(double seconds, double damping, double preDelaySeconds, int sampleRate,
                                           long seed) {
        if (!(seconds > 0) || !Double.isFinite(seconds) || !(damping >= 0 && damping <= 1)
                || !(preDelaySeconds >= 0) || sampleRate <= 0) {
            throw new IllegalArgumentException("A room rings for a positive time, with damping 0..1.");
        }
        int pre = (int) Math.round(preDelaySeconds * sampleRate);
        // Long enough to fall by about 70 dB.
        int ring = (int) Math.ceil(seconds * 1.15 * sampleRate);
        int frames = pre + ring;
        float[] left = new float[frames];
        float[] right = new float[frames];
        SplittableRandom random = new SplittableRandom(seed);
        double decay = Math.log(1000) / (seconds * sampleRate);
        int build = Math.max(1, (int) (0.008 * sampleRate));
        // The heights fall faster: a one-pole low-pass whose corner sinks over the ring.
        double startCorner = 16000 - 6000 * damping;
        double endCorner = 1500 + 2000 * (1 - damping);
        double lowLeft = 0;
        double lowRight = 0;
        for (int i = 0; i < ring; i++) {
            double t = i / (double) ring;
            double corner = startCorner * Math.pow(endCorner / startCorner, Math.min(1, t * (0.6 + damping)));
            double a = Math.exp(-2 * Math.PI * Math.min(corner, 0.45 * sampleRate) / sampleRate);
            double envelope = Math.exp(-decay * i) * Math.min(1, (i + 1) / (double) build);
            lowLeft = (1 - a) * (random.nextDouble() * 2 - 1) + a * lowLeft;
            lowRight = (1 - a) * (random.nextDouble() * 2 - 1) + a * lowRight;
            left[pre + i] = (float) (lowLeft * envelope);
            right[pre + i] = (float) (lowRight * envelope);
        }
        return new ImpulseResponse(left, right, sampleRate);
    }
}
