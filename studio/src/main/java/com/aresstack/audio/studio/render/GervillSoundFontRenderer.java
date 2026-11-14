package com.aresstack.audio.studio.render;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Run Gervill inside the current Java installation without any native sampler executable. */
public final class GervillSoundFontRenderer {
    private final ExternalProcessRunner processRunner;

    public GervillSoundFontRenderer(ExternalProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    public void render(Path soundFont, Path midi, Path output) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        if (!System.getProperty("java.specification.version", "1.8").startsWith("1.")) {
            command.add("--add-exports");
            command.add("java.desktop/com.sun.media.sound=ALL-UNNAMED");
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(GervillOfflineRenderer.class.getName());
        command.add(midi.toAbsolutePath().toString());
        command.add(soundFont.toAbsolutePath().toString());
        command.add(output.toAbsolutePath().toString());
        processRunner.execute(command, output);
    }

    private static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toString();
    }
}
