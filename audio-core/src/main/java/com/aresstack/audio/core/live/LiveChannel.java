package com.aresstack.audio.core.live;

import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.SeekableSource;
import com.aresstack.audio.core.dsp.GlidingBiquad;
import com.aresstack.audio.core.dsp.StereoStrip;
import com.aresstack.audio.core.mix.Seat;
import com.aresstack.audio.core.mix.SpatialMotion;
import com.aresstack.audio.core.spatial.BinauralPanner;
import com.aresstack.audio.core.spatial.HrirSet;

/**
 * One channel of a live mix: the same chain as {@link com.aresstack.audio.core.mix.StemChannel},
 * but every parameter may change while it plays.
 *
 * <pre>
 * source ─► strip (level, width) ─┬─► send to the room bus
 *                                 └─► distance shelf ─┬─► panner ─► master bus
 *                                                     └─► early reflections ─► master bus
 * </pre>
 *
 * <p>Any thread hands over new {@link ChannelParameters} with {@link #set}; the audio thread takes
 * the newest at the start of its next block and glides there over {@link LiveGraph#glideFrames()}:
 * level, width, panner gains and sends in a straight line, the shelf by gliding coefficients
 * ({@link GlidingBiquad}), changed echo delays by a crossfade. Two neighbouring samples therefore
 * never differ by more than the signal itself and a glide step allow. While nothing glides, the
 * channel computes exactly what {@code StemChannel} computes, sample for sample.
 *
 * <p>A source that moves with the piece ({@link ChannelParameters#motion()}, work packages D3
 * and D4) is asked every {@link SpatialMotion#RAMP} frames of the mix where it will be one ramp
 * later, and everything that follows it glides there over that ramp: panner gains, sends,
 * distance shelf, wall echoes and, on headphones, the direction. That is the grid of the file
 * ({@code SpatialChannel}), whatever the size of the blocks, so that live and file take the same
 * course. Other parameters handed over meanwhile, and a changed motion, are reached by the next
 * such frame, without a jump.
 *
 * <p>Made with a {@link HrirSet}, the channel is heard on headphones (work package D4): the
 * direct sound goes through a {@link BinauralPanner} that glides to the direction of the
 * parameters, and the panner gains are the distance gain only.
 */
public final class LiveChannel {
    private final SeekableSource source;
    private final long frames;
    private final int echoCapacity;
    private final BiquadCoefficients echoLowPass;
    private volatile ChannelParameters wanted;
    private ChannelParameters taken;
    private final Ramp level = new Ramp(0);
    private final Ramp width = new Ramp(1);
    private final Ramp toLeft = new Ramp(0);
    private final Ramp toRight = new Ramp(0);
    private final Ramp sendLeft = new Ramp(0);
    private final Ramp sendRight = new Ramp(0);
    private final GlidingBiquad shelf = new GlidingBiquad(null);
    private final LiveReflections reflections;
    private final BinauralPanner binaural;
    private float[] mono = new float[0];
    private AudioBlock block;
    private int glide;
    /** The level curve between two looks at it, as in {@link StereoStrip}. */
    private float curveFactor = 1;
    private float curveStep;
    /** Set after a seek: the level curve is looked up afresh for the frame the channel goes on at. */
    private boolean realign;
    /** Set after a seek or at the start: a moving source stands at once where its motion says. */
    private boolean replace = true;
    /** Where a moving source goes, written by its motion on the audio thread. */
    private final Seat moved;
    /** The frame of the mix the glides of a moving source aim at; -1 before the first look. */
    private long aimed = -1;
    private SpatialMotion aimedMotion;

    /**
     * @param source       the sound of the channel at the rate of the mix
     * @param frames       how many frames of the source are heard at most
     * @param start        where the channel stands at first
     * @param echoCapacity the longest echo delay, in frames, any later parameters may ask for
     *                     (longer ones are shortened to it)
     * @param echoLowPass  how the walls dull the echoes; {@code null} together with no walls in
     *                     {@code start} for a channel without echoes
     */
    public LiveChannel(SeekableSource source, long frames, ChannelParameters start, int echoCapacity,
                       BiquadCoefficients echoLowPass) {
        this(source, frames, start, echoCapacity, echoLowPass, null);
    }

    /**
     * A live channel for headphones when {@code hrirs} is given (see the class documentation);
     * {@code null} gives the loudspeaker channel of the other constructor.
     */
    public LiveChannel(SeekableSource source, long frames, ChannelParameters start, int echoCapacity,
                       BiquadCoefficients echoLowPass, HrirSet hrirs) {
        if (source == null || start == null) {
            throw new IllegalArgumentException("A live channel needs a source and its first parameters.");
        }
        if (start.echoes() > 0 && echoLowPass == null) {
            throw new IllegalArgumentException("Echoes need the low-pass of the walls.");
        }
        this.source = source;
        this.frames = Math.max(0, frames);
        this.echoCapacity = Math.max(echoCapacity, start.longestEcho());
        this.echoLowPass = echoLowPass;
        this.wanted = start;
        reflections = start.echoes() > 0 ? new LiveReflections(start.echoes(), this.echoCapacity, echoLowPass) : null;
        binaural = hrirs != null ? new BinauralPanner(hrirs) : null;
        moved = new Seat(start.echoes());
    }

    /**
     * Hands over where the channel stands now. Any thread may call this; the audio thread glides
     * there from its next block on. The parameters must have as many walls as the first ones.
     */
    public void set(ChannelParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("A live channel needs its parameters.");
        }
        if (parameters.echoes() != (reflections == null ? 0 : reflections.walls())) {
            throw new IllegalArgumentException("A live channel keeps the number of walls it was made with.");
        }
        wanted = parameters;
    }

    /** The parameters handed over last. */
    public ChannelParameters parameters() {
        return wanted;
    }

    /** Whether the audio thread has reached the parameters handed over last (for tests and meters). */
    public boolean settled() {
        return taken == wanted && level.settled() && width.settled() && toLeft.settled() && toRight.settled()
                && sendLeft.settled() && sendRight.settled() && shelf.settled()
                && (binaural == null || binaural.settled());
    }

    void prepare(int sampleRate, int maxBlockFrames, int glideFrames) {
        block = new AudioBlock(maxBlockFrames);
        source.prepare(sampleRate, maxBlockFrames);
        shelf.prepare(sampleRate, maxBlockFrames);
        if (binaural != null) {
            binaural.prepare(sampleRate, maxBlockFrames);
            mono = new float[maxBlockFrames];
        }
        glide = glideFrames;
        ChannelParameters start = wanted;
        jumpTo(start);
        if (reflections != null) {
            reflections.prepare(maxBlockFrames, glideFrames, start);
        }
        source.seek(0);
    }

    private void jumpTo(ChannelParameters now) {
        taken = now;
        level.reset(now.level());
        width.reset(now.width());
        toLeft.reset(now.toLeft());
        toRight.reset(now.toRight());
        sendLeft.reset(now.sendLeft());
        sendRight.reset(now.sendRight());
        shelf.jumpTo(now.shelf());
        if (binaural != null) {
            binaural.jumpTo(now.azimuth(), now.elevation());
        }
        if (reflections != null) {
            reflections.jumpTo(now);
        }
    }

    /** Starts the source again from its first frame, as at the end of a loop; filters and echoes ring on. */
    void rewind() {
        source.seek(0);
        realign = true;
    }

    /** Goes on at this frame of the mix; called by the graph on the audio thread. */
    void seek(long frame) {
        source.seek(Math.min(frame, frames));
        realign = true;
        replace = true;
        shelf.reset();
        if (binaural != null) {
            binaural.clear();
        }
        if (reflections != null) {
            reflections.clear();
        }
    }

    /**
     * Adds the {@code count} frames from {@code position} on to the master bus and, if there is
     * one, to the send bus.
     */
    void render(long position, int count, AudioBlock master, AudioBlock send) {
        ChannelParameters now = wanted;
        if (now != taken) {
            taken = now;
            level.glideTo(now.level(), glide);
            width.glideTo(now.width(), glide);
            if (now.motion() == null) {
                toLeft.glideTo(now.toLeft(), glide);
                toRight.glideTo(now.toRight(), glide);
                sendLeft.glideTo(now.sendLeft(), glide);
                sendRight.glideTo(now.sendRight(), glide);
            }
            if (now.motion() == null) {
                shelf.glideTo(now.shelf(), glide);
                if (binaural != null) {
                    binaural.aimAt(now.azimuth(), now.elevation(), glide);
                }
                if (reflections != null) {
                    reflections.glideTo(now, glide);
                }
            }
        }
        SpatialMotion motion = now.motion();
        if (motion != aimedMotion) {
            aimedMotion = motion;
            aimed = -1;
        }
        if (motion != null && replace) {
            // At the start and after a jump the source stands where it is at that frame.
            motion.seatAt(position, moved);
            toLeft.reset(moved.toLeft);
            toRight.reset(moved.toRight);
            sendLeft.reset(moved.sendLeft);
            sendRight.reset(moved.sendRight);
            shelf.jumpTo(moved.shelf);
            if (binaural != null) {
                binaural.jumpTo(moved.azimuth, moved.elevation);
            }
            if (reflections != null) {
                reflections.jumpTo(moved);
            }
            aimed = -1;
        }
        replace = false;
        int heard = 0;
        if (position < frames) {
            int asked = (int) Math.min(count, frames - position);
            block.setFrames(asked);
            heard = source.read(block);
        }
        if (realign) {
            realign = false;
            if (now.curve() != null) {
                alignCurve(now.curve(), position);
            }
        }
        if (motion != null) {
            if (heard > 0) {
                strip(now.curve(), position, heard);
            } else {
                for (int i = 0; i < count; i++) {
                    level.next();
                    width.next();
                }
            }
            follow(motion, position, heard, count, master, send);
            return;
        }
        if (heard > 0) {
            strip(now.curve(), position, heard);
            if (send != null) {
                addTo(send, heard, sendLeft, sendRight);
            }
            shelf.process(block, heard);
            if (binaural == null) {
                addTo(master, heard, toLeft, toRight);
            } else {
                place(master, heard, count);
            }
        } else {
            // Nothing heard: the glides still run, so that they end on time.
            skip(count);
            if (binaural != null) {
                // The filters and delays of the head still ring out.
                java.util.Arrays.fill(mono, 0, count, 0f);
                binaural.process(mono, count, master);
            }
        }
        if (reflections != null) {
            reflections.process(block, heard, count, master);
        }
    }

    /**
     * The block of a source that moves with the piece, in the same pieces as the file renders it:
     * at every {@link SpatialMotion#RAMP}th frame of the mix the motion says where the source is
     * one ramp later, and gains, sends, shelf, echoes and direction glide there over that ramp.
     * A block that starts between two such frames (after a jump, or with a new motion) glides to
     * the next one from where it is.
     */
    private void follow(SpatialMotion motion, long position, int heard, int count, AudioBlock master,
                        AudioBlock send) {
        int i = 0;
        while (i < count) {
            long at = position + i;
            int ahead = SpatialMotion.RAMP - (int) (at & (SpatialMotion.RAMP - 1));
            if (at + ahead != aimed) {
                aimed = at + ahead;
                motion.seatAt(aimed, moved);
                toLeft.glideTo(moved.toLeft, ahead);
                toRight.glideTo(moved.toRight, ahead);
                sendLeft.glideTo(moved.sendLeft, ahead);
                sendRight.glideTo(moved.sendRight, ahead);
                shelf.glideTo(moved.shelf, ahead);
                if (binaural != null) {
                    binaural.aimAt(moved.azimuth, moved.elevation, ahead);
                }
                if (reflections != null) {
                    reflections.glideTo(moved, ahead);
                }
            }
            int end = Math.min(count, i + ahead);
            int heardEnd = Math.max(i, Math.min(end, heard));
            float[] left = block.left;
            float[] right = block.right;
            if (send != null) {
                float[] busLeft = send.left;
                float[] busRight = send.right;
                for (int j = i; j < heardEnd; j++) {
                    busLeft[j] += left[j] * sendLeft.next();
                    busRight[j] += right[j] * sendRight.next();
                }
            } else {
                for (int j = i; j < heardEnd; j++) {
                    sendLeft.next();
                    sendRight.next();
                }
            }
            for (int j = heardEnd; j < end; j++) {
                sendLeft.next();
                sendRight.next();
            }
            if (heardEnd > i) {
                shelf.process(block, i, heardEnd - i);
            }
            if (binaural == null) {
                float[] busLeft = master.left;
                float[] busRight = master.right;
                for (int j = i; j < heardEnd; j++) {
                    busLeft[j] += left[j] * toLeft.next();
                    busRight[j] += right[j] * toRight.next();
                }
                for (int j = heardEnd; j < end; j++) {
                    toLeft.next();
                    toRight.next();
                }
            } else {
                for (int j = i; j < end; j++) {
                    float gl = toLeft.next();
                    float gr = toRight.next();
                    mono[j] = j < heardEnd ? (left[j] * gl + right[j] * gr) * 0.5f : 0f;
                }
                // The filters and delays of the head ring out after the source has ended.
                binaural.process(mono, i, end - i, master);
            }
            if (reflections != null) {
                reflections.process(block, i, heardEnd, end, master);
            }
            i = end;
        }
    }

    /** Level and width; with a level curve exactly as {@link StereoStrip} while the level does not glide. */
    private void strip(StereoStrip.LevelCurve curve, long position, int count) {
        float[] left = block.left;
        float[] right = block.right;
        if (level.settled() && width.settled()) {
            float l = level.value();
            float w = width.value();
            float g = curve == null ? l : curveFactor;
            float s = curve == null ? 0 : curveStep;
            long at = position;
            for (int i = 0; i < count; i++, at++) {
                if (curve != null && (at & (StereoStrip.RAMP - 1)) == 0) {
                    float from = l * curve.factorAt(at);
                    float to = l * curve.factorAt(at + StereoStrip.RAMP);
                    g = from;
                    s = (to - from) / StereoStrip.RAMP;
                }
                float middle = (left[i] + right[i]) * 0.5f;
                float side = (left[i] - right[i]) * 0.5f * w;
                left[i] = (middle + side) * g;
                right[i] = (middle - side) * g;
                g += s;
            }
            // While the level is settled the curve's gain carries the level in it.
            curveFactor = g;
            curveStep = s;
        } else {
            // Gliding: the curve as a pure factor, times the gliding level.
            float c = 1;
            float cs = 0;
            long at = position;
            for (int i = 0; i < count; i++, at++) {
                if (curve != null && (at & (StereoStrip.RAMP - 1)) == 0) {
                    float from = curve.factorAt(at);
                    c = from;
                    cs = (curve.factorAt(at + StereoStrip.RAMP) - from) / StereoStrip.RAMP;
                } else if (curve != null && i == 0) {
                    float from = curve.factorAt(at - (at & (StereoStrip.RAMP - 1)));
                    float to = curve.factorAt(at - (at & (StereoStrip.RAMP - 1)) + StereoStrip.RAMP);
                    cs = (to - from) / StereoStrip.RAMP;
                    c = from + cs * (at & (StereoStrip.RAMP - 1));
                }
                float g = level.next() * c;
                float w = width.next();
                float middle = (left[i] + right[i]) * 0.5f;
                float side = (left[i] - right[i]) * 0.5f * w;
                left[i] = (middle + side) * g;
                right[i] = (middle - side) * g;
                c += cs;
            }
            if (curve != null && level.settled()) {
                // Hand the curve back to the settled loop, which carries the level in its gain.
                alignCurve(curve, position + count);
            }
        }
    }

    /** Sets the gain of the settled loop to where the level curve stands at this frame. */
    private void alignCurve(StereoStrip.LevelCurve curve, long frame) {
        long grid = frame - (frame & (StereoStrip.RAMP - 1));
        float l = level.value();
        float from = l * curve.factorAt(grid);
        float to = l * curve.factorAt(grid + StereoStrip.RAMP);
        curveStep = (to - from) / StereoStrip.RAMP;
        curveFactor = from + curveStep * (frame - grid);
    }

    /** Adds the first {@code count} frames of the block to a bus, each side with its gliding gain. */
    private void addTo(AudioBlock bus, int count, Ramp gainLeft, Ramp gainRight) {
        if (gainLeft.settled() && gainRight.settled()) {
            block.addTo(bus, count, gainLeft.value(), gainRight.value());
            return;
        }
        float[] left = block.left;
        float[] right = block.right;
        float[] busLeft = bus.left;
        float[] busRight = bus.right;
        for (int i = 0; i < count; i++) {
            busLeft[i] += left[i] * gainLeft.next();
            busRight[i] += right[i] * gainRight.next();
        }
    }

    /** Folds the heard frames to mono with the gliding distance gains and places them for headphones. */
    private void place(AudioBlock master, int heard, int count) {
        float[] left = block.left;
        float[] right = block.right;
        for (int i = 0; i < heard; i++) {
            mono[i] = (left[i] * toLeft.next() + right[i] * toRight.next()) * 0.5f;
        }
        java.util.Arrays.fill(mono, heard, count, 0f);
        binaural.process(mono, count, master);
    }

    private void skip(int count) {
        for (int i = 0; i < count; i++) {
            level.next();
            width.next();
            toLeft.next();
            toRight.next();
            sendLeft.next();
            sendRight.next();
        }
    }

    void close() throws java.io.IOException {
        source.close();
    }
}
