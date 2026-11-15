package com.aresstack.audio.core.live;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.AudioSource;
import com.aresstack.audio.core.dsp.RoomReverb;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Channels on a stage in one room, for live playback: the graph of
 * {@link com.aresstack.audio.core.mix.MixGraph}, whose channels can be moved while it plays and
 * which can jump to any frame.
 *
 * <pre>
 * live channel 1..n ─► master bus ◄── room reverb ◄── send bus ◄── live channel 1..n
 * </pre>
 *
 * <p>The audio thread is the only one that reads blocks. Other threads move channels
 * ({@link LiveChannel#set}), change the share of the room ({@link #setRoomGain}) and ask for a jump
 * ({@link #seek}); each takes effect at the start of the next block. With parameters that do not
 * change, the graph delivers the same samples as the {@code MixGraph} of the same stage.
 */
public final class LiveGraph implements AudioSource {
    /** How long a parameter glides to its new value by default: 20 ms, short enough to feel immediate. */
    public static final double DEFAULT_GLIDE_MS = 20;

    private final LiveChannel[] channels;
    private final RoomReverb room;
    private final long frames;
    private final double glideMs;
    private final Ramp roomGain;
    private volatile float wantedRoomGain;
    private final AtomicLong seekTo = new AtomicLong(-1);
    private AudioBlock send;
    private AudioBlock ring;
    private int glideFrames;
    private long position;
    private volatile long published;

    /**
     * @param channels the channels, in the order in which they are added
     * @param room     the shared room, or {@code null} for a dry mix
     * @param roomGain how loud the ring of the room is
     * @param frames   how long the mix is, including the ring at the end
     */
    public LiveGraph(List<LiveChannel> channels, RoomReverb room, float roomGain, long frames) {
        this(channels, room, roomGain, frames, DEFAULT_GLIDE_MS);
    }

    public LiveGraph(List<LiveChannel> channels, RoomReverb room, float roomGain, long frames, double glideMs) {
        if (!(glideMs >= 0) || Double.isInfinite(glideMs)) {
            throw new IllegalArgumentException("A glide takes a finite time of at least 0 ms.");
        }
        this.channels = channels.toArray(new LiveChannel[0]);
        this.room = room;
        this.frames = Math.max(0, frames);
        this.glideMs = glideMs;
        this.roomGain = new Ramp(roomGain);
        this.wantedRoomGain = roomGain;
    }

    public long frames() {
        return frames;
    }

    /** The channels, in the order given; move them with {@link LiveChannel#set}. */
    public LiveChannel channel(int index) {
        return channels[index];
    }

    public int channels() {
        return channels.length;
    }

    /** How many frames a parameter takes to glide to a new value; known after {@link #prepare}. */
    public int glideFrames() {
        return glideFrames;
    }

    /** Changes how loud the ring of the room is; any thread, glides from the next block on. */
    public void setRoomGain(float gain) {
        if (!(gain >= 0) || Float.isInfinite(gain)) {
            throw new IllegalArgumentException("A gain is a finite factor of at least 0.");
        }
        wantedRoomGain = gain;
    }

    /** Asks the audio thread to go on at this frame with its next block; any thread. */
    public void seek(long frame) {
        seekTo.set(Math.max(0, Math.min(frames, frame)));
    }

    /** The frame the next block starts at, as published by the audio thread after each block. */
    public long position() {
        return published;
    }

    @Override
    public void prepare(int sampleRate, int maxBlockFrames) {
        glideFrames = (int) Math.round(glideMs * sampleRate / 1000);
        for (LiveChannel channel : channels) {
            channel.prepare(sampleRate, maxBlockFrames, glideFrames);
        }
        if (room != null) {
            send = new AudioBlock(maxBlockFrames);
            ring = new AudioBlock(maxBlockFrames);
            room.prepare(sampleRate, maxBlockFrames);
        }
        roomGain.reset(wantedRoomGain);
        position = 0;
        published = 0;
        seekTo.set(-1);
    }

    /** Never throws: the sources of a live graph hold their sound already. */
    @Override
    public int read(AudioBlock block) {
        long jump = seekTo.getAndSet(-1);
        if (jump >= 0) {
            position = jump;
            for (LiveChannel channel : channels) {
                channel.seek(jump);
            }
        }
        int wanted = block.frames();
        int count = (int) Math.max(0, Math.min(wanted, frames - position));
        block.clear(0, wanted);
        if (count == 0) {
            return 0;
        }
        if (send != null) {
            send.clear(0, count);
        }
        for (LiveChannel channel : channels) {
            channel.render(position, count, block, send);
        }
        if (room != null) {
            roomGain.glideTo(wantedRoomGain, glideFrames);
            if (roomGain.settled()) {
                room.process(send, count, block, roomGain.value());
            } else {
                // The room adds its ring scaled by one gain per call; a gliding gain goes frame by frame.
                ring(count, block);
            }
        }
        position += count;
        published = position;
        return count;
    }

    /** The ring of the room with a gliding gain: rendered at full level into a block of its own, then glided onto the master. */
    private void ring(int count, AudioBlock block) {
        ring.clear(0, count);
        room.process(send, count, ring, 1f);
        float[] busLeft = block.left;
        float[] busRight = block.right;
        float[] ringLeft = ring.left;
        float[] ringRight = ring.right;
        for (int i = 0; i < count; i++) {
            float g = roomGain.next();
            busLeft[i] += ringLeft[i] * g;
            busRight[i] += ringRight[i] * g;
        }
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (LiveChannel channel : channels) {
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
