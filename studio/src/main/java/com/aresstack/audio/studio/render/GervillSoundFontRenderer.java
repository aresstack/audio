package com.aresstack.audio.studio.render;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Run Gervill inside the current Java installation without any native sampler executable. */
public final class GervillSoundFontRenderer extends SamplerStemRenderer {
    private final ExternalProcessRunner processRunner;

    public GervillSoundFontRenderer(ExternalProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    public void render(Path soundFont, Path midi, Path output) throws Exception {
        renderFile(soundFont, midi, output, RenderLimits.STANDARD);
    }

    @Override
    public String engine() {
        return "gervill";
    }

    @Override
    protected String settings() {
        // Gervill is part of the JDK; another Java may sound different.
        return "gervill\t44100 Hz stereo\ttail 2.5 s\tjava " + System.getProperty("java.specification.version");
    }

    @Override
    protected void run(Path soundFont, Path midi, Path output, RenderLimits limits, double seconds)
            throws Exception {
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
        command.add(Long.toString(limits.maxPiece().toSeconds()));
        processRunner.execute(command, output, limits.processTimeout(seconds));
    }

    private static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toString();
    }
}
