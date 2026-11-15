package com.aresstack.audio.studio.application;

import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Übernehmen" of a mix proposal of the AI that came with movements ("Mit Bewegungen"): for the
 * user one proposal, so one step. The stage file ({@code spatial-scene.json}) gets the taken stage,
 * and the mix of the model the same places together with the movement curves in one undoable step
 * with revisions ({@link ProjectSession#takeStage}). Undo, redo and a revision brought back then
 * take or bring back all of it: the stage follows the mix of the model ({@link StageFollowsMix}).
 *
 * <p>If one half cannot be kept, the other is taken back: nothing stands as taken by half
 * (acceptance of PR #56). A proposal without movements is taken as before, on the stage alone.
 */
public final class MixProposalTaking {
    /** What the step is called in the history and in the revisions. */
    public static final String STEP = "Abmischung der KI mit Bewegungen übernommen";

    private MixProposalTaking() {
    }

    /**
     * Takes the proposal.
     *
     * @param before the stage as the user sees it
     * @param taken  the stage with the proposal taken (protected tracks as they were)
     * @return how many movement curves were kept
     * @throws IOException if the piece has no model or a file cannot be written; then nothing of
     *                     the proposal is taken: the stage file is as before, and the model holds at
     *                     most the stage as the user saw it before taking (no step, no revision)
     */
    public static int take(PieceModel model, StageFollowsMix follows, SpatialSceneStore stages,
                           SpatialScene before, SpatialScene taken, MovementDirector.Proposal moves)
            throws IOException {
        ProjectSession session = model.session()
                .orElseThrow(() -> new IOException("Das Stück hat noch kein Werkmodell: erst komponieren."));
        Path folder = session.folder();
        List<ProjectSession.Curve> curves = curves(model.movements(), moves, taken);
        stages.save(folder, taken);
        try {
            session.takeStage(before, taken, curves, STEP);
        } catch (IOException | RuntimeException failed) {
            try {
                stages.save(folder, before); // not taken by half
            } catch (IOException kept) {
                failed.addSuppressed(kept);
            }
            // The model may have learnt the stage as it stands before the step could not be written;
            // that is the stage the user sees, so the next step compares with it.
            follows.remember(session);
            throw failed;
        }
        follows.remember(session); // the mix now belongs to the stage as it stands
        return curves.size();
    }

    /**
     * The curves of a proposal in ticks, for tracks that may move: not protected on the taken
     * stage, with a channel in the mix document; only the parameters of the movements.
     */
    static List<ProjectSession.Curve> curves(StageMovements movements, MovementDirector.Proposal moves,
                                             SpatialScene taken) {
        List<ProjectSession.Curve> curves = new ArrayList<>();
        if (moves == null) {
            return curves;
        }
        StageMovements drawn = movements;
        for (Map.Entry<String, Map<String, List<StageMovements.Point>>> track : moves.curves().entrySet()) {
            SoundSourcePlacement placement = taken == null ? null : taken.placement(track.getKey());
            if ((placement != null && placement.locked()) || !drawn.editable(track.getKey())) {
                continue;
            }
            for (Map.Entry<String, List<StageMovements.Point>> curve : track.getValue().entrySet()) {
                if (!StageMovements.PARAMETERS.contains(curve.getKey())) {
                    continue;
                }
                drawn = drawn.with(track.getKey(), curve.getKey(), curve.getValue());
                curves.add(new ProjectSession.Curve(track.getKey(), curve.getKey(),
                        drawn.ticks(track.getKey(), curve.getKey())));
            }
        }
        return curves;
    }
}
