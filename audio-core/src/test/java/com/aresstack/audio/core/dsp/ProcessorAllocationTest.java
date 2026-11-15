package com.aresstack.audio.core.dsp;

import com.aresstack.audio.core.Allocation;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.BiquadCoefficients;
import com.aresstack.audio.core.TestSignals;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

/**
 * Rule 1: after {@code prepare}, processing a block allocates nothing. Measured per processor
 * with the allocation counter of the thread, after warming up, over many blocks; a few bytes are
 * allowed for the counter itself.
 */
public final class ProcessorAllocationTest {
    private static final int RATE = 44100;
    private static final int BLOCK = 512;
    private static final int BLOCKS = 2000;
    private static final long NOISE = 256;

    @Test
    public void theStripDoesNotAllocateEvenWithAMovingLevel() throws Exception {
        StereoStrip strip = new StereoStrip(new StereoStrip.Settings(0.8f, 0.5f,
                frame -> (float) Math.pow(10, -6 * Math.sin(frame * 1e-4) / 20)));
        strip.prepare(RATE, BLOCK);
        AudioBlock block = block();
        assertFree(() -> {
            for (int i = 0; i < BLOCKS; i++) {
                strip.process(block);
            }
        });
    }

    @Test
    public void theFilterDoesNotAllocateEvenWhenItsCoefficientsChange() throws Exception {
        StereoBiquad shelf = new StereoBiquad(BiquadCoefficients.highShelf(RATE, 4500, -4, 1));
        BiquadCoefficients other = BiquadCoefficients.highShelf(RATE, 4500, -5, 1);
        shelf.prepare(RATE, BLOCK);
        AudioBlock block = block();
        assertFree(() -> {
            for (int i = 0; i < BLOCKS; i++) {
                if (i == BLOCKS / 2) {
                    shelf.setCoefficients(other);
                }
                shelf.process(block);
            }
        });
    }

    @Test
    public void echoesRoomPannerAndMeterDoNotAllocate() throws Exception {
        EarlyReflections reflections = new EarlyReflections(Arrays.asList(new EarlyReflections.Tap(400, 0.3f, 0.1f),
                new EarlyReflections.Tap(2500, 0.1f, 0.3f)), BiquadCoefficients.lowPass(RATE, 6000, Math.sqrt(0.5)));
        RoomReverb room = new RoomReverb(new RoomReverb.Settings(2, 0.4, 0.03));
        StereoPanner panner = new StereoPanner(0.3, 0.9f);
        PeakMeter meter = new PeakMeter();
        reflections.prepare(RATE, BLOCK);
        room.prepare(RATE, BLOCK);
        meter.prepare(RATE, BLOCK);
        AudioBlock block = block();
        AudioBlock bus = new AudioBlock(BLOCK);
        assertFree(() -> {
            for (int i = 0; i < BLOCKS; i++) {
                bus.clear();
                panner.addTo(block, BLOCK, bus);
                reflections.process(block, BLOCK, BLOCK, bus);
                room.process(block, BLOCK, bus, 0.4f);
                meter.process(bus);
            }
        });
    }

    @Test
    public void theLimiterAndTheMasterBusDoNotAllocateEvenWhenTheirSettingsChange() throws Exception {
        LookaheadLimiter limiter = new LookaheadLimiter();
        com.aresstack.audio.core.mix.MasterBus bus = new com.aresstack.audio.core.mix.MasterBus(
                com.aresstack.audio.core.mix.MasterBus.Settings.fixed(0));
        com.aresstack.audio.core.mix.MasterBus.Settings quieter = com.aresstack.audio.core.mix.MasterBus.Settings.fixed(-4,
                new LookaheadLimiter.Settings(-3, 50));
        LookaheadLimiter.Settings lower = new LookaheadLimiter.Settings(-6, 120);
        limiter.prepare(RATE, BLOCK);
        bus.prepare(RATE, BLOCK);
        AudioBlock block = new AudioBlock(BLOCK);
        float[] loud = TestSignals.noise(BLOCK, 4, 3.0);
        assertFree(() -> {
            for (int i = 0; i < BLOCKS; i++) {
                if (i == BLOCKS / 2) {
                    limiter.setSettings(lower);
                    bus.setSettings(quieter);
                }
                System.arraycopy(loud, 0, block.left, 0, BLOCK);
                System.arraycopy(loud, 0, block.right, 0, BLOCK);
                limiter.process(block);
                System.arraycopy(loud, 0, block.left, 0, BLOCK);
                System.arraycopy(loud, 0, block.right, 0, BLOCK);
                bus.process(block);
            }
        });
    }

    private static AudioBlock block() {
        AudioBlock block = new AudioBlock(BLOCK);
        float[] noise = TestSignals.noise(BLOCK, 1, 0.5);
        System.arraycopy(noise, 0, block.left, 0, BLOCK);
        System.arraycopy(noise, 0, block.right, 0, BLOCK);
        return block;
    }

    private static void assertFree(Allocation.ThrowingRunnable work) throws Exception {
        long bytes = Allocation.of(work);
        Assert.assertTrue(bytes + " bytes allocated over " + BLOCKS + " blocks", bytes <= NOISE);
    }
}
