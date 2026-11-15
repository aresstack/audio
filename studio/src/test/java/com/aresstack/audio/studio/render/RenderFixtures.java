package com.aresstack.audio.studio.render;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.studio.application.ProjectSession;
import com.aresstack.audio.studio.golden.Golden;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Pieces for the render tests: the golden fixtures, opened as models, and small changes to them. */
public final class RenderFixtures {
    private RenderFixtures() {
    }

    /** Opens a golden fixture as a piece in a folder of its own. */
    public static ProjectSession open(String fixture, Path folder) throws Exception {
        Files.createDirectories(folder);
        Files.copy(Golden.fixture(fixture), folder.resolve("arrangement.tsv"));
        return ProjectSession.open(folder).orElseThrow();
    }

    /** The same piece with the first note of one part one step louder (or softer at the top). */
    public static Composition louder(Composition composition, String partId) {
        List<Track> tracks = new ArrayList<>();
        boolean[] done = {false};
        for (Track track : composition.tracks()) {
            List<Clip> clips = new ArrayList<>();
            for (Clip clip : track.clips()) {
                if (!done[0] && clip instanceof MidiClip midi && partId.equals(track.partOf(midi))
                        && !midi.notes().isEmpty()) {
                    List<Note> notes = new ArrayList<>(midi.notes());
                    Note first = notes.get(0);
                    int velocity = first.velocity() < 127 ? first.velocity() + 1 : 126;
                    notes.set(0, new Note(first.id(), first.tick(), first.length(), first.pitch(), velocity,
                            first.voiceId()));
                    clip = midi.withNotes(notes);
                    done[0] = true;
                }
                clips.add(clip);
            }
            tracks.add(track.withClips(clips));
        }
        if (!done[0]) {
            throw new IllegalArgumentException("No note of " + partId);
        }
        return composition.withTracks(tracks);
    }

    /** The same piece with another role for one part. */
    public static Composition withRole(Composition composition, String partId, String role) {
        List<Part> parts = new ArrayList<>();
        for (Part part : composition.parts()) {
            parts.add(part.id().equals(partId) ? new Part(part.id(), part.name(), role, part.instrument(),
                    part.program(), part.libraryId(), part.staffLayout(), part.voices(), part.legacyChannel()) : part);
        }
        return composition.withParts(parts);
    }

    /** The first part that does not play drums. */
    public static Part melodic(Composition composition) {
        for (Part part : composition.parts()) {
            if (!part.drums()) {
                return part;
            }
        }
        throw new IllegalArgumentException("No melodic part");
    }
}
