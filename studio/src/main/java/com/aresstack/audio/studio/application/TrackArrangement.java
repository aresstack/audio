package com.aresstack.audio.studio.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validate a variable arrangement and assign GM-compatible MIDI channels. There is no upper
 * limit of tracks: a MIDI port has 15 melodic channels, so the sixteenth melodic track begins
 * again at the first channel, on the next port. Every track is rendered alone, so tracks
 * that share a channel never get in each other's way.
 */
public final class TrackArrangement {
    private final List<MusicTrack> tracks;

    public TrackArrangement(List<MusicTrack> proposed) {
        if (proposed == null || proposed.isEmpty()) {
            throw new IllegalArgumentException("Select at least one track.");
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
                if (track.id().equals(trackId)) {
                    int channel = melodicChannel % 15; // 15 melodic channels to a port, then the next port
                    return channel >= 9 ? channel + 1 : channel;
                }
                melodicChannel++;
            }
        }
        throw new IllegalArgumentException("Unknown arrangement track: " + trackId);
    }
}
