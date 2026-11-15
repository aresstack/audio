package com.aresstack.audio.core.live;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.dsp.BiquadFilter;

/**
 * The first echoes of a channel from the walls, for a source that may move while it plays. Like
 * {@link com.aresstack.audio.core.dsp.EarlyReflections}: the channel folded to mono, dulled by a
 * low-pass and added to a bus once per wall, each time delayed and placed on its own.
 *
 * <p>When the source moves, the gains of each echo glide, and a changed delay is not jumped to
 * (that would cut the echo and click) but crossfaded: for a short while the echo is read at the
 * old and the new delay at once, the old fading out as the new fades in. The history holds as many
 * frames as the longest echo the channel was made for; longer delays are shortened to it.
 */
final class LiveReflections {
    private final int walls;
    private final int capacity;
    private final BiquadFilter lowPass;
    private final int[] delay;
    private final int[] oldDelay;
    private final int[] fadeLeft;
    private final Ramp[] toLeft;
    private final Ramp[] toRight;
    private float[] history = new float[0];
    private float[] mono = new float[0];
    private long written;
    private int fadeFrames;

    LiveReflections(int walls, int capacityFrames, BiquadCoefficients lowPass) {
        if (walls <= 0) {
            throw new IllegalArgumentException("Echoes need at least one wall.");
        }
        this.walls = walls;
        this.capacity = Math.max(0, capacityFrames);
        this.lowPass = new BiquadFilter(lowPass);
        delay = new int[walls];
        oldDelay = new int[walls];
        fadeLeft = new int[walls];
        toLeft = new Ramp[walls];
        toRight = new Ramp[walls];
        for (int i = 0; i < walls; i++) {
            toLeft[i] = new Ramp(0);
            toRight[i] = new Ramp(0);
        }
    }

    int walls() {
        return walls;
    }

    void prepare(int maxBlockFrames, int fadeFrames, ChannelParameters start) {
        history = new float[capacity + maxBlockFrames];
        mono = new float[maxBlockFrames];
        this.fadeFrames = Math.max(1, fadeFrames);
        lowPass.reset();
        written = 0;
        jumpTo(start);
    }

    /** Takes the echoes of these parameters at once. */
    void jumpTo(ChannelParameters parameters) {
        for (int i = 0; i < walls; i++) {
            delay[i] = Math.min(capacity, parameters.echoDelay(i));
            oldDelay[i] = delay[i];
            fadeLeft[i] = 0;
            toLeft[i].reset(parameters.echoLeft(i));
            toRight[i].reset(parameters.echoRight(i));
        }
    }

    /** Glides to the echoes of these parameters, starting with the next frame. */
    void glideTo(ChannelParameters parameters, int frames) {
        for (int i = 0; i < walls; i++) {
            int wanted = Math.min(capacity, parameters.echoDelay(i));
            if (wanted != delay[i]) {
                // What is heard now (in the middle of a fade: the side that is fading in) fades out.
                oldDelay[i] = fadeLeft[i] > 0 && fadeLeft[i] > fadeFrames / 2 ? oldDelay[i] : delay[i];
                delay[i] = wanted;
                fadeLeft[i] = fadeFrames;
            }
            toLeft[i].glideTo(parameters.echoLeft(i), frames);
            toRight[i].glideTo(parameters.echoRight(i), frames);
        }
    }

    /** Forgets what was heard (after a seek): the old echoes would belong to another place in the piece. */
    void clear() {
        java.util.Arrays.fill(history, 0f);
        lowPass.reset();
    }

    /**
     * Takes the next {@code count} frames of the channel, of which the first {@code heard} carry
     * its sound, and adds the echoes that fall into them to the bus.
     */
    void process(AudioBlock channel, int heard, int count, AudioBlock bus) {
        int size = history.length;
        float[] left = channel.left;
        float[] right = channel.right;
        for (int i = 0; i < heard; i++) {
            mono[i] = (left[i] + right[i]) * 0.5f;
        }
        lowPass.process(mono, 0, heard);
        int at = (int) (written % size);
        for (int i = 0; i < count; i++) {
            history[at] = i < heard ? mono[i] : 0f;
            at = at + 1 == size ? 0 : at + 1;
        }
        float[] busLeft = bus.left;
        float[] busRight = bus.right;
        for (int wall = 0; wall < walls; wall++) {
            Ramp gainLeft = toLeft[wall];
            Ramp gainRight = toRight[wall];
            int read = Math.floorMod(written - delay[wall], size);
            if (fadeLeft[wall] == 0 && gainLeft.settled() && gainRight.settled()) {
                float gl = gainLeft.value();
                float gr = gainRight.value();
                for (int i = 0; i < count; i++) {
                    float echo = history[read];
                    busLeft[i] += echo * gl;
                    busRight[i] += echo * gr;
                    read = read + 1 == size ? 0 : read + 1;
                }
            } else {
                int old = Math.floorMod(written - oldDelay[wall], size);
                int fade = fadeLeft[wall];
                float share = 1f / fadeFrames;
                for (int i = 0; i < count; i++) {
                    float echo = history[read];
                    if (fade > 0) {
                        float in = 1f - fade * share;
                        echo = history[old] * (1f - in) + echo * in;
                        fade--;
                    }
                    busLeft[i] += echo * gainLeft.next();
                    busRight[i] += echo * gainRight.next();
                    read = read + 1 == size ? 0 : read + 1;
                    old = old + 1 == size ? 0 : old + 1;
                }
                fadeLeft[wall] = fade;
                if (fade == 0) {
                    oldDelay[wall] = delay[wall];
                }
            }
        }
        written += count;
    }
}
