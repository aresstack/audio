package com.aresstack.audio.core;

import java.util.Random;

/** Signals for the tests of the core. */
public final class TestSignals {
    private TestSignals() {
    }

    public static float[] noise(int frames, long seed, double height) {
        Random random = new Random(seed);
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (float) ((random.nextDouble() * 2 - 1) * height);
        }
        return samples;
    }

    public static float[] sine(int frames, double frequency, int rate, double height) {
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (float) (height * Math.sin(2 * Math.PI * frequency * i / rate));
        }
        return samples;
    }

    public static double rms(float[] samples, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += samples[i] * (double) samples[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }

    /** An endless sine on both sides that is made while it is read: a source of any length without memory. */
    public static final class SineSource implements AudioSource {
        private final double step;
        private final long frames;
        private long position;

        public SineSource(double frequency, int rate, long frames) {
            this.step = 2 * Math.PI * frequency / rate;
            this.frames = frames;
        }

        @Override
        public void prepare(int sampleRate, int maxBlockFrames) {
            position = 0;
        }

        @Override
        public int read(AudioBlock block) {
            int count = (int) Math.min(block.frames(), frames - position);
            for (int i = 0; i < count; i++) {
                float value = (float) (0.4 * Math.sin(step * (position + i)));
                block.left[i] = value;
                block.right[i] = value;
            }
            block.clear(count, block.frames() - count);
            position += count;
            return count;
        }
    }
}
