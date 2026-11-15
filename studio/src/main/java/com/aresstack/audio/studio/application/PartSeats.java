package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.studio.stage.Performer;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A part that comes into a piece gets a place on the stage and a channel strip, and the files the
 * old pipeline still reads learn of it (work package C1): the mix receives a channel with the usual
 * seat of the part's role (as {@link SpatialScene#standard} seats it, fanned out against the parts
 * of the same role already there), {@code tracks.json} an entry and {@code spatial-scene.json} the
 * same place. A part that goes (undo of the change) leaves both files again. Parts that were there
 * before are never touched.
 */
final class PartSeats {
    private PartSeats() {
    }

    /** The mix with a channel for every part of {@code after} that is new against {@code before} and has none. */
    static MixScene seatNewParts(MixScene mix, Composition before, Composition after, Supplier<String> ids) {
        if (mix == null) {
            return null;
        }
        List<MixChannel> channels = new ArrayList<>(mix.channels());
        SpatialScene usual = SpatialScene.standard(performers(after));
        boolean added = false;
        for (Part part : after.parts()) {
            MixChannel.Owner owner = MixChannel.Owner.part(part.id());
            if (before.part(part.id()) != null || mix.channelOf(owner) != null) {
                continue;
            }
            SoundSourcePlacement seat = usual.placement(part.id());
            channels.add(new MixChannel(ids.get(), owner, seat.x(), seat.depth(), seat.levelDb(), seat.width(),
                    seat.reverb(), false, false));
            added = true;
        }
        return added ? new MixScene(mix.room(), mix.masterDb(), mix.levelMode(), channels, mix.automation()) : mix;
    }

    /**
     * Brings {@code tracks.json} and {@code spatial-scene.json}, where the piece has them, in line
     * with the parts a change added or removed. Entries of other parts stay as they are.
     */
    static void followOldFiles(Path folder, ChangeSet changes, Composition after, MixScene mix) throws IOException {
        if (changes.added().isEmpty() && changes.removed().isEmpty()) {
            return;
        }
        if (Files.isRegularFile(folder.resolve(TrackArrangementStore.FILE_NAME))) {
            TrackArrangementStore store = new TrackArrangementStore();
            List<MusicTrack> tracks = new ArrayList<>();
            for (MusicTrack track : store.load(folder).tracks()) {
                if (!changes.removed().contains(track.id())) {
                    tracks.add(track);
                }
            }
            for (String id : changes.added()) {
                Part part = after.part(id);
                if (tracks.stream().noneMatch(t -> t.id().equals(id))) {
                    tracks.add(new MusicTrack(id, role(part), part.instrument() == null ? part.name() : part.instrument(),
                            part.program(), part.libraryId()));
                }
            }
            if (!tracks.isEmpty()) {
                store.save(folder, new TrackArrangement(tracks));
            }
        }
        SpatialSceneStore stages = new SpatialSceneStore();
        SpatialScene scene = stages.load(folder);
        if (scene != null) {
            List<SoundSourcePlacement> placements = new ArrayList<>();
            for (SoundSourcePlacement placement : scene.placements()) {
                if (!changes.removed().contains(placement.trackId())) {
                    placements.add(placement);
                }
            }
            // The stage keeps how its level is made (fixed or as before); the seats that stay keep their height.
            SpatialScene changed = new SpatialScene(scene.room(), placements, scene.masterDb(), scene.fixedLevel());
            for (String id : changes.added()) {
                MixChannel channel = mix == null ? null : mix.channelOf(MixChannel.Owner.part(id));
                if (channel != null && changed.placement(id) == null) {
                    changed = changed.with(new SoundSourcePlacement(id, channel.x(), channel.depth(), channel.levelDb(),
                            channel.width(), channel.reverb(), channel.muted(), channel.locked(), null));
                }
            }
            stages.save(folder, changed);
        }
    }

    private static List<Performer> performers(Composition piece) {
        List<Performer> performers = new ArrayList<>();
        for (Part part : piece.parts()) {
            String role = role(part);
            performers.add(new Performer() {
                @Override
                public String id() {
                    return part.id();
                }

                @Override
                public String role() {
                    return role;
                }
            });
        }
        return performers;
    }

    private static String role(Part part) {
        return part.role() != null && Role.ALL.contains(part.role()) ? part.role() : Role.HARMONY;
    }
}
