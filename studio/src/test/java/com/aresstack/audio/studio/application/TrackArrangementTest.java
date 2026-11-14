package com.aresstack.audio.studio.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Verify user-editable instrumental arrangements and stable MIDI channel assignments. */
public final class TrackArrangementTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savesSixTracksAndAssignsDrumsToChannelTen() throws Exception {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>(MusicTrack.defaults());
        tracks.add(new MusicTrack("flute", MusicTrack.MELODY, "Solo flute", 73, "builtin"));
        tracks.add(new MusicTrack("cello", MusicTrack.HARMONY, "Cello", 42, "builtin"));
        TrackArrangement original = new TrackArrangement(tracks);
        Assert.assertEquals(9, original.channelFor("drums"));
        Assert.assertEquals(3, original.channelFor("flute"));
        Assert.assertEquals(4, original.channelFor("cello"));

        Path directory = folder.newFolder("six-track-song").toPath();
        TrackArrangementStore store = new TrackArrangementStore();
        store.save(directory, original);
        TrackArrangement loaded = store.load(directory);
        Assert.assertEquals(6, loaded.tracks().size());
        Assert.assertEquals("Solo flute", loaded.tracks().get(4).instrument());
        Assert.assertEquals(73, loaded.tracks().get(4).program());
    }

    @Test
    public void permitsRemovingEveryDefaultInstrumentExceptOne() {
        TrackArrangement single = new TrackArrangement(
                java.util.Collections.singletonList(new MusicTrack(
                        "piano", MusicTrack.MELODY, "Solo piano", 0, "builtin")));
        Assert.assertEquals(0, single.channelFor("piano"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void forbidsTwoDrumKitsSharingOneMidiChannel() {
        new TrackArrangement(java.util.Arrays.asList(
                new MusicTrack("drum1", MusicTrack.DRUMS, "Kit one", 0, "builtin"),
                new MusicTrack("drum2", MusicTrack.DRUMS, "Kit two", 0, "builtin")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSixteenMelodicTracksWithoutPercussion() {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (int i = 0; i < 16; i++) {
            tracks.add(new MusicTrack("voice_" + i, MusicTrack.TEXTURE,
                    "Synth texture", i, "builtin"));
        }
        new TrackArrangement(tracks);
    }

    @Test
    public void assignsFifteenMelodicTracksAroundPercussionChannel() {
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (int i = 0; i < 15; i++) {
            tracks.add(new MusicTrack("voice_" + i, MusicTrack.TEXTURE,
                    "Synth texture", i, "builtin"));
        }
        tracks.add(new MusicTrack("drums", MusicTrack.DRUMS, "GM drum kit", 0, "builtin"));
        TrackArrangement arrangement = new TrackArrangement(tracks);
        Assert.assertEquals(15, arrangement.channelFor("voice_14"));
        Assert.assertEquals(9, arrangement.channelFor("drums"));
    }
}
