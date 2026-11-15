package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.studio.score.Voicing;
import java.util.List;

/**
 * Everything the planner and the generator of a new voice are told: the context of the piece,
 * the request, the id the new part will have, the time map of the piece, the frames it may play
 * in, its range, and which
 * attempt this is (attempts after the first ask for a different version and get their own cache).
 */
public record VoiceTask(MusicalContext context, AddVoiceRequest request, String partId, TimeMap time,
                        List<VoiceFrame> frames,
                        InstrumentRange range, int attempt) {
    public VoiceTask {
        frames = List.copyOf(frames);
        if (frames.isEmpty() || attempt < 1) {
            throw new IllegalArgumentException("A voice task needs a frame and an attempt from 1.");
        }
    }

    /** Whether the new part is written on a grand staff (piano, organ, harp; work package S1). */
    public boolean grand() {
        return Voicing.layoutFor(request.program(), Role.DRUMS.equals(request.role())) == Part.StaffLayout.GRAND;
    }

    /** Part, instrument and role of the new voice in one line, for prompts. */
    String target() {
        return "id=" + partId + ", name=\"" + request.name() + "\", instrument=" + request.instrument() + ", role="
                + request.role() + ", General MIDI program=" + request.program() + " (zero-based), playable range "
                + ContextBuilder.pitch(range.low()) + "/" + range.low() + " to " + ContextBuilder.pitch(range.high()) + "/"
                + range.high();
    }

    /** The user's wishes on motifs, for prompts; empty for free invention. */
    String motifRule() {
        return switch (request.motifUse()) {
            case FREE -> "";
            case QUOTE -> "Take up the motifs of part " + request.motifPartId()
                    + " (MOTIF-SOURCE) recognisably: answer, echo or double them where it fits.";
            case VARY -> "Vary the motifs of part " + request.motifPartId()
                    + " (MOTIF-SOURCE): keep them recognisable, change rhythm, contour or register.";
        };
    }

    /** What makes a later attempt differ from the first; empty for the first. */
    String take() {
        return attempt == 1 ? "" : "\nAttempt " + attempt + ": write a clearly different version than before.";
    }
}
