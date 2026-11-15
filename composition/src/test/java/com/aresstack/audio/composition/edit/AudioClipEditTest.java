package com.aresstack.audio.composition.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Asset;
import com.aresstack.audio.composition.AudioClip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.Track;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Work package B3 in the edit core: assets come in with a patch, an audio clip changes gain and fades in place. */
public final class AudioClipEditTest {
    private static final Asset BOOM = new Asset("boom", "Boom", "assets/boom.wav");

    private static EditScope lane(EditOp.Kind... kinds) {
        return new EditScope(false, Set.of(), Set.of("sfx"), Set.of(), 0, EditScope.END,
                EnumSet.of(kinds[0], kinds), false, 0);
    }

    private static Composition withLaneAndClip() {
        Composition piece = Pieces.eightBars();
        EditResult added = CompositionEditor.apply(piece, new Patch("lane", List.of(
                new EditOp.AddTrack(new Track("sfx", "Effekte", Track.Kind.AUDIO, null, List.of())),
                new EditOp.AddAsset(BOOM),
                new EditOp.AddClip("sfx", new AudioClip("c1", Pieces.BAR, 44100, "boom", 0, 0, 0)))),
                lane(EditOp.Kind.ADD_TRACK, EditOp.Kind.ADD_ASSET, EditOp.Kind.ADD_CLIP));
        assertTrue(added.violations().toString(), added.applied());
        return added.result();
    }

    @Test
    public void anAssetComesInWithItsClip() {
        Composition piece = withLaneAndClip();
        assertNotNull(piece.asset("boom"));
        assertEquals(List.of(), CompositionValidator.validate(piece));
        // At 100 bpm a second is 800 ticks: the clip ends there.
        AudioClip clip = (AudioClip) piece.track("sfx").clips().get(0);
        assertEquals(Pieces.BAR + 800, clip.endTick(piece.timeMap()));
    }

    @Test
    public void aScopeWithoutAddAssetRefusesANewAsset() {
        Composition piece = Pieces.eightBars();
        EditResult refused = CompositionEditor.apply(piece, Patch.of("asset", new EditOp.AddAsset(BOOM)),
                lane(EditOp.Kind.ADD_CLIP));
        assertFalse(refused.applied());
    }

    @Test
    public void gainAndFadesChangeInPlaceButNotTheStart() {
        Composition piece = withLaneAndClip();
        EditResult shaped = CompositionEditor.apply(piece, Patch.of("fades",
                new EditOp.SetAudioClip(new AudioClip("c1", Pieces.BAR, 44100, "boom", -3, 4410, 4410))),
                lane(EditOp.Kind.SET_AUDIO_CLIP));
        assertTrue(shaped.violations().toString(), shaped.applied());
        AudioClip clip = (AudioClip) shaped.result().track("sfx").clips().get(0);
        assertEquals(-3, clip.gainDb(), 0);
        assertEquals(4410, clip.fadeInSamples());

        EditResult moved = CompositionEditor.apply(piece, Patch.of("move",
                new EditOp.SetAudioClip(new AudioClip("c1", 0, 44100, "boom", 0, 0, 0))),
                lane(EditOp.Kind.SET_AUDIO_CLIP));
        assertFalse(moved.applied());
    }

    @Test
    public void overlappingAudioClipsAreRefusedByTheirLengthInTime() {
        Composition piece = withLaneAndClip();
        EditResult overlap = CompositionEditor.apply(piece, Patch.of("overlap",
                new EditOp.AddClip("sfx", new AudioClip("c2", Pieces.BAR + 500, 100, "boom", 0, 0, 0))),
                lane(EditOp.Kind.ADD_CLIP));
        assertFalse(overlap.applied());
        EditResult after = CompositionEditor.apply(piece, Patch.of("after",
                new EditOp.AddClip("sfx", new AudioClip("c2", Pieces.BAR + 800, 100, "boom", 0, 0, 0))),
                lane(EditOp.Kind.ADD_CLIP));
        assertTrue(after.violations().toString(), after.applied());
    }
}
