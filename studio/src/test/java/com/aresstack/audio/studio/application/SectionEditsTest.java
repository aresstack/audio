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
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
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

    private static Composition withTimeAndDynamics(Composition piece, TimeMap time, Dynamics dynamics) {
        return new Composition(piece.meta(), time, piece.sections(), piece.parts(), piece.tracks(), piece.assets(),
                dynamics);
    }

    @Test
    public void tempoKeyAndDynamicsTravelWithADraggedSection() {
        // the outro is slower (80) and soft (p); the piece around it is 96 and mf
        TimeMap time = new TimeMap(List.of(new TimeMap.Tempo(0, 96), new TimeMap.Tempo(8 * BAR, 80)),
                TimeMap.of(96).meters(), TimeMap.of(96).keys());
        Dynamics dynamics = new Dynamics(List.of(new Dynamics.Mark(0, Dynamics.Level.MF, null),
                new Dynamics.Mark(8 * BAR, Dynamics.Level.P, null)), List.of(), Dynamics.Scale.STANDARD);
        Composition piece = withTimeAndDynamics(RegionReworkTest.flutePiece(), time, dynamics);
        Composition gap = applied(piece, SectionEdits.insertEmpty(piece, 4, 2, counting()));
        // the chorus now lies in bars 7-10, the outro in 11-14 at 80; drag the outro into the gap (bars 5-6 are too few)
        assertNotNull(SectionEdits.moveSection(gap, "outro", 4, counting()).refusal());

        Composition later = applied(piece, SectionEdits.moveSection(piece, "outro", 14, counting()));
        assertEquals("the outro keeps its tempo", 80, later.timeMap().tempoAt(14 * BAR), 1e-9);
        assertEquals("the gap it left goes on at the tempo before", 96, later.timeMap().tempoAt(9 * BAR), 1e-9);
        assertEquals(Dynamics.Level.P, levelAt(later.dynamics(), 15 * BAR));
        assertEquals(Dynamics.Level.MF, levelAt(later.dynamics(), 9 * BAR));

        Composition closed = applied(piece, SectionEdits.deleteSection(piece, "chorus", true, counting()));
        assertEquals("the outro moved forward with its tempo", 80, closed.timeMap().tempoAt(4 * BAR), 1e-9);
        assertEquals(96, closed.timeMap().tempoAt(3 * BAR), 1e-9);
        assertEquals(Dynamics.Level.P, levelAt(closed.dynamics(), 4 * BAR));
    }

    private static Dynamics.Level levelAt(Dynamics dynamics, long tick) {
        Dynamics.Level level = null;
        for (Dynamics.Mark mark : dynamics.marks()) {
            if (mark.tick() <= tick) {
                level = mark.level();
            }
        }
        return level;
    }

    @Test
    public void theAutomationCurvesOfTheMixFollowTheMusic() {
        MixScene mix = new MixScene(new Room("hall", 2, 0.4), 0, LevelMode.LEGACY_PEAK,
                List.of(new MixChannel("c1", MixChannel.Owner.part("flute"), 0, 0.2, 0, 1, 0.3, false, false)),
                List.of(new AutomationLane("l1", "c1", AutomationLane.X, List.of(new AutomationLane.Point(0, 0),
                        new AutomationLane.Point(4 * BAR, 0), new AutomationLane.Point(8 * BAR, 1),
                        new AutomationLane.Point(12 * BAR, -1)))));
        // the chorus (bars 5-8) is taken out: the outro's sweep from 1 to -1 now runs in bars 5-8
        AutomationLane closed = SectionEdits.closedMix(mix, 4 * BAR, 8 * BAR).automation().get(0);
        assertEquals(0, closed.valueAt(2 * BAR), 1e-9);
        assertEquals("the bars before end as they were", 0, closed.valueAt(4 * BAR), 1e-9);
        assertEquals("then the curve goes on as it went after the chorus", 1, closed.valueAt(4 * BAR + 1), 1e-3);
        assertEquals(-1, closed.valueAt(8 * BAR), 1e-9);

        // the outro (bars 9-12) goes to bars 15-18: it sweeps there as it did, and bars 13-14 hold what was there
        AutomationLane moved = SectionEdits.movedMix(mix, 8 * BAR, 12 * BAR, 14 * BAR).automation().get(0);
        assertEquals(1, moved.valueAt(14 * BAR), 1e-9);
        assertEquals(0, moved.valueAt(16 * BAR), 1e-3);
        assertEquals("the chorus still rises as before", 0.5, moved.valueAt(6 * BAR), 1e-9);
    }

    @Test
    public void undoTakesTheMixBackWithTheMusic() throws Exception {
        Path folder = temporary.newFolder("mixed").toPath();
        CompositionJson.save(folder, RegionReworkTest.flutePiece());
        ProjectSession session = ProjectSession.open(folder).orElseThrow();
        MixScene before = session.mix();
        SectionEdits.SectionEdit edit = SectionEdits.deleteSection(session.composition(), "chorus", true, counting());
        assertNotNull("closing a gap moves the curves", edit.mix());
        session.apply(edit.patch(), edit.scope(), edit.mix());
        assertEquals(List.of("Strophe 1-4", "Ausklang 5-8"), names(session.composition()));
        session.undo();
        assertEquals(List.of("Strophe 1-4", "Refrain 5-8", "Ausklang 9-12"), names(session.composition()));
        assertEquals(before, session.mix());
    }
}
