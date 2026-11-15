package com.aresstack.audio.core.live;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.dsp.BiquadFilter;

/**
 * The first echoes of a channel from the walls, for a source that may move while it plays. Like
 * {@link com.aresstack.audio.core.dsp.EarlyReflections}: the channel folded to mono, dulled by a
 * low-pass and added to a bus once per wall, each time delayed and placed on its own.
 *
 * <p>When the source moves, the gains of each echo glide, and so does its delay:
 * <ul>
 *   <li>A small change, as when a source is dragged (at most {@link #GLIDE_SHARE} of the glide
 *       time per glide), is <b>glided</b>: the echo is read between two samples, so the wall moves
 *       continuously, with the slight change of pitch a moving source has (Doppler). Nothing is
 *       mixed with a delayed copy of itself, so a tone does not partly cancel out.</li>
 *   <li>A large change (a jump across the stage) would sweep the pitch audibly; it is
 *       <b>crossfaded</b> from the old delay to the new one instead. A change that arrives during
 *       a crossfade waits until it has ended.</li>
 * </ul>
 * While nothing moves, the echo is read at a whole delay exactly as {@code EarlyReflections}
 * reads it. The history holds as many frames as the longest echo the channel was made for;
 * longer delays are shortened to it.
 */
final class LiveReflections {
    /** The largest delay change, as a share of the glide time, that is glided rather than crossfaded (≈ 1 semitone). */
    static final double GLIDE_SHARE = 0.06;

    private final int walls;
    private final int capacity;
    private final BiquadFilter lowPass;
    /** The delay now, in frames; between two whole frames while it glides. */
    private final double[] delay;
    private final double[] step;
    private final int[] glideLeft;
    /** Where the delay is going: whole frames. */
    private final int[] target;
    /** Where a glide ends: the target, or a place between two frames for a source that moves with the piece. */
    private final double[] goal;
    private final double[] oldDelay;
    private final int[] fadeLeft;
    /** A delay that arrived while a fade was running; it is taken when that fade ends. -1 for none. */
    private final int[] pending;
    private final Ramp[] toLeft;
    private final Ramp[] toRight;
    private float[] history = new float[0];
    private float[] mono = new float[0];
    private long written;
    private int glideFrames;

    LiveReflections(int walls, int capacityFrames, BiquadCoefficients lowPass) {
        if (walls <= 0) {
            throw new IllegalArgumentException("Echoes need at least one wall.");
        }
        this.walls = walls;
        this.capacity = Math.max(0, capacityFrames);
        this.lowPass = new BiquadFilter(lowPass);
        delay = new double[walls];
        step = new double[walls];
        glideLeft = new int[walls];
        target = new int[walls];
        goal = new double[walls];
        oldDelay = new double[walls];
        fadeLeft = new int[walls];
        pending = new int[walls];
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

    void prepare(int maxBlockFrames, int glideFrames, ChannelParameters start) {
        // one frame more than the longest delay: a delay between two frames reads the one behind it too
        history = new float[capacity + maxBlockFrames + 1];
        mono = new float[maxBlockFrames];
        this.glideFrames = Math.max(1, glideFrames);
        lowPass.reset();
        written = 0;
        jumpTo(start);
    }

    /** Takes the echoes of these parameters at once. */
    void jumpTo(ChannelParameters parameters) {
        for (int i = 0; i < walls; i++) {
            target[i] = Math.min(capacity, parameters.echoDelay(i));
            goal[i] = target[i];
            delay[i] = target[i];
            oldDelay[i] = target[i];
            glideLeft[i] = 0;
            fadeLeft[i] = 0;
            pending[i] = -1;
            toLeft[i].reset(parameters.echoLeft(i));
            toRight[i].reset(parameters.echoRight(i));
        }
    }

    /** Glides to the echoes of these parameters, starting with the next frame. */
    void glideTo(ChannelParameters parameters, int frames) {
        for (int i = 0; i < walls; i++) {
            change(i, Math.min(capacity, parameters.echoDelay(i)));
            toLeft[i].glideTo(parameters.echoLeft(i), frames);
            toRight[i].glideTo(parameters.echoRight(i), frames);
        }
    }

    /** Takes the echoes of a moving source at once. */
    void jumpTo(com.aresstack.audio.core.mix.Seat seat) {
        for (int i = 0; i < walls; i++) {
            goal[i] = Math.max(0, Math.min(capacity, seat.echoDelay[i]));
            target[i] = (int) Math.round(goal[i]);
            delay[i] = goal[i];
            oldDelay[i] = target[i];
            glideLeft[i] = 0;
            fadeLeft[i] = 0;
            pending[i] = -1;
            toLeft[i].reset(seat.echoLeft[i]);
            toRight[i].reset(seat.echoRight[i]);
        }
    }

    /** Glides to the echoes of a moving source, starting with the next frame. */
    void glideTo(com.aresstack.audio.core.mix.Seat seat, int frames) {
        for (int i = 0; i < walls; i++) {
            double wanted = Math.max(0, Math.min(capacity, seat.echoDelay[i]));
            if (fadeLeft[i] > 0) {
                // A crossfade from before the movement ends first; then the echo goes on from here.
                pending[i] = (int) Math.round(wanted);
            } else if (wanted != delay[i] || glideLeft[i] > 0) {
                // The wall moves with the source, between samples, as in the file.
                goal[i] = wanted;
                target[i] = (int) Math.round(wanted);
                glideLeft[i] = Math.max(1, frames);
                step[i] = (wanted - delay[i]) / glideLeft[i];
            }
            toLeft[i].glideTo(seat.echoLeft[i], frames);
            toRight[i].glideTo(seat.echoRight[i], frames);
        }
    }

    /** Starts the way of one wall's delay to a new whole number of frames. */
    private void change(int wall, int wanted) {
        if (fadeLeft[wall] > 0) {
            pending[wall] = wanted == target[wall] ? -1 : wanted;
            return;
        }
        if (wanted == target[wall] && (delay[wall] == wanted || (glideLeft[wall] > 0 && goal[wall] == wanted))) {
            return;
        }
        target[wall] = wanted;
        goal[wall] = wanted;
        double distance = wanted - delay[wall];
        if (Math.abs(distance) <= GLIDE_SHARE * glideFrames) {
            glideLeft[wall] = glideFrames;
            step[wall] = distance / glideFrames;
        } else {
            oldDelay[wall] = delay[wall];
            delay[wall] = wanted;
            glideLeft[wall] = 0;
            fadeLeft[wall] = glideFrames;
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
        int base = (int) (written % size);
        for (int wall = 0; wall < walls; wall++) {
            Ramp gainLeft = toLeft[wall];
            Ramp gainRight = toRight[wall];
            if (fadeLeft[wall] == 0 && glideLeft[wall] == 0 && delay[wall] == target[wall]
                    && gainLeft.settled() && gainRight.settled()) {
                // Nothing moves: a whole delay, exactly as EarlyReflections reads it.
                int read = wrap(base - target[wall], size);
                float gl = gainLeft.value();
                float gr = gainRight.value();
                for (int i = 0; i < count; i++) {
                    float echo = history[read];
                    busLeft[i] += echo * gl;
                    busRight[i] += echo * gr;
                    read = read + 1 == size ? 0 : read + 1;
                }
                continue;
            }
            float share = 1f / glideFrames;
            for (int i = 0; i < count; i++) {
                int now = base + i;
                if (glideLeft[wall] > 0) {
                    if (--glideLeft[wall] == 0) {
                        delay[wall] = goal[wall];
                    } else {
                        delay[wall] += step[wall];
                    }
                }
                float echo = read(now, delay[wall], size);
                if (fadeLeft[wall] > 0) {
                    float in = 1f - fadeLeft[wall] * share;
                    echo = read(now, oldDelay[wall], size) * (1f - in) + echo * in;
                    if (--fadeLeft[wall] == 0) {
                        oldDelay[wall] = delay[wall];
                        if (pending[wall] >= 0) {
                            int next = pending[wall];
                            pending[wall] = -1;
                            change(wall, next);
                        }
                    }
                }
                busLeft[i] += echo * gainLeft.next();
                busRight[i] += echo * gainRight.next();
            }
        }
        written += count;
    }

    /** The history {@code delay} frames before frame {@code now} of the ring, between two samples if need be. */
    private float read(int now, double delay, int size) {
        int whole = (int) delay;
        float part = (float) (delay - whole);
        float near = history[wrap(now - whole, size)];
        if (part == 0f) {
            return near;
        }
        return near + (history[wrap(now - whole - 1, size)] - near) * part;
    }

    /** An index into the history; {@code at} lies within one length below or above it. */
    private static int wrap(int at, int size) {
        return at < 0 ? at + size : at >= size ? at - size : at;
    }
}
