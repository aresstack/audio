package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lets the stage the window keeps ({@code spatial-scene.json}) follow the mix of the model when a
 * step of the user changed it: undo, redo, a revision brought back, a proposal taken. Such a step
 * may change nothing but the mix; the change set of the notes is then empty, and yet the stage
 * and the mix have to be renewed (PO finding on bringing back a revision that differs in the mix
 * alone).
 *
 * <p>The window writes its stage itself, and the model takes it over only when the piece is
 * composed or a track renamed. So the mix of the model may be older than the stage in places the
 * step did not touch. Only what the step changed is taken: a channel, its level curve, the room
 * or the master that differ from the mix this object saw last. Everything else on the stage stays
 * as the user left it.
 */
public final class StageFollowsMix {
    private Path folder;
    private MixScene seen;

    /** Notes the mix of a piece as the stage stands now, when it is opened; nothing is changed. */
    public synchronized void remember(ProjectSession piece) {
        folder = piece == null ? null : piece.folder().toAbsolutePath().normalize();
        seen = piece == null ? null : piece.mix();
    }

    /**
     * What the stage becomes after a step on the piece.
     *
     * @param shown the stage as the window shows it
     * @return the stage to show, to save and to mix on, if the step changed the mix; empty if the
     *         mix is as it was, so that nothing has to be mixed again
     */
    public synchronized Optional<SpatialScene> follow(ProjectSession piece, SpatialScene shown) {
        Path where = piece.folder().toAbsolutePath().normalize();
        MixScene before = where.equals(folder) ? seen : null;
        MixScene after = piece.mix();
        folder = where;
        seen = after;
        if (before == null || after == null || after.equals(before)) {
            return Optional.empty();
        }
        return Optional.of(taking(shown, before, after, piece.composition()));
    }

    /** The stage with what differs between two mixes taken from the later one. */
    static SpatialScene taking(SpatialScene shown, MixScene before, MixScene after, Composition piece) {
        SpatialScene stage = shown.fitting(StageMixService.Piece.of(piece).tracks());
        if (!after.room().equals(before.room())) {
            stage = stage.inRoom(AcousticRoom.of(after.room().kind())
                    .withReverbSeconds(after.room().reverbSeconds()).withAmount(after.room().amount()));
        }
        if (after.masterDb() != before.masterDb()) {
            stage = stage.withMaster(after.masterDb());
        }
        long barTicks = piece.timeMap().meters().get(0).barTicks();
        for (MixChannel channel : after.channels()) {
            if (channel.owner().kind() != MixChannel.Owner.Kind.PART || stage.placement(channel.owner().id()) == null) {
                continue;
            }
            MixChannel was = before.channelOf(channel.owner());
            AutomationLane level = after.lane(channel.id(), AutomationLane.LEVEL);
            if (channel.equals(was) && Objects.equals(level, before.lane(channel.id(), AutomationLane.LEVEL))) {
                continue;
            }
            // The level curve of the stage is in bars, as the model took it from there.
            List<SoundSourcePlacement.LevelPoint> dynamics = new ArrayList<SoundSourcePlacement.LevelPoint>();
            if (level != null) {
                for (AutomationLane.Point point : level.points()) {
                    dynamics.add(new SoundSourcePlacement.LevelPoint(point.tick() / (double) barTicks, point.value()));
                }
            }
            // The mix document holds no height of a channel (work package D4): the one of the stage stays.
            stage = stage.with(new SoundSourcePlacement(channel.owner().id(), channel.x(), channel.depth(),
                    channel.levelDb(), channel.width(), channel.reverb(), channel.muted(), channel.locked(),
                    dynamics).atHeight(stage.placement(channel.owner().id()).height()));
        }
        return stage;
    }
}
