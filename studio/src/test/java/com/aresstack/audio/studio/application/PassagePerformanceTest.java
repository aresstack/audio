package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Composition.PlacedNote;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.Dynamics;
import com.aresstack.audio.composition.Ids;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.Selection;
import com.aresstack.audio.studio.ai.FakeChatModel;
import com.aresstack.audio.studio.ai.RegionComposer;
import com.aresstack.audio.studio.ai.RegionRequest;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.project.CompositionJson;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * When the AI writes a passage anew (or fills bars from the section bar, which is the same
 * rework), its first answer may also say how the passage is played: tempo changes, dynamic marks
 * and hairpins. They go into the passage only, for the rewritten parts only; after it the piece
 * goes on in its own tempo and at its own level, and the other parts sound as before.
 */
public final class PassagePerformanceTest {
    private static final long BAR = 1920;
    private static final Progress QUIET = message -> { };

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private ProjectSession session;

    @Before
    public void aPieceWithAFlute() throws Exception {
        Path folder = temporary.newFolder("piece").toPath();
        CompositionJson.save(folder, RegionReworkTest.flutePiece());
        session = ProjectSession.open(folder).orElseThrow();
    }

    /** Two notes per bar of the passage; the first call also plans the performance. */
    private static FakeChatModel model(String performance) {
        return new FakeChatModel((instruction, prompt) -> {
            String part = FakeChatModel.quoted(prompt, "part");
            String section = FakeChatModel.quoted(prompt, "section");
            StringBuilder notes = new StringBuilder();
            for (int bar = 1; bar <= 4; bar++) {
                notes.append(bar == 1 ? "" : ",").append("{\"bar\":").append(bar)
                        .append(",\"beat\":0,\"duration\":2,\"pitch\":81,\"velocity\":80},{\"bar\":").append(bar)
                        .append(",\"beat\":2,\"duration\":2,\"pitch\":79,\"velocity\":80}");
            }
            boolean plans = prompt.contains("Plan the performance of the passage");
            return "{\"part\":\"" + part + "\",\"section\":\"" + section + "\",\"notes\":[" + notes + "]"
                    + (plans ? "," + performance : "") + "}";
        });
    }

    @Test
    public void theRewrittenFluteIsPlayedAsPlannedAndThePieceGoesOnAsBefore() throws Exception {
        Composition before = session.composition();
        double bpm = before.timeMap().tempos().get(0).bpm();
        FakeChatModel model = model("\"tempoChanges\":[{\"bar\":7,\"bpm\":" + (bpm - 16) + ",\"glide\":true}],"
                + "\"dynamics\":[{\"bar\":5,\"level\":\"pp\"},{\"bar\":11,\"level\":\"ff\"}],"
                + "\"hairpins\":[{\"fromBar\":7,\"toBar\":9,\"to\":\"f\"}]");
        Selection flute = Selection.bars(before, List.of("flute"), 5, 8);
        RegionRework rework = RegionRework.start(session, RegionComposer.with(model),
                RegionRequest.replace(flute, "leiser beginnen"), Ids.seeded(3), QUIET);

        assertTrue(model.prompts().get(0).contains("Plan the performance of the passage of the parts flute, bars 5-8"));
        Composition after = rework.proposal().result();
        assertEquals(List.of(), CompositionValidator.validate(after));
        assertTrue(rework.proposal().scope().allows(EditOp.Kind.SET_DYNAMICS));
        assertEquals(List.of(), RegionEdit.outsideChanges(before.withTimeMap(after.timeMap())
                .withDynamics(after.dynamics()), after, flute));

        // the tempo slows down from bar 7 and is the piece's own again from bar 9
        TimeMap time = after.timeMap();
        assertEquals(List.of(new TimeMap.Tempo(0, bpm), new TimeMap.Tempo(6 * BAR, bpm - 16, true),
                new TimeMap.Tempo(8 * BAR, bpm)), time.tempos());
        assertEquals(before.timeMap().seconds(6 * BAR), time.seconds(6 * BAR), 1e-9);

        // the marks hold for the flute alone; the mark in bar 11 lies outside the passage
        Dynamics dynamics = after.dynamics();
        assertTrue(dynamics.marks().contains(new Dynamics.Mark(4 * BAR, Dynamics.Level.PP, "flute")));
        assertTrue(dynamics.marks().contains(new Dynamics.Mark(8 * BAR, Dynamics.Level.MF, "flute")));
        assertFalse(dynamics.marks().stream().anyMatch(m -> m.partId() == null || m.tick() >= 10 * BAR));
        assertEquals(List.of(new Dynamics.Hairpin(6 * BAR, 8 * BAR, Dynamics.Level.F, "flute")), dynamics.hairpins());

        for (String other : List.of("strings", "bass")) {
            for (PlacedNote note : after.notesOf(other)) {
                assertEquals(note.note().velocity(), after.playedVelocity(note));
            }
        }
        for (PlacedNote note : after.notesOf("flute")) {
            int played = after.playedVelocity(note);
            if (note.tick() < 4 * BAR || note.tick() >= 8 * BAR) {
                assertEquals(note.note().velocity(), played);
            } else if (note.tick() < 6 * BAR) {
                assertTrue("pianissimo in bars 5-6", played < note.note().velocity() / 2 + 2);
            }
        }
    }

    @Test
    public void aPassageWithoutPlannedPerformanceChangesOnlyItsNotes() throws Exception {
        Composition before = session.composition();
        Selection flute = Selection.bars(before, List.of("flute"), 5, 8);
        RegionRework rework = RegionRework.start(session, RegionComposer.with(model("\"x\":0")),
                RegionRequest.replace(flute, "heller"), Ids.seeded(4), QUIET);
        Composition after = rework.proposal().result();
        assertEquals(before.timeMap(), after.timeMap());
        assertEquals(before.dynamics(), after.dynamics());
        assertFalse(rework.proposal().scope().allows(EditOp.Kind.SET_DYNAMICS));
    }
}
