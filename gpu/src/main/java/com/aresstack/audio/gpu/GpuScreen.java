package com.aresstack.audio.gpu;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A window that fills the screen and draws the visualizer with pixel shaders on the graphics card:
 * Direct3D 11 on Windows (with WARP, the software renderer of Windows, where no driver is),
 * OpenGL 3.3 on Linux. It runs on a thread of its own, which asks the studio for every picture
 * ({@link Source#fill}). Escape, a double-click or closing the window ends it.
 *
 * <p>This class uses no preview feature; the native part is loaded by name only when the screen
 * opens, so a JVM without {@code --enable-preview} merely gets a {@link GpuUnavailableException}.
 */
public final class GpuScreen implements AutoCloseable {
    /** The effects the graphics card draws, by the keys the studio uses for them. */
    public static final List<String> EFFECTS = List.of("spectrum", "tunnel", "particles", "alchemy");

    /** The studio's side of a screen: called on the render thread. */
    public interface Source {
        /** Fills the frame for the next picture. */
        void fill(GpuFrame frame);
    }

    /** What the native part does; implemented in {@code internal}, loaded by name. */
    public interface Native {
        /** Opens the window on the calling thread; {@code ready} is told the description of the device. */
        void run(Settings settings, Source source, Consumer<String> ready, Runnable stopped) throws Exception;

        /** Asks the window to close; any thread. */
        void requestClose();
    }

    /**
     * @param title          the title of the window (shown in the task bar)
     * @param points         bands and wave points handed to the shaders
     * @param vsync          wait for the screen's refresh between pictures
     * @param doubleClickMs  the longest time between the two clicks of a double-click
     * @param openSeconds    how long opening may take before it counts as failed
     * @param backend        auto, d3d11 or opengl, as in 3d4j
     */
    public record Settings(String title, int points, boolean vsync, int doubleClickMs, double openSeconds,
                           String backend) {
    }

    private static final String NATIVE = "com.aresstack.audio.gpu.internal.NativeScreen";

    private final Native screen;
    private final Thread thread;
    private final String description;

    private GpuScreen(Native screen, Thread thread, String description) {
        this.screen = screen;
        this.thread = thread;
        this.description = description;
    }

    /** Whether this JVM may try at all: Java 21 started with --enable-preview. {@code null} if so, else why not. */
    public static String whyNot() {
        if (Runtime.version().feature() != 21) {
            return "Die Grafikkarte wird nur mit Java 21 genutzt, laufend ist Java " + Runtime.version().feature() + ".";
        }
        boolean preview = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                .contains("--enable-preview");
        if (!preview) {
            return "Java läuft ohne --enable-preview; die Grafikkarte bleibt ungenutzt.";
        }
        return null;
    }

    /**
     * Opens the screen and returns once it draws.
     *
     * @param stopped told on the render thread when the window has closed, for whatever reason
     */
    public static GpuScreen open(Settings settings, Source source, Runnable stopped) throws GpuUnavailableException {
        String reason = whyNot();
        if (reason != null) {
            throw new GpuUnavailableException(reason, null);
        }
        Native screen;
        try {
            screen = (Native) Class.forName(NATIVE).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException missing) {
            throw new GpuUnavailableException("Der Grafikteil lässt sich nicht laden: " + missing, missing);
        }
        CountDownLatch opened = new CountDownLatch(1);
        AtomicReference<String> device = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                screen.run(settings, source, said -> {
                    device.set(said);
                    opened.countDown();
                }, () -> { });
            } catch (Throwable failed) {
                failure.set(failed);
            } finally {
                opened.countDown();
                if (stopped != null) {
                    stopped.run();
                }
            }
        }, "visualizer-gpu");
        thread.setDaemon(true);
        thread.start();
        try {
            if (!opened.await((long) (settings.openSeconds() * 1000), TimeUnit.MILLISECONDS)) {
                screen.requestClose();
                throw new GpuUnavailableException("Die Grafikkarte hat nicht rechtzeitig geantwortet.", null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            screen.requestClose();
            throw new GpuUnavailableException("Unterbrochen beim Öffnen.", interrupted);
        }
        Throwable failed = failure.get();
        if (failed != null || device.get() == null) {
            throw new GpuUnavailableException("Die Grafikkarte lässt sich nicht nutzen: "
                    + (failed == null ? "das Fenster ging gleich wieder zu" : failed), failed);
        }
        return new GpuScreen(screen, thread, device.get());
    }

    /** The graphics API and device, as the engine names them (e.g. Direct3D 11 … | hardware). */
    public String description() {
        return description;
    }

    /** Whether the window is still open. */
    public boolean open() {
        return thread.isAlive();
    }

    /** Closes the window; it closes on its own thread a moment later. */
    @Override
    public void close() {
        screen.requestClose();
    }
}
