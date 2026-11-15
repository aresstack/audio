package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;

/**
 * A part as the generators of a continuation see it: an existing part that goes on, or a new
 * instrument of the plan.
 *
 * @param key   the id of the existing part, or the key of the new part in the plan ("new1")
 * @param role  one of {@link Role#ALL}, or {@code null}
 * @param idea  the job of a new part in the plan; empty for an existing part
 */
public record ContinuationVoice(String key, String name, String instrument, int program, String role,
                                boolean newPart, String idea) {
    public static ContinuationVoice of(Part part) {
        return new ContinuationVoice(part.id(), part.name(), part.instrument() == null ? part.name() : part.instrument(),
                part.program(), part.role(), false, "");
    }

    public static ContinuationVoice of(ContinuationPlan.NewPart part) {
        return new ContinuationVoice(part.key(), part.name(), part.instrument(), part.program(), part.role(), true,
                part.idea());
    }

    public boolean drums() {
        return Role.DRUMS.equals(role);
    }

    /** The pitches its notes are limited to. */
    public InstrumentRange range() {
        return InstrumentRange.of(program, drums());
    }

    /** The voice in one line, for prompts. */
    String describe() {
        InstrumentRange range = range();
        return "id=" + key + ", name=\"" + name.replace('"', '\'') + "\", instrument=" + instrument.replace('"', '\'')
                + ", role=" + (role == null ? "-" : role) + ", General MIDI program=" + program + ", playable range "
                + ContextBuilder.pitch(range.low()) + "/" + range.low() + " to " + ContextBuilder.pitch(range.high())
                + "/" + range.high();
    }
}
