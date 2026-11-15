package com.aresstack.audio.studio.render;

import java.nio.file.Path;

/**
 * Turns the notes of one part into one stem (architecture review 2.5, E7.2). There is one
 * implementation per sound engine: the built-in Java sound, Gervill, FluidSynth and sfizz.
 * Which one plays a part is chosen by {@link InstrumentSampleRenderer#select}.
 *
 * <p>The stem cache asks {@link #identity} what besides the MIDI of the part decides the
 * sound; together they make the cache key. A renderer must name everything there that changes
 * what it writes for the same notes (engine version, sample rate, settings, the sound bank),
 * and nothing that does not, or stems are rendered again for nothing.
 */
public interface StemRenderer {
    /** A short, stable name of the engine: "builtin", "gervill", "fluidsynth", "sfizz". */
    String engine();

    /** Everything apart from the MIDI of the part that decides how the stem sounds, as text. */
    String identity(StemJob job) throws Exception;

    /** Renders the stem of the job into the output file (a WAV), replacing it. */
    void render(StemJob job, Path output) throws Exception;
}
