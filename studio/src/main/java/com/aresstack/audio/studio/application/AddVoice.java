package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.studio.ai.ComposedVoice;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * "Compose a new voice into the piece" ({@code ADD_TRACK}) as a change of the edit core: the
 * application builds the patch from the notes the AI wrote for exactly the new part (architecture
 * review E5, first safeguard), the scope allows nothing but creating that part, its track and its
 * clips within the chosen sections (second safeguard), and the editor proves that everything else
 * is as it was (third safeguard). {@link #untouched} checks the same once more on the notes of
 * every existing part, as the acceptance test A4 asks.
 */
public final class AddVoice {
    /** The only kinds of operation a new voice needs. */
    public static final Set<EditOp.Kind> KINDS = Set.copyOf(EnumSet.of(EditOp.Kind.ADD_PART, EditOp.Kind.ADD_TRACK,
            EditOp.Kind.ADD_CLIP));

    private AddVoice() {
    }

    /** The scope: the new part and its track, within the frames, creating only. */
    public static EditScope scope(ComposedVoice voice) {
        return new EditScope(false, Set.of(voice.part().id()), Set.of(voice.track().id()), Set.of(), voice.fromTick(),
                voice.toTick(), KINDS, false, 0);
    }

    /** The patch: the part, its empty track, then its clips with their notes. */
    public static Patch patch(ComposedVoice voice) {
        List<EditOp> operations = new ArrayList<>();
        operations.add(new EditOp.AddPart(voice.part()));
        operations.add(new EditOp.AddTrack(voice.track()));
        for (MidiClip clip : voice.clips()) {
            operations.add(new EditOp.AddClip(voice.track().id(), clip));
        }
        return new Patch("Neue Stimme " + voice.part().name() + " hinzukomponiert", operations);
    }

    /**
     * What is different about the existing parts between two states of a piece: a part that is gone
     * or changed, a note of an existing part that is gone, new or changed, a track that is gone or
     * changed. Empty when only the new part came in.
     */
    public static List<String> untouched(Composition before, Composition after, String newPartId) {
        List<String> differences = new ArrayList<>();
        for (Part part : before.parts()) {
            Part now = after.part(part.id());
            if (!part.equals(now)) {
                differences.add("part " + part.id() + " changed");
            } else if (!before.notesOf(part.id()).equals(after.notesOf(part.id()))) {
                differences.add("notes of part " + part.id() + " changed");
            }
        }
        for (var track : before.tracks()) {
            if (!track.equals(after.track(track.id()))) {
                differences.add("track " + track.id() + " changed");
            }
        }
        if (!before.timeMap().equals(after.timeMap()) || !before.sections().equals(after.sections())
                || !before.meta().equals(after.meta()) || !before.assets().equals(after.assets())) {
            differences.add("time map, form, meta or assets changed");
        }
        for (Part part : after.parts()) {
            if (before.part(part.id()) == null && !part.id().equals(newPartId)) {
                differences.add("unexpected new part " + part.id());
            }
        }
        for (var track : after.tracks()) {
            if (before.track(track.id()) == null) {
                for (Clip clip : track.clips()) {
                    if (clip instanceof MidiClip midi && !newPartId.equals(track.partOf(midi))) {
                        differences.add("clip " + clip.id() + " of a new track plays part " + track.partOf(midi));
                    }
                }
            }
        }
        return differences;
    }
}
