package com.aresstack.audio.sounds;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Render every effect in a structured JSON plan to its own WAV file. */
public final class SoundEffectRenderer {
    private SoundEffectRenderer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: SoundEffectRenderer sound-plan.json output-directory");
        }
        Path output = Paths.get(args[1]);
        Files.createDirectories(output);
        SoundSynthesizer synthesizer = new SoundSynthesizer();
        for (SoundDesign design : new SoundPlanReader().read(Paths.get(args[0]))) {
            Path file = output.resolve(design.id() + ".wav");
            synthesizer.render(design, file);
            System.out.println("Rendered " + file);
        }
    }
}
