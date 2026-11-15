package com.aresstack.audio.studio.render;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import java.nio.file.Path;

/**
 * What a {@link StemRenderer} needs for one part.
 *
 * @param composition the piece the part belongs to
 * @param part        the part whose stem is wanted
 * @param midi        the part alone as a MIDI file ({@link PartMidi}), already written
 * @param soundbank   the sound bank file, or {@code null} for the built-in sound
 * @param limits      the limits of the render path
 */
public record StemJob(Composition composition, Part part, Path midi, Path soundbank, RenderLimits limits) {
    /** The length of the piece in seconds, to its last whole bar. */
    public double pieceSeconds() {
        return pieceSeconds(composition);
    }

    /** The length of a piece in seconds, to the end of the bar its last note or section ends in. */
    public static double pieceSeconds(Composition composition) {
        var time = composition.timeMap();
        return time.seconds(time.tickOfBar(time.barsCovering(composition.endTick())));
    }
}
