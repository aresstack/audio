package com.aresstack.audio.studio.render;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** FluidSynth (SF2, SF3) or sfizz (SFZ) as an external program. */
public final class CommandStemRenderer extends SamplerStemRenderer {
    public enum Program { FLUIDSYNTH, SFIZZ }

    private final Program program;
    private final String executable;
    private final ExternalProcessRunner processRunner;

    public CommandStemRenderer(Program program, String executable, ExternalProcessRunner processRunner) {
        this.program = program;
        this.executable = executable == null ? "" : executable.trim();
        this.processRunner = processRunner;
    }

    @Override
    public String engine() {
        return program == Program.SFIZZ ? "sfizz" : "fluidsynth";
    }

    @Override
    protected String settings() {
        return program == Program.SFIZZ
                ? "sfizz\t" + executable + "\t--samplerate 44100"
                : "fluidsynth\t" + executable + "\t-g 0.35 -r 44100";
    }

    /** The command line that renders the MIDI file with the sound bank. */
    public List<String> command(Path soundbank, Path midi, Path output) {
        List<String> command = new ArrayList<String>();
        if (program == Program.SFIZZ) {
            command.add(executable);
            command.add("--sfz");
            command.add(soundbank.toAbsolutePath().toString());
            command.add("--midi");
            command.add(midi.toAbsolutePath().toString());
            command.add("--wav");
            command.add(output.toAbsolutePath().toString());
            command.add("--samplerate");
            command.add("44100");
        } else {
            command.add(executable);
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
        return command;
    }

    @Override
    protected void run(Path soundbank, Path midi, Path output, RenderLimits limits, double seconds)
            throws Exception {
        processRunner.execute(command(soundbank, midi, output), output, limits.processTimeout(seconds));
    }
}
