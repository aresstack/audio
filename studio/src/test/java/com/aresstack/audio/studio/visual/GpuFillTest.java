package com.aresstack.audio.studio.visual;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.gpu.GpuFrame;
import org.junit.Test;

public final class GpuFillTest {

    @Test
    public void bandsWaveAndLevelsReachTheFrame() {
        SoundPicture picture = new SoundPicture(8, 4);
        float[] bands = {0.1f, 0.2f, 0.3f, 0.4f};
        System.arraycopy(bands, 0, picture.bands, 0, 4);
        for (int i = 0; i < 8; i++) {
            picture.left[i] = i / 8f;
            picture.right[i] = i / 8f;
        }
        picture.bass = 0.7f;
        picture.beat = 1f;
        picture.rmsLeft = 0.5f;
        GpuFrame frame = new GpuFrame(8);
        GpuFill.copy(picture, frame);
        assertEquals(0.1f, frame.bands[0], 0f);
        assertEquals(0.1f, frame.bands[1], 0f);
        assertEquals(0.4f, frame.bands[7], 0f);
        assertEquals(7 / 8f, frame.wave[7], 1e-6f);
        assertEquals(0.7f, frame.bass, 0f);
        assertEquals(1f, frame.beat, 0f);
        assertEquals(0.5f, frame.loudness, 0f);
    }

    @Test
    public void theSilentFeedFillsAQuietFrameWithTheChosenEffect() {
        GpuFill fill = new GpuFill(SoundFeed.SILENT, VisualSettings.DEFAULTS, () -> "tunnel");
        GpuFrame frame = new GpuFrame(VisualSettings.DEFAULTS.bands());
        fill.fill(frame);
        assertEquals("tunnel", frame.effect);
        assertEquals(0f, frame.loudness, 0f);
    }

    @Test
    public void onlyTheArtisticEffectsAndTheSpectrumGoToTheGraphicsCard() {
        assertTrue(GpuFill.drawsOnGpu("tunnel"));
        assertTrue(GpuFill.drawsOnGpu("spectrum"));
        assertFalse(GpuFill.drawsOnGpu("meter"));
        assertFalse(GpuFill.drawsOnGpu("score"));
        for (String key : com.aresstack.audio.gpu.GpuScreen.EFFECTS) {
            assertEquals("every effect of the graphics card has a Java2D twin", key,
                    Effects.find(Effects.all(), key).key());
        }
    }
}
