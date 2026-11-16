package com.aresstack.audio.studio.visual;

import com.aresstack.audio.core.scope.SoundTap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A feed over a {@link SoundTap}: the window ends at the frame the owner of the tap says is heard.
 */
public final class TapFeed implements SoundFeed {
    private final SoundTap tap;
    private final LongSupplier heard;
    private final BooleanSupplier playing;
    private final int sampleRate;
    private final Supplier<List<TrackLight>> lights;

    public TapFeed(SoundTap tap, LongSupplier heard, BooleanSupplier playing, int sampleRate,
                   Supplier<List<TrackLight>> lights) {
        this.tap = tap;
        this.heard = heard;
        this.playing = playing;
        this.sampleRate = sampleRate;
        this.lights = lights == null ? List::of : lights;
    }

    @Override
    public boolean window(float[] left, float[] right, int count) {
        return playing.getAsBoolean() && tap.read(heard.getAsLong(), left, right, count);
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public List<TrackLight> lights() {
        return lights.get();
    }

    /**
     * Two feeds heard at once, as during a cross-fade of two titles: their windows are added, the
     * lights are those of the first that has any. Either may be silent.
     */
    public static SoundFeed both(SoundFeed first, SoundFeed second) {
        if (second == null || second == SILENT) {
            return first == null ? SILENT : first;
        }
        if (first == null || first == SILENT) {
            return second;
        }
        return new SoundFeed() {
            private float[] extraLeft = new float[0];
            private float[] extraRight = new float[0];

            @Override
            public synchronized boolean window(float[] left, float[] right, int count) {
                boolean one = first.window(left, right, count);
                if (extraLeft.length < count) {
                    extraLeft = new float[count];
                    extraRight = new float[count];
                }
                boolean two = second.window(extraLeft, extraRight, count);
                if (!one && !two) {
                    return false;
                }
                for (int i = 0; i < count; i++) {
                    left[i] = (one ? left[i] : 0f) + (two ? extraLeft[i] : 0f);
                    right[i] = (one ? right[i] : 0f) + (two ? extraRight[i] : 0f);
                }
                return true;
            }

            @Override
            public int sampleRate() {
                return first.sampleRate();
            }

            @Override
            public List<TrackLight> lights() {
                List<TrackLight> seen = first.lights();
                return seen.isEmpty() ? second.lights() : seen;
            }

            @Override
            public boolean lightsComing() {
                return first.lightsComing() || second.lightsComing();
            }

            @Override
            public java.awt.image.BufferedImage figure(String trackId) {
                java.awt.image.BufferedImage known = first.figure(trackId);
                return known == null ? second.figure(trackId) : known;
            }

            @Override
            public double figureHeight() {
                return first.figureHeight();
            }

            @Override
            public String style() {
                String known = first.style();
                return known == null || known.isBlank() ? second.style() : known;
            }
        };
    }
}
