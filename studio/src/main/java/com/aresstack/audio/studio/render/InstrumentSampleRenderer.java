package com.aresstack.audio.studio.render;

import com.aresstack.audio.instruments.InstrumentLibrary;
import java.nio.file.Path;

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
        select(library, sf2Engine, fluidSynthCommand, sfizzCommand)
                .renderFile(soundbank, midi, output, RenderLimits.STANDARD);
    }

    /** The engine that plays a sound bank: Gervill or FluidSynth for SF2, FluidSynth for SF3, sfizz for SFZ. */
    public SamplerStemRenderer select(InstrumentLibrary library, Sf2Engine sf2Engine,
                                      String fluidSynthCommand, String sfizzCommand) {
        if (library.format() == InstrumentLibrary.Format.SF2 && sf2Engine == Sf2Engine.GERVILL) {
            return gervill;
        }
        if (library.format() == InstrumentLibrary.Format.SFZ) {
            return new CommandStemRenderer(CommandStemRenderer.Program.SFIZZ, sfizzCommand, processRunner);
        }
        return new CommandStemRenderer(CommandStemRenderer.Program.FLUIDSYNTH, fluidSynthCommand, processRunner);
    }

    /**
     * Whether a sound bank follows General MIDI: then the program of a part picks its instrument;
     * any other bank holds one instrument at program 0.
     */
    public static boolean generalMidi(InstrumentLibrary library) {
        String id = library.id();
        return id.equals("generaluser") || id.startsWith("generaluser::")
                || id.equals("musescore-general") || id.startsWith("musescore-general::");
    }
}
