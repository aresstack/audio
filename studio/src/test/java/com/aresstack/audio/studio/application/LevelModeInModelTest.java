package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.edit.EditOp;
import com.aresstack.audio.composition.edit.EditScope;
import com.aresstack.audio.composition.edit.Patch;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.nio.charset.StandardCharsets;
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
 * The deliberate switch of a piece to the fixed level is in {@code mix.json} too (acceptance
 * finding on the level controls), and stays there through saving, reopening, a change of the
 * stage, undo and a restored revision. A piece that was never switched keeps the old way.
 */
public final class LevelModeInModelTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final SpatialSceneStore stage = new SpatialSceneStore();
    private Path piece;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        piece = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, piece.resolve(file.getFileName()));
            }
        }
    }

    private LevelMode saved() throws Exception {
        String json = new String(Files.readAllBytes(piece.resolve(MixJson.FILE_NAME)), StandardCharsets.UTF_8);
        assertEquals("mix.json names exactly the mode the model has", MixJson.load(piece).levelMode() == LevelMode.FIXED,
                json.contains("\"levelMode\": \"fixed\""));
        return MixJson.load(piece).levelMode();
    }

    /** What the window does when the user confirms the switch: the stage file, then the model. */
    private void switchTo(PieceModel model, boolean fixed) throws Exception {
        stage.save(piece, stage.load(piece).withFixedLevel(fixed));
        model.levelFixed(fixed);
    }

    @Test
    public void aPieceThatWasNeverSwitchedKeepsTheOldWayThroughEverything() throws Exception {
        PieceModel model = new PieceModel(new ArrayList<String>()::add);
        model.open(piece);
        assertEquals(LevelMode.LEGACY_PEAK, saved());
        ProjectSession session = model.session().orElseThrow();
        String first = session.revisions().get(0).id();

        SpatialScene scene = stage.load(piece);
        SoundSourcePlacement bass = scene.placement("bass");
        stage.save(piece, scene.with(bass.withLevel(bass.levelDb() - 3)));
        model.synchronize();
        assertEquals(LevelMode.LEGACY_PEAK, saved());
        change(session);
        session.undo();
        session.save();
        session.restoreRevision(first);
        assertEquals(LevelMode.LEGACY_PEAK, saved());
        assertEquals(LevelMode.LEGACY_PEAK, ProjectSession.open(piece).orElseThrow().mix().levelMode());
        assertFalse(stage.load(piece).fixedLevel());
    }

    @Test
    public void theSwitchIsInMixJsonAndStaysThroughSavingReopeningStageChangeUndoAndRevision() throws Exception {
        List<String> said = new ArrayList<String>();
        PieceModel model = new PieceModel(said::add);
        model.open(piece);
        ProjectSession session = model.session().orElseThrow();
        String original = session.revisions().get(0).id();
        byte[] notes = Files.readAllBytes(piece.resolve("composition.json"));

        switchTo(model, true);
        assertEquals("the switch itself writes the mode", LevelMode.FIXED, saved());
        assertEquals(LevelMode.FIXED, session.mix().levelMode());
        assertTrue("and nothing else of the piece", java.util.Arrays.equals(notes,
                Files.readAllBytes(piece.resolve("composition.json"))));
        assertFalse("the switch is no step to undo", session.canUndo());

        session.save();
        assertEquals("saving", LevelMode.FIXED, saved());

        PieceModel again = new PieceModel(said::add);
        again.open(piece);
        session = again.session().orElseThrow();
        assertEquals("reopening", LevelMode.FIXED, session.mix().levelMode());
        assertEquals(LevelMode.FIXED, saved());

        // A change on the stage, taken over the way the studio does it after composing or renaming.
        SpatialScene scene = stage.load(piece);
        SoundSourcePlacement bass = scene.placement("bass");
        stage.save(piece, scene.with(bass.withLevel(bass.levelDb() - 3)));
        assertEquals("the stage file alone does not touch the model's mode", LevelMode.FIXED, saved());
        again.synchronize();
        assertEquals("a stage change taken over", LevelMode.FIXED, saved());

        change(session);
        assertEquals("a change by hand", LevelMode.FIXED, saved());
        session.undo();
        assertEquals("undo", LevelMode.FIXED, saved());
        session.undo(); // back before the stage change, to a state from before it was taken over
        assertEquals("undo of the stage change", LevelMode.FIXED, saved());
        session.redo();
        assertEquals("redo", LevelMode.FIXED, saved());

        session.restoreRevision(original); // the original was made while the piece was mixed the old way
        assertEquals("a restored revision", LevelMode.FIXED, saved());
        assertEquals(LevelMode.FIXED, session.mix().levelMode());
        assertEquals(LevelMode.FIXED, ProjectSession.open(piece).orElseThrow().mix().levelMode());
        assertTrue("the stage file and the model agree", stage.load(piece).fixedLevel());

        switchTo(again, false);
        assertEquals("the switch can be taken back", LevelMode.LEGACY_PEAK, saved());
    }

    @Test
    public void aPieceSwitchedBeforeItHadAModelIsTakenIntoItSwitched() throws Exception {
        stage.save(piece, stage.load(piece).withFixedLevel(true));
        PieceModel model = new PieceModel(new ArrayList<String>()::add);
        model.open(piece);
        assertEquals(LevelMode.FIXED, saved());
    }

    private static void change(ProjectSession session) throws Exception {
        MidiClip bass = (MidiClip) session.composition().track("bass").clips().get(0);
        Note first = bass.notes().get(0);
        session.apply(Patch.of("Bass lauter", new EditOp.ChangeNote(bass.id(), new Note(first.id(), first.tick(),
                first.length(), first.pitch(), first.velocity() == 127 ? 100 : 127, first.voiceId()))),
                EditScope.part("bass"));
    }
}
