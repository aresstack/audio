package com.aresstack.audio.studio.ai;

import com.aresstack.audio.composition.Composition;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The ids of the objects a new voice brings: its part, the part's one voice, its track, and a
 * source for clips and notes. Part, voice and track keep their ids when the voice is generated
 * again, so that every attempt is a proposal for the same new part.
 */
public record NewVoiceIds(String partId, String voiceId, String trackId, Supplier<String> more) {
    public NewVoiceIds {
        if (partId == null || voiceId == null || trackId == null || more == null) {
            throw new IllegalArgumentException("A new voice needs ids for part, voice and track and a source of more.");
        }
    }

    /** Fresh ids that are not used in the piece. */
    public static NewVoiceIds fresh(Composition piece, Supplier<String> ids) {
        Set<String> used = new HashSet<>();
        piece.parts().forEach(p -> used.add(p.id()));
        piece.tracks().forEach(t -> used.add(t.id()));
        String part = unused(ids, used);
        String voice = unused(ids, used);
        String track = unused(ids, used);
        return new NewVoiceIds(part, voice, track, ids);
    }

    private static String unused(Supplier<String> ids, Set<String> used) {
        String id;
        do {
            id = ids.get();
        } while (!used.add(id));
        return id;
    }
}
