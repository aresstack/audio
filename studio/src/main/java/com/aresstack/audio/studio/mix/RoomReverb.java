package com.aresstack.audio.studio.mix;

/**
 * The ring of the room: all sources send into it together, the way one room answers
 * everything played in it. Eight feedback delays side by side and four all-pass
 * stages per channel (the well-known Schroeder/Moorer arrangement); the feedback is
 * set so that the ring falls by 60 dB in the wanted time, and a low-pass in each
 * loop makes the high notes die away sooner, as walls do.
 */
final class RoomReverb {
    private static final int[] COMBS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALL_PASSES = {556, 441, 341, 225};
    private static final int SPREAD = 23;

    private final Channel left;
    private final Channel right;
    private final int preDelay;

    RoomReverb(int sampleRate, double seconds, double damping, double preDelaySeconds) {
        left = new Channel(sampleRate, seconds, damping, 0);
        right = new Channel(sampleRate, seconds, damping, SPREAD);
        preDelay = (int) Math.round(preDelaySeconds * sampleRate);
    }

    /** Adds the ring of what was sent to the output; the send buffers are used up. */
    void process(float[] sendLeft, float[] sendRight, float[] outLeft, float[] outRight, float gain) {
        int frames = outLeft.length;
        for (int i = 0; i + preDelay < frames; i++) {
            // Each side rings mostly for itself, with a little of the other, so that the room stays wide.
            float inLeft = i < sendLeft.length ? sendLeft[i] : 0;
            float inRight = i < sendRight.length ? sendRight[i] : 0;
            float wetLeft = left.next(inLeft * 0.8f + inRight * 0.2f);
            float wetRight = right.next(inRight * 0.8f + inLeft * 0.2f);
            outLeft[i + preDelay] += wetLeft * gain;
            outRight[i + preDelay] += wetRight * gain;
        }
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
