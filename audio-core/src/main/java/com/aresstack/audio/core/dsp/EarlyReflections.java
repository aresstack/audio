package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.BiquadCoefficients;
import java.util.List;

/**
 * The first echoes of a channel from the walls of the room: the channel is folded to mono,
 * dulled by a low-pass (walls swallow the heights) and added to a bus once per wall, each time
 * delayed and placed on its own.
 *
 * <p>Echoes go on after the channel has ended, until the longest delay has passed. The echoes
 * of a frame are only added once the frame itself has been heard; the result therefore does
 * not depend on the block size.
 */
public final class EarlyReflections {
    /** One wall: how many frames later its echo arrives, and how loud it is on each side. */
    public record Tap(int delayFrames, float toLeft, float toRight) {
        public Tap {
            if (delayFrames < 0) {
                throw new IllegalArgumentException("An echo cannot come before the sound.");
            }
            if (!Float.isFinite(toLeft) || !Float.isFinite(toRight)) {
                throw new IllegalArgumentException("The gains of an echo are finite.");
            }
        }
    }

    private final Tap[] taps;
    private final int longest;
    private final BiquadFilter lowPass;
    private float[] history = new float[0];
    private float[] mono = new float[0];
    /** Frames that went through, heard or not. */
    private long position;
    /** Frames of the channel that were heard, i.e. that are in the history. */
    private long heard;
    private boolean ended;

    public EarlyReflections(List<Tap> taps, BiquadCoefficients lowPass) {
        this.taps = taps.toArray(new Tap[0]);
        int most = 0;
        for (Tap tap : this.taps) {
            most = Math.max(most, tap.delayFrames());
        }
        longest = most;
        this.lowPass = new BiquadFilter(lowPass);
    }

    public void prepare(int sampleRate, int maxBlockFrames) {
        history = new float[longest + maxBlockFrames];
        mono = new float[maxBlockFrames];
        lowPass.reset();
        position = 0;
        heard = 0;
        ended = false;
    }

    /**
     * Takes the next {@code frames} frames of the channel, of which the first {@code heard} carry
     * its sound (fewer than {@code frames} once it has ended), and adds the echoes that fall into
     * these frames to the bus.
     */
    public void process(AudioBlock channel, int heard, int frames, AudioBlock bus) {
        int size = history.length;
        if (heard > 0 && !ended) {
            float[] left = channel.left;
            float[] right = channel.right;
            for (int i = 0; i < heard; i++) {
                mono[i] = (left[i] + right[i]) * 0.5f;
            }
            lowPass.process(mono, 0, heard);
            int at = (int) (this.heard % size);
            for (int i = 0; i < heard; i++) {
                history[at] = mono[i];
                at = at + 1 == size ? 0 : at + 1;
            }
            this.heard += heard;
        }
        if (heard < frames) {
            ended = true;
        }
        float[] busLeft = bus.left;
        float[] busRight = bus.right;
        for (Tap tap : taps) {
            float toLeft = tap.toLeft();
            float toRight = tap.toRight();
            // The frames i of this block whose source frame (position + i - delay) was heard.
            long first = Math.max(0, tap.delayFrames() - position);
            long last = Math.min(frames, this.heard + tap.delayFrames() - position);
            if (first >= last) {
                continue;
            }
            int at = (int) ((position + first - tap.delayFrames()) % size);
            for (int i = (int) first; i < last; i++) {
                float echo = history[at];
                busLeft[i] += echo * toLeft;
                busRight[i] += echo * toRight;
                at = at + 1 == size ? 0 : at + 1;
            }
        }
        position += frames;
    }

    /** Whether the channel has ended and its last echo has been added. */
    public boolean drained() {
        return ended && position >= heard + longest;
    }

    public int latencyFrames() {
        return 0;
    }
}
