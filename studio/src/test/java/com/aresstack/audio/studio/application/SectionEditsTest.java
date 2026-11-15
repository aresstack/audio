package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.Section;
import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.composition.edit.CompositionEditor;
import com.aresstack.audio.composition.edit.EditResult;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.ai.RegionComposer;
import com.aresstack.audio.studio.ai.RegionRequest;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.project.CompositionJson;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The section bar edits the form (Angelo, 2026-11-15): a section such as an unwanted ending is
 * deleted, the bars of a section or any marked bars are deleted with a gap left or closed, a section
 * is dragged into a gap or behind the end, empty bars are put in, and the AI fills a gap with a
 * transition, taking its new section in the same step.
 *
 * <p>The piece: twelve bars, "Strophe" 1-4, "Refrain" 5-8, "Ausklang" 9-12, one clip per part.
 */
public final class SectionEditsTest {
    private static final long BAR = 1920;
    private static final Progress QUIET = message -> { };

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static Supplier<String> counting() {
        Iterator<Integer> numbers = Stream.iterate(1, n -> n + 1).iterator();
        return () -> "made" + numbers.next();
    }

    private static Composition applied(Composition piece, SectionEdits.SectionEdit edit) {
        assertNull(edit.refusal(), edit.refusal());
        EditResult result = CompositionEditor.apply(piece, edit.patch(), edit.scope());
        assertTrue(result.violations().toString(), result.applied());
        assertTrue(CompositionValidator.validate(result.result()).toString(),
                CompositionValidator.validate(result.result()).isEmpty());
        return result.result();
    }

    private static List<String> names(Composition piece) {
        List<String> names = new ArrayList<>();
        for (Section section : piece.sections()) {
            names.add(section.name() + " " + (section.startTick() / BAR + 1) + "-" + section.endTick() / BAR);
        }
        return names;
    }

    private static long notesBetween(Composition piece, long from, long to) {
        long count = 0;
        for (var part : piece.parts()) {
            for (PlacedNote note : piece.notesOf(part.id())) {
                if (note.tick() >= from && note.tick() < to) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int noteCount(Composition piece) {
        int count = 0;
        for (var part : piece.parts()) {
            count += piece.notesOf(part.id()).size();
        }
        return count;
    }

    @Test
    public void theUnwantedEndingIsDeletedAndThePieceEndsBeforeIt() {
        Composition piece = RegionReworkTest.flutePiece();
        Composition after = applied(piece, SectionEdits.deleteSection(piece, "outro", false, counting()));
        assertEquals(List.of("Strophe 1-4", "Refrain 5-8"), names(after));
        assertEquals("no gap is left behind the last section", 8 * BAR, after.endTick());
        assertEquals(0, notesBetween(after, 8 * BAR, Long.MAX_VALUE));
        assertEquals("the bars before stay as they were", notesBetween(piece, 0, 8 * BAR), notesBetween(after, 0, 8 * BAR));
    }

    @Test
    public void aSectionInTheMiddleLeavesAGapOrCloses() {
        Composition piece = RegionReworkTest.flutePiece();
        Composition gap = applied(piece, SectionEdits.deleteSection(piece, "chorus", false, counting()));
        assertEquals(List.of("Strophe 1-4", "Lücke 5-8", "Ausklang 9-12"), names(gap));
        assertTrue(SectionEdits.isGap(gap.sections().get(1)));
        assertTrue(SectionEdits.isSilent(gap, 4, 8));
        assertEquals(0, notesBetween(gap, 4 * BAR, 8 * BAR));
        assertEquals(notesBetween(piece, 8 * BAR, 12 * BAR), notesBetween(gap, 8 * BAR, 12 * BAR));
        // a note held from bar 4 into the gap ends where the gap begins
        for (PlacedNote note : gap.notesOf("flute")) {
            assertTrue(note.note().id(), note.tick() >= 8 * BAR || note.endTick() <= 4 * BAR);
        }

        Composition closed = applied(piece, SectionEdits.deleteSection(piece, "chorus", true, counting()));
        assertEquals(List.of("Strophe 1-4", "Ausklang 5-8"), names(closed));
        assertEquals(8 * BAR, closed.endTick());
        assertEquals("the outro moved forward with its notes", notesBetween(piece, 8 * BAR, 12 * BAR),
                notesBetween(closed, 4 * BAR, 8 * BAR));
        assertEquals(noteCount(piece) - notesBetween(piece, 4 * BAR, 8 * BAR), noteCount(closed));

        // the gap itself can be closed later
        Composition later = applied(gap, SectionEdits.deleteSection(gap, gap.sections().get(1).id(), true, counting()));
        assertEquals(List.of("Strophe 1-4", "Ausklang 5-8"), names(later));
    }

    @Test
    public void barsWithinASectionCutItInTwoAndKeepTheChordsOfTheirBars() {
        Composition piece = RegionReworkTest.flutePiece();
        Composition after = applied(piece, SectionEdits.deleteBars(piece, 5, 7, false, counting()));
        assertEquals(List.of("Strophe 1-4", "Refrain 5-5", "Lücke 6-7", "Refrain 8-8", "Ausklang 9-12"), names(after));
        assertEquals(List.of("C"), after.sections().get(1).harmony());
        assertEquals(List.of("G"), after.sections().get(3).harmony());
        assertEquals("chorus", after.sections().get(1).id());
    }

    @Test
    public void aSectionIsDraggedIntoAGapOrBehindTheEnd() {
        Composition piece = RegionReworkTest.flutePiece();
        assertNotNull("it cannot land on another section",
                SectionEdits.moveSection(piece, "outro", 2, counting()).refusal());

        // the outro goes two bars behind the end: a gap of two bars before it
        Composition later = applied(piece, SectionEdits.moveSection(piece, "outro", 14, counting()));
        assertEquals(List.of("Strophe 1-4", "Refrain 5-8", "Lücke 9-14", "Ausklang 15-18"), names(later));
        assertEquals(notesBetween(piece, 8 * BAR, 12 * BAR), notesBetween(later, 14 * BAR, 18 * BAR));
        assertEquals(noteCount(piece), noteCount(later));

        // and back into the gap, two bars after the chorus: the gap is now before and after it
        Composition back = applied(later, SectionEdits.moveSection(later, "outro", 10, counting()));
        assertEquals(List.of("Strophe 1-4", "Refrain 5-8", "Lücke 9-10", "Ausklang 11-14"), names(back));
        assertEquals("the trailing gap is dropped: the piece ends with its last section", 14 * BAR, back.endTick());
        assertEquals(noteCount(piece), noteCount(back));
    }

    @Test
    public void emptyBarsAreAGapAndASectionAroundThemIsCut() {
        Composition piece = RegionReworkTest.flutePiece();
        Composition border = applied(piece, SectionEdits.insertEmpty(piece, 8, 2, counting()));
        assertEquals(List.of("Strophe 1-4", "Refrain 5-8", "Lücke 9-10", "Ausklang 11-14"), names(border));
        assertEquals(noteCount(piece), noteCount(border));
        assertEquals(0, notesBetween(border, 8 * BAR, 10 * BAR));

        Composition inside = applied(piece, SectionEdits.insertEmpty(piece, 6, 1, counting()));
        assertEquals(List.of("Strophe 1-4", "Refrain 5-6", "Lücke 7-7", "Refrain 8-9", "Ausklang 10-13"), names(inside));
    }

    @Test
    public void theAiFillsAGapWithATransitionAndItsSectionInOneStep() throws Exception {
        Composition piece = RegionReworkTest.flutePiece();
        Composition gap = applied(piece, SectionEdits.deleteSection(piece, "chorus", false, counting()));
        Path folder = temporary.newFolder("piece").toPath();
        CompositionJson.save(folder, gap);
        ProjectSession session = ProjectSession.open(folder).orElseThrow();

        List<String> parts = SectionEdits.playingParts(gap);
        assertEquals(List.of("flute", "strings", "bass"), parts);
        String instruction = SectionEdits.transitionInstruction(gap, 4, 8);
        assertTrue(instruction, instruction.contains("These bars are empty"));
        assertTrue(instruction, instruction.contains("\"Strophe\"") && instruction.contains("\"Ausklang\""));
        List<Section> form = SectionEdits.filledForm(gap, 4, 8, counting());
        assertEquals("Übergang", form.get(1).name());
        assertEquals("between the intensities of its neighbours", 0.4, form.get(1).intensity(), 1e-9);
        assertNull("bars without a gap keep their form", SectionEdits.filledForm(gap, 0, 4, counting()));

        FakeChatModel model = new FakeChatModel((system, prompt) -> {
            String part = FakeChatModel.quoted(prompt, "part");
            String section = FakeChatModel.quoted(prompt, "section");
            assertTrue(prompt, prompt.contains("These bars are empty"));
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":["
                    + "{\"bar\":1,\"beat\":0,\"duration\":4,\"pitch\":60,\"velocity\":80},"
                    + "{\"bar\":4,\"beat\":0,\"duration\":4,\"pitch\":62,\"velocity\":80}]}";
        });
        RegionRework rework = RegionRework.start(session, RegionComposer.with(model),
                RegionRequest.replace(Selection.bars(gap, parts, 5, 8), instruction), form, Ids.seeded(3), QUIET);
        ChangeSet changes = rework.accept();
        assertFalse(changes.isEmpty());
        Composition filled = session.composition();
        assertEquals(List.of("Strophe 1-4", "Übergang 5-8", "Ausklang 9-12"), names(filled));
        assertTrue("the gap holds music now", notesBetween(filled, 4 * BAR, 8 * BAR) > 0);
        assertEquals(notesBetween(gap, 0, 4 * BAR), notesBetween(filled, 0, 4 * BAR));
        assertEquals(notesBetween(gap, 8 * BAR, 12 * BAR), notesBetween(filled, 8 * BAR, 12 * BAR));

        // one step: "Rückgängig" brings the gap back, notes and section together
        session.undo();
        assertEquals(List.of("Strophe 1-4", "Lücke 5-8", "Ausklang 9-12"), names(session.composition()));
        assertEquals(0, notesBetween(session.composition(), 4 * BAR, 8 * BAR));
    }
}
