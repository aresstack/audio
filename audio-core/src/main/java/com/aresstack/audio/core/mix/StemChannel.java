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
        if (source == null || strip == null || panner == null) {
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
    }

    void prepare(int sampleRate, int maxBlockFrames) {
        block = new AudioBlock(maxBlockFrames);
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
            if (send != null) {
                block.addTo(send, heard, sendLeft, sendRight);
            }
            if (shelf != null) {
                shelf.process(block, heard);
            }
            panner.addTo(block, heard, master);
        }
        if (reflections != null && !reflections.drained()) {
            reflections.process(block, heard, count, master);
        }
        position += count;
    }

    void close() throws IOException {
        source.close();
    }
}
