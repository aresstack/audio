package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Compose a track anew", the first user of the edit core (work package F3): from a candidate of
 * the whole piece, as a generator wrote it, only the notes of one part are taken. The patch is
 * built here, by the application, and addresses nothing but that part (architecture review E5,
 * first safeguard); whatever else the candidate says differently is ignored, and the scope
 * ({@link #scope}) and the proof of the editor make sure of it.
 */
public final class TrackRecomposition {
    private TrackRecomposition() {
    }

    /** The scope of composing one part anew: its notes and clips over the whole piece. */
    public static EditScope scope(String partId) {
        return EditScope.part(partId);
    }

    /**
     * The patch that gives a part of {@code base} the notes the part has in {@code candidate}.
     * Clips of the same id and frame keep their place and get the new notes; other clips of the
     * part go, and the candidate's clips come in their stead. A note whose voice the part does not
     * have goes to the part's first voice.
     */
    public static Patch patch(Composition base, Composition candidate, String partId) {
        Part part = base.part(partId);
        if (part == null) {
            throw new IllegalArgumentException("No such part: " + partId);
        }
        Map<String, Located> wanted = new LinkedHashMap<>();
        for (Track track : candidate.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi && partId.equals(track.partOf(midi))) {
                    wanted.put(midi.id(), new Located(track, midi));
                }
            }
        }
        List<EditOp> removals = new ArrayList<>();
        List<EditOp> additions = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (Track track : base.tracks()) {
            for (Clip clip : track.clips()) {
                if (!(clip instanceof MidiClip midi) || !partId.equals(track.partOf(midi))) {
                    continue;
                }
                Located other = wanted.get(midi.id());
                if (other != null && other.track.id().equals(track.id()) && other.clip.startTick() == midi.startTick()
                        && other.clip.lengthTicks() == midi.lengthTicks()) {
                    kept.add(midi.id());
                    List<Note> notes = voiced(part, other.clip.notes());
                    if (!notes.equals(midi.notes())) {
                        if (!midi.notes().isEmpty()) {
                            removals.add(new EditOp.RemoveNotes(midi.id(), midi.notes().stream().map(Note::id).toList()));
                        }
                        if (!notes.isEmpty()) {
                            additions.add(new EditOp.AddNotes(midi.id(), notes));
                        }
                    }
                } else {
                    removals.add(new EditOp.RemoveClip(midi.id()));
                }
            }
        }
        for (Located other : wanted.values()) {
            if (kept.contains(other.clip.id())) {
                continue;
            }
            Track track = base.track(other.track.id());
            if (track == null || track.kind() != Track.Kind.MIDI) {
                track = base.tracks().stream().filter(t -> partId.equals(t.defaultPartId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No track for part " + partId));
            }
            String clipPart = partId.equals(track.defaultPartId()) ? null : partId;
            additions.add(new EditOp.AddClip(track.id(), new MidiClip(other.clip.id(), other.clip.startTick(),
                    other.clip.lengthTicks(), clipPart, voiced(part, other.clip.notes()))));
        }
        List<EditOp> all = new ArrayList<>(removals);
        all.addAll(additions);
        return new Patch("Spur " + part.name() + " neu komponiert", all);
    }

    private record Located(Track track, MidiClip clip) {
    }

    private static List<Note> voiced(Part part, List<Note> notes) {
        List<Note> voiced = new ArrayList<>();
        for (Note n : notes) {
            voiced.add(part.voice(n.voiceId()) != null ? n
                    : new Note(n.id(), n.tick(), n.length(), n.pitch(), n.velocity(), part.firstVoice().id(),
                            n.articulation()));
        }
        return voiced;
    }
}
