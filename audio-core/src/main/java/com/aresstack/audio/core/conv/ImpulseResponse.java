package com.aresstack.audio.core.conv;

import java.util.Arrays;

/**
 * How a room answers a click, for the left and the right ear, at a sample rate: the input of a
 * convolution reverb. Immutable; the arrays are copied in and out.
 */
public final class ImpulseResponse {
    private final float[] left;
    private final float[] right;
    private final int sampleRate;

    public ImpulseResponse(float[] left, float[] right, int sampleRate) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            throw new IllegalArgumentException("An impulse response has two sides of the same, non-zero length.");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("An impulse response needs its sample rate.");
        }
        for (int i = 0; i < left.length; i++) {
            if (!Float.isFinite(left[i]) || !Float.isFinite(right[i])) {
                throw new IllegalArgumentException("An impulse response holds finite numbers only.");
            }
        }
        this.left = left.clone();
        this.right = right.clone();
        this.sampleRate = sampleRate;
    }

    public float[] left() {
        return left.clone();
    }

    public float[] right() {
        return right.clone();
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int length() {
        return left.length;
    }

    public double seconds() {
        return left.length / (double) sampleRate;
    }

    /** The energy of both sides together: the sum of all squared samples. */
    public double energy() {
        double sum = 0;
        for (int i = 0; i < left.length; i++) {
            sum += (double) left[i] * left[i] + (double) right[i] * right[i];
        }
        return sum;
    }

    /** The same response, scaled by a factor. */
    public ImpulseResponse scaled(double factor) {
        float[] l = left.clone();
        float[] r = right.clone();
        for (int i = 0; i < l.length; i++) {
            l[i] = (float) (l[i] * factor);
            r[i] = (float) (r[i] * factor);
        }
        return new ImpulseResponse(l, r, sampleRate);
    }

    /** The same response with this energy (unchanged if it is silent). */
    public ImpulseResponse withEnergy(double wanted) {
        double own = energy();
        return own > 0 ? scaled(Math.sqrt(wanted / own)) : this;
    }

    /** The response cut to at most this many samples, with a short fade at the end. */
    public ImpulseResponse cut(int frames) {
        if (frames >= left.length) {
            return this;
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("A response keeps at least one sample.");
        }
        float[] l = Arrays.copyOf(left, frames);
        float[] r = Arrays.copyOf(right, frames);
        int fade = Math.min(frames, Math.max(1, sampleRate / 100));
        for (int i = 0; i < fade; i++) {
            float g = (float) (0.5 + 0.5 * Math.cos(Math.PI * (i + 1) / fade));
            l[frames - fade + i] *= g;
            r[frames - fade + i] *= g;
        }
        return new ImpulseResponse(l, r, sampleRate);
    }

    /**
     * The response at another sample rate, by linear interpolation, keeping its energy per second
     * (a response is a sequence of weights per sample: at twice the rate each weight halves).
     */
    public ImpulseResponse at(int rate) {
        if (rate == sampleRate) {
            return this;
        }
        if (rate <= 0) {
            throw new IllegalArgumentException("A sample rate is positive.");
        }
        int frames = (int) Math.max(1, Math.round(left.length * (double) rate / sampleRate));
        float[] l = new float[frames];
        float[] r = new float[frames];
        double step = sampleRate / (double) rate;
        double scale = sampleRate / (double) rate;
        for (int i = 0; i < frames; i++) {
            double at = i * step;
            int k = (int) at;
            double f = at - k;
            float l0 = k < left.length ? left[k] : 0;
            float l1 = k + 1 < left.length ? left[k + 1] : 0;
            float r0 = k < right.length ? right[k] : 0;
            float r1 = k + 1 < right.length ? right[k + 1] : 0;
            l[i] = (float) ((l0 + (l1 - l0) * f) * scale);
            r[i] = (float) ((r0 + (r1 - r0) * f) * scale);
        }
        return new ImpulseResponse(l, r, rate);
    }
}
