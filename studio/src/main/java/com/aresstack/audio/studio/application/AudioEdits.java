package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Asset;
import com.aresstack.audio.composition.AudioClip;
import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What the arrangement does to audio lanes (work package B3), as patches for the edit core: a new
 * audio lane, a sound placed on it as an audio clip (with its file as a new asset), and the gain and
 * the fades of a clip. An audio lane is no player: it gets no part, no instrument and no staff, but a
 * channel strip of its own in the mix (E3, PO condition O1), which {@link ProjectSession} gives it
 * when the lane comes in. Moving, cutting away and deleting an audio clip is {@link ClipEdits}.
 */
public final class AudioEdits {
    private AudioEdits() {
    }

    /** A new, empty audio lane at the end of the arrangement. */
    public static ClipEdits.ClipEdit addTrack(Composition piece, String name, Supplier<String> ids) {
        String id = fresh(piece, ids);
        Track track = new Track(id, name == null || name.isBlank() ? "Audio" : name, Track.Kind.AUDIO, null, List.of());
        EditScope scope = new EditScope(false, Set.of(), Set.of(id), Set.of(), 0, EditScope.END,
                EnumSet.of(EditOp.Kind.ADD_TRACK), false, 0);
        return new ClipEdits.ClipEdit(Patch.of("Audiospur angelegt", new EditOp.AddTrack(track)), scope, id, null);
    }

    /**
     * Places a sound on an audio lane: an audio clip from a tick, as long as the file (in samples at
     * {@link AudioClip#SAMPLE_RATE}), at unity gain and without fades. An asset the piece does not
     * have yet comes in with it.
     */
    public static ClipEdits.ClipEdit place(Composition piece, String trackId, long startTick, Asset asset,
                                           long lengthSamples, Supplier<String> ids) {
        Track track = piece.track(trackId);
        if (track == null || track.kind() != Track.Kind.AUDIO) {
            return ClipEdits.ClipEdit.refused("Ein Klang gehört auf eine Audiospur.");
        }
        if (lengthSamples < 1) {
            return ClipEdits.ClipEdit.refused("Die Datei ist leer.");
        }
        String id = fresh(piece, ids);
        AudioClip clip = new AudioClip(id, Math.max(0, startTick), lengthSamples, asset.id(), 0, 0, 0);
        Optional<String> busy = overlapping(piece, track, clip.startTick(), clip.endTick(piece.timeMap()), null);
        if (busy.isPresent()) {
            return ClipEdits.ClipEdit.refused("Dort liegt schon Clip " + busy.get() + "; Clips einer Spur überlappen nicht.");
        }
        List<EditOp> operations = new ArrayList<>();
        if (piece.asset(asset.id()) == null) {
            operations.add(new EditOp.AddAsset(asset));
        }
        operations.add(new EditOp.AddClip(trackId, clip));
        EditScope scope = new EditScope(false, Set.of(), Set.of(trackId), Set.of(), 0, EditScope.END,
                EnumSet.of(EditOp.Kind.ADD_ASSET, EditOp.Kind.ADD_CLIP), false, 0);
        return new ClipEdits.ClipEdit(new Patch(asset.name() + " eingefügt", operations), scope, id, null);
    }

    /** Sets the fades of an audio clip, in samples; neither longer than the clip, both together at most as long. */
    public static ClipEdits.ClipEdit fades(Composition piece, String clipId, long fadeIn, long fadeOut) {
        Optional<ClipEdits.Placed> found = ClipEdits.find(piece, clipId);
        if (found.isEmpty() || !(found.get().clip() instanceof AudioClip audio)) {
            return ClipEdits.ClipEdit.refused("Blenden hat nur ein Audio-Clip.");
        }
        long in = Math.max(0, Math.min(fadeIn, audio.lengthSamples()));
        long out = Math.max(0, Math.min(fadeOut, audio.lengthSamples() - in));
        if (in == audio.fadeInSamples() && out == audio.fadeOutSamples()) {
            return new ClipEdits.ClipEdit(new Patch("", List.of()), null, clipId, null);
        }
        return set(found.get(), new AudioClip(audio.id(), audio.startTick(), audio.lengthSamples(), audio.assetId(),
                audio.gainDb(), in, out), "Blenden geändert");
    }

    /** Sets the gain of an audio clip in dB, within -60 and +12. */
    public static ClipEdits.ClipEdit gain(Composition piece, String clipId, double gainDb) {
        Optional<ClipEdits.Placed> found = ClipEdits.find(piece, clipId);
        if (found.isEmpty() || !(found.get().clip() instanceof AudioClip audio)) {
            return ClipEdits.ClipEdit.refused("Eine Verstärkung hat nur ein Audio-Clip.");
        }
        double db = Math.max(-60, Math.min(12, gainDb));
        if (db == audio.gainDb()) {
            return new ClipEdits.ClipEdit(new Patch("", List.of()), null, clipId, null);
        }
        return set(found.get(), new AudioClip(audio.id(), audio.startTick(), audio.lengthSamples(), audio.assetId(), db,
                audio.fadeInSamples(), audio.fadeOutSamples()), String.format(java.util.Locale.ROOT,
                "Clip-Pegel %+.1f dB", db));
    }

    /** Whether a stretch of an audio lane is free; the id of the clip in the way if not. */
    static Optional<String> overlapping(Composition piece, Track track, long from, long to, String except) {
        for (Clip clip : track.clips()) {
            if (clip.id().equals(except)) {
                continue;
            }
            long start = clip.startTick();
            long end = ClipEdits.endOf(piece, clip);
            if (start < to && from < end) {
                return Optional.of(clip.id());
            }
        }
        return Optional.empty();
    }

    private static ClipEdits.ClipEdit set(ClipEdits.Placed at, AudioClip changed, String description) {
        EditScope scope = new EditScope(false, Set.of(), Set.of(at.track().id()), Set.of(), 0, EditScope.END,
                EnumSet.of(EditOp.Kind.SET_AUDIO_CLIP), false, 0);
        return new ClipEdits.ClipEdit(Patch.of(description, new EditOp.SetAudioClip(changed)), scope, changed.id(), null);
    }

    private static String fresh(Composition piece, Supplier<String> ids) {
        Set<String> used = new HashSet<>();
        for (Track track : piece.tracks()) {
            used.add(track.id());
            for (Clip clip : track.clips()) {
                used.add(clip.id());
            }
        }
        for (var part : piece.parts()) {
            used.add(part.id());
        }
        for (Asset asset : piece.assets()) {
            used.add(asset.id());
        }
        String id = ids.get();
        while (used.contains(id)) {
            id = ids.get();
        }
        return id;
    }
}
