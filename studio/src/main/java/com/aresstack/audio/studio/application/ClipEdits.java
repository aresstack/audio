package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What the arrangement does to the clips of a piece (work package B2), as patches for the edit
 * core: move a clip in time or to another lane, copy it, cut it in two, delete it, start an empty
 * one, and let another part play it (architecture review E2/E3: the clip carries the part, not the
 * lane, so piano in bars 1 to 8 and flute in bars 9 to 16 are two clips on one lane, A7).
 *
 * <p>Clips of a lane do not overlap; that is a rule of this editor and of the validator, not of the
 * format (E2, PO condition O2). An action that would make clips overlap, or move a MIDI clip onto
 * an audio lane, is refused here with a reason before the edit core is asked. A clip end bounds the
 * notes in it, not their sound: the stems are rendered per part with what rings on.
 */
public final class ClipEdits {
    /** The kinds of operation a change of clips needs. */
    public static final Set<EditOp.Kind> CLIPS = Set.copyOf(EnumSet.of(EditOp.Kind.ADD_CLIP, EditOp.Kind.REMOVE_CLIP,
            EditOp.Kind.MOVE_CLIP, EditOp.Kind.SPLIT_CLIP, EditOp.Kind.SET_CLIP_PART, EditOp.Kind.ADD_PART,
            EditOp.Kind.ADD_NOTES, EditOp.Kind.REMOVE_NOTES, EditOp.Kind.CHANGE_NOTE));

    private ClipEdits() {
    }

    /** One action on clips: the patch, its scope, the clip selected after it, or why it cannot be. */
    public record ClipEdit(Patch patch, EditScope scope, String selected, String refusal) {
        public boolean isEmpty() {
            return patch == null || patch.isEmpty();
        }

        static ClipEdit refused(String why) {
            return new ClipEdit(null, null, null, why);
        }
    }

    /** An instrument for a new part: what the user picks when a clip shall sound different. */
    public record Instrument(String name, int program, String libraryId, String role) {
    }

    /** A clip of the piece with its lane. */
    public record Placed(Track track, Clip clip) {
    }

    /** Where a clip ends in ticks; an audio clip keeps its length in time, measured on the piece's time map. */
    public static long endOf(Composition piece, Clip clip) {
        return clip instanceof com.aresstack.audio.composition.AudioClip audio ? audio.endTick(piece.timeMap())
                : lengthOf(clip) + clip.startTick();
    }

    /** Finds a clip by its id. */
    public static Optional<Placed> find(Composition piece, String clipId) {
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip.id().equals(clipId)) {
                    return Optional.of(new Placed(track, clip));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Moves a clip to another start, and to another lane if one is named.
     *
     * @param trackId the lane to move to, or {@code null} to stay
     */
    public static ClipEdit move(Composition piece, String clipId, long startTick, String trackId) {
        Optional<Placed> found = find(piece, clipId);
        if (found.isEmpty()) {
            return ClipEdit.refused("Diesen Clip gibt es nicht mehr.");
        }
        Placed at = found.get();
        Track target = trackId == null ? at.track() : piece.track(trackId);
        if (target == null) {
            return ClipEdit.refused("Diese Spur gibt es nicht.");
        }
        if (target.kind() != at.clip().kind()) {
            return ClipEdit.refused("Ein " + kindName(at.clip()) + " gehört auf eine "
                    + (at.clip().kind() == Track.Kind.MIDI ? "MIDI" : "Audio") + "-Spur.");
        }
        long start = Math.max(0, startTick);
        if (start == at.clip().startTick() && target.id().equals(at.track().id())) {
            return new ClipEdit(new Patch("", List.of()), null, clipId, null);
        }
        long end = at.clip() instanceof com.aresstack.audio.composition.AudioClip audio
                ? new com.aresstack.audio.composition.AudioClip(audio.id(), start, audio.lengthSamples(),
                audio.assetId(), audio.gainDb(), audio.fadeInSamples(), audio.fadeOutSamples()).endTick(piece.timeMap())
                : start + lengthOf(at.clip());
        Optional<String> busy = AudioEdits.overlapping(piece, target, start, end, clipId);
        if (busy.isPresent()) {
            return ClipEdit.refused("Dort liegt schon Clip " + busy.get() + "; Clips einer Spur überlappen nicht.");
        }
        List<EditOp> operations = new ArrayList<>();
        operations.add(new EditOp.MoveClip(clipId, target.id().equals(at.track().id()) ? null : target.id(), start));
        if (at.clip() instanceof MidiClip midi && !target.id().equals(at.track().id())) {
            // The clip takes its player along: on the other lane it is still the same instrument.
            String part = at.track().partOf(midi);
            String wanted = part != null && part.equals(target.defaultPartId()) ? null : part;
            if (!java.util.Objects.equals(midi.partId(), wanted)) {
                operations.add(new EditOp.SetClipPart(clipId, wanted));
            }
        }
        return edit("Clip verschoben", operations, new LinkedHashSet<>(List.of(at.track().id(), target.id())), clipId);
    }

    /** Copies a MIDI clip with its notes, under new ids, to a start on a lane. */
    public static ClipEdit copy(Composition piece, String clipId, long startTick, String trackId, Supplier<String> ids) {
        Optional<Placed> found = find(piece, clipId);
        if (found.isEmpty() || !(found.get().clip() instanceof MidiClip midi)) {
            return ClipEdit.refused("Nur MIDI-Clips lassen sich hier kopieren.");
        }
        Track target = trackId == null ? found.get().track() : piece.track(trackId);
        if (target == null || target.kind() != Track.Kind.MIDI) {
            return ClipEdit.refused("Ein MIDI-Clip gehört auf eine MIDI-Spur.");
        }
        long start = Math.max(0, startTick);
        Optional<String> busy = overlapping(target, start, start + midi.lengthTicks(), null);
        if (busy.isPresent()) {
            return ClipEdit.refused("Dort liegt schon Clip " + busy.get() + "; Clips einer Spur überlappen nicht.");
        }
        Set<String> used = usedIds(piece);
        String newId = fresh(ids, used);
        List<Note> notes = new ArrayList<>();
        for (Note note : midi.notes()) {
            notes.add(new Note(fresh(ids, used), note.tick(), note.length(), note.pitch(), note.velocity(), note.voiceId(),
                    note.articulation()));
        }
        // The copy is played by the part that plays the original, also on a lane whose default part is another.
        String part = found.get().track().partOf(midi);
        String keep = part != null && part.equals(target.defaultPartId()) ? null : part;
        MidiClip copied = new MidiClip(newId, start, midi.lengthTicks(), keep, notes);
        return edit("Clip kopiert", List.of(new EditOp.AddClip(target.id(), copied)), Set.of(target.id()), newId);
    }

    /** Cuts a MIDI clip in two at a tick of the piece; the second half gets a new id. */
    public static ClipEdit split(Composition piece, String clipId, long atTick, Supplier<String> ids) {
        Optional<Placed> found = find(piece, clipId);
        if (found.isEmpty() || !(found.get().clip() instanceof MidiClip midi)) {
            return ClipEdit.refused("Nur MIDI-Clips lassen sich hier teilen.");
        }
        if (atTick <= midi.startTick() || atTick >= midi.endTick()) {
            return ClipEdit.refused("Geteilt wird innerhalb des Clips.");
        }
        String newId = fresh(ids, usedIds(piece));
        return edit("Clip geteilt", List.of(new EditOp.SplitClip(clipId, atTick, newId)),
                Set.of(found.get().track().id()), newId);
    }

    /** Takes a clip away with its notes. */
    public static ClipEdit delete(Composition piece, String clipId) {
        Optional<Placed> found = find(piece, clipId);
        if (found.isEmpty()) {
            return ClipEdit.refused("Diesen Clip gibt es nicht mehr.");
        }
        return edit("Clip gelöscht", List.of(new EditOp.RemoveClip(clipId)), Set.of(found.get().track().id()), null);
    }

    /** A new, empty MIDI clip on a lane, played by the lane's default part. */
    public static ClipEdit create(Composition piece, String trackId, long startTick, long lengthTicks,
                                  Supplier<String> ids) {
        Track track = piece.track(trackId);
        if (track == null || track.kind() != Track.Kind.MIDI) {
            return ClipEdit.refused("Ein MIDI-Clip gehört auf eine MIDI-Spur.");
        }
        long start = Math.max(0, startTick);
        long length = Math.max(1, lengthTicks);
        // Up to the next clip, if it begins sooner.
        for (Clip clip : track.clips()) {
            if (clip.startTick() > start) {
                length = Math.min(length, clip.startTick() - start);
            }
        }
        Optional<String> busy = overlapping(track, start, start + length, null);
        if (busy.isPresent()) {
            return ClipEdit.refused("Dort liegt schon Clip " + busy.get() + ".");
        }
        String id = fresh(ids, usedIds(piece));
        return edit("Clip angelegt", List.of(new EditOp.AddClip(trackId, new MidiClip(id, start, length, null, List.of()))),
                Set.of(trackId), id);
    }

    /**
     * Lets another part of the piece play a MIDI clip; {@code null} gives it back to the lane's
     * default part. The notes go to the voice of that part they fit best, so that the score keeps
     * writing them.
     */
    public static ClipEdit playBy(Composition piece, String clipId, String partId) {
        Optional<Placed> found = find(piece, clipId);
        if (found.isEmpty() || !(found.get().clip() instanceof MidiClip midi)) {
            return ClipEdit.refused("Nur MIDI-Clips werden von einem Instrument gespielt.");
        }
        Track track = found.get().track();
        String wanted = partId == null || partId.equals(track.defaultPartId()) ? null : partId;
        Part part = piece.part(wanted == null ? track.defaultPartId() : wanted);
        if (part == null) {
            return ClipEdit.refused("Diesen Part gibt es nicht.");
        }
        if (java.util.Objects.equals(midi.partId(), wanted)) {
            return new ClipEdit(new Patch("", List.of()), null, clipId, null);
        }
        List<EditOp> operations = new ArrayList<>();
        operations.add(new EditOp.SetClipPart(clipId, wanted));
        operations.addAll(revoiced(midi, part));
        return edit("Clip spielt jetzt " + part.name(), operations, Set.of(track.id()), clipId);
    }

    /**
     * Gives a MIDI clip a new instrument: a new part (a new player on the stage, with a channel strip
     * of its own) plays it from now on. The lane and the other clips stay as they are.
     */
    public static ClipEdit playByNew(Composition piece, String clipId, Instrument instrument, Supplier<String> ids) {
        Optional<Placed> found = find(piece, clipId);
        if (found.isEmpty() || !(found.get().clip() instanceof MidiClip midi)) {
            return ClipEdit.refused("Nur MIDI-Clips werden von einem Instrument gespielt.");
        }
        Track track = found.get().track();
        Part old = piece.part(track.partOf(midi));
        Set<String> used = usedIds(piece);
        for (Part part : piece.parts()) {
            used.add(part.id());
            for (Voice voice : part.voices()) {
                used.add(voice.id());
            }
        }
        String partId = fresh(ids, used);
        used.add(partId);
        List<Voice> voices = new ArrayList<>();
        if (old != null) {
            for (Voice voice : old.voices()) {
                String id = fresh(ids, used);
                used.add(id);
                voices.add(new Voice(id, voice.name(), voice.staff(), voice.stem()));
            }
        } else {
            voices.add(new Voice(fresh(ids, used), "Stimme 1", null, Voice.Stem.AUTO));
        }
        String role = instrument.role() != null && Role.ALL.contains(instrument.role()) ? instrument.role()
                : old != null && old.role() != null ? old.role() : Role.HARMONY;
        Part part = new Part(partId, instrument.name(), role, instrument.name(), instrument.program(),
                instrument.libraryId(), old == null ? Part.StaffLayout.SINGLE : old.staffLayout(), voices, null);
        List<EditOp> operations = new ArrayList<>();
        operations.add(new EditOp.AddPart(part));
        operations.add(new EditOp.SetClipPart(clipId, partId));
        // Same voices in the same order: a note keeps its line in the score.
        for (Note note : midi.notes()) {
            int index = old == null ? 0 : Math.max(0, indexOfVoice(old, note.voiceId()));
            operations.add(new EditOp.ChangeNote(clipId, new Note(note.id(), note.tick(), note.length(), note.pitch(),
                    note.velocity(), voices.get(Math.min(index, voices.size() - 1)).id(), note.articulation())));
        }
        return edit("Clip spielt jetzt " + instrument.name(), operations, Set.of(track.id()), clipId);
    }

    /** Whether a stretch on a lane is free of other clips; the id of the one in the way if not. */
    public static Optional<String> overlapping(Track track, long from, long to, String except) {
        for (Clip clip : track.clips()) {
            if (clip.id().equals(except)) {
                continue;
            }
            long start = clip.startTick();
            long end = start + lengthOf(clip);
            if (start < to && from < end) {
                return Optional.of(clip.id());
            }
        }
        return Optional.empty();
    }

    /** The scope of a change of the clips of some lanes: these lanes, clip and note operations, new parts. */
    public static EditScope scope(Set<String> trackIds) {
        return new EditScope(false, Set.of(), trackIds, Set.of(), 0, EditScope.END, CLIPS, false, 0);
    }

    // ---------------------------------------------------------------------------------------

    private static ClipEdit edit(String description, List<EditOp> operations, Set<String> tracks, String selected) {
        return new ClipEdit(new Patch(description, operations), scope(new LinkedHashSet<>(tracks)), selected, null);
    }

    /** The notes of a clip moved to the voices of another part: same position in the list of voices, else the first. */
    private static List<EditOp> revoiced(MidiClip clip, Part to) {
        List<EditOp> operations = new ArrayList<>();
        for (Note note : clip.notes()) {
            if (to.voice(note.voiceId()) != null) {
                continue;
            }
            operations.add(new EditOp.ChangeNote(clip.id(), new Note(note.id(), note.tick(), note.length(), note.pitch(),
                    note.velocity(), to.firstVoice().id(), note.articulation())));
        }
        return operations;
    }

    private static int indexOfVoice(Part part, String voiceId) {
        for (int i = 0; i < part.voices().size(); i++) {
            if (part.voices().get(i).id().equals(voiceId)) {
                return i;
            }
        }
        return -1;
    }

    /** How long a MIDI clip lasts in ticks; for an audio clip see {@link #endOf}. */
    static long lengthOf(Clip clip) {
        return clip instanceof MidiClip midi ? midi.lengthTicks() : 1;
    }

    private static String kindName(Clip clip) {
        return clip.kind() == Track.Kind.MIDI ? "MIDI-Clip" : "Audio-Clip";
    }

    private static Set<String> usedIds(Composition piece) {
        Set<String> used = new HashSet<>();
        for (Track track : piece.tracks()) {
            used.add(track.id());
            for (Clip clip : track.clips()) {
                used.add(clip.id());
                if (clip instanceof MidiClip midi) {
                    for (Note note : midi.notes()) {
                        used.add(note.id());
                    }
                }
            }
        }
        return used;
    }

    private static String fresh(Supplier<String> ids, Set<String> used) {
        String id = ids.get();
        while (used.contains(id)) {
            id = ids.get();
        }
        used.add(id);
        return id;
    }
}
