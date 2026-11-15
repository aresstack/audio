package com.aresstack.audio.composition;

import java.util.ArrayList;
import java.util.List;

/**
 * A piece of music. It cannot be changed: every change gives a new composition, so that undo,
 * preview and rendering in the background need no locks. Whether a composition is sound in
 * itself (ids unique, references resolved, clips in place) says {@link CompositionValidator}.
 */
public record Composition(Meta meta, TimeMap timeMap, List<Section> sections, List<Part> parts, List<Track> tracks,
                          List<Asset> assets) {
    /** A note together with where it sounds in the piece. */
    public record PlacedNote(Note note, long tick, Track track, MidiClip clip) {
        public long endTick() {
            return tick + note.length();
        }
    }

    public Composition {
        meta = meta == null ? Meta.EMPTY : meta;
        if (timeMap == null) {
            throw new IllegalArgumentException("A composition needs a time map.");
        }
        sections = List.copyOf(sections == null ? List.of() : sections);
        parts = List.copyOf(parts == null ? List.of() : parts);
        tracks = List.copyOf(tracks == null ? List.of() : tracks);
        assets = List.copyOf(assets == null ? List.of() : assets);
    }

    public Part part(String id) {
        for (Part part : parts) {
            if (part.id().equals(id)) {
                return part;
            }
        }
        return null;
    }

    public Track track(String id) {
        for (Track track : tracks) {
            if (track.id().equals(id)) {
                return track;
            }
        }
        return null;
    }

    /** The end of the last clip or section, in ticks. */
    public long endTick() {
        long end = 0;
        for (Section section : sections) {
            end = Math.max(end, section.endTick());
        }
        for (Track track : tracks) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi) {
                    end = Math.max(end, midi.endTick());
                } else if (clip instanceof AudioClip audio) {
                    end = Math.max(end, audio.endTick(timeMap));
                }
            }
        }
        return end;
    }

    /** The notes a part plays, track by track and clip by clip, each in its written order. */
    public List<PlacedNote> notesOf(String partId) {
        List<PlacedNote> notes = new ArrayList<>();
        for (Track track : tracks) {
            for (Clip clip : track.clips()) {
                if (clip instanceof MidiClip midi && partId.equals(track.partOf(midi))) {
                    for (Note note : midi.notes()) {
                        notes.add(new PlacedNote(note, midi.startTick() + note.tick(), track, midi));
                    }
                }
            }
        }
        return notes;
    }

    public Asset asset(String id) {
        for (Asset asset : assets) {
            if (asset.id().equals(id)) {
                return asset;
            }
        }
        return null;
    }

    public Composition withAssets(List<Asset> changed) {
        return new Composition(meta, timeMap, sections, parts, tracks, changed);
    }

    public Composition withMeta(Meta changed) {
        return new Composition(changed, timeMap, sections, parts, tracks, assets);
    }

    public Composition withParts(List<Part> changed) {
        return new Composition(meta, timeMap, sections, changed, tracks, assets);
    }

    public Composition withTracks(List<Track> changed) {
        return new Composition(meta, timeMap, sections, parts, changed, assets);
    }
}
