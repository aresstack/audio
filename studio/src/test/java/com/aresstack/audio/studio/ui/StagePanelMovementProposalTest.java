package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
import com.aresstack.audio.studio.application.MovementDirector;
import com.aresstack.audio.studio.application.MusicTrack;
import com.aresstack.audio.studio.application.StageMovements;
import com.aresstack.audio.studio.stage.SpatialScene;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.Assert;
import org.junit.Test;

/**
 * The switch "Mit Bewegungen" under "KI mischt ab" (off by default) and the movements the AI
 * proposes with a mix: drawn dashed in the field under the sliders, taken with the mix or dropped
 * with it. Checked without a window.
 */
public final class StagePanelMovementProposalTest {
    private static final class Told implements StagePanel.Host {
        final List<Boolean> wishes = new ArrayList<>();
        final List<MovementDirector.Proposal> taken = new ArrayList<>();
        final List<SpatialScene> befores = new ArrayList<>();
        final List<SpatialScene> stages = new ArrayList<>();
        final List<SpatialScene> scenes = new ArrayList<>();
        boolean keeps = true;

        @Override
        public void sceneChanged(SpatialScene scene, boolean settled) {
            scenes.add(scene);
        }

        @Override
        public void movementChanged(StageMovements movements, String trackId, String parameter, boolean settled) {
        }

        @Override
        public void askDirector() {
        }

        @Override
        public void movementWishChanged(boolean wanted) {
            wishes.add(wanted);
        }

        @Override
        public boolean takeWithMovements(SpatialScene before, SpatialScene stage, MovementDirector.Proposal proposal) {
            taken.add(proposal);
            befores.add(before);
            stages.add(stage);
            return keeps;
        }
    }

    @Test
    public void theSwitchIsOffAndAProposalWithMovementsIsShownTakenOrDropped() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (javax.swing.UIManager.getFont("defaultFont") == null) {
                javax.swing.UIManager.put("defaultFont", new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12));
            }
            Told told = new Told();
            StagePanel panel = new StagePanel(told);
            List<MusicTrack> tracks = Arrays.asList(new MusicTrack("melody", MusicTrack.MELODY, "melody", 73, "builtin"),
                    new MusicTrack("bass", MusicTrack.BASS, "bass", 32, "builtin"));
            SpatialScene scene = SpatialScene.standard(tracks);
            panel.setScene(scene, tracks);
            panel.setBars(8);
            MixScene mix = new MixScene(new Room("hall", 2, 0.4), 0, LevelMode.LEGACY_PEAK,
                    List.of(new MixChannel("c1", MixChannel.Owner.part("melody"), 0, 0.2, 0, 1, 0.3, false, false),
                            new MixChannel("c2", MixChannel.Owner.part("bass"), 0, 0.5, 0, 1, 0.3, false, false)),
                    List.of());
            panel.setMovements(StageMovements.of(mix, TimeMap.of(120)));

            // Off unless the user sets it; the setting read at start does not count as the user's switch.
            Assert.assertFalse(panel.movementsWanted());
            panel.setMovementsWanted(true);
            Assert.assertTrue(panel.movementsWanted());
            Assert.assertTrue(told.wishes.isEmpty());
            panel.movementSwitch().doClick();
            Assert.assertFalse(panel.movementsWanted());
            Assert.assertEquals(List.of(false), told.wishes);

            // A proposal with movements: said in the status, marked on the key, dashed in the field.
            MovementDirector.Proposal moves = new MovementDirector.Proposal(Map.of("melody",
                    Map.of(AutomationLane.X, List.of(new StageMovements.Point(0, -0.5), new StageMovements.Point(6, 0.5)))));
            SpatialScene proposed = scene.with(scene.placement("melody").at(-0.5, 0.3));
            panel.setProposal(proposed, moves);
            Assert.assertSame(moves, panel.proposedMovements());
            Assert.assertTrue(text(panel).contains("Bewegungen für 1 Spur"));
            panel.select("melody");
            panel.showCourse(AutomationLane.X);
            Assert.assertTrue(text(panel).contains("Seite ◌"));
            Assert.assertTrue(text(panel).contains("Vorschlag der KI gestrichelt"));
            Assert.assertTrue("nothing moves before it is taken", panel.movements().of("melody", AutomationLane.X).isEmpty());
            JComponent field = panel.courseField();
            field.setSize(332, 78);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(332, 78,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            field.paint(graphics);
            graphics.dispose();

            // Dropped: nothing is told, and the proposal is gone with its movements.
            button(panel, "Verwerfen").doClick();
            Assert.assertTrue(told.taken.isEmpty());
            Assert.assertTrue(panel.proposedMovements().empty());
            Assert.assertFalse(text(panel).contains("◌"));

            // Taken, but it could not be kept: the proposal stays, the page changed nothing itself.
            panel.setProposal(proposed, moves);
            told.keeps = false;
            button(panel, "Übernehmen").doClick();
            Assert.assertEquals(List.of(moves), told.taken);
            Assert.assertSame(moves, panel.proposedMovements());
            Assert.assertTrue("the page leaves stage and movements to the host as one step", told.scenes.isEmpty());

            // Taken and kept: the host had the stage before and with the proposal, as one step.
            told.keeps = true;
            button(panel, "Übernehmen").doClick();
            Assert.assertEquals(2, told.taken.size());
            Assert.assertEquals(-0.5, told.stages.get(1).placement("melody").x(), 1e-9);
            Assert.assertEquals(scene.placement("melody").x(), told.befores.get(1).placement("melody").x(), 1e-9);
            Assert.assertTrue(panel.proposedMovements().empty());
            Assert.assertTrue(told.scenes.isEmpty());

            // A proposal without movements (the switch off) is taken on the stage as before.
            panel.setProposal(proposed);
            Assert.assertFalse(text(panel).contains("Bewegungen für"));
            button(panel, "Übernehmen").doClick();
            Assert.assertEquals(2, told.taken.size());
            Assert.assertEquals(1, told.scenes.size());
        });
    }

    private static JButton button(java.awt.Container root, String text) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof java.awt.Container container) {
                JButton found = button(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** All texts of labels and buttons on the page. */
    private static String text(java.awt.Container root) {
        StringBuilder all = new StringBuilder();
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel label) {
                all.append(label.getText()).append('\n');
            } else if (child instanceof AbstractButton button) {
                all.append(button.getText()).append('\n');
            }
            if (child instanceof java.awt.Container container) {
                all.append(text(container));
            }
        }
        return all.toString();
    }
}
