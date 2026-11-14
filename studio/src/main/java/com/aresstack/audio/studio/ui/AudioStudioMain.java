package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentRegistry;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Start the Java-8-compatible desktop studio or run its headless smoke test. */
public final class AudioStudioMain {
    private AudioStudioMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "--smoke-test".equals(arguments[0])) {
            verifyHeadlessStartup();
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Audio Studio requires a graphical desktop.");
        }
        final InstrumentRegistry registry = InstrumentRegistry.standard();
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new AudioStudioFrame(registry).setVisible(true);
            }
        });
    }

    private static void verifyHeadlessStartup() throws Exception {
        if (InstrumentCatalog.standard().size() < 9) {
            throw new IllegalStateException("Instrument catalog is incomplete.");
        }
        Path temp = Files.createTempDirectory("audio-studio-smoke-");
        try {
            InstrumentRegistry registry = new InstrumentRegistry(temp.resolve("libraries.properties"));
            if (registry.isInstalled("generaluser")) {
                throw new IllegalStateException("Empty instrument registry should not contain sample banks.");
            }
            System.out.println("Audio Studio headless smoke test passed: "
                    + InstrumentCatalog.standard().size() + " instrument library entries.");
        } finally {
            Files.deleteIfExists(temp.resolve("libraries.properties"));
            Files.deleteIfExists(temp);
        }
    }
}
