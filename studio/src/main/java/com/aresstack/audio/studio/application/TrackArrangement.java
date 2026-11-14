package com.aresstack.audio.studio.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validate a variable MIDI arrangement and assign unique GM-compatible MIDI channels. */
public final class TrackArrangement {
    public static final int MAX_TRACKS = 16;
    private final List<MusicTrack> tracks;

    public TrackArrangement(List<MusicTrack> proposed) {
        if (proposed == null || proposed.isEmpty() || proposed.size() > MAX_TRACKS) {
            throw new IllegalArgumentException("Select between 1 and 16 tracks.");
        }
        Set<String> ids = new HashSet<String>();
        int drumCount = 0;
        List<MusicTrack> checked = new ArrayList<MusicTrack>();
        for (MusicTrack track : proposed) {
            if (track == null || !ids.add(track.id())) {
                throw new IllegalArgumentException("Track IDs must be unique.");
            }
            if (MusicTrack.DRUMS.equals(track.role())) {
                drumCount++;
            }
            checked.add(track);
        }
        if (drumCount > 1) {
            throw new IllegalArgumentException("Only one shared GM percussion channel is supported.");
        }
        this.tracks = Collections.unmodifiableList(checked);
    }

    public static TrackArrangement defaults() {
        return new TrackArrangement(MusicTrack.defaults());
    }

    public List<MusicTrack> tracks() {
        return tracks;
    }

    public int channelFor(String trackId) {
        int melodicChannel = 0;
        for (MusicTrack track : tracks) {
            if (MusicTrack.DRUMS.equals(track.role())) {
                if (track.id().equals(trackId)) {
                    return 9;
                }
            } else {
                if (melodicChannel == 9) {
                    melodicChannel++;
                }
                if (track.id().equals(trackId)) {
                    return melodicChannel;
                }
                melodicChannel++;
            }
        }
        throw new IllegalArgumentException("Unknown arrangement track: " + trackId);
    }
}
