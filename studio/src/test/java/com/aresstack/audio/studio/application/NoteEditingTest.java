package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.CompositionEditor;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditResult;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.application.NoteEdits.NoteRef;
import com.aresstack.audio.studio.project.CompositionJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package B1: every action of the note editor is exactly one patch through the edit core,
 * touches nothing but the notes it names, is saved at once, can be undone, and survives a later
 * change of the AI to another part (A9, A10).
 */
public final class NoteEditingTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path folder;
    private ProjectSession session;
    private PieceEditing editing;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        folder = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        session = ProjectSession.open(folder).orElseThrow();
        editing = new PieceEditing(() -> Optional.of(session), counting());
    }

    private static Supplier<String> counting() {
        Iterator<Integer> numbers = Stream.iterate(1, n -> n + 1).iterator();
        return () -> "hand" + numbers.next();
    }

    private Composition piece() {
        return session.composition();
    }

    private static MidiClip clipOf(Composition piece, String trackId) {
        return (MidiClip) piece.track(trackId).clips().get(0);
    }

    private NoteRef melody(int index) {
        MidiClip clip = clipOf(piece(), "melody");
        return new NoteRef(clip.id(), clip.notes().get(index).id());
    }

    private static Note note(Composition piece, NoteRef ref) {
        for (Track track : piece.tracks()) {
            for (Clip clip : track.clips()) {
                if (clip.id().equals(ref.clipId())) {
                    for (Note note : ((MidiClip) clip).notes()) {
                        if (note.id().equals(ref.noteId())) {
                            return note;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Everything but the melody, to prove a change of the melody touched nothing else. */
    private static List<Track> others(Composition piece) {
        List<Track> others = new ArrayList<>(piece.tracks());
        others.removeIf(track -> track.id().equals("melody"));
        return others;
    }

    @Test
    public void everyActionIsExactlyOnePatchThatTheEditCoreAccepts() { // A9
        Composition piece = piece();
        NoteRef first = melody(0);
        NoteRef second = melody(1);
        NoteEdits.NoteEdit offGrid = NoteEdits.move(piece, List.of(first), 37, 0);
        Composition moved = CompositionEditor.apply(piece, offGrid.patch(), offGrid.scope()).result();
        List<NoteEdits.NoteEdit> edits = List.of(
                NoteEdits.move(piece, List.of(first, second), 120, 2),
                NoteEdits.resize(piece, List.of(first), 120, 60),
                NoteEdits.insert(piece, "melody", 3000, 62, 240, counting()),
                NoteEdits.delete(piece, List.of(second)),
                NoteEdits.quantize(moved, List.of(first), 120),
                NoteEdits.paste(piece, NoteEdits.copy(piece, List.of(first, second)), "harmony", 1920, counting()));
        for (NoteEdits.NoteEdit edit : edits) {
            assertFalse(edit.patch().description(), edit.isEmpty());
            for (EditOp op : edit.patch().operations()) {
                assertTrue(op.kind() + " is a change of notes", NoteEdits.NOTES.contains(op.kind()));
            }
        }
        for (int i = 0; i < edits.size(); i++) {
            NoteEdits.NoteEdit edit = edits.get(i);
            EditResult result = CompositionEditor.apply(i == 4 ? moved : piece, edit.patch(), edit.scope());
            assertTrue(edit.patch().description() + ": " + result.violations(), result.applied());
        }
    }

    @Test
    public void movingAGroupKeepsItsShapeAndStopsAtTheClip() {
        NoteRef first = melody(0);
        NoteRef second = melody(1);
        Composition before = piece();
        PieceEditing.Outcome moved = editing.move(List.of(first, second), 240, 3);
        assertTrue(moved.said(), moved.changed());
        assertEquals(240, note(piece(), first).tick());
        assertEquals(480, note(piece(), second).tick());
        assertEquals(63, note(piece(), first).pitch());
        assertEquals(67, note(piece(), second).pitch());
        assertEquals(others(before), others(piece()));

        // Earlier than the clip start: the whole group stops where the first note reaches tick 0.
        editing.move(List.of(first, second), -100000, 0);
        assertEquals(0, note(piece(), first).tick());
        assertEquals(240, note(piece(), second).tick());

        // Higher than 127: stops at 127 for the highest note.
        editing.move(List.of(first, second), 0, 200);
        assertEquals(127, note(piece(), second).pitch());
        assertEquals(123, note(piece(), first).pitch());
    }

    @Test
    public void lengthStaysWithinTheClipAndAboveTheShortest() {
        NoteRef first = melody(0);
        editing.resize(List.of(first), -100000, 60);
        assertEquals(60, note(piece(), first).length());
        MidiClip clip = clipOf(piece(), "melody");
        editing.resize(List.of(first), 10_000_000, 60);
        assertEquals(clip.lengthTicks(), note(piece(), first).length());
    }

    @Test
    public void anInsertedNoteFitsItsClipAndTakesTheVoiceAndLoudnessOfItsNeighbours() {
        PieceEditing.Outcome inserted = editing.insert("melody", 1000, 70, 240);
        assertTrue(inserted.said(), inserted.changed());
        assertEquals(1, inserted.selected().size());
        Note note = note(piece(), inserted.selected().get(0));
        assertNotNull(note);
        assertEquals(1000, note.tick());
        assertEquals(70, note.pitch());
        assertNotNull(piece().part("melody").voice(note.voiceId()));
        assertTrue(note.velocity() >= 75 && note.velocity() <= 85);

        MidiClip clip = clipOf(piece(), "melody");
        PieceEditing.Outcome atTheEnd = editing.insert("melody", clip.lengthTicks() - 10, 70, 480);
        assertEquals(10, note(piece(), atTheEnd.selected().get(0)).length());
        assertFalse("beyond the clip there is nothing to insert into",
                editing.insert("melody", clip.lengthTicks() + 10, 70, 480).changed());
    }

    @Test
    public void deleteCopyPasteAndQuantize() {
        NoteRef first = melody(0);
        NoteRef second = melody(1);
        int before = clipOf(piece(), "harmony").notes().size();
        assertEquals(2, editing.copy(List.of(first, second)));
        PieceEditing.Outcome pasted = editing.paste("harmony", 1930);
        assertTrue(pasted.said(), pasted.changed());
        assertEquals(before + 2, clipOf(piece(), "harmony").notes().size());
        Note one = note(piece(), pasted.selected().get(0));
        Note two = note(piece(), pasted.selected().get(1));
        assertEquals(1930, one.tick());
        assertEquals(2170, two.tick());
        assertEquals(60, one.pitch());
        assertNotNull("a pasted note goes to a voice of the part it lands in",
                piece().part("harmony").voice(one.voiceId()));

        PieceEditing.Outcome snapped = editing.quantize(pasted.selected(), 120);
        assertTrue(snapped.changed());
        assertEquals(1920, note(piece(), pasted.selected().get(0)).tick());
        assertEquals(2160, note(piece(), pasted.selected().get(1)).tick());

        PieceEditing.Outcome cut = editing.cut(List.of(first));
        assertTrue(cut.changed());
        assertNull(note(piece(), first));
        assertTrue(editing.canPaste());
    }

    @Test
    public void anEditIsSavedAtOnceAndReadsBackTheSame() throws Exception { // A9
        NoteRef first = melody(0);
        editing.move(List.of(first), 480, 5);
        editing.insert("bass", 960, 40, 480);
        Composition edited = piece();
        assertEquals(edited, CompositionJson.load(folder));
        ProjectSession reopened = ProjectSession.open(folder).orElseThrow();
        assertEquals(edited, reopened.composition());
        assertEquals(480, note(reopened.composition(), first).tick());
        assertTrue("the arrangement file follows, so renderer and score hear and show the change",
                Files.readString(folder.resolve("arrangement.tsv")).contains("note\tmelody\t480\t240\t65\t80"));
    }

    @Test
    public void undoAndRedoTakeBackExactlyOneAction() {
        Composition start = piece();
        editing.move(List.of(melody(0)), 120, 0);
        Composition moved = piece();
        editing.delete(List.of(melody(1)));
        assertTrue(editing.canUndo());
        editing.undo();
        assertEquals(moved, piece());
        editing.undo();
        assertEquals(start, piece());
        editing.redo();
        assertEquals(moved, piece());
    }

    @Test
    public void aHandEditSurvivesALaterChangeOfTheAiToAnotherPart() throws Exception { // A10
        NoteRef first = melody(0);
        editing.move(List.of(first), 240, 2);
        Note mine = note(piece(), first);
        Composition candidate = bassAnew(piece());
        Proposal proposal = session.proposeRecomposition("bass", candidate, "run-1");
        session.accept(proposal);
        assertEquals(mine, note(piece(), first));
        assertEquals(mine, note(ProjectSession.open(folder).orElseThrow().composition(), first));
    }

    @Test
    public void theEditorsPicksAndTheRangeSelectionOfTheAiAreTheSame() { // shared with C2
        Composition piece = piece();
        Selection bars = Selection.bars(piece, List.of("melody"), 2, 2);
        List<NoteRef> notes = NoteEdits.notesOf(piece, bars);
        assertFalse(notes.isEmpty());
        Selection back = NoteEdits.selectionOf(piece, notes).orElseThrow();
        assertEquals(Set.of(clipOf(piece, "melody").id()), back.clipIds());
        assertEquals(notes, NoteEdits.notesOf(piece, back));
    }

    @Test
    public void aPieceWithoutAModelCannotBeEdited() {
        PieceEditing none = new PieceEditing(Optional::empty);
        assertFalse(none.canEdit());
        assertFalse(none.insert("melody", 0, 60, 240).changed());
    }

    /** The bass as a generator might write it anew. */
    private static Composition bassAnew(Composition piece) {
        List<Track> tracks = new ArrayList<>();
        for (Track track : piece.tracks()) {
            if (!track.id().equals("bass")) {
                tracks.add(track);
                continue;
            }
            MidiClip clip = (MidiClip) track.clips().get(0);
            List<Note> notes = new ArrayList<>();
            int i = 0;
            for (Note n : clip.notes()) {
                notes.add(new Note("ai" + i++, n.tick(), n.length(), n.pitch() + 1, n.velocity(), n.voiceId()));
            }
            tracks.add(track.withClips(List.of(clip.withNotes(notes))));
        }
        return piece.withTracks(tracks);
    }
}
