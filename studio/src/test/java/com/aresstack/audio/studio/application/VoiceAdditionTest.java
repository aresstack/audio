package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.edit.Proposal;
import com.aresstack.audio.composition.edit.Revision;
import com.aresstack.audio.composition.edit.ScopeProof;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.studio.ai.AddVoiceRequest;
import com.aresstack.audio.studio.ai.ChatVoicePlanner;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.ai.ForeignPartException;
import com.aresstack.audio.studio.ai.VoiceComposer;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.PreviewFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package C1, acceptance A4: a trumpet is added to an existing arrangement. The AI is told
 * the real notes of every other part, its answer becomes a proposal that adds nothing but the new
 * part, every other note stays provably unchanged, and the voice can be listened to alone,
 * discarded, generated again and accepted.
 */
public final class VoiceAdditionTest {
    private static final long BAR = 1920;
    private static final Progress QUIET = message -> { };

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path piece;
    private ProjectSession session;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        piece = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, piece.resolve(file.getFileName()));
            }
        }
        session = ProjectSession.open(piece).orElseThrow();
    }

    /** A model that answers plan and notes for whatever part and section it is asked; attempt 2 plays higher. */
    private static FakeChatModel trumpetPlayer() {
        return new FakeChatModel((instruction, prompt) -> {
            String part = FakeChatModel.quoted(prompt, "part");
            if (instruction.equals(ChatVoicePlanner.INSTRUCTION)) {
                return "{\"part\":\"" + part + "\",\"register\":{\"low\":60,\"high\":82},\"sections\":[],"
                        + "\"motifs\":\"answers the melody\"}";
            }
            String section = FakeChatModel.quoted(prompt, "section");
            int pitch = prompt.contains("Attempt 2") ? 79 : 72;
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":["
                    + "{\"bar\":1,\"beat\":2,\"duration\":2,\"pitch\":" + pitch + ",\"velocity\":90},"
                    + "{\"bar\":2,\"beat\":0,\"duration\":1,\"pitch\":" + (pitch + 2) + ",\"velocity\":85},"
                    + "{\"bar\":4,\"beat\":2,\"duration\":2,\"pitch\":" + (pitch - 1) + ",\"velocity\":80}]}";
        });
    }

    private static AddVoiceRequest trumpet(List<String> sections, boolean plan) {
        return new AddVoiceRequest("Trompete", "Trumpet", 56, "melody", null, sections, "strahlend, nicht zu dicht",
                "melody", AddVoiceRequest.MotifUse.VARY, plan);
    }

    @Test
    public void aTrumpetIsAddedAndNothingElseChanges() throws Exception {
        FakeChatModel model = trumpetPlayer();
        Composition before = session.composition();
        byte[] modelOnDisk = Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME));

        VoiceAddition addition = VoiceAddition.start(session, VoiceComposer.with(model), trumpet(List.of(), true),
                Ids.seeded(7), QUIET);

        // the AI was told the actual notes of all other parts, in plan and generation
        assertEquals(2, model.prompts().size());
        for (String prompt : model.prompts()) {
            for (Part part : before.parts()) {
                assertTrue(prompt.contains("\npart " + part.id()));
            }
            assertTrue(prompt.contains("  1 0 0.5 C4/60 80")); // first melody note of the fixture
            assertTrue(prompt.contains("MOTIF-SOURCE"));
        }
        // the generator follows the plan
        assertTrue(model.prompts().get(1).contains("Motifs: answers the melody"));
        assertTrue(model.prompts().get(1).contains("Register: C4/60 to A#5/82"));

        Proposal proposal = addition.proposal();
        Composition after = proposal.result();
        Part trumpet = addition.voice().part();
        assertEquals(before.parts().size() + 1, after.parts().size());
        assertEquals("Trompete", after.part(trumpet.id()).name());
        assertEquals(56, after.part(trumpet.id()).program());
        assertEquals(3, after.notesOf(trumpet.id()).size());
        assertEquals(List.of(), AddVoice.untouched(before, after, trumpet.id()));
        for (Part part : before.parts()) {
            assertEquals(before.notesOf(part.id()), after.notesOf(part.id()));
        }
        EditScope scope = proposal.scope();
        assertEquals(ScopeProof.hashOutside(before, scope), ScopeProof.hashOutside(after, scope, before));
        // only the new part is rendered; no stem of an existing part goes stale
        assertEquals(java.util.Set.of(trumpet.id()), proposal.changes().partsToRender());
        assertEquals(java.util.Set.of(), proposal.changes().staleStems());
        assertEquals(Revision.Author.AI, proposal.author());
        assertTrue(proposal.runId().startsWith("add-voice/" + trumpet.id() + "/1/"));

        // nothing of the piece is written before acceptance
        assertEquals(before, session.composition());
        assertArrayEquals(modelOnDisk, Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME)));

        // listen alone and with the piece
        Path preview = addition.preview();
        assertEquals(PreviewFolder.of(piece, proposal.id()), preview);
        assertTrue(Files.isRegularFile(preview.resolve(VoiceAddition.SOLO)));
        assertTrue(Files.isRegularFile(preview.resolve(CompositionJson.FILE_NAME)));
        assertEquals(after, CompositionJson.load(preview));

        // accept: the piece has the trumpet, a revision of the AI names the run
        ChangeSet changes = addition.accept();
        assertEquals(java.util.Set.of(trumpet.id()), changes.partsToRender());
        assertEquals(java.util.Set.of(), changes.staleStems());
        assertEquals(after, session.composition());
        assertFalse(Files.exists(preview));
        Revision last = session.revisions().get(session.revisions().size() - 1);
        assertEquals(Revision.Author.AI, last.author());
        assertEquals(proposal.runId(), last.runId());
        assertEquals(after, CompositionJson.load(piece));
        assertTrue(session.canUndo());
        session.undo();
        assertEquals(before, session.composition());
    }

    @Test
    public void anAcceptedVoiceGetsAStageSeatAChannelAndAnEntryInTheOldFiles() throws Exception {
        MixScene mixBefore = session.mix();
        TrackArrangement tracksBefore = new TrackArrangementStore().load(piece);
        SpatialScene stageBefore = new SpatialSceneStore().load(piece);
        VoiceAddition addition = VoiceAddition.start(session, VoiceComposer.with(trumpetPlayer()),
                trumpet(List.of(), false), Ids.seeded(13), QUIET);
        String id = addition.voice().part().id();
        assertEquals("nothing before acceptance", mixBefore, session.mix());
        addition.accept();

        // a channel with the usual seat of a second melody, the existing channels as they were
        MixChannel channel = session.mix().channelOf(MixChannel.Owner.part(id));
        assertNotNull(channel);
        SoundSourcePlacement usual = SpatialScene.standard(List.of(new MusicTrack("melody", "melody", "x", 0, null),
                new MusicTrack("other", "melody", "x", 0, null))).placement("other");
        assertEquals(usual.x(), channel.x(), 1e-9);
        assertEquals(usual.depth(), channel.depth(), 1e-9);
        assertEquals(usual.levelDb(), channel.levelDb(), 1e-9);
        assertFalse(channel.muted());
        assertEquals(mixBefore.channels().size() + 1, session.mix().channels().size());
        assertTrue(session.mix().channels().containsAll(mixBefore.channels()));
        assertEquals(session.mix(), com.aresstack.audio.studio.project.MixJson.load(piece));

        // tracks.json and spatial-scene.json know the trumpet; nothing else in them changed
        List<MusicTrack> tracks = new TrackArrangementStore().load(piece).tracks();
        assertEquals(tracksBefore.tracks().size() + 1, tracks.size());
        MusicTrack added = tracks.get(tracks.size() - 1);
        assertEquals(id, added.id());
        assertEquals("melody", added.role());
        assertEquals("Trumpet", added.instrument());
        assertEquals(56, added.program());
        assertEquals(described(tracksBefore.tracks()), described(tracks.subList(0, tracks.size() - 1)));
        SpatialScene stage = new SpatialSceneStore().load(piece);
        assertEquals(channel.x(), stage.placement(id).x(), 1e-3);
        for (SoundSourcePlacement seat : stageBefore.placements()) {
            assertEquals(SpatialSceneStore.write(new SpatialScene(null, List.of(seat))).toString(),
                    SpatialSceneStore.write(new SpatialScene(null, List.of(stage.placement(seat.trackId())))).toString());
        }
        assertFalse("our own writes are no external change", session.externalChanges());

        // undo takes the trumpet out of mix and old files again
        session.undo();
        assertNull(session.mix().channelOf(MixChannel.Owner.part(id)));
        assertEquals(mixBefore, session.mix());
        assertEquals(described(tracksBefore.tracks()), described(new TrackArrangementStore().load(piece).tracks()));
        assertNull(new SpatialSceneStore().load(piece).placement(id));
        assertFalse(session.externalChanges());
        session.redo();
        assertEquals(channel, session.mix().channelOf(MixChannel.Owner.part(id)));
        assertNotNull(new SpatialSceneStore().load(piece).placement(id));
    }

    @Test
    public void discardLeavesThePieceAndRegenerateMakesAnotherVersion() throws Exception {
        FakeChatModel model = trumpetPlayer();
        Composition before = session.composition();
        VoiceAddition addition = VoiceAddition.start(session, VoiceComposer.with(model), trumpet(List.of(), false),
                Ids.seeded(3), QUIET);
        assertEquals(1, model.prompts().size()); // no planning step asked for
        Proposal first = addition.proposal();
        Path firstPreview = addition.preview();

        Proposal second = addition.regenerate(QUIET);
        assertEquals(2, addition.attempt());
        assertNotEquals(first.id(), second.id());
        assertFalse("the old preview is gone", Files.exists(firstPreview));
        assertTrue(model.prompts().get(1).contains("Attempt 2"));
        String partId = addition.voice().part().id();
        assertEquals("the same new part, another version", first.result().part(partId), second.result().part(partId));
        assertNotEquals(first.result().notesOf(partId).get(0).note().pitch(),
                second.result().notesOf(partId).get(0).note().pitch());

        Path preview = addition.preview();
        addition.discard();
        assertNull(addition.proposal());
        assertFalse(Files.exists(preview));
        assertEquals(before, session.composition());
        assertTrue(session.pending().isEmpty());
        assertFalse(session.canUndo());

        // the first attempt again: answered from the cache, the model is not asked
        VoiceAddition again = VoiceAddition.start(session, VoiceComposer.with(model), trumpet(List.of(), false),
                Ids.seeded(3), QUIET);
        assertEquals(2, model.prompts().size());
        assertEquals(first.result(), again.proposal().result());
    }

    @Test
    public void theVoicePlaysOnlyInTheChosenSections() throws Exception {
        long end = session.composition().endTick();
        session.apply(Patch.of("Form", new EditOp.SetSections(List.of(
                new Section("verse", "Strophe", "verse", 0, 4 * BAR, null, List.of("C", "G", "Am", "F"), null),
                new Section("chorus", "Refrain", "chorus", 4 * BAR, end - 4 * BAR, null, List.of("C", "G", "F", "G"), null)))),
                EditScope.everything());
        Composition before = session.composition();
        VoiceAddition addition = VoiceAddition.start(session, VoiceComposer.with(trumpetPlayer()),
                trumpet(List.of("chorus"), false), Ids.seeded(5), QUIET);
        Composition after = addition.proposal().result();
        String partId = addition.voice().part().id();
        List<MidiClip> clips = new ArrayList<>();
        after.track(addition.voice().track().id()).clips().forEach(c -> clips.add((MidiClip) c));
        assertEquals(1, clips.size());
        assertEquals(4 * BAR, clips.get(0).startTick());
        for (var note : after.notesOf(partId)) {
            assertTrue(note.tick() >= 4 * BAR);
        }
        assertEquals(4 * BAR, addition.proposal().scope().fromTick());
        assertEquals(List.of(), AddVoice.untouched(before, after, partId));
    }

    @Test
    public void anAnswerForAnotherPartIsRefusedAndNothingIsProposed() throws Exception {
        FakeChatModel liar = new FakeChatModel((instruction, prompt) ->
                "{\"part\":\"melody\",\"notes\":[{\"bar\":1,\"beat\":0,\"duration\":1,\"pitch\":72,\"velocity\":90}]}");
        Composition before = session.composition();
        try {
            VoiceAddition.start(session, VoiceComposer.with(liar), trumpet(List.of(), false), Ids.seeded(1), QUIET);
            fail("an answer for the melody was taken");
        } catch (ForeignPartException expected) {
            assertTrue(expected.getMessage().contains("melody"));
        }
        assertTrue(session.pending().isEmpty());
        assertEquals(before, session.composition());
    }

    @Test
    public void aPatchThatTouchesAnotherPartIsRefusedByTheEditCore() throws Exception {
        VoiceAddition addition = VoiceAddition.start(session, VoiceComposer.with(trumpetPlayer()),
                trumpet(List.of(), false), Ids.seeded(9), QUIET);
        Composition before = session.composition();
        MidiClip melody = (MidiClip) before.track("melody").clips().get(0);
        Patch tampered = AddVoice.patch(addition.voice()).then(Patch.of("und die Melodie",
                new EditOp.RemoveNotes(melody.id(), List.of(melody.notes().get(0).id()))));
        try {
            session.propose(tampered, AddVoice.scope(addition.voice()), Revision.Author.AI, "test");
            fail("the melody was changed");
        } catch (EditRefusedException expected) {
            assertFalse(expected.violations().isEmpty());
        }
        // another new part than the one asked for is refused by the scope as well
        Patch otherPart = Patch.of("fremd", new EditOp.AddClip("melody", new MidiClip("zz1", 0, BAR, null, List.of())));
        try {
            session.propose(otherPart, AddVoice.scope(addition.voice()), Revision.Author.AI, "test");
            fail("a clip on the melody track was added");
        } catch (EditRefusedException expected) {
            assertNotNull(expected.violations());
        }
    }

    @Test
    public void theAiNeverAddressesExistingObjects() throws IOException, EditRefusedException {
        VoiceAddition addition = VoiceAddition.start(session, VoiceComposer.with(trumpetPlayer()),
                trumpet(List.of(), false), Ids.seeded(11), QUIET);
        Composition before = session.composition();
        for (EditOp op : addition.proposal().patch().operations()) {
            assertTrue(op.kind().toString(), AddVoice.KINDS.contains(op.kind()));
            if (op instanceof EditOp.AddClip add) {
                assertNull(before.track(add.trackId()));
            }
            if (op instanceof EditOp.AddPart add) {
                assertNull(before.part(add.part().id()));
            }
        }
    }

    private static List<String> described(List<MusicTrack> tracks) {
        return tracks.stream().map(t -> t.id() + "|" + t.role() + "|" + t.instrument() + "|" + t.program() + "|"
                + t.libraryId()).toList();
    }
}
