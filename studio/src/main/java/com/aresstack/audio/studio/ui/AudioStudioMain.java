package com.aresstack.audio.studio.ui;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.instruments.network.AudioProxyRoutes;
import com.aresstack.audio.studio.application.GemmaMusicComposer;
import com.aresstack.audio.studio.application.StudioMusicRenderer;
import com.aresstack.audio.studio.keepass.AudioKeePassService;
import com.aresstack.audio.studio.progress.Progress;
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
        AudioStudioTheme.install();
        final InstrumentRegistry registry = InstrumentRegistry.standard();
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                new AudioStudioFrame(registry).setVisible(true);
            }
        });
    }

    private static void verifyHeadlessStartup(Path optionalSoundFont) throws Exception {
        // Verify the desktop theme and all runtime dependencies on Java 8, without opening a window.
        AudioStudioTheme.install();
        Class.forName("com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcLink");
        java.util.Properties proxySettings = new java.util.Properties();
        proxySettings.setProperty("proxyMode", "DISABLED");
        AudioProxyRoutes proxy = new AudioProxyRoutes(proxySettings);
        if (proxy.proxyFor(java.net.URI.create("https://example.org")) != java.net.Proxy.NO_PROXY) {
            throw new IllegalStateException("The proxy-disabled smoke test attempted networking.");
        }
        if (InstrumentCatalog.standard().size() < 9) {
            throw new IllegalStateException("Instrument catalog is incomplete.");
        }
        Path sandbox = Files.createTempDirectory("audio-studio-smoke-");
        try {
            if (AudioKeePassService.isPaired(sandbox)) {
                throw new IllegalStateException("A fresh temporary profile cannot be KeePass-paired.");
            }
            InstrumentRegistry registry = new InstrumentRegistry(sandbox.resolve("libraries.properties"));
            verifyDirectoryImport(registry, sandbox);
            Path tsv = copyBundledComposition(sandbox);
            verifyPieceModel(sandbox, tsv);
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
                    new Progress() {
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
            System.out.println("Studio smoke test passed: SFZ directory import, piece model, "
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

    /**
     * Opening the bundled piece takes it into the model, as opening any piece from before the
     * model does: its arrangement stays byte for byte, in place and in the folder "legacy".
     */
    private static void verifyPieceModel(Path folder, Path arrangement) throws Exception {
        byte[] written = Files.readAllBytes(arrangement);
        java.util.Optional<com.aresstack.audio.studio.application.ProjectSession> session =
                com.aresstack.audio.studio.application.ProjectSession.open(folder);
        if (!session.isPresent() || session.get().composition().tracks().isEmpty()
                || !Files.isRegularFile(folder.resolve("composition.json"))) {
            throw new IllegalStateException("The bundled piece was not taken into the model.");
        }
        if (!java.util.Arrays.equals(written, Files.readAllBytes(arrangement))
                || !java.util.Arrays.equals(written, Files.readAllBytes(folder.resolve("legacy").resolve("arrangement.tsv")))) {
            throw new IllegalStateException("Opening the bundled piece changed its arrangement.");
        }
        byte[] model = Files.readAllBytes(folder.resolve("composition.json"));
        com.aresstack.audio.studio.application.ProjectSession again =
                com.aresstack.audio.studio.application.ProjectSession.open(folder).get();
        if (again.externalChanges() || again.synchronize()
                || !java.util.Arrays.equals(model, Files.readAllBytes(folder.resolve("composition.json")))) {
            throw new IllegalStateException("A piece that was not touched changed when it was opened again.");
        }
        System.out.println("Piece model: " + session.get().composition().tracks().size() + " tracks, "
                + session.get().composition().parts().size() + " parts taken over; old files kept.");
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
