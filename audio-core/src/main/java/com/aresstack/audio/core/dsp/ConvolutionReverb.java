package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.conv.ImpulseResponse;
import com.aresstack.audio.core.conv.PartitionedConvolver;

/**
 * The ring of the room from an impulse response (work package D4): what was sent to the room is
 * convolved with the response, the left side with its left half, the right side with its right
 * half. As in {@link RoomReverb} each side rings mostly for itself with a little of the other,
 * so the room stays wide. The convolution adds no latency; the pre-delay is part of the response.
 *
 * <p>The response is brought to the rate of the mix in {@link #prepare}, the only place that
 * allocates.
 */
public final class ConvolutionReverb implements RoomBus {
    private final ImpulseResponse response;
    private final int head;
    private final int largestPartition;
    private PartitionedConvolver convolver;
    private float[] inLeft = new float[0];
    private float[] inRight = new float[0];
    private float[] outLeft = new float[0];
    private float[] outRight = new float[0];

    public ConvolutionReverb(ImpulseResponse response) {
        this(response, PartitionedConvolver.DEFAULT_HEAD, PartitionedConvolver.DEFAULT_LARGEST_PARTITION);
    }

    public ConvolutionReverb(ImpulseResponse response, int head, int largestPartition) {
        if (response == null) {
            throw new IllegalArgumentException("A convolution reverb needs an impulse response.");
        }
        this.response = response;
        this.head = head;
        this.largestPartition = largestPartition;
    }

    public ImpulseResponse response() {
        return response;
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        ImpulseResponse fitting = response.at(sampleRate);
        convolver = new PartitionedConvolver(fitting.left(), fitting.right(), head, largestPartition);
        convolver.prepare(maxBlockFrames);
        inLeft = new float[maxBlockFrames];
        inRight = new float[maxBlockFrames];
        outLeft = new float[maxBlockFrames];
        outRight = new float[maxBlockFrames];
    }

    @Override
    public void process(AudioBlock send, int frames, AudioBlock bus, float gain) {
        float[] sendLeft = send.left;
        float[] sendRight = send.right;
        for (int i = 0; i < frames; i++) {
            inLeft[i] = sendLeft[i] * 0.8f + sendRight[i] * 0.2f;
            inRight[i] = sendRight[i] * 0.8f + sendLeft[i] * 0.2f;
        }
        convolver.process(inLeft, inRight, outLeft, outRight, frames);
        float[] busLeft = bus.left;
        float[] busRight = bus.right;
        for (int i = 0; i < frames; i++) {
            busLeft[i] += outLeft[i] * gain;
            busRight[i] += outRight[i] * gain;
        }
    }

    @Override
    public int latencyFrames() {
        return 0;
    }
}
