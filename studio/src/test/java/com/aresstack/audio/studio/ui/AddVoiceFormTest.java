package com.aresstack.audio.studio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.studio.ai.AddVoiceRequest;
import com.aresstack.audio.studio.application.ProjectSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The form "Stimme hinzukomponieren" (work package C1, the window's part): what it asks the AI for. */
public final class AddVoiceFormTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Composition piece;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        Path folder = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        piece = ProjectSession.open(folder).orElseThrow().composition();
    }

    @Test
    public void untouchedItAsksForATrumpetOverTheWholePieceFreelyInventedAfterAPlan() {
        AddVoiceRequest request = new AddVoiceForm(piece).read();
        assertEquals("Trumpet", request.name());
        assertEquals(56, request.program());
        assertEquals("melody", request.role());
        assertEquals(List.of(), request.sectionIds());
        assertEquals(AddVoiceRequest.MotifUse.FREE, request.motifUse());
        assertNull(request.motifPartId());
        assertNull("the new voice plays the built-in sound until a bank is chosen in the table", request.libraryId());
        assertTrue(request.plan());
    }

    @Test
    public void itSaysWhatWasChosen() {
        AddVoiceForm form = new AddVoiceForm(piece);
        form.choose("Solovioline", 40, 4, new int[]{0}, "  leise, weit oben ", 0, 1, false);
        AddVoiceRequest request = form.read();
        assertEquals("Solovioline", request.name());
        assertEquals("Violin", request.instrument());
        assertEquals(40, request.program());
        assertEquals("texture", request.role());
        assertEquals("leise, weit oben", request.instruction());
        assertEquals(piece.parts().get(0).id(), request.motifPartId());
        assertEquals(AddVoiceRequest.MotifUse.QUOTE, request.motifUse());
        assertFalse(request.plan());
        // The fixture has one section: choosing every section is the whole piece.
        assertEquals(piece.sections().size() == 1 ? List.of() : List.of(piece.sections().get(0).id()),
                request.sectionIds());
        form.choose(null, 40, 0, new int[0], "", 1, 2, true);
        assertEquals(AddVoiceRequest.MotifUse.VARY, form.read().motifUse());
        assertEquals(piece.parts().get(1).id(), form.read().motifPartId());
        form.choose(null, 40, 0, new int[0], "", -1, 2, true);
        assertEquals("without a voice to take motifs from, the voice is invented freely",
                AddVoiceRequest.MotifUse.FREE, form.read().motifUse());
    }

    @Test
    public void theNameFollowsTheInstrumentUntilTheUserNamesTheVoice() {
        AddVoiceForm form = new AddVoiceForm(piece);
        form.choose(null, 73, 0, new int[0], "", -1, 0, true);
        assertEquals("Flute", form.nameShown());
        form.choose("Meine Flöte", 73, 0, new int[0], "", -1, 0, true);
        form.choose(null, 40, 0, new int[0], "", -1, 0, true);
        assertEquals("Meine Flöte", form.nameShown());
        assertEquals("Violin", form.read().instrument());
    }

    @Test
    public void aVoiceWithoutANameIsRefusedInTheWordsOfTheRequest() {
        AddVoiceForm form = new AddVoiceForm(piece);
        form.choose("  ", 56, 0, new int[0], "", -1, 0, true);
        try {
            form.read();
            fail();
        } catch (IllegalArgumentException refused) {
            assertEquals("Die neue Stimme braucht einen Namen.", refused.getMessage());
        }
    }
}
