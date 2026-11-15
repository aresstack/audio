package com.aresstack.audio.core.scope;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SoundTapTest {

    @Test
    public void readsTheFramesThatEndAtTheAskedFrame() {
        SoundTap tap = new SoundTap(8);
        float[] left = {1, 2, 3, 4, 5};
        float[] right = {-1, -2, -3, -4, -5};
        tap.write(left, right, 5);
        float[] l = new float[3];
        float[] r = new float[3];
        assertTrue(tap.read(4, l, r, 3));
        assertEquals(2f, l[0], 0f);
        assertEquals(4f, l[2], 0f);
        assertEquals(-3f, r[1], 0f);
    }

    @Test
    public void framesBeforeTheStartAreSilence() {
        SoundTap tap = new SoundTap(8);
        tap.write(new float[]{7}, new float[]{7}, 1);
        float[] l = new float[4];
        float[] r = new float[4];
        assertTrue(tap.read(1, l, r, 4));
        assertEquals(0f, l[0], 0f);
        assertEquals(7f, l[3], 0f);
    }

    @Test
    public void framesNotYetWrittenOrOverwrittenCannotBeRead() {
        SoundTap tap = new SoundTap(4);
        assertEquals(4, tap.capacity());
        float[] block = new float[10];
        for (int i = 0; i < block.length; i++) {
            block[i] = i;
        }
        tap.write(block, block, 10);
        float[] l = new float[4];
        float[] r = new float[4];
        assertFalse("not written yet", tap.read(11, l, r, 2));
        assertFalse("gone round the ring", tap.read(7, l, r, 4));
        assertTrue(tap.read(10, l, r, 4));
        assertEquals(6f, l[0], 0f);
        assertEquals(9f, l[3], 0f);
    }

    @Test
    public void sixteenBitFramesAreReadAsHeard() {
        SoundTap tap = new SoundTap(16);
        // stereo: left 16384 (half scale), right -32768 (full scale down)
        byte[] pcm = {0x00, 0x40, 0x00, (byte) 0x80};
        tap.write16(pcm, 0, pcm.length, 2, 1f);
        // mono at half gain: 16384 -> a quarter
        tap.write16(new byte[]{0x00, 0x40}, 0, 2, 1, 0.5f);
        float[] l = new float[2];
        float[] r = new float[2];
        assertTrue(tap.read(2, l, r, 2));
        assertEquals(0.5f, l[0], 1e-6f);
        assertEquals(-1f, r[0], 1e-6f);
        assertEquals(0.25f, l[1], 1e-6f);
        assertEquals(0.25f, r[1], 1e-6f);
    }

    @Test
    public void capacityIsRoundedUpToAPowerOfTwo() {
        assertEquals(1024, new SoundTap(1000).capacity());
    }
}
