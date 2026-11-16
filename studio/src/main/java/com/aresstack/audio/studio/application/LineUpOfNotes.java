package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.studio.stage.SpatialScene;
import java.util.ArrayList;
import java.util.List;

/**
 * The tracks and the stage of a piece as its saved notes have them. The model of a piece keeps
 * who plays and where they stand beside the notes, and never takes another line-up that was only
 * written into the old files ({@code tracks.json}, {@code spatial-scene.json}) beside it. So when
 * something wrote other tracks there (an album production that chose new instruments and was
 * refused afterwards), the notes are still whole, only no track shown belongs to them; this gives
 * the tracks and the stage of the notes back. Nothing is written here and no note is read anew.
 */
public final class LineUpOfNotes {
    /**
     * @param tracks who plays, in the order of the notes
     * @param stage  where they stand, how loud they are and the room, as the mix of the piece says
     */
    public record LineUp(List<MusicTrack> tracks, SpatialScene stage) {
    }

    private LineUpOfNotes() {
    }

    /**
     * @param piece the saved piece
     * @param shown the stage as it is shown now; what the mix of the piece does not say stays from it
     * @throws IllegalArgumentException if the notes have no voice
     */
    public static LineUp of(ProjectSession piece, SpatialScene shown) {
        Composition composition = piece.composition();
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (Part part : composition.parts()) {
            String role = part.role() != null && MusicTrack.ROLES.contains(part.role()) ? part.role()
                    : part.drums() ? MusicTrack.DRUMS : MusicTrack.HARMONY;
            String instrument = part.instrument() != null && !part.instrument().trim().isEmpty() ? part.instrument()
                    : part.name() != null && !part.name().trim().isEmpty() ? part.name() : part.id();
            tracks.add(new MusicTrack(part.id(), role, instrument, Math.max(0, Math.min(127, part.program())),
                    part.libraryId() == null ? "builtin" : part.libraryId()));
        }
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("Die gespeicherten Noten dieses Stücks haben keine Stimme.");
        }
        SpatialScene stage = (shown == null ? SpatialScene.standard(tracks) : shown.fitting(tracks));
        if (piece.mix() != null) {
            stage = StageFollowsMix.whole(stage, piece.mix(), composition);
        }
        return new LineUp(tracks, stage);
    }
}
