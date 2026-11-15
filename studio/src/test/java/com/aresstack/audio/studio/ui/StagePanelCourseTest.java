package com.aresstack.audio.studio.ui;

import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.LevelMode;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
import com.aresstack.audio.studio.application.MusicTrack;
import com.aresstack.audio.studio.application.StageMovements;
import com.aresstack.audio.studio.stage.SpatialScene;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.Assert;
import org.junit.Test;

/**
 * The field under the sliders of the stage page draws and edits, beside the level, how a track
 * moves over the bars: side, depth and share of the room (work package D3). Checked without a
 * window: the mouse is handed to the field as events.
 */
public final class StagePanelCourseTest {
    /** What the page told the studio. */
    private static final class Told implements StagePanel.Host {
        final List<String> movements = new ArrayList<String>();
        final List<SpatialScene> scenes = new ArrayList<SpatialScene>();
        StageMovements last;

        @Override
        public void sceneChanged(SpatialScene scene, boolean settled) {
            scenes.add(scene);
        }

        @Override
        public void movementChanged(StageMovements all, String trackId, String parameter, boolean settled) {
            movements.add(trackId + " " + parameter + (settled ? " settled" : " moving"));
            last = all;
        }

        @Override
        public void askDirector() {
        }
    }

    private static final int WIDTH = 332;
    private static final int HEIGHT = 78;

    @Test
    public void curvesOfSideDepthAndRoomShareAreDrawnAndEditedLikeTheLevel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            // The look of the studio names its font; without a window a plain one stands in for it.
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
            panel.select("melody");
            JComponent field = panel.courseField();
            field.setSize(WIDTH, HEIGHT);

            // Without a mix document a movement cannot be drawn; the level can, as always.
            panel.showCourse(AutomationLane.X);
            click(field, bar(4), HEIGHT / 2);
            Assert.assertTrue(told.movements.isEmpty());

            MixScene mix = new MixScene(new Room("hall", 2, 0.4), 0, LevelMode.LEGACY_PEAK,
                    List.of(new MixChannel("c1", MixChannel.Owner.part("melody"), 0, 0.2, 0, 1, 0.3, false, false),
                            new MixChannel("c2", MixChannel.Owner.part("bass"), 0, 0.5, 0, 1, 0.3, false, false)),
                    List.of(new AutomationLane("l1", "c1", AutomationLane.REVERB,
                            List.of(new AutomationLane.Point(0, 0.5)))));
            panel.setMovements(StageMovements.of(mix, TimeMap.of(120)));

            // Side: a click at bar 2 at the upper edge (right), one at bar 6 at the lower edge (left).
            panel.showCourse(AutomationLane.X);
            click(field, bar(2), 5);
            click(field, bar(6), HEIGHT - 5);
            Assert.assertEquals(List.of(new StageMovements.Point(2, 1), new StageMovements.Point(6, -1)),
                    panel.movements().of("melody", AutomationLane.X));
            Assert.assertEquals("melody x settled", told.movements.get(told.movements.size() - 1));
            Assert.assertSame(panel.movements(), told.last);

            // The second point is dragged to the middle at bar 4: told while moving, settled at the end.
            int before = told.movements.size();
            field.dispatchEvent(mouse(field, MouseEvent.MOUSE_PRESSED, bar(6), HEIGHT - 5, MouseEvent.BUTTON1));
            field.dispatchEvent(mouse(field, MouseEvent.MOUSE_DRAGGED, bar(5), HEIGHT / 2, MouseEvent.BUTTON1));
            field.dispatchEvent(mouse(field, MouseEvent.MOUSE_RELEASED, bar(4), HEIGHT / 2, MouseEvent.BUTTON1));
            Assert.assertEquals(List.of(new StageMovements.Point(2, 1), new StageMovements.Point(4, 0)),
                    panel.movements().of("melody", AutomationLane.X));
            Assert.assertEquals(Arrays.asList("melody x moving", "melody x settled"),
                    told.movements.subList(before, told.movements.size()));

            // Depth and share of the room have fields of their own; what was read from the piece is there.
            panel.showCourse(AutomationLane.DEPTH);
            click(field, bar(0), 5);
            Assert.assertEquals(List.of(new StageMovements.Point(0, 1)),
                    panel.movements().of("melody", AutomationLane.DEPTH));
            panel.showCourse(AutomationLane.REVERB);
            Assert.assertEquals(List.of(new StageMovements.Point(0, 0.5)),
                    panel.movements().of("melody", AutomationLane.REVERB));
            // The right mouse button takes the point away: the track stands still in that again.
            field.dispatchEvent(mouse(field, MouseEvent.MOUSE_PRESSED, bar(0), HEIGHT / 2, MouseEvent.BUTTON3));
            Assert.assertTrue(panel.movements().of("melody", AutomationLane.REVERB).isEmpty());
            Assert.assertEquals("melody reverb settled", told.movements.get(told.movements.size() - 1));

            // Nothing of this touched the scene; the level curve still does, and only the scene.
            Assert.assertTrue(told.scenes.isEmpty());
            int movements = told.movements.size();
            panel.showCourse(AutomationLane.LEVEL);
            click(field, bar(3), 5);
            Assert.assertEquals(movements, told.movements.size());
            SpatialScene changed = told.scenes.get(told.scenes.size() - 1);
            Assert.assertEquals(1, changed.placement("melody").dynamics().size());
            Assert.assertEquals(3, changed.placement("melody").dynamics().get(0).bar(), 0);
            Assert.assertEquals(6, changed.placement("melody").dynamics().get(0).db(), 0);

            // Another track has its own curves; it can be painted without a window.
            panel.select("bass");
            panel.showCourse(AutomationLane.X);
            Assert.assertTrue(panel.movements().of("bass", AutomationLane.X).isEmpty());
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(WIDTH, HEIGHT,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (String parameter : AutomationLane.PARAMETERS) {
                panel.select("melody");
                panel.showCourse(parameter);
                java.awt.Graphics2D graphics = image.createGraphics();
                field.paint(graphics);
                graphics.dispose();
            }
        });
    }

    @Test
    public void valuesAreSaidInTheWordsOfTheirSliders() {
        Assert.assertEquals("Mitte", StagePanel.words(AutomationLane.X, 0));
        Assert.assertEquals("80 L", StagePanel.words(AutomationLane.X, -0.8));
        Assert.assertEquals("25 R", StagePanel.words(AutomationLane.X, 0.25));
        Assert.assertEquals("7,5 m", StagePanel.words(AutomationLane.DEPTH, 0.5));
        Assert.assertEquals("35 %", StagePanel.words(AutomationLane.REVERB, 0.35));
    }

    /** The x of a bar in the field of eight bars. */
    private static int bar(double bar) {
        return (int) Math.round(6 + (WIDTH - 12) * bar / 8);
    }

    private static void click(JComponent field, int x, int y) {
        field.dispatchEvent(mouse(field, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1));
        field.dispatchEvent(mouse(field, MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1));
    }

    private static MouseEvent mouse(JComponent field, int kind, int x, int y, int button) {
        int held = button == MouseEvent.BUTTON3 ? MouseEvent.BUTTON3_DOWN_MASK : MouseEvent.BUTTON1_DOWN_MASK;
        return new MouseEvent(field, kind, System.currentTimeMillis(), kind == MouseEvent.MOUSE_RELEASED ? 0 : held,
                x, y, 1, false, kind == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : button);
    }
}
