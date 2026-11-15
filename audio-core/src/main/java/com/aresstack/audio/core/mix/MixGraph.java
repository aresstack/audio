package com.aresstack.audio.core.mix;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.dsp.RoomReverb;
import java.io.IOException;
import java.util.List;

/**
 * Channels on a stage in one room, pulled block by block:
 *
 * <pre>
 * channel 1..n ─► master bus ◄── room reverb ◄── send bus ◄── channel 1..n
 * </pre>
 *
 * The channels are added to the buses one after the other in the order given, then the ring of
 * the room; the order matters for the last bit of a float sum and is therefore fixed. The
 * graph delivers exactly {@code frames} frames and then ends. It never holds more than one block
 * per channel, whatever the length of the piece.
 */
public final class MixGraph implements AudioSource {
    private final StemChannel[] channels;
    private final RoomReverb room;
    private final float roomGain;
    private final long frames;
    private AudioBlock send;
    private long position;

    /**
     * @param channels the channels, in the order in which they are added
     * @param room     the shared room, or {@code null} for a dry mix (then nothing is sent)
     * @param roomGain how loud the ring of the room is
     * @param frames   how long the mix is, including the ring at the end
     */
    public MixGraph(List<StemChannel> channels, RoomReverb room, float roomGain, long frames) {
        this.channels = channels.toArray(new StemChannel[0]);
        this.room = room;
        this.roomGain = roomGain;
        this.frames = Math.max(0, frames);
    }

    public long frames() {
        return frames;
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        for (StemChannel channel : channels) {
            channel.prepare(sampleRate, maxBlockFrames);
        }
        if (room != null) {
            send = new AudioBlock(maxBlockFrames);
            room.prepare(sampleRate, maxBlockFrames);
        }
        position = 0;
    }

    @Override
    public int read(AudioBlock block) throws IOException {
        int wanted = block.frames();
        int count = (int) Math.min(wanted, frames - position);
        block.clear(0, wanted);
        if (count == 0) {
            return 0;
        }
        if (send != null) {
            send.clear(0, count);
        }
        for (StemChannel channel : channels) {
            channel.render(count, block, send);
        }
        if (room != null) {
            room.process(send, count, block, roomGain);
        }
        position += count;
        return count;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (StemChannel channel : channels) {
            try {
                channel.close();
            } catch (IOException failed) {
                if (first == null) {
                    first = failed;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
