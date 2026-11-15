package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Role;
import java.util.List;

/**
 * What the user asks for when a new voice is to be composed into a piece ({@code ADD_TRACK},
 * architecture review E4; report 6): which instrument in which role, where it plays, what it
 * should do with the motifs already there.
 *
 * @param name        the name of the new part as the user sees it, for example "Trompete"
 * @param instrument  the instrument in words, for the model and the display
 * @param program     the General MIDI program, 0 to 127
 * @param role        one of {@link Role#ALL}
 * @param libraryId   the sound library of the new part, {@code null} for the built-in sound
 * @param sectionIds  the sections the new voice plays in; empty for the whole piece
 * @param instruction what the new voice should do, in the user's words; may be empty
 * @param motifPartId the part whose motifs the new voice should take up, or {@code null}
 * @param motifUse    what to do with those motifs
 * @param plan        whether a planning step decides first where and how the voice plays; without
 *                    it the voice plays in every chosen section
 */
public record AddVoiceRequest(String name, String instrument, int program, String role, String libraryId,
                              List<String> sectionIds, String instruction, String motifPartId, MotifUse motifUse,
                              boolean plan) {
    /** What the new voice does with motifs that are already in the piece. */
    public enum MotifUse {
        /** Free invention within the harmony. */
        FREE,
        /** Take a motif up recognisably, for example as an answer or in unison. */
        QUOTE,
        /** Vary a motif: rhythm, contour or harmony changed, still recognisable. */
        VARY
    }

    public AddVoiceRequest {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Die neue Stimme braucht einen Namen.");
        }
        instrument = instrument == null || instrument.isBlank() ? name : instrument;
        if (program < 0 || program > 127) {
            throw new IllegalArgumentException("Ein General-MIDI-Programm liegt zwischen 0 und 127: " + program);
        }
        if (!Role.ALL.contains(role)) {
            throw new IllegalArgumentException("Unbekannte Rolle: " + role + "; erlaubt sind " + Role.ALL);
        }
        sectionIds = List.copyOf(sectionIds == null ? List.of() : sectionIds);
        instruction = instruction == null ? "" : instruction.strip();
        motifUse = motifUse == null ? MotifUse.FREE : motifUse;
        if (motifUse != MotifUse.FREE && motifPartId == null) {
            throw new IllegalArgumentException("Für " + motifUse + " muss eine Stimme mit dem Motiv genannt sein.");
        }
    }

    /** A request for a voice over the whole piece, without planning step and motif rules. */
    public static AddVoiceRequest of(String name, String instrument, int program, String role) {
        return new AddVoiceRequest(name, instrument, program, role, null, List.of(), "", null, MotifUse.FREE, false);
    }
}
