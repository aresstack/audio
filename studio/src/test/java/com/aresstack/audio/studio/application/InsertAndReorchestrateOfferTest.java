package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.studio.ai.ComposedContinuation;
import com.aresstack.audio.studio.ai.ContinuationComposer;
import com.aresstack.audio.studio.ai.ContinuationPreset;
import com.aresstack.audio.studio.ai.ContinuationRequest;
import com.aresstack.audio.studio.ai.IntensityCurve;
import com.aresstack.audio.studio.ai.OrchestrationComposer;
import com.aresstack.audio.studio.ai.OrchestrationPreset;
import com.aresstack.audio.studio.ai.ReorchestrationRequest;
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
 * Inserted bars and a passage orchestrated anew as the window offers them (work package C4, the
 * window's part). An insertion that has to shorten notes of the original lists every one with
 * its length before and after, and is not taken until the user confirmed them.
 */
public final class InsertAndReorchestrateOfferTest {
    private static final long BAR = 1920;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private ProjectSession session;

    @Before
    public void anOldPieceOfEightBars() throws Exception {
        Path folder = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        session = ProjectSession.open(folder).orElseThrow();
    }

    /** Makes one note before bar 5 sound to the end of its clip: longer than two inserted bars can hold. */
    private PlacedNote aNoteThatSoundsFarBeyondBarFive() throws Exception {
        long point = 4 * BAR;
        PlacedNote held = session.composition().parts().stream()
                .flatMap(part -> session.composition().notesOf(part.id()).stream())
                .filter(n -> n.tick() < point && n.clip().startTick() < point && n.clip().endTick() > point + 3 * BAR)
                .max(java.util.Comparator.comparingLong(PlacedNote::tick))
                .orElseThrow(() -> new AssertionError("the fixture needs a clip across bars 5-7"));
        Note longer = new Note(held.note().id(), held.note().tick(), held.clip().endTick() - held.tick(),
                held.note().pitch(), held.note().velocity(), held.note().voiceId());
        session.apply(new Patch("longer note", List.of(new EditOp.ChangeNote(held.clip().id(), longer))),
                EditScope.everything());
        return held;
    }

    private ExtensionOffer inserted(int bars) throws Exception {
        return ExtensionOffer.start(session, ContinuationComposer.with(PieceInsertionTest.model()),
                ContinuationRequest.insert(5, bars, ContinuationPreset.STANDALONE, ""), message -> { });
    }

    @Test
    public void everyShortenedNoteIsListedWithItsLengthBeforeAndAfter() throws Exception {
        PlacedNote held = aNoteThatSoundsFarBeyondBarFive();
        ExtensionOffer offer = inserted(2);
        ComposedContinuation written = offer.extension().continuation();
        assertEquals(1, written.shortened().size());
        ComposedContinuation.Hold hold = written.shortened().get(0);
        List<String> lines = offer.toConfirm();
        assertEquals("one line per shortened note", 1, lines.size());
        String line = lines.get(0);
        assertTrue(line, line.contains("ab Takt " + (held.tick() / BAR + 1) + ", Schlag "));
        assertTrue("the length in the original: " + line,
                line.contains("im Original " + quarters(hold.length()) + " Viertel lang"));
        assertTrue("the length that stays: " + line,
                line.endsWith("bleibt " + quarters(written.keptLength(hold)) + " Viertel"));
        assertTrue(written.keptLength(hold) < hold.length());
        assertTrue(offer.what(), offer.what().endsWith("; 1 Note des Originals wird gekürzt."));
        assertTrue(offer.what(), offer.what().startsWith("Die KI hat vor Takt 5 2 Takte eingefügt ("));
    }

    private static String quarters(long ticks) {
        double value = Math.round(ticks / 480.0 * 100) / 100.0;
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value).replace('.', ',');
    }

    @Test
    public void shorteningsThatWereNotConfirmedAreNotTaken() throws Exception {
        aNoteThatSoundsFarBeyondBarFive();
        Composition before = session.composition();
        ExtensionOffer offer = inserted(2);
        try {
            offer.accept();
            fail("an insertion that shortens notes is not taken without the user's word");
        } catch (IllegalStateException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("bestätigt"));
        }
        assertEquals("the piece is as it was", before, session.composition());
        assertTrue("the proposal is still open", session.pending().isPresent());
        // A new attempt has to be confirmed anew: what was said yes to was another proposal.
        offer.confirm();
        offer.again(message -> { });
        assertFalse(offer.toConfirm().isEmpty());
        try {
            offer.accept();
            fail("the second attempt was not confirmed");
        } catch (IllegalStateException refused) {
            assertEquals(before, session.composition());
        }
        offer.confirm();
        assertFalse(offer.accept().isEmpty());
        assertFalse(before.equals(session.composition()));
        session.undo();
        assertEquals(before, session.composition());
    }

    @Test
    public void anInsertionThatKeepsEveryNoteAsksForNothing() throws Exception {
        Composition before = session.composition();
        ExtensionOffer offer = inserted(4);
        assertEquals(List.of(), offer.toConfirm());
        assertTrue(offer.what(), offer.what().endsWith(")."));
        assertTrue(offer.notes().toString(), offer.notes().get(offer.notes().size() - 1)
                .startsWith("Die Takte davor und danach bleiben Note für Note"));
        assertEquals("listening starts two bars before the insertion point",
                offer.proposed().timeMap().seconds(2 * BAR), offer.listenFromSecond(), 1e-9);
        assertFalse(offer.accept().isEmpty());
        assertEquals(12, session.composition().timeMap().barsCovering(session.composition().endTick()));
        assertFalse(before.equals(session.composition()));
    }

    @Test
    public void aPassageOrchestratedAnewTellsWhoPlaysWhat() throws Exception {
        Composition before = session.composition();
        ReorchestrationOffer offer = ReorchestrationOffer.start(session,
                OrchestrationComposer.with(ReorchestrationTest.model(ReorchestrationTest::redistribute)),
                ReorchestrationRequest.of(3, 6, OrchestrationPreset.REDISTRIBUTE).withNewParts(1), message -> { });
        assertEquals(before, session.composition());
        String what = offer.what();
        assertTrue(what, what.startsWith("Die KI hat Takt 3 bis 6 umorchestriert (Material neu verteilen, "));
        List<String> notes = offer.notes();
        assertEquals("Idee: the melody sinks into the middle voice", notes.get(0));
        assertTrue(notes.toString(), notes.stream().anyMatch(line -> line.startsWith("Takt 3")
                && line.contains("harmony: the melody an octave lower (von melody)")));
        assertTrue(notes.toString(), notes.contains("Neues Instrument: Streicher"));
        assertEquals("Außerhalb der Passage und in den nicht gewählten Stimmen bleibt alles unverändert.",
                notes.get(notes.size() - 1));
        assertEquals(List.of(), offer.toConfirm());
        assertEquals(List.of(48), List.copyOf(offer.newPrograms().values()));
        assertEquals("from the first bar: two bars before bar 3", 0.0, offer.listenFromSecond(), 1e-9);
        offer.preview();
        assertFalse(offer.accept().isEmpty());
        assertFalse(before.equals(session.composition()));
    }

    @Test
    public void theIntensityOfASectionIsSetWithoutTouchingANote() throws Exception {
        // The form comes with a continuation: the old piece has no sections of its own.
        ExtensionOffer.start(session, ContinuationComposer.with(PieceInsertionTest.model()),
                ContinuationRequest.insert(5, 4, ContinuationPreset.STANDALONE, ""), message -> { }).accept();
        Composition before = session.composition();
        assertFalse(before.sections().isEmpty());
        String id = before.sections().get(0).id();
        assertFalse(IntensityEdit.apply(session, Map.of(id, 0.9)).isEmpty());
        assertEquals(0.9, session.composition().sections().get(0).intensity(), 1e-9);
        for (com.aresstack.audio.composition.Part part : before.parts()) {
            assertEquals(before.notesOf(part.id()), session.composition().notesOf(part.id()));
        }
        IntensityEdit.apply(session, 1, 12, IntensityCurve.ramp(0.2, 0.8));
        session.undo();
        assertEquals(0.9, session.composition().sections().get(0).intensity(), 1e-9);
    }
}
