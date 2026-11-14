package com.aresstack.audio.studio.render;

import com.aresstack.audio.instruments.InstrumentLibrary;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Choose a soundbank backend by format and the selected SF2 engine. */
public final class InstrumentSampleRenderer {
    public enum Sf2Engine { GERVILL, FLUIDSYNTH }

    private final ExternalProcessRunner processRunner = new ExternalProcessRunner();
    private final GervillSoundFontRenderer gervill =
            new GervillSoundFontRenderer(processRunner);

    public void render(InstrumentLibrary library, Path soundbank, Path midi, Path output,
                       String fluidSynthCommand, String sfizzCommand) throws Exception {
        render(library, soundbank, midi, output, Sf2Engine.GERVILL,
                fluidSynthCommand, sfizzCommand);
    }

    public void render(InstrumentLibrary library, Path soundbank, Path midi, Path output,
                       Sf2Engine sf2Engine, String fluidSynthCommand, String sfizzCommand)
            throws Exception {
        if (library.format() == InstrumentLibrary.Format.SF2
                && sf2Engine == Sf2Engine.GERVILL) {
            gervill.render(soundbank, midi, output);
            return;
        }
        List<String> command = new ArrayList<String>();
        if (library.format() == InstrumentLibrary.Format.SFZ) {
            command.add(sfizzCommand.trim());
            command.add("--sfz");
            command.add(soundbank.toAbsolutePath().toString());
            command.add("--midi");
            command.add(midi.toAbsolutePath().toString());
            command.add("--wav");
            command.add(output.toAbsolutePath().toString());
            command.add("--samplerate");
            command.add("44100");
        } else {
            command.add(fluidSynthCommand.trim());
            command.add("-n");
            command.add("-i");
            command.add("-g");
            command.add("0.35");
            command.add("-r");
            command.add("44100");
            command.add("-T");
            command.add("wav");
            command.add("-F");
            command.add(output.toAbsolutePath().toString());
            command.add(soundbank.toAbsolutePath().toString());
            command.add(midi.toAbsolutePath().toString());
        }
        processRunner.execute(command, output);
    }
}
