package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.application.GemmaMusicComposer;
import com.aresstack.audio.studio.application.StudioMusicRenderer;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Launch the desktop studio and check its entire local rendering pipeline without a desktop. */
public final class AudioStudioMain {
    private AudioStudioMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && "--smoke-test".equals(arguments[0])) {
            verifyHeadlessStartup(arguments.length >= 2
                    ? java.nio.file.Paths.get(arguments[1]) : null);
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

    private static void verifyHeadlessStartup(Path optionalSoundFont) throws Exception {
        if (InstrumentCatalog.standard().size() < 9) {
            throw new IllegalStateException("Instrument catalog is incomplete.");
        }
        Path sandbox = Files.createTempDirectory("audio-studio-smoke-");
        try {
            InstrumentRegistry registry = new InstrumentRegistry(sandbox.resolve("libraries.properties"));
            verifyDirectoryImport(registry, sandbox);
            Path tsv = copyBundledComposition(sandbox);
            Map<String, InstrumentLibrary> assignments =
                    new HashMap<String, InstrumentLibrary>();
            if (optionalSoundFont != null) {
                InstrumentLibrary generalUser = InstrumentCatalog.standard().get(0);
                registry.importLocation(generalUser, optionalSoundFont);
                for (InstrumentLibrary installed : registry.installedInstruments()) {
                    if (installed.id().startsWith("generaluser::")) {
                        assignments.put("melody", installed);
                        break;
                    }
                }
                if (!assignments.containsKey("melody")) {
                    throw new IllegalStateException("Failed to register the test SoundFont.");
                }
            }
            Path mixed = new StudioMusicRenderer(registry).render(
                    tsv, sandbox.resolve("render"), assignments, "fluidsynth", "sfizz_render",
                    new GemmaMusicComposer.Progress() {
                        @Override
                        public void update(String message) {
                            System.out.println(message);
                        }
                    });
            if (!Files.isRegularFile(mixed) || Files.size(mixed) < 1000
                    || !Files.isRegularFile(mixed.getParent().resolve("arrangement.mid"))) {
                throw new IllegalStateException("The studio did not produce MIDI and mixed WAV.");
            }
            try (AudioInputStream stream = AudioSystem.getAudioInputStream(mixed.toFile())) {
                if (stream.getFrameLength() < 44100) {
                    throw new IllegalStateException("The mixed WAV is unexpectedly short.");
                }
            }
            System.out.println("Studio smoke test passed: SFZ directory import, "
                    + (optionalSoundFont == null ? "Java fallback" : "GeneralUser sampled melody")
                    + ", MIDI export and WAV mix.");
        } finally {
            removeTree(sandbox);
        }
    }

    private static void verifyDirectoryImport(InstrumentRegistry registry, Path sandbox)
            throws Exception {
        Path folder = Files.createDirectories(sandbox.resolve("sfz-library"));
        Files.write(folder.resolve("choir.sfz"), new byte[]{'<', 'g', 'r', 'o', 'u', 'p', '>'});
        Files.write(folder.resolve("brass.sfz"), new byte[]{'<', 'g', 'r', 'o', 'u', 'p', '>'});
        Files.write(folder.resolve("ignored.txt"), new byte[]{'x'});
        int imported = registry.importLocation(InstrumentCatalog.standard().get(8), folder);
        if (imported != 2 || registry.installedInstruments().size() != 2) {
            throw new IllegalStateException("Importing two SFZ presets should yield two instruments.");
        }
        InstrumentRegistry reloaded = new InstrumentRegistry(sandbox.resolve("libraries.properties"));
        if (reloaded.installedInstruments().size() != 2) {
            throw new IllegalStateException("Imported presets were not persisted.");
        }
    }

    private static Path copyBundledComposition(Path directory) throws Exception {
        Path output = directory.resolve("arrangement.tsv");
        try (InputStream input = AudioStudioMain.class.getResourceAsStream("/district-dawn.tsv")) {
            if (input == null) {
                throw new IllegalStateException("Original Gemma demo is not bundled.");
            }
            Files.copy(input, output);
        }
        return output;
    }

    private static void removeTree(Path directory) throws Exception {
        try (Stream<Path> children = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) children.sorted(Collections.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
