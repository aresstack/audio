package com.aresstack.audio.composition;

/**
 * A note of a MIDI clip. Its tick counts from the start of its clip, so that moving a clip
 * moves its notes without touching them.
 *
 * @param voiceId the voice of the clip's part the note belongs to
 */
public record Note(String id, long tick, long length, int pitch, int velocity, String voiceId) {
    public Note {
        Ids.require(id, "a note");
        if (tick < 0 || length < 1) {
            throw new IllegalArgumentException("A note starts at 0 or later and lasts at least one tick: " + id);
        }
        if (pitch < 0 || pitch > 127 || velocity < 1 || velocity > 127) {
            throw new IllegalArgumentException("Pitch runs from 0 to 127 and velocity from 1 to 127: " + id);
        }
        Ids.require(voiceId, "the voice of a note");
    }

    public long endTick() {
        return tick + length;
    }
}
