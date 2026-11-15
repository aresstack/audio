package com.aresstack.audio.studio.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Watches whether the window still answers. When the event thread does not answer for a while,
 * where it hangs is written to a file, while it hangs, so that a frozen window can be explained
 * afterwards; once it answers again the protocol says how long it hung and where the file is.
 */
final class EdtWatch {
    /** From how many seconds without an answer the window counts as hanging. */
    static final int HANG_SECONDS = 5;
    private final Path folder;
    private final Consumer<String> protocol;
    private volatile long answered = System.currentTimeMillis();

    EdtWatch(Path folder, Consumer<String> protocol) {
        this.folder = folder;
        this.protocol = protocol;
    }

    void start() {
        Thread watch = new Thread(this::watch, "edt-watch");
        watch.setDaemon(true);
        watch.start();
    }

    private void watch() {
        boolean hanging = false;
        Path written = null;
        long hungSince = 0;
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException stopped) {
                return;
            }
            SwingUtilities.invokeLater(() -> answered = System.currentTimeMillis());
            long silent = System.currentTimeMillis() - answered;
            if (silent > HANG_SECONDS * 1000L) {
                if (!hanging) {
                    hanging = true;
                    hungSince = answered;
                    written = write(silent);
                }
            } else if (hanging) {
                hanging = false;
                String where = written == null ? "" : "; wo sie hing, steht in " + written;
                long hung = (answered - hungSince) / 1000;
                SwingUtilities.invokeLater(() -> protocol.accept("WARN: Die Oberfläche hing etwa " + hung + " s"
                        + where));
            }
        }
    }

    /** Writes where every thread is, the event thread first; the file, or {@code null} if it failed. */
    private Path write(long silent) {
        StringBuilder text = new StringBuilder("Die Oberfläche antwortet seit " + silent / 1000 + " s nicht.\n\n");
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (boolean edt : new boolean[]{true, false}) {
            for (Map.Entry<Thread, StackTraceElement[]> each : all.entrySet()) {
                if (each.getKey().getName().startsWith("AWT-EventQueue") != edt) {
                    continue;
                }
                text.append('"').append(each.getKey().getName()).append("\" ").append(each.getKey().getState())
                        .append('\n');
                for (StackTraceElement at : each.getValue()) {
                    text.append("    at ").append(at).append('\n');
                }
                text.append('\n');
            }
        }
        try {
            Files.createDirectories(folder);
            Path file = folder.resolve("haenger-" + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
            Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
            System.err.println("Die Oberfläche hängt; wo, steht in " + file);
            return file;
        } catch (IOException | RuntimeException unwritten) {
            System.err.println(text);
            return null;
        }
    }
}
