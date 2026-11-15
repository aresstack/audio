package com.aresstack.audio.gpu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public final class GpuScreenTest {

    @Test
    public void theTestJvmMayTryTheGraphicsCard() {
        // The tests run on Java 21 with --enable-preview, as the studio does after its launcher.
        assertNull(GpuScreen.whyNot());
    }

    @Test
    public void withoutAScreenOpeningFailsWithAReasonAndNeverHangs() {
        boolean noScreen = System.getenv("DISPLAY") == null && !System.getProperty("os.name", "").startsWith("Windows");
        assumeTrue("only where there is no screen (the CI)", noScreen);
        GpuScreen.Settings settings = new GpuScreen.Settings("test", 16, true, 400, 5, "auto");
        long started = System.nanoTime();
        try {
            GpuScreen screen = GpuScreen.open(settings, frame -> { }, null);
            screen.close();
            throw new AssertionError("a window opened without a screen");
        } catch (GpuUnavailableException expected) {
            assertNotNull(expected.getMessage());
        }
        assertTrue("gives up in time", (System.nanoTime() - started) / 1e9 < settings.openSeconds() + 2);
    }

    @Test
    public void bothShadersKnowTheSameUniformsAndEffects() throws IOException {
        String glsl = read("/shaders/visual.frag");
        String hlsl = read("/shaders/visual.hlsl");
        for (String uniform : List.of("uTime", "uLevels", "uScreen", "uTexture")) {
            assertTrue("GLSL " + uniform, glsl.contains(uniform));
            assertTrue("HLSL " + uniform, hlsl.contains(uniform));
        }
        for (String function : List.of("spectrumRing(", "tunnel(", "stars(", "alchemy(", "palette(", "band(", "wave(")) {
            assertTrue("GLSL " + function, glsl.contains(function));
            assertTrue("HLSL " + function, hlsl.contains(function));
        }
        assertEquals(4, GpuScreen.EFFECTS.size());
        assertNotNull(read("/shaders/visual.vert"));
        assertNotNull(read("/shaders/blit.hlsl"));
    }

    @Test
    public void aFrameHasAsManyPointsAsAsked() {
        GpuFrame frame = new GpuFrame(48);
        assertEquals(48, frame.bands.length);
        assertEquals(48, frame.wave.length);
        assertEquals(GpuScreen.EFFECTS.get(0), frame.effect);
    }

    private static String read(String path) throws IOException {
        try (InputStream in = GpuScreenTest.class.getResourceAsStream(path)) {
            assertNotNull(path, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
