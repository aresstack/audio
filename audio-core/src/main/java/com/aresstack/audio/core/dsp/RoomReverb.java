package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;

/**
 * The ring of the room: all channels send into it together, the way one room answers
 * everything played in it. Eight feedback delays side by side and four all-pass stages per
 * side (the well-known Schroeder/Moorer arrangement); the feedback is set so that the ring falls
 * by 60 dB in the wanted time, and a low-pass in each loop makes the high notes die away sooner,
 * as walls do. The ring starts after a pre-delay.
 */
public final class RoomReverb implements RoomBus {
    private static final int[] COMBS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALL_PASSES = {556, 441, 341, 225};
    private static final int SPREAD = 23;

    /** How the room rings: time to fall by 60 dB, damping of the heights 0..1, pre-delay. */
    public record Settings(double seconds, double damping, double preDelaySeconds) {
        public Settings {
            if (!(seconds > 0) || !Double.isFinite(seconds)) {
                throw new IllegalArgumentException("A room rings for a positive time.");
            }
            if (!(damping >= 0 && damping <= 1)) {
                throw new IllegalArgumentException("Damping lies between 0 and 1.");
            }
            if (!(preDelaySeconds >= 0) || !Double.isFinite(preDelaySeconds)) {
                throw new IllegalArgumentException("A pre-delay is not negative.");
            }
        }
    }

    private final Settings settings;
    private Channel left;
    private Channel right;
    private int preDelay;
    private float[] delayedLeft = new float[1];
    private float[] delayedRight = new float[1];
    private long position;

    public RoomReverb(Settings settings) {
        this.settings = settings;
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        left = new Channel(sampleRate, settings.seconds(), settings.damping(), 0);
        right = new Channel(sampleRate, settings.seconds(), settings.damping(), SPREAD);
        preDelay = (int) Math.round(settings.preDelaySeconds() * sampleRate);
        delayedLeft = new float[preDelay + 1];
        delayedRight = new float[preDelay + 1];
        position = 0;
    }

    /** Adds the ring of the first {@code frames} frames that were sent to the bus, scaled by {@code gain}. */
    @Override
    public void process(AudioBlock send, int frames, AudioBlock bus, float gain) {
        int size = delayedLeft.length;
        int at = (int) (position % size);
        float[] sendLeft = send.left;
        float[] sendRight = send.right;
        float[] busLeft = bus.left;
        float[] busRight = bus.right;
        long now = position;
        for (int i = 0; i < frames; i++, now++) {
            delayedLeft[at] = sendLeft[i];
            delayedRight[at] = sendRight[i];
            at = at + 1 == size ? 0 : at + 1;
            if (now >= preDelay) {
                // What was sent preDelay frames ago; with the pre-delay the ring buffer is exactly one frame longer.
                float inLeft = delayedLeft[at];
                float inRight = delayedRight[at];
                // Each side rings mostly for itself, with a little of the other, so that the room stays wide.
                float wetLeft = left.next(inLeft * 0.8f + inRight * 0.2f);
                float wetRight = right.next(inRight * 0.8f + inLeft * 0.2f);
                busLeft[i] += wetLeft * gain;
                busRight[i] += wetRight * gain;
            }
        }
        position = now;
    }

    @Override
    public int latencyFrames() {
        return 0;
    }

    private static final class Channel {
        private final float[][] combs = new float[COMBS.length][];
        private final int[] combAt = new int[COMBS.length];
        private final float[] feedback = new float[COMBS.length];
        private final float[] dulled = new float[COMBS.length];
        private final float[][] passes = new float[ALL_PASSES.length][];
        private final int[] passAt = new int[ALL_PASSES.length];
        private final float damp;

        Channel(int sampleRate, double seconds, double damping, int spread) {
            double scale = sampleRate / 44100.0;
            for (int i = 0; i < COMBS.length; i++) {
                int length = Math.max(8, (int) Math.round((COMBS[i] + spread) * scale));
                combs[i] = new float[length];
                feedback[i] = (float) Math.min(0.985, Math.pow(10, -3.0 * length / (seconds * sampleRate)));
            }
            for (int i = 0; i < ALL_PASSES.length; i++) {
                passes[i] = new float[Math.max(4, (int) Math.round((ALL_PASSES[i] + spread) * scale))];
            }
            damp = (float) (0.1 + 0.6 * damping);
        }

        float next(float in) {
            float out = 0;
            float fed = in * 0.05f;
            for (int i = 0; i < combs.length; i++) {
                float[] line = combs[i];
                int at = combAt[i];
                float delayed = line[at];
                out += delayed;
                dulled[i] = delayed * (1 - damp) + dulled[i] * damp;
                if (Math.abs(dulled[i]) < 1e-20f) {
                    dulled[i] = 0;
                }
                line[at] = fed + dulled[i] * feedback[i];
                combAt[i] = at + 1 == line.length ? 0 : at + 1;
            }
            for (int i = 0; i < passes.length; i++) {
                float[] line = passes[i];
                int at = passAt[i];
                float delayed = line[at];
                float through = delayed - out;
                line[at] = out + delayed * 0.5f;
                passAt[i] = at + 1 == line.length ? 0 : at + 1;
                out = through;
            }
            return out;
        }
    }
}
