package com.aresstack.audio.gpu.internal;

import com.aresstack.audio.gpu.GpuFrame;
import com.aresstack.audio.gpu.GpuScreen;
import com.aresstack.threed4j.gfx.GraphicsDevice;
import com.aresstack.threed4j.gfx.Mesh;
import com.aresstack.threed4j.gfx.Shader;
import com.aresstack.threed4j.gfx.ShaderLayout;
import com.aresstack.threed4j.gfx.Texture;
import com.aresstack.threed4j.gfx.d3d11.D3D11Device;
import com.aresstack.threed4j.gfx.gl.GLDevice;
import com.aresstack.threed4j.math.Vec3;
import com.aresstack.threed4j.platform.Key;
import com.aresstack.threed4j.platform.MouseButton;
import com.aresstack.threed4j.platform.Window;
import com.aresstack.threed4j.platform.WindowConfig;
import com.aresstack.threed4j.platform.win32.Win32Window;
import com.aresstack.threed4j.platform.x11.X11Window;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The native screen: a window of 3d4j over the whole screen, one rectangle over it and the shader
 * {@code visual} that draws the chosen effect from the spectrum, the waveform and the levels.
 * Everything here runs on the render thread that called {@link #run}.
 */
public final class NativeScreen implements GpuScreen.Native {
    /** The size of the window before it fills the screen. */
    private static final int START_WIDTH = 1280;
    private static final int START_HEIGHT = 720;

    private volatile boolean closeWanted;

    @Override
    public void requestClose() {
        closeWanted = true;
    }

    @Override
    public void run(GpuScreen.Settings settings, GpuScreen.Source source, Consumer<String> ready, Runnable stopped)
            throws Exception {
        WindowConfig config = new WindowConfig(settings.title(), START_WIDTH, START_HEIGHT, settings.vsync());
        Opened opened = open(config, settings.backend());
        try (Window window = opened.window; GraphicsDevice graphics = opened.graphics) {
            window.setFullscreen(true);
            Shader shader = graphics.createShader("visual", ShaderLayout.attributes(2, 2)
                    .vec3("uTime").vec3("uLevels").vec3("uScreen"));
            Mesh quad = graphics.createMesh(new float[]{
                    -1, -1, 0, 1,
                    1, -1, 1, 1,
                    1, 1, 1, 0,
                    -1, 1, 0, 0}, new int[]{0, 1, 2, 0, 2, 3}, 2, 2);
            GpuFrame frame = new GpuFrame(settings.points());
            int[] pixels = new int[settings.points()];
            Texture texture = null;
            long started = System.nanoTime();
            long lastClick = Long.MIN_VALUE / 2;
            ready.accept(graphics.description());
            try {
                while (!closeWanted && !window.shouldClose()) {
                    window.input().beginFrame();
                    window.pollEvents();
                    if (window.input().wasPressed(Key.ESCAPE)) {
                        break;
                    }
                    if (window.input().wasPressed(MouseButton.LEFT)) {
                        long now = System.nanoTime();
                        if (now - lastClick <= settings.doubleClickMs() * 1_000_000L) {
                            break;
                        }
                        lastClick = now;
                    }
                    frame.seconds = (float) ((System.nanoTime() - started) / 1e9);
                    source.fill(frame);
                    for (int i = 0; i < pixels.length; i++) {
                        int band = channel(frame.bands[i]);
                        int wave = channel(frame.wave[i] * 0.5f + 0.5f);
                        pixels[i] = 0xFF000000 | band << 16 | wave << 8;
                    }
                    if (texture != null) {
                        texture.close();
                    }
                    texture = graphics.createTexture(pixels.length, 1, pixels, true);
                    graphics.beginFrame(window.width(), window.height());
                    graphics.clear(0, 0, 0);
                    shader.setUniform("uTime", new Vec3(frame.seconds, frame.beat, frame.loudness));
                    shader.setUniform("uLevels", new Vec3(frame.bass, frame.middle, frame.treble));
                    shader.setUniform("uScreen", new Vec3(graphics.sceneWidth(), graphics.sceneHeight(),
                            Math.max(0, GpuScreen.EFFECTS.indexOf(frame.effect))));
                    shader.setTexture(texture);
                    graphics.draw(quad, shader);
                    graphics.present();
                }
            } finally {
                if (texture != null) {
                    texture.close();
                }
                quad.close();
                shader.close();
            }
        } finally {
            stopped.run();
        }
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }

    private record Opened(Window window, GraphicsDevice graphics) {
    }

    /** As {@code Display.open} of 3d4j: Direct3D 11 on Windows and OpenGL if that fails; OpenGL on Linux. */
    private static Opened open(WindowConfig config, String wanted) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String backend = wanted == null ? "auto" : wanted.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            if (!backend.equals("opengl")) {
                Win32Window window = new Win32Window(config, false);
                try {
                    return new Opened(window, new D3D11Device(window, config.vsync()));
                } catch (RuntimeException | LinkageError direct3D) {
                    window.close();
                    if (backend.equals("d3d11")) {
                        throw direct3D;
                    }
                }
            }
            Win32Window window = new Win32Window(config, true);
            try {
                return new Opened(window, new GLDevice(window));
            } catch (RuntimeException | LinkageError openGL) {
                window.close();
                throw openGL;
            }
        }
        if (os.contains("linux") || os.contains("bsd")) {
            X11Window window = new X11Window(config);
            try {
                return new Opened(window, new GLDevice(window));
            } catch (RuntimeException | LinkageError openGL) {
                window.close();
                throw openGL;
            }
        }
        throw new UnsupportedOperationException("Kein Grafikfenster für " + os);
    }
}
