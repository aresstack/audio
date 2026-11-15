package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.studio.mix.StageAutomation;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.visual.TrackLight;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class StageLightsTest {
    @Test
    public void aRenderedTrackGlowsWhereItSoundsAndIsDarkWhereItRests() throws Exception {
        int rate = 44100;
        int frames = rate; // one second: silent, then loud
        byte[] bytes = new byte[frames * 4];
        for (int i = frames / 2; i < frames; i++) {
            short value = (short) (0.8 * Short.MAX_VALUE * Math.sin(i * 0.05));
            for (int side = 0; side < 2; side++) {
                bytes[4 * i + 2 * side] = (byte) value;
                bytes[4 * i + 2 * side + 1] = (byte) (value >> 8);
            }
        }
        Path file = Files.createTempFile("stage-lights-", ".wav");
        try {
            javax.sound.sampled.AudioFormat format = new javax.sound.sampled.AudioFormat(rate, 16, 2, true, false);
            javax.sound.sampled.AudioSystem.write(new javax.sound.sampled.AudioInputStream(
                    new java.io.ByteArrayInputStream(bytes), format, frames),
                    javax.sound.sampled.AudioFileFormat.Type.WAVE, file.toFile());
            float[] levels = StageLights.fromFile(file);
            assertTrue(levels.length > 10);
            assertEquals(0f, levels[1], 1e-4f);
            assertTrue("loud at the end: " + levels[levels.length - 2], levels[levels.length - 2] > 0.7f);

            SoundSourcePlacement left = new SoundSourcePlacement("geige", -0.5, 0.2);
            SoundSourcePlacement muted = new SoundSourcePlacement("bass", 0.5, 0.6).withMuted(true);
            StageLights lights = new StageLights(List.of("geige", "bass"), List.of(left, muted),
                    StageAutomation.NONE, List.of(levels, levels));
            List<TrackLight> early = lights.at(0.1);
            List<TrackLight> late = lights.at(0.9);
            assertEquals(-0.5, late.get(0).x(), 1e-9);
            assertEquals(0.6, late.get(1).depth(), 1e-9);
            assertEquals(0f, early.get(0).level(), 1e-4f);
            assertTrue(late.get(0).level() > 0.5f);
            assertEquals("a muted track stays dark", 0f, late.get(1).level(), 0f);
            assertEquals("after the end it is dark", 0f, lights.at(5).get(0).level(), 0f);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void aMissingFileGivesNoCurveAndNoModelGivesSilence() {
        assertNull(StageLights.fromFile(Path.of("gibt-es-nicht.wav")));
        assertEquals(0, StageLights.fromNotes(null, "geige").length);
    }
}
