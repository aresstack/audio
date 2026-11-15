package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Articulation;
import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * How the piece is played, set by hand: its tempo with jumps and glides (accelerando,
 * ritardando), its dynamic marks and hairpins with the velocity each level stands for, and the
 * articulation of notes. Every change goes through the edit core as one undoable step; what
 * sounds different afterwards is rendered again (a change of tempo every part, a dynamic mark
 * the parts it holds for, an articulation the part of the notes).
 */
public final class PerformanceEdit {
    private PerformanceEdit() {
    }

    /** The scope of a change of tempo and dynamics: nothing else. */
    public static EditScope scope() {
        return new EditScope(false, Set.of(), Set.of(), Set.of(), 0, EditScope.END,
                EnumSet.of(EditOp.Kind.SET_TIME_MAP, EditOp.Kind.SET_DYNAMICS), false, 0);
    }

    /**
     * The patch that gives the piece these tempos and these dynamics; meter and key stay.
     *
     * @throws IllegalArgumentException if the tempos are not a valid course (the first at tick 0,
     *                                  in order, the last not gliding)
     */
    public static Patch patch(Composition piece, List<TimeMap.Tempo> tempos, Dynamics dynamics) {
        TimeMap time = piece.timeMap();
        TimeMap changed = new TimeMap(tempos, time.meters(), time.keys());
        List<EditOp> operations = new ArrayList<>();
        if (!changed.equals(time)) {
            operations.add(new EditOp.SetTimeMap(changed));
        }
        if (!Objects.equals(dynamics, piece.dynamics())) {
            operations.add(new EditOp.SetDynamics(dynamics));
        }
        return new Patch("Tempo und Dynamik gesetzt", operations);
    }

    /** Applies tempos and dynamics at once, as an undoable step by the user. */
    public static ChangeSet apply(ProjectSession session, List<TimeMap.Tempo> tempos, Dynamics dynamics)
            throws EditRefusedException, IOException {
        Patch patch = patch(session.composition(), tempos, dynamics);
        if (patch.operations().isEmpty()) {
            return ChangeSet.between(session.composition(), session.composition());
        }
        return session.apply(patch, scope());
    }

    /**
     * The patch that gives every note of a part that begins in bars {@code firstBar..lastBar}
     * (counted from 1) this articulation, or none with {@code null}.
     *
     * @throws IllegalArgumentException if the part is unknown or the bars are not a range
     */
    public static Patch articulate(Composition piece, String partId, int firstBar, int lastBar,
                                   Articulation articulation) {
        if (piece.part(partId) == null) {
            throw new IllegalArgumentException("Unbekannte Stimme: " + partId);
        }
        if (firstBar < 1 || lastBar < firstBar) {
            throw new IllegalArgumentException("Ungültiger Bereich: Takt " + firstBar + " bis " + lastBar);
        }
        TimeMap time = piece.timeMap();
        long from = time.tickOfBar(firstBar - 1);
        long to = time.tickOfBar(lastBar);
        List<EditOp> operations = new ArrayList<>();
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (!(clip instanceof MidiClip midi) || !partId.equals(track.partOf(midi))) {
                    continue;
                }
                for (Note note : midi.notes()) {
                    long at = midi.startTick() + note.tick();
                    if (at >= from && at < to && note.articulation() != articulation) {
                        operations.add(new EditOp.ChangeNote(midi.id(), note.withArticulation(articulation)));
                    }
                }
            }
        }
        return new Patch((articulation == null ? "Artikulation entfernt" : articulation.germanName()) + " in "
                + partId + ", Takt " + firstBar + " bis " + lastBar, operations);
    }

    /** Applies an articulation to the notes of a part in some bars, as an undoable step by the user. */
    public static ChangeSet articulate(ProjectSession session, String partId, int firstBar, int lastBar,
                                       Articulation articulation) throws EditRefusedException, IOException {
        Patch patch = articulate(session.composition(), partId, firstBar, lastBar, articulation);
        if (patch.operations().isEmpty()) {
            return ChangeSet.between(session.composition(), session.composition());
        }
        TimeMap time = session.composition().timeMap();
        return session.apply(patch, EditScope.parts(Set.of(partId), time.tickOfBar(firstBar - 1),
                time.tickOfBar(lastBar)));
    }
}
