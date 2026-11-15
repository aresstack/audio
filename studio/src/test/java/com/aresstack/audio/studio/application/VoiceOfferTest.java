package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.studio.ai.AddVoiceRequest;
import com.aresstack.audio.studio.ai.ChatVoicePlanner;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.ai.VoiceComposer;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.ProjectFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A new voice as the window offers it (work package C1, the window's part): heard in a preview
 * that takes the stems of the piece along, made again, taken or thrown away; the saved piece
 * changes only when it is taken.
 */
public final class VoiceOfferTest {
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
                    + "{\"bar\":2,\"beat\":0,\"duration\":1,\"pitch\":" + (pitch + 2) + ",\"velocity\":85}]}";
        });
    }

    private VoiceOffer offered() throws Exception {
        return VoiceOffer.start(session, VoiceComposer.with(trumpetPlayer()), new AddVoiceRequest("Trompete",
                "Trumpet", 56, "melody", null, List.of(), "strahlend", null, AddVoiceRequest.MotifUse.FREE, true),
                message -> { });
    }

    private Path stem(Path folder, String name) throws Exception {
        Path stems = Files.createDirectories(folder.resolve(ProjectFolder.RENDER).resolve("stems"));
        return Files.write(stems.resolve(name), name.getBytes());
    }

    @Test
    public void theOfferSaysWhatItIsAndThePieceStaysUntilItIsTaken() throws Exception {
        Composition before = session.composition();
        byte[] saved = Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME));
        stem(piece, "kept.wav");
        VoiceOffer offer = offered();

        assertTrue(offer.what(), offer.what().contains("„Trompete“") && offer.what().contains("2 Noten"));
        assertTrue(offer.notes().toString(), offer.notes().contains("Plan: answers the melody"));
        assertEquals("Alle anderen Stimmen bleiben unverändert.", offer.notes().get(offer.notes().size() - 1));
        String added = offer.soloPartId();
        assertNull(before.part(added));
        assertNotNull(offer.proposed().part(added));
        assertEquals(Map.of(added, 56), offer.newPrograms());

        Path preview = offer.preview();
        assertTrue("the stems of the piece go along, so that only the new voice is rendered",
                Files.isRegularFile(preview.resolve(ProjectFolder.RENDER).resolve("stems").resolve("kept.wav")));
        assertEquals(before, session.composition());
        assertTrue(java.util.Arrays.equals(saved, Files.readAllBytes(piece.resolve(CompositionJson.FILE_NAME))));

        offer.discard();
        assertEquals(before, session.composition());
        assertFalse(session.pending().isPresent());
        assertFalse(Files.exists(preview));
        offer.discard(); // thrown away twice is thrown away
    }

    @Test
    public void madeAgainItIsAnotherAttemptAndTakenItIsPartOfThePieceWithItsStem() throws Exception {
        VoiceOffer offer = offered();
        offer.preview();
        offer.again(message -> { });
        assertTrue(offer.what(), offer.what().contains("2. Versuch"));
        assertEquals(79, offer.proposed().notesOf(offer.soloPartId()).get(0).note().pitch());

        Path preview = offer.preview();
        stem(preview, "new-voice.wav"); // what rendering the preview leaves there
        String added = offer.soloPartId();
        assertEquals(java.util.Set.of(added), offer.accept().partsToRender());
        assertNotNull(session.composition().part(added));
        assertEquals("Trompete", session.composition().part(added).name());
        assertTrue("the stem that was heard is the stem of the piece now",
                Files.isRegularFile(piece.resolve(ProjectFolder.RENDER).resolve("stems").resolve("new-voice.wav")));
        assertTrue(session.canUndo());
        session.undo();
        assertNull(session.composition().part(added));
    }
}
