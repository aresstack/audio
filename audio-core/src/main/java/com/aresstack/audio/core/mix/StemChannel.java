package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.dsp.EarlyReflections;
import com.aresstack.audio.core.dsp.StereoBiquad;
import com.aresstack.audio.core.dsp.StereoPanner;
import com.aresstack.audio.core.dsp.StereoStrip;
import java.io.IOException;

/**
 * One channel of a mix, from its source to the buses:
 *
 * <pre>
 * source ─► strip (level, width) ─┬─► send to the room bus
 *                                 └─► distance shelf ─┬─► panner ─► master bus
 *                                                     └─► early reflections ─► master bus
 * </pre>
 *
 * The room hears the channel before the distance takes its toll: the direct sound fades, the
 * ring stays. A channel is heard for at most {@code frames} frames from the start of the mix.
 *
 * <p>A channel either stands (a fixed {@link StereoPanner} and fixed sends) or moves: then a
 * {@link ChannelMotion} gives the gains of the panner and of the send over time, and the channel
 * glides between them every {@link ChannelMotion#RAMP} frames (work package D3). A motion that
 * does not move gives exactly the mix of the standing channel. The distance shelf and the early
 * reflections stay where the channel is set up; that they follow a moving source is part of the
 * later work on height and binaural sound.
 */
public final class StemChannel {
    private final AudioSource source;
    private final long frames;
    private final StereoStrip strip;
    private final float sendLeft;
    private final float sendRight;
    private final StereoBiquad shelf;
    private final StereoPanner panner;
    private final EarlyReflections reflections;
    private final ChannelMotion motion;
    private final float[] glide = new float[ChannelMotion.GAINS];
    private final float[] steps = new float[ChannelMotion.GAINS];
    private final float[] target = new float[ChannelMotion.GAINS];
    private float[][] gains;
    private AudioBlock block;
    private long position;
    private boolean ended;

    /**
     * @param source      the sound of the channel, at the rate of the mix
     * @param frames      how many frames of the source are heard at most
     * @param strip       level and width
     * @param sendLeft    share of the left side sent to the room bus
     * @param sendRight   share of the right side sent to the room bus
     * @param shelf       the dulling of the heights with distance, or {@code null}
     * @param panner      the place between left and right, with the distance gain
     * @param reflections the first echoes from the walls, or {@code null}
     */
    public StemChannel(AudioSource source, long frames, StereoStrip strip,
                       float sendLeft, float sendRight, StereoBiquad shelf, StereoPanner panner,
                       EarlyReflections reflections) {
        this(source, frames, strip, sendLeft, sendRight, shelf, panner, reflections, null);
        if (panner == null) {
            throw new IllegalArgumentException("A channel needs a source, a strip and a panner.");
        }
    }

    /**
     * A moving channel.
     *
     * @param source      the sound of the channel, at the rate of the mix
     * @param frames      how many frames of the source are heard at most
     * @param strip       level and width
     * @param shelf       the dulling of the heights with distance, or {@code null}
     * @param motion      where the direct sound goes and how much reaches the room, over time
     * @param reflections the first echoes from the walls, or {@code null}
     */
    public StemChannel(AudioSource source, long frames, StereoStrip strip, StereoBiquad shelf, ChannelMotion motion,
                       EarlyReflections reflections) {
        this(source, frames, strip, 0, 0, shelf, null, reflections, motion);
        if (motion == null) {
            throw new IllegalArgumentException("A moving channel needs its motion.");
        }
    }

    private StemChannel(AudioSource source, long frames, StereoStrip strip, float sendLeft, float sendRight,
                        StereoBiquad shelf, StereoPanner panner, EarlyReflections reflections, ChannelMotion motion) {
        if (source == null || strip == null) {
            throw new IllegalArgumentException("A channel needs a source, a strip and a panner.");
        }
        this.source = source;
        this.frames = Math.max(0, frames);
        this.strip = strip;
        this.sendLeft = sendLeft;
        this.sendRight = sendRight;
        this.shelf = shelf;
        this.panner = panner;
        this.reflections = reflections;
        this.motion = motion;
    }

    void prepare(int sampleRate, int maxBlockFrames) {
        block = new AudioBlock(maxBlockFrames);
        if (motion != null) {
            gains = new float[ChannelMotion.GAINS][maxBlockFrames];
        }
        source.prepare(sampleRate, maxBlockFrames);
        strip.prepare(sampleRate, maxBlockFrames);
        if (shelf != null) {
            shelf.prepare(sampleRate, maxBlockFrames);
        }
        if (reflections != null) {
            reflections.prepare(sampleRate, maxBlockFrames);
        }
        position = 0;
        ended = frames == 0;
    }

    /** Adds the next {@code count} frames of the channel to the master bus and, if there is one, to the send bus. */
    void render(int count, AudioBlock master, AudioBlock send) throws IOException {
        int heard = 0;
        if (!ended) {
            int wanted = (int) Math.min(count, frames - position);
            block.setFrames(wanted);
            heard = source.read(block);
            if (heard < wanted || position + heard >= frames) {
                ended = true;
            }
        }
        if (heard > 0) {
            strip.process(block, heard);
            if (motion == null) {
                if (send != null) {
                    block.addTo(send, heard, sendLeft, sendRight);
                }
                if (shelf != null) {
                    shelf.process(block, heard);
                }
                panner.addTo(block, heard, master);
            } else {
                glide(heard);
                if (send != null) {
                    add(block, heard, send, gains[ChannelMotion.SEND_LEFT], gains[ChannelMotion.SEND_RIGHT]);
                }
                if (shelf != null) {
                    shelf.process(block, heard);
                }
                add(block, heard, master, gains[ChannelMotion.TO_LEFT], gains[ChannelMotion.TO_RIGHT]);
            }
        }
        if (reflections != null && !reflections.drained()) {
            reflections.process(block, heard, count, master);
        }
        position += count;
    }

    /**
     * The gains of the next {@code count} frames from the motion: looked up on the grid of
     * {@link ChannelMotion#RAMP} frames from the start of the mix, in a straight line between.
     */
    private void glide(int count) {
        long at = position;
        for (int i = 0; i < count; i++, at++) {
            if ((at & (ChannelMotion.RAMP - 1)) == 0) {
                motion.gainsAt(at, glide);
                motion.gainsAt(at + ChannelMotion.RAMP, target);
                for (int k = 0; k < ChannelMotion.GAINS; k++) {
                    steps[k] = (target[k] - glide[k]) / ChannelMotion.RAMP;
                }
            }
            for (int k = 0; k < ChannelMotion.GAINS; k++) {
                gains[k][i] = glide[k];
                glide[k] += steps[k];
            }
        }
    }

    /** Adds the first {@code count} frames of the block to a bus, each side with its gain of the frame. */
    private static void add(AudioBlock block, int count, AudioBlock bus, float[] toLeft, float[] toRight) {
        float[] left = block.left;
        float[] right = block.right;
        float[] busLeft = bus.left;
        float[] busRight = bus.right;
        for (int i = 0; i < count; i++) {
            busLeft[i] += left[i] * toLeft[i];
            busRight[i] += right[i] * toRight[i];
        }
    }

    void close() throws IOException {
        source.close();
    }
}
