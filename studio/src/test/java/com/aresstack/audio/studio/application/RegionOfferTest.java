package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.ai.RegionComposer;
import com.aresstack.audio.studio.ai.RegionRequest;
import com.aresstack.audio.studio.project.CompositionJson;
import com.aresstack.audio.studio.project.ProjectFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A passage written anew as the window offers it (work package C2, the window's part): heard in a
 * preview that takes the stems of the piece along, written again, taken or thrown away; the saved
 * piece changes only when it is taken, and then only in the passage.
 */
public final class RegionOfferTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path folder;
    private ProjectSession session;

    @Before
    public void aPieceWithAFlute() throws Exception {
        folder = temporary.newFolder("piece").toPath();
        CompositionJson.save(folder, RegionReworkTest.flutePiece());
        session = ProjectSession.open(folder).orElseThrow();
    }

    /** Two notes in every bar asked for; the second attempt lower. */
    private static FakeChatModel writer() {
        return new FakeChatModel((instruction, prompt) -> {
            String part = FakeChatModel.quoted(prompt, "part");
            String section = FakeChatModel.quoted(prompt, "section");
            int pitch = prompt.contains("Attempt 2") ? 67 : 84;
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":["
                    + "{\"bar\":1,\"beat\":0,\"duration\":2,\"pitch\":" + pitch + ",\"velocity\":85},"
                    + "{\"bar\":2,\"beat\":0,\"duration\":2,\"pitch\":" + (pitch + 2) + ",\"velocity\":80}]}";
        });
    }

    private RegionOffer offered(List<String> parts, int first, int last, boolean variate) throws Exception {
        Selection selection = Selection.bars(session.composition(), parts, first, last);
        RegionRequest request = variate ? RegionRequest.variate(selection, RegionRequest.Preset.CALMER, "")
                : RegionRequest.replace(selection, "heller");
        return RegionOffer.start(session, RegionComposer.with(writer()), request, parts, first, last, message -> { });
    }

    @Test
    public void theOfferNamesThePassageAndThePieceStaysUntilItIsTaken() throws Exception {
        Composition before = session.composition();
        Path stems = Files.createDirectories(folder.resolve(ProjectFolder.RENDER).resolve("stems"));
        Files.write(stems.resolve("kept.wav"), new byte[]{1});
        RegionOffer offer = offered(List.of("flute"), 5, 8, false);

        assertTrue(offer.what(), offer.what().startsWith("Die KI hat Takt 5 bis 8 in Flöte neu geschrieben ("));
        assertEquals("Außerhalb der markierten Passage bleibt alles unverändert.",
                offer.notes().get(offer.notes().size() - 1));
        assertEquals("one track: it can be heard alone", "flute", offer.soloPartId());
        assertEquals("Nur diese Stimme anhören", offer.soloName());
        assertEquals(Map.of(), offer.newPrograms());
        Path preview = offer.preview();
        assertTrue(Files.isRegularFile(preview.resolve(ProjectFolder.RENDER).resolve("stems").resolve("kept.wav")));
        assertEquals(before, session.composition());

        offer.discard();
        assertEquals(before, session.composition());
        assertFalse(session.pending().isPresent());
        offer.discard();
    }

    @Test
    public void writtenAgainItIsAnotherAttemptAndTakenItChangesOnlyThePassage() throws Exception {
        Composition before = session.composition();
        RegionOffer offer = offered(List.of("flute", "strings"), 5, 5, true);
        assertTrue(offer.what(), offer.what().startsWith("Die KI hat Takt 5 in Flöte, Streicher variiert ("));
        assertNull("several tracks: only the whole piece is heard", offer.soloPartId());
        offer.again(message -> { });
        assertTrue(offer.what(), offer.what().contains("2. Versuch"));
        offer.preview();
        assertEquals(java.util.Set.of("flute", "strings"), offer.accept().partsToRender());
        Composition after = session.composition();
        assertEquals(before.notesOf("bass"), after.notesOf("bass"));
        assertFalse(before.notesOf("flute").equals(after.notesOf("flute")));
        assertTrue(session.canUndo());
        session.undo();
        assertEquals(before, session.composition());
    }
}
