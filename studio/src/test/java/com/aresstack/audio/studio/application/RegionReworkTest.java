package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Meta;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Role;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.composition.edit.ScopeProof;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.ai.ChatRegionGenerator;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.ai.ForeignPartException;
import com.aresstack.audio.studio.ai.RegionComposer;
import com.aresstack.audio.studio.ai.RegionRequest;
import com.aresstack.audio.studio.ai.RewrittenRegion;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.PreviewFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package C2, acceptance A5 and A6: a passage of selected parts is written anew or varied.
 * The AI sees the bars before and after the passage, its answer becomes a proposal that changes
 * nothing but the selected notes, a note held into the passage stays, notes the AI writes past the
 * passage are cut and recorded, and the passage can be listened to, written again, accepted and
 * discarded.
 */
public final class RegionReworkTest {
    private static final long BAR = 1920;
    private static final long BEAT = 480;
    private static final Progress QUIET = message -> { };

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path folder;
    private ProjectSession session;

    /** Twelve bars in three sections: flute melody, strings in whole-bar chords, bass in quarters. */
    public static Composition flutePiece() {
        Part flute = part("flute", "Flöte", Role.MELODY, "Flute", 73);
        Part strings = part("strings", "Streicher", Role.HARMONY, "String ensemble", 48);
        Part bass = part("bass", "Bass", Role.BASS, "Acoustic bass", 32);
        List<Note> fluteNotes = new ArrayList<>();
        List<Note> stringNotes = new ArrayList<>();
        List<Note> bassNotes = new ArrayList<>();
        int[] melody = {72, 74, 76, 77, 79, 77, 76, 74};
        for (int bar = 0; bar < 12; bar++) {
            long start = bar * BAR;
            if (bar == 3) {
                fluteNotes.add(new Note("fl" + bar + "a", start, BEAT, 76, 90, "flute-v"));
                // beat 3 of bar 4, a whole note: sounds two beats into bar 5
                fluteNotes.add(new Note("held", start + 2 * BEAT, 4 * BEAT, 79, 90, "flute-v"));
            } else {
                for (int i = 0; i < 4; i++) {
                    fluteNotes.add(new Note("fl" + bar + i, start + i * BEAT, BEAT, melody[(bar + i) % 8], 90, "flute-v"));
                }
            }
            for (int pitch : new int[] {60, 64, 67}) {
                stringNotes.add(new Note("st" + bar + "-" + pitch, start, BAR, pitch, 90, "strings-v"));
            }
            for (int i = 0; i < 4; i++) {
                bassNotes.add(new Note("ba" + bar + i, start + i * BEAT, BEAT, 36 + (i % 2) * 7, 95, "bass-v"));
            }
        }
        List<Track> tracks = List.of(
                new Track("flute-t", "Flöte", Track.Kind.MIDI, "flute",
                        List.of(new MidiClip("flute-c", 0, 12 * BAR, null, fluteNotes))),
                new Track("strings-t", "Streicher", Track.Kind.MIDI, "strings",
                        List.of(new MidiClip("strings-c", 0, 12 * BAR, null, stringNotes))),
                new Track("bass-t", "Bass", Track.Kind.MIDI, "bass",
                        List.of(new MidiClip("bass-c", 0, 12 * BAR, null, bassNotes))));
        List<String> chords = List.of("C", "Am", "F", "G");
        List<Section> sections = List.of(
                new Section("verse", "Strophe", "verse", 0, 4 * BAR, 0.5, chords, null),
                new Section("chorus", "Refrain", "chorus", 4 * BAR, 4 * BAR, 0.8, chords, "Höhepunkt"),
                new Section("outro", "Ausklang", "outro", 8 * BAR, 4 * BAR, 0.3, chords, null));
        return new Composition(new Meta("Flötenstück", "Folk", "hell", null, null, null), TimeMap.of(96), sections,
                List.of(flute, strings, bass), tracks, List.of());
    }

    private static Part part(String id, String name, String role, String instrument, int program) {
        return new Part(id, name, role, instrument, program, null, Part.StaffLayout.SINGLE,
                List.of(new Voice(id + "-v", name, null, null)), null);
    }

    @Before
    public void aPieceWithAFlute() throws Exception {
        folder = temporary.newFolder("piece").toPath();
        CompositionJson.save(folder, flutePiece());
        session = ProjectSession.open(folder).orElseThrow();
    }

    /** A model that writes what {@code notes} gives for part and section, as JSON. */
    private static FakeChatModel model(BiFunction<String, String, String> notes) {
        return new FakeChatModel((instruction, prompt) -> {
            assertEquals(ChatRegionGenerator.INSTRUCTION, instruction);
            String part = FakeChatModel.quoted(prompt, "part");
            String section = FakeChatModel.quoted(prompt, "section");
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":[" + notes.apply(part, prompt)
                    + "]}";
        });
    }

    private static String note(int bar, double beat, double duration, int pitch, int velocity) {
        return "{\"bar\":" + bar + ",\"beat\":" + beat + ",\"duration\":" + duration + ",\"pitch\":" + pitch
                + ",\"velocity\":" + velocity + "}";
    }

    /** A new flute line: two notes per bar, attempt 2 a fifth lower; and one note in bar 5 of 4, which is bar 9. */
    private static FakeChatModel newFlute() {
        return model((part, prompt) -> {
            int base = prompt.contains("Attempt 2") ? 67 : 84;
            StringBuilder notes = new StringBuilder();
            for (int bar = 1; bar <= 4; bar++) {
                notes.append(bar == 1 ? "" : ",").append(note(bar, 0, 2, base - bar, 85)).append(',')
                        .append(note(bar, 2, 2, base + 2 - bar, 80));
            }
            // one note sounds past the end of the region, one begins after it (bar 9 of the piece)
            notes.append(',').append(note(4, 3, 3, base, 70)).append(',').append(note(5, 0, 1, base, 70));
            return notes.toString();
        });
    }

    @Test
    public void a5TheFluteIsReplacedInBarsFiveToEightAndNothingElseChanges() throws Exception {
        Composition before = session.composition();
        byte[] modelOnDisk = Files.readAllBytes(folder.resolve(CompositionJson.FILE_NAME));
        FakeChatModel model = newFlute();
        Selection flute = Selection.bars(before, List.of("flute"), 5, 8);

        RegionRework rework = RegionRework.start(session, RegionComposer.with(model),
                RegionRequest.replace(flute, "verspielter, mit Triolengefühl"), Ids.seeded(5), QUIET);

        // one call: the region lies in one section
        assertEquals(1, model.prompts().size());
        String prompt = model.prompts().get(0);
        // context before AND after: the bars around the region of every part, in full
        assertTrue(prompt, prompt.contains("# NOTES bars 3-10"));
        assertTrue(prompt, prompt.contains("REGION bars 5-8: 16 notes to be written anew, not shown"));
        assertFalse("the old flute notes of the region are not shown", prompt.contains("  5 0 1 G5/79 90"));
        assertTrue("strings in the region are context", prompt.contains("  5 0 4 C4/60 90"));
        assertTrue("the flute after the region", prompt.contains("After the region the part continues with"));
        assertTrue(prompt, prompt.contains("  9 0 1 "));
        assertTrue("the note held into the region", prompt.contains("held-into-region 4 2 4 G5/79 90"));
        assertTrue(prompt, prompt.contains("Still sounding into the region from before"));
        assertTrue(prompt, prompt.contains("Write NEW notes for ONE part in the REGION, bars 5-8"));
        assertTrue(prompt, prompt.contains("User instruction: verspielter, mit Triolengefühl"));
        assertTrue(prompt, prompt.contains("Chords by bar of these bars: C | Am | F | G"));

        Proposal proposal = rework.proposal();
        Composition after = proposal.result();
        // only the flute in bars 5-8 is different; every other note is where it was
        assertEquals(List.of(), RegionEdit.outsideChanges(before, after, flute));
        EditScope scope = proposal.scope();
        assertEquals(ScopeProof.hashOutside(before, scope), ScopeProof.hashOutside(after, scope, before));
        assertEquals(before.notesOf("strings"), after.notesOf("strings"));
        assertEquals(before.notesOf("bass"), after.notesOf("bass"));
        for (PlacedNote n : before.notesOf("flute")) {
            if (n.tick() < 4 * BAR || n.tick() >= 8 * BAR) {
                assertTrue("kept " + n.note().id(), after.notesOf("flute").stream()
                        .anyMatch(m -> m.note().equals(n.note()) && m.tick() == n.tick()));
            }
        }
        // the held note stays as it was, sounding into bar 5
        PlacedNote held = after.notesOf("flute").stream().filter(n -> n.note().id().equals("held")).findFirst()
                .orElseThrow();
        assertEquals(3 * BAR + 2 * BEAT, held.tick());
        assertEquals(4 * BEAT, held.note().length());
        // the new passage: 8 notes plus the one cut at the end of bar 8; the one in bar 9 is left out
        List<PlacedNote> inRegion = after.notesOf("flute").stream()
                .filter(n -> n.tick() >= 4 * BAR && n.tick() < 8 * BAR).toList();
        assertEquals(9, inRegion.size());
        assertTrue(inRegion.stream().allMatch(n -> n.endTick() <= 8 * BAR));
        assertTrue(inRegion.stream().allMatch(n -> n.note().voiceId().equals("flute-v")));
        assertEquals(83, inRegion.get(0).note().pitch());
        assertEquals(after.notesOf("flute").size() - 9,
                before.notesOf("flute").size() - flute.notes(before, "flute").size());
        // ... and both are recorded
        String corrections = String.join("\n", rework.corrections());
        assertTrue(corrections, corrections.contains("flute\tchorus\t9\tduration\t3\t1\tam Abschnittsende abgeschnitten"));
        assertTrue(corrections, corrections.contains("flute\tchorus\t10\tnote\tpresent\tskipped\tTakt 5 liegt nicht im Abschnitt (1-4)"));
        // only the flute is rendered again
        assertEquals(Set.of("flute"), proposal.changes().partsToRender());
        assertEquals(Revision.Author.AI, proposal.author());
        assertTrue(proposal.runId(), proposal.runId().startsWith("replace-region/flute/1/"));
        assertEquals("Neu geschrieben: Flöte, Takt 5-8", proposal.patch().description());

        // nothing of the piece is written before acceptance
        assertEquals(before, session.composition());
        assertArrayEquals(modelOnDisk, Files.readAllBytes(folder.resolve(CompositionJson.FILE_NAME)));

        // listen with the piece and to the flute alone
        Path preview = rework.preview();
        assertEquals(PreviewFolder.of(folder, proposal.id()), preview);
        assertTrue(Files.isRegularFile(preview.resolve(RegionRework.PARTS)));

        // accept: a revision of the AI, undoable
        ChangeSet changes = rework.accept();
        assertEquals(Set.of("flute"), changes.partsToRender());
        assertEquals(after, session.composition());
        assertEquals(after, CompositionJson.load(folder));
        assertEquals(Revision.Author.AI, session.revisions().get(session.revisions().size() - 1).author());
        session.undo();
        assertEquals(before, session.composition());
    }

    @Test
    public void regenerateAndDiscard() throws Exception {
        Composition before = session.composition();
        FakeChatModel model = newFlute();
        Selection flute = Selection.bars(before, List.of("flute"), 5, 8);
        RegionRework rework = RegionRework.start(session, RegionComposer.with(model), RegionRequest.replace(flute, ""),
                Ids.seeded(9), QUIET);
        Composition first = rework.proposal().result();
        Proposal second = rework.regenerate(QUIET);
        assertEquals(2, rework.attempt());
        assertTrue(model.prompts().get(1).contains("Attempt 2"));
        assertNotEquals(first, second.result());
        assertEquals(List.of(), RegionEdit.outsideChanges(before, second.result(), flute));
        rework.discard();
        assertNull(rework.proposal());
        assertEquals(before, session.composition());
        assertFalse(session.pending().isPresent());
        // the answers stay in the piece as the record of where a passage came from
        assertTrue(Files.isDirectory(folder.resolve(RegionRework.CACHE)));
    }

    @Test
    public void a6TheStringsBecomeCalmerInTheChorus() throws Exception {
        Composition before = session.composition();
        // a calmer variation: per bar one long chord tone instead of three, softer
        FakeChatModel model = model((part, prompt) -> {
            StringBuilder notes = new StringBuilder();
            for (int bar = 1; bar <= 4; bar++) {
                notes.append(bar == 1 ? "" : ",").append(note(bar, 0, 4, 60 + (bar % 2) * 4, 60));
            }
            return notes.toString();
        });
        Selection strings = Selection.bars(before, List.of("strings"), 5, 8);
        RegionRework rework = RegionRework.start(session, RegionComposer.with(model),
                RegionRequest.variate(strings, RegionRequest.Preset.CALMER, "ruhiger, getragen"), Ids.seeded(3), QUIET);

        String prompt = model.prompts().get(0);
        assertTrue(prompt, prompt.contains("Write a VARIATION of ONE part in the REGION, bars 5-8"));
        // the old notes are the template, bars counted from the start of the passage
        assertTrue(prompt, prompt.contains("Template, the notes of the part here so far"));
        assertTrue(prompt, prompt.contains("  1 0 4 C4/60 90\n  1 0 4 E4/64 90\n  1 0 4 G4/67 90\n"));
        assertTrue(prompt, prompt.contains("  4 0 4 G4/67 90\n"));
        assertTrue(prompt, prompt.contains(RegionRequest.Preset.CALMER.rule()));
        assertTrue(prompt, prompt.contains("User instruction: ruhiger, getragen"));

        Composition after = rework.proposal().result();
        assertEquals(List.of(), RegionEdit.outsideChanges(before, after, strings));
        assertEquals(before.notesOf("flute"), after.notesOf("flute"));
        assertEquals(before.notesOf("bass"), after.notesOf("bass"));
        List<PlacedNote> chorus = after.notesOf("strings").stream()
                .filter(n -> n.tick() >= 4 * BAR && n.tick() < 8 * BAR).toList();
        List<PlacedNote> was = strings.notes(before, "strings");
        assertEquals(4, chorus.size());
        assertEquals(12, was.size());
        // calmer: fewer notes, softer
        assertTrue(chorus.stream().allMatch(n -> n.note().velocity() < 90));
        assertTrue(rework.proposal().runId().startsWith("variate-region/strings/1/"));
        assertEquals("Variation: Streicher, Takt 5-8", rework.proposal().patch().description());
    }

    @Test
    public void twoPartsAcrossTwoSectionsAreWrittenSectionBySectionWithConnection() throws Exception {
        Composition before = session.composition();
        FakeChatModel model = model((part, prompt) -> note(1, 0, 1, part.equals("bass") ? 40 : 81, 77) + ","
                + note(2, 0, 1, part.equals("bass") ? 43 : 79, 77));
        Selection both = Selection.bars(before, List.of("bass", "flute"), 7, 10);
        RegionRework rework = RegionRework.start(session, RegionComposer.with(model), RegionRequest.replace(both, ""),
                Ids.seeded(11), QUIET);
        // flute then bass (order of the piece), each in chorus (bars 7-8) and outro (bars 9-10)
        assertEquals(4, model.prompts().size());
        assertEquals("flute", FakeChatModel.quoted(model.prompts().get(0), "part"));
        assertEquals("chorus", FakeChatModel.quoted(model.prompts().get(0), "section"));
        assertEquals("outro", FakeChatModel.quoted(model.prompts().get(1), "section"));
        assertEquals("bass", FakeChatModel.quoted(model.prompts().get(2), "part"));
        // the second section continues from what the first just wrote
        assertTrue(model.prompts().get(1).contains("Before these bars the part plays"));
        assertTrue(model.prompts().get(1).contains("  8 0 1 G5/79 77"));
        assertTrue(model.prompts().get(0).contains("The region continues after these bars"));
        assertTrue(model.prompts().get(1).contains("After the region the part continues with"));
        Composition after = rework.proposal().result();
        assertEquals(List.of(), RegionEdit.outsideChanges(before, after, both));
        assertEquals(before.notesOf("strings"), after.notesOf("strings"));
        assertEquals(4, both.notes(after, "flute").size());
        assertEquals(4, both.notes(after, "bass").size());
        assertEquals(Set.of("flute", "bass"), rework.proposal().changes().partsToRender());
    }

    @Test
    public void aPartThatHasNoClipThereGetsANewOneOnItsTrack() throws Exception {
        // the flute pauses from bar 9 on: its clip ends with bar 8
        Composition shorter = session.composition();
        MidiClip clip = (MidiClip) shorter.track("flute-t").clips().get(0);
        List<String> late = clip.notes().stream().filter(n -> n.tick() >= 8 * BAR).map(Note::id).toList();
        session.apply(Patch.of("kürzer", new EditOp.RemoveNotes("flute-c", late), new EditOp.RemoveClip("flute-c"),
                new EditOp.AddClip("flute-t", new MidiClip("flute-c", 0, 8 * BAR, null, clip.notes().stream()
                        .filter(n -> n.tick() < 8 * BAR).toList()))), EditScope.everything());
        Composition before = session.composition();
        FakeChatModel model = model((part, prompt) -> note(1, 0, 2, 80, 70) + "," + note(2, 2, 4, 81, 70));
        Selection flute = Selection.bars(before, List.of("flute"), 7, 10);
        RegionRework rework = RegionRework.start(session, RegionComposer.with(model), RegionRequest.replace(flute, ""),
                Ids.seeded(13), QUIET);
        Composition after = rework.proposal().result();
        assertEquals(List.of(), RegionEdit.outsideChanges(before, after, flute));
        Track track = after.track("flute-t");
        assertEquals(2, track.clips().size());
        MidiClip added = (MidiClip) track.clips().get(1);
        assertEquals(8 * BAR, added.startTick());
        assertEquals(2 * BAR, added.lengthTicks());
        assertNull(added.partId());
        // bar 9 beat 0, and bar 10 beat 3 cut at the end of the region
        assertEquals(2, added.notes().size());
        assertEquals(BAR + 2 * BEAT, added.notes().get(1).tick());
        assertEquals(2 * BEAT, added.notes().get(1).length());
        assertEquals(4, flute.notes(after, "flute").size());
    }

    @Test
    public void aClipSelectionChangesOnlyThatClip() throws Exception {
        Composition before = session.composition();
        FakeChatModel model = model((part, prompt) -> note(1, 0, 4, 41, 70));
        Selection clip = Selection.clips(before, List.of("bass-c")).within(0, 2 * BAR);
        RegionRework rework = RegionRework.start(session, RegionComposer.with(model), RegionRequest.replace(clip, ""),
                Ids.seeded(17), QUIET);
        Composition after = rework.proposal().result();
        assertEquals(List.of(), RegionEdit.outsideChanges(before, after, clip));
        assertEquals(1, clip.notes(after, "bass").size());
        assertEquals(before.notesOf("bass").size() - 8 + 1, after.notesOf("bass").size());
        assertEquals(before.notesOf("flute"), after.notesOf("flute"));
    }

    @Test
    public void anAnswerForAnotherPartIsRefusedAndNothingIsProposed() throws Exception {
        FakeChatModel model = new FakeChatModel((instruction, prompt) -> "{\"part\":\"strings\",\"section\":\"chorus\","
                + "\"notes\":[" + note(1, 0, 1, 60, 80) + "]}");
        Selection flute = Selection.bars(session.composition(), List.of("flute"), 5, 8);
        try {
            RegionRework.start(session, RegionComposer.with(model), RegionRequest.replace(flute, ""), QUIET);
            fail("refused");
        } catch (ForeignPartException expected) {
            assertTrue(expected.getMessage().contains("strings"));
        }
        assertFalse(session.pending().isPresent());
    }

    @Test
    public void theEditCoreRefusesAPassageOutsideTheSelection() {
        Composition before = session.composition();
        Selection flute = Selection.bars(before, List.of("flute"), 5, 8);
        // a passage that would reach the strings: the patch builder takes only selected parts,
        // and the scope of the selection refuses even a hand-made patch for them
        Patch strings = Patch.of("fremd", new EditOp.RemoveNotes("strings-c", List.of("st5-60")));
        assertFalse(com.aresstack.audio.composition.edit.CompositionEditor.apply(before, strings,
                RegionEdit.scope(flute)).applied());
        Patch early = Patch.of("zu früh", new EditOp.RemoveNotes("flute-c", List.of("held")));
        assertFalse(com.aresstack.audio.composition.edit.CompositionEditor.apply(before, early,
                RegionEdit.scope(flute)).applied());
        RewrittenRegion foreign = new RewrittenRegion(RegionRequest.replace(flute, ""),
                java.util.Map.of("flute", List.of(new RewrittenRegion.NewNote(2 * BAR, BEAT, 70, 80))),
                RegionComposer.context(before, RegionRequest.replace(flute, "")), List.of(), 1);
        Patch outside = RegionEdit.patch(before, foreign, Ids.seeded(1), new ArrayList<>());
        assertFalse(com.aresstack.audio.composition.edit.CompositionEditor.apply(before, outside,
                RegionEdit.scope(flute)).applied());
    }
}
