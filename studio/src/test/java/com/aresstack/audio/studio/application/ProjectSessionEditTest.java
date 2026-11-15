package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.composition.edit.ScopeProof;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.PreviewFolder;
import com.aresstack.audio.studio.project.ProjectFile;
import com.aresstack.audio.studio.project.ProjectFolder;
import com.aresstack.audio.studio.project.StemManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package F3 in the folder of a piece: a proposal is previewed without overwriting
 * anything, accepted or discarded; changes can be undone; revisions are full copies, the first
 * the original; after a change only the stems of parts that sound different are dropped (A11).
 */
public final class ProjectSessionEditTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path piece;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        piece = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, piece.resolve(file.getFileName()));
            }
        }
        for (String stem : List.of("melody-sampled.wav", "harmony-sampled.wav", "bass.wav", "drums.wav")) {
            Files.write(piece.resolve(stem), stem.getBytes());
        }
    }

    /** The melody as a generator might write it anew, with the bass changed by mistake as well. */
    private static Composition candidate(Composition piece) {
        List<Track> tracks = new ArrayList<>();
        for (Track track : piece.tracks()) {
            List<com.aresstack.audio.composition.Clip> clips = new ArrayList<>();
            for (com.aresstack.audio.composition.Clip clip : track.clips()) {
                MidiClip midi = (MidiClip) clip;
                if (track.id().equals("melody") || track.id().equals("bass")) {
                    List<Note> notes = new ArrayList<>();
                    int i = 0;
                    for (Note n : midi.notes()) {
                        notes.add(new Note("new" + track.id() + i++, n.tick(), n.length(), Math.min(127, n.pitch() + 2),
                                n.velocity(), n.voiceId()));
                    }
                    clips.add(midi.withNotes(notes));
                } else {
                    clips.add(clip);
                }
            }
            tracks.add(track.withClips(clips));
        }
        return piece.withTracks(tracks);
    }

    private Map<String, byte[]> pieceFiles() throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        for (String name : List.of(CompositionJson.FILE_NAME, ProjectFolder.ARRANGEMENT, "mix.json")) {
            files.put(name, Files.readAllBytes(piece.resolve(name)));
        }
        return files;
    }

    private static void assertSame(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String name : expected.keySet()) {
            assertArrayEquals(name, expected.get(name), actual.get(name));
        }
    }

    @Test
    public void aProposalIsPreviewedWithoutOverwritingAndDiscardedWithoutTrace() throws Exception { // A11
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition before = session.composition();
        Map<String, byte[]> files = pieceFiles();
        List<StemManifest.Entry> stems = StemManifest.load(piece);

        Proposal proposal = session.proposeRecomposition("melody", candidate(before), "run-1");
        Path preview = session.preview(proposal);
        assertEquals(PreviewFolder.of(piece, proposal.id()), preview);
        assertTrue(Files.isRegularFile(preview.resolve(CompositionJson.FILE_NAME)));
        assertTrue(Files.isRegularFile(preview.resolve(ProjectFolder.ARRANGEMENT)));
        assertTrue(Files.isRegularFile(preview.resolve(ProjectFolder.MIDI)));
        assertEquals(proposal.result(), CompositionJson.load(preview));
        assertEquals(before, session.composition());
        assertSame(files, pieceFiles());
        assertEquals(stems, StemManifest.load(piece));

        session.discard(proposal);
        assertFalse(Files.exists(piece.resolve(ProjectFolder.PREVIEW)));
        assertEquals(before, session.composition());
        assertSame(files, pieceFiles());
        assertTrue(session.pending().isEmpty());
        assertFalse(session.canUndo());
    }

    @Test
    public void acceptingTakesOnlyThatPartAndDropsOnlyItsStem() throws Exception { // A11
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition before = session.composition();
        Proposal proposal = session.proposeRecomposition("melody", candidate(before), "run-1");
        session.preview(proposal);
        ChangeSet changes = session.accept(proposal);

        assertEquals(Set.of("melody"), changes.partsToRender());
        Composition after = session.composition();
        assertNotEquals(before.notesOf("melody"), after.notesOf("melody"));
        assertEquals("the bass the candidate changed stays", before.notesOf("bass"), after.notesOf("bass"));
        EditScope melody = TrackRecomposition.scope("melody");
        assertEquals(ScopeProof.hashOutside(before, melody), ScopeProof.hashOutside(after, melody));
        assertTrue(StemManifest.load(piece).stream().noneMatch(e -> e.partId().equals("melody")));
        assertTrue(StemManifest.load(piece).stream().anyMatch(e -> e.partId().equals("bass")));
        assertFalse(Files.exists(piece.resolve(ProjectFolder.PREVIEW)));
        assertEquals(after, CompositionJson.load(piece));
        assertEquals(after, ProjectSession.open(piece).orElseThrow().composition());

        List<Revision> revisions = session.revisions();
        assertEquals(List.of(Revision.Author.ORIGINAL, Revision.Author.AI),
                revisions.stream().map(Revision::author).toList());
        assertEquals(before, revisions.get(0).composition());
        assertEquals(after, revisions.get(1).composition());
        assertEquals("run-1", revisions.get(1).runId());
        assertEquals(revisions.get(1).id(), ProjectFile.load(piece).currentRevision());
    }

    @Test
    public void undoAndRedoGoBackAndForthInFilesToo() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition before = session.composition();
        byte[] arrangement = Files.readAllBytes(piece.resolve(ProjectFolder.ARRANGEMENT));
        Proposal proposal = session.proposeRecomposition("melody", candidate(before), null);
        session.accept(proposal);
        Composition after = session.composition();
        assertTrue(session.canUndo());

        assertEquals(Set.of("melody"), session.undo().partsToRender());
        assertEquals(before, session.composition());
        assertEquals(before, CompositionJson.load(piece));
        assertArrayEquals(arrangement, Files.readAllBytes(piece.resolve(ProjectFolder.ARRANGEMENT)));
        session.redo();
        assertEquals(after, session.composition());
        assertEquals(after, CompositionJson.load(piece));
    }

    @Test
    public void aRevisionLoadedIsTheStateItSaved() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition original = session.composition();
        MidiClip bass = (MidiClip) original.track("bass").clips().get(0);
        Note first = bass.notes().get(0);
        session.apply(Patch.of("Bass lauter", new EditOp.ChangeNote(bass.id(), new Note(first.id(), first.tick(),
                first.length(), first.pitch(), 127, first.voiceId()))), EditScope.part("bass"));
        session.save();
        Composition saved = session.composition();
        List<Revision> revisions = session.revisions();
        assertEquals(2, revisions.size());
        assertEquals(saved, revisions.get(1).composition());

        session.restoreRevision(revisions.get(0).id());
        assertEquals(original, session.composition());
        assertEquals(original, ProjectSession.open(piece).orElseThrow().composition());
        session.restoreRevision(revisions.get(1).id());
        assertEquals(saved, session.composition());
        assertEquals(4, session.revisions().size());
        assertTrue(session.revisions().get(0).original());
    }

    @Test
    public void aRefusedChangeChangesNothing() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Map<String, byte[]> files = pieceFiles();
        MidiClip bass = (MidiClip) session.composition().track("bass").clips().get(0);
        try {
            session.propose(Patch.of("fremd", new EditOp.RemoveNotes(bass.id(), List.of(bass.notes().get(0).id()))),
                    EditScope.part("melody"), Revision.Author.AI, null);
            fail("refused");
        } catch (EditRefusedException refused) {
            assertTrue(refused.violations().toString(), refused.violations().toString().contains("not a target"));
        }
        assertTrue(session.pending().isEmpty());
        assertSame(files, pieceFiles());
    }

    @Test
    public void aProposalOnAnOlderStateCannotBeAccepted() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Proposal proposal = session.proposeRecomposition("melody", candidate(session.composition()), null);
        MidiClip bass = (MidiClip) session.composition().track("bass").clips().get(0);
        session.apply(Patch.of("Hand", new EditOp.RemoveNotes(bass.id(), List.of(bass.notes().get(0).id()))),
                EditScope.everything());
        try {
            session.accept(proposal);
            fail("stale");
        } catch (IllegalStateException expected) {
            // the user decides anew on the current state
        }
    }

    @Test
    public void composingATrackAnewFromAnotherFolderLeavesThePieceFilesAlone() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Map<String, byte[]> files = pieceFiles();
        Path composed = temporary.newFolder("composed").toPath();
        List<String> lines = new ArrayList<>(Files.readAllLines(piece.resolve(ProjectFolder.ARRANGEMENT)));
        lines.replaceAll(line -> line.startsWith("note\tmelody\t0\t") ? line.replace("\t60\t", "\t62\t") : line);
        Files.write(composed.resolve(ProjectFolder.ARRANGEMENT), lines);
        Proposal proposal = session.proposeRecomposition("melody", composed, "run-2");
        assertSame(files, pieceFiles());
        assertEquals(Set.of("melody"), proposal.changes().partsToRender());
        session.accept(proposal);
        assertEquals(62, session.composition().notesOf("melody").get(0).note().pitch());
    }

    @Test
    public void restoringARevisionAfterAShiftBringsBackItsMixToo() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        Composition original = session.composition();
        com.aresstack.audio.composition.mix.MixScene mix = session.mix();
        session.apply(Patch.of("Zwei Takte Platz", new EditOp.ShiftTime(2 * 1920, 1920)), EditScope.everything());
        assertNotEquals("the automation moved along", mix, session.mix());
        session.restoreRevision(session.revisions().get(0).id());
        assertEquals(original, session.composition());
        assertEquals(mix, session.mix());
    }

    @Test
    public void aPieceBeyondTheTsvGetsItsMidiWrittenFromTheModel() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        // Since F2 the TSV says one meter and one key; a change of tempo it still cannot say.
        double bpm = session.composition().timeMap().tempoAt(0);
        com.aresstack.audio.composition.TimeMap faster = new com.aresstack.audio.composition.TimeMap(
                List.of(new com.aresstack.audio.composition.TimeMap.Tempo(0, bpm),
                        new com.aresstack.audio.composition.TimeMap.Tempo(4 * 1920, bpm + 20)),
                session.composition().timeMap().meters(),
                session.composition().timeMap().keys());
        session.apply(Patch.of("Schneller", new EditOp.SetTimeMap(faster)), EditScope.everything());
        assertArrayEquals(com.aresstack.audio.studio.project.MidiExport.bytes(session.composition()),
                Files.readAllBytes(piece.resolve(ProjectFolder.MIDI)));
        assertFalse(session.externalChanges());
        assertEquals(session.composition(), ProjectSession.open(piece).orElseThrow().composition());
    }

    @Test
    public void aWaltzStaysInTheTsvWithItsMeterLine() throws Exception {
        ProjectSession session = ProjectSession.open(piece).orElseThrow();
        com.aresstack.audio.composition.TimeMap waltz = new com.aresstack.audio.composition.TimeMap(
                session.composition().timeMap().tempos(),
                List.of(new com.aresstack.audio.composition.TimeMap.Meter(0, 3, 4)),
                session.composition().timeMap().keys());
        session.apply(Patch.of("Walzer", new EditOp.SetTimeMap(waltz)), EditScope.everything());
        assertTrue(session.withinTsv());
        assertTrue(Files.readAllLines(piece.resolve(ProjectFolder.ARRANGEMENT)).contains("meter\t3\t4"));
        assertFalse(session.externalChanges());
        assertEquals(session.composition(), ProjectSession.open(piece).orElseThrow().composition());
    }
}
