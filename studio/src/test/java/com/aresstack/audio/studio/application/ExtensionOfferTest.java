package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.studio.ai.ChatContinuationPlanner;
import com.aresstack.audio.studio.ai.ContinuationComposer;
import com.aresstack.audio.studio.ai.ContinuationEnding;
import com.aresstack.audio.studio.ai.ContinuationPreset;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.FakeChatModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A longer piece as the window offers it (work package C3, the window's part): the offer tells the
 * plan of the AI, is heard from shortly before the new bars, can be written again, taken or
 * thrown away; the saved piece changes only when it is taken.
 */
public final class ExtensionOfferTest {
    private static final long BAR = 1920;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path folder;
    private ProjectSession session;

    @Before
    public void anOldPieceOfEightBars() throws Exception {
        folder = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        session = ProjectSession.open(folder).orElseThrow();
    }

    /** A plan of two sections over eight bars that brings timpani in, and two notes per bar for every part. */
    private static FakeChatModel model() {
        return new FakeChatModel((instruction, prompt) -> {
            if (instruction.equals(ChatContinuationPlanner.INSTRUCTION)) {
                return "{\"sections\":["
                        + "{\"name\":\"Aufbau\",\"function\":\"build\",\"bars\":4,\"intensity\":0.5,"
                        + "\"chords\":[\"Am\",\"F\",\"C\",\"G\"],\"idea\":\"strings swell\","
                        + "\"parts\":[\"melody\",\"harmony\",\"bass\"]},"
                        + "{\"name\":\"Höhepunkt\",\"function\":\"climax\",\"bars\":4,\"intensity\":1.0,"
                        + "\"chords\":[\"C\",\"G\",\"Am\",\"F\"],\"idea\":\"tutti\","
                        + "\"parts\":[\"melody\",\"harmony\",\"bass\",\"drums\",\"new1\"]}],"
                        + "\"newParts\":[{\"key\":\"new1\",\"name\":\"Pauke\",\"instrument\":\"Timpani\",\"program\":47,"
                        + "\"role\":\"bass\",\"idea\":\"rolls into the climax\"}],"
                        + "\"motifs\":\"the opening arpeggio returns\",\"arc\":\"rise\"}";
            }
            String part = FakeChatModel.quoted(prompt, "part");
            String section = FakeChatModel.quoted(prompt, "section");
            int bars = Integer.parseInt(prompt.substring(prompt.lastIndexOf("up to ") + 6).split(";")[0].trim());
            int pitch = (prompt.contains("Attempt 2") ? 1 : 0) + switch (part) {
                case "melody" -> 72;
                case "harmony" -> 60;
                case "bass" -> 40;
                case "drums" -> 36;
                default -> 45;
            };
            StringBuilder notes = new StringBuilder();
            for (int bar = 1; bar <= bars; bar++) {
                notes.append(notes.length() == 0 ? "" : ",").append("{\"bar\":").append(bar)
                        .append(",\"beat\":0,\"duration\":2,\"pitch\":").append(pitch).append(",\"velocity\":80},")
                        .append("{\"bar\":").append(bar).append(",\"beat\":2,\"duration\":2,\"pitch\":")
                        .append(pitch + 2).append(",\"velocity\":75}");
            }
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":[" + notes + "]}";
        });
    }

    private ExtensionOffer offered(ContinuationRequest request) throws Exception {
        return ExtensionOffer.start(session, ContinuationComposer.with(model()), request, message -> { });
    }

    @Test
    public void theOfferTellsThePlanAndLeavesThePieceAlone() throws Exception {
        Composition before = session.composition();
        ExtensionOffer offer = offered(ContinuationRequest.of(8, ContinuationPreset.DRAMATIC_BUILD,
                ContinuationEnding.CLOSED));
        assertEquals("nothing is taken yet", before, session.composition());
        String what = offer.what();
        assertTrue(what, what.startsWith("Die KI hat das Stück um 8 Takte verlängert (2 Abschnitte, "));
        assertTrue(what, what.contains("1 neues Instrument"));
        List<String> notes = offer.notes();
        assertTrue(notes.toString(), notes.contains("Verlauf: rise"));
        assertTrue(notes.toString(), notes.contains("Motive: the opening arpeggio returns"));
        assertTrue(notes.toString(), notes.contains("Abschnitt „Aufbau“ (build) · neue Takte 1 bis 4 · Intensität 50 %"
                + " · Am | F | C | G · strings swell"));
        assertTrue(notes.toString(), notes.contains("Abschnitt „Höhepunkt“ (climax) · neue Takte 5 bis 8"
                + " · Intensität 100 % · C | G | Am | C · tutti"));
        assertTrue("what the application repaired in the plan is said: " + notes,
                notes.contains("Am Plan korrigiert: Schlussakkord F durch die Tonika C ersetzt (Abschluss)."));
        assertTrue(notes.toString(), notes.contains("Neues Instrument: Pauke (Timpani) · rolls into the climax"));
        assertEquals("Das bisherige Stück bleibt Note für Note unverändert.", notes.get(notes.size() - 1));
        assertNull("new bars are heard in the whole piece", offer.soloPartId());
        assertEquals("the studio's table does not know the new instrument yet", List.of(47),
                List.copyOf(offer.newPrograms().values()));
        for (Part part : before.parts()) {
            assertEquals("the bars there were stay as they are: " + part.id(),
                    before.notesOf(part.id()).stream().map(Composition.PlacedNote::note).toList(),
                    offer.proposed().notesOf(part.id()).stream().filter(note -> note.tick() < 8 * BAR)
                            .map(Composition.PlacedNote::note).toList());
        }
    }

    @Test
    public void listeningStartsTwoBarsBeforeTheNewOnes() throws Exception {
        ExtensionOffer offer = offered(ContinuationRequest.of(8, ContinuationPreset.STANDALONE, ContinuationEnding.OPEN));
        Composition proposed = offer.proposed();
        assertEquals("bar 7 of 8 old bars", proposed.timeMap().seconds(6 * BAR), offer.listenFromSecond(), 1e-9);
        ExtensionOffer revised = offered(ContinuationRequest.of(8, ContinuationPreset.STANDALONE,
                ContinuationEnding.OPEN).revisingTail(2));
        assertEquals("with a revised transition two bars before the first revised one",
                revised.proposed().timeMap().seconds(4 * BAR), revised.listenFromSecond(), 1e-9);
        assertTrue(revised.notes().toString(), revised.notes().get(revised.notes().size() - 1)
                .startsWith("Die letzten 2 Takte des bisherigen Stücks wurden für den Übergang überarbeitet"));
    }

    @Test
    public void itIsHeardInAFolderOfItsOwnWrittenAgainAndTaken() throws Exception {
        Composition before = session.composition();
        ExtensionOffer offer = offered(ContinuationRequest.of(8, ContinuationPreset.STANDALONE, ContinuationEnding.CLOSED));
        Path preview = offer.preview();
        assertTrue(Files.isDirectory(preview));
        assertFalse("the preview is not the piece", preview.equals(folder));
        Composition first = offer.proposed();
        offer.again(message -> { });
        assertTrue(offer.what(), offer.what().contains("2. Versuch"));
        assertFalse("the second attempt is another one", first.equals(offer.proposed()));
        assertEquals(before, session.composition());
        offer.preview();
        assertFalse(offer.accept().isEmpty());
        assertEquals(16, session.composition().timeMap().barsCovering(end(session.composition())));
        assertTrue(session.canUndo());
        session.undo();
        assertEquals("taking it back gives the piece of before", before, session.composition());
    }

    @Test
    public void thrownAwayItLeavesNothing() throws Exception {
        Composition before = session.composition();
        ExtensionOffer offer = offered(ContinuationRequest.of(8, ContinuationPreset.STANDALONE, ContinuationEnding.CLOSED));
        offer.discard();
        offer.discard(); // a second time does no harm
        assertEquals(before, session.composition());
        assertFalse(session.pending().isPresent());
    }

    private static long end(Composition piece) {
        long end = 0;
        for (Part part : piece.parts()) {
            for (Composition.PlacedNote note : piece.notesOf(part.id())) {
                end = Math.max(end, note.tick() + note.note().length());
            }
        }
        return end;
    }
}
