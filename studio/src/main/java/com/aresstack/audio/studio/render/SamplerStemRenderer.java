package com.aresstack.audio.studio.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import javax.sound.midi.MidiSystem;

/** A stem renderer that plays a MIDI file with a sound bank, in a process of its own. */
public abstract class SamplerStemRenderer implements StemRenderer {
    /** Renders a MIDI file with a sound bank to a WAV; the length of the MIDI file is checked first. */
    public final void renderFile(Path soundbank, Path midi, Path output, RenderLimits limits) throws Exception {
        double seconds = midiSeconds(midi);
        limits.checkPiece(seconds, "The MIDI file " + midi.getFileName());
        run(soundbank, midi, output, limits, seconds);
    }

    @Override
    public final void render(StemJob job, Path output) throws Exception {
        if (job.soundbank() == null) {
            throw new IllegalArgumentException(engine() + " needs a sound bank for " + job.part().id());
        }
        renderFile(job.soundbank(), job.midi(), output, job.limits());
    }

    @Override
    public final String identity(StemJob job) throws IOException {
        return settings() + "\nbank\t" + fingerprint(job.soundbank());
    }

    /** The settings of the engine that shape its sound, as text. */
    protected abstract String settings();

    protected abstract void run(Path soundbank, Path midi, Path output, RenderLimits limits, double seconds)
            throws Exception;

    /** A sound bank as the cache knows it: name, size and time of the last change. */
    static String fingerprint(Path soundbank) throws IOException {
        if (soundbank == null) {
            return "none";
        }
        FileTime changed = Files.getLastModifiedTime(soundbank);
        return soundbank.getFileName() + "\t" + Files.size(soundbank) + "\t" + changed.toMillis();
    }

    static double midiSeconds(Path midi) throws IOException {
        try {
            return MidiSystem.getSequence(midi.toFile()).getMicrosecondLength() / 1000000.0;
        } catch (javax.sound.midi.InvalidMidiDataException invalid) {
            throw new IOException("Not a MIDI file: " + midi, invalid);
        }
    }
}
