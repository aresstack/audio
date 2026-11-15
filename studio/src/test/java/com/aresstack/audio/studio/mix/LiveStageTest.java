package com.aresstack.audio.studio.mix;

import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.mix.AutomationLane;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.composition.mix.Room;
import com.aresstack.audio.core.AudioBlock;
import com.aresstack.audio.core.live.LiveGraph;
import com.aresstack.audio.core.mix.LevelMode;
import com.aresstack.audio.core.mix.MasterBus;
import com.aresstack.audio.studio.stage.AcousticRoom;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

/**
 * The live stage (work package D2): it sounds exactly like the file mix of the same stage as long
 * as nothing moves, and a moved instrument is heard at once, without a jump (acceptance A13 as far
 * as it can be checked without a sound card; on target hardware it is a listening test).
 */
public final class LiveStageTest {
    private static final int RATE = SpatialStemMixer.RATE;
    private static final AcousticRoom[] ROOMS = {AcousticRoom.STUDIO.withAmount(0), AcousticRoom.STUDIO,
            AcousticRoom.HALL, AcousticRoom.CATHEDRAL.withAmount(1)};

    @Test
    public void withoutMovesTheLiveStageSoundsExactlyLikeTheFileMix() throws Exception {
        List<SpatialStemMixer.Signal> signals = new ArrayList<SpatialStemMixer.Signal>();
        List<SoundSourcePlacement> placements = new ArrayList<SoundSourcePlacement>();
        List<SoundSourcePlacement.LevelPoint> swell = Arrays.asList(new SoundSourcePlacement.LevelPoint(0, -18),
                new SoundSourcePlacement.LevelPoint(1.3, 0), new SoundSourcePlacement.LevelPoint(2.7, -6));
        signals.add(new SpatialStemMixer.Signal(noise(3 * RATE, 2), noise(3 * RATE, 3), RATE));
        placements.add(new SoundSourcePlacement("a", -0.7, 0.2, -3, 0.4, 0.5, false, false, swell));
        signals.add(new SpatialStemMixer.Signal(tone(RATE + 77, 220, 22050), tone(RATE + 77, 330, 22050), 22050));
        placements.add(new SoundSourcePlacement("b", 0.4, 0.8, 2, 1, 0.9, false, false, null));
        signals.add(new SpatialStemMixer.Signal(noise(RATE, 4), noise(RATE, 4), RATE));
        placements.add(new SoundSourcePlacement("c", 0.95, 0.05, -12, 0, 0.1, false, false, swell));
        for (AcousticRoom room : ROOMS) {
            SpatialStemMixer.Mix file = new SpatialStemMixer().render(signals, placements, room, 1.7);
            float[][] live;
            try (LiveStage stage = LiveStage.of(signals, placements, room, 1.7)) {
                live = drain(stage.graph(), 512);
            }
            Assert.assertArrayEquals(room.kind() + " left", file.left, live[0], 0f);
            Assert.assertArrayEquals(room.kind() + " right", file.right, live[1], 0f);
        }
    }

    @Test
    public void aMutedTrackIsSilentLiveAndCanBeTurnedUpWhilePlaying() throws Exception {
        List<SpatialStemMixer.Signal> signals = List.of(new SpatialStemMixer.Signal(tone(2 * RATE, 440, RATE),
                tone(2 * RATE, 440, RATE), RATE));
        SoundSourcePlacement muted = new SoundSourcePlacement("a", 0, 0, 0, 1, 0.3, true, false, null);
        try (LiveStage stage = LiveStage.of(signals, List.of(muted), AcousticRoom.STUDIO, 2)) {
            LiveGraph graph = stage.graph();
            AudioBlock block = new AudioBlock(512);
            graph.prepare(RATE, 512);
            block.setFrames(512);
            graph.read(block);
            Assert.assertEquals(0, rms(block.left, 0, 512), 0);
            Assert.assertTrue(stage.move(muted.withMuted(false)));
            for (int i = 0; i < 4; i++) {
                block.setFrames(512);
                graph.read(block);
            }
            Assert.assertTrue(rms(block.left, 0, 512) > 0.1);
        }
    }

    /**
     * A13: an instrument at the front left is dragged to the right and back while the piece plays. The
     * block after the move already changes; after the glide the instrument is on the right, quieter
     * and further away; no sample jumps on the way.
     */
    @Test
    public void anInstrumentMovedWhilePlayingIsHeardAtOnceWithoutAJump() throws Exception {
        float[] sine = tone(3 * RATE, 110, RATE);
        SoundSourcePlacement frontLeft = new SoundSourcePlacement("violin", -1, 0, 0, 1, 0.3, false, false, null);
        SoundSourcePlacement backRight = frontLeft.at(1, 0.3);
        try (LiveStage stage = LiveStage.of(List.of(new SpatialStemMixer.Signal(sine, sine, RATE)), List.of(frontLeft),
                AcousticRoom.CHAMBER, 2)) {
            LiveGraph graph = stage.graph();
            int blockFrames = SpatialStemMixer.BLOCK;
            float[][] out = new float[][]{new float[(int) graph.frames()], new float[(int) graph.frames()]};
            AudioBlock block = new AudioBlock(blockFrames);
            graph.prepare(RATE, blockFrames);
            int at = 0;
            int movedAt = -1;
            int got;
            do {
                if (movedAt < 0 && at >= RATE) {
                    stage.move(backRight); // what the stage view would do while dragging
                    movedAt = at;
                }
                block.setFrames(blockFrames);
                got = graph.read(block);
                System.arraycopy(block.left, 0, out[0], at, got);
                System.arraycopy(block.right, 0, out[1], at, got);
                at += got;
            } while (got == blockFrames);
            int glide = graph.glideFrames();
            // before: left loud, right only the room
            double leftBefore = rms(out[0], movedAt - 4096, movedAt);
            double rightBefore = rms(out[1], movedAt - 4096, movedAt);
            Assert.assertTrue(leftBefore > 3 * rightBefore);
            // in the very first block after the move the balance already shifts
            double firstLeft = rms(out[0], movedAt + blockFrames / 2, movedAt + blockFrames);
            Assert.assertTrue("first block " + firstLeft + " vs " + leftBefore, firstLeft < 0.9 * leftBefore);
            // after the glide: right louder than left, and the whole quieter (farther away)
            int after = movedAt + glide + blockFrames;
            double leftAfter = rms(out[0], after, after + 4096);
            double rightAfter = rms(out[1], after, after + 4096);
            Assert.assertTrue("left " + leftAfter + ", right " + rightAfter, rightAfter > 2 * leftAfter);
            Assert.assertTrue(rightAfter < leftBefore);
            // no jump: neighbouring samples never differ by more than the sine and its room allow
            float most = 0;
            for (float[] side : out) {
                for (int i = movedAt - blockFrames; i < after; i++) {
                    most = Math.max(most, Math.abs(side[i] - side[i - 1]));
                }
            }
            Assert.assertTrue("largest step " + most, most < 0.03f);
        }
    }

    /**
     * Work packages D2 and D3 together: a source that moves with the piece (place and room share
     * from the mix document) is heard live as in the file. The file looks at the movement every 64
     * frames, live playback once per block; both follow the same curve, so they differ by a trifle.
     */
    @Test
    public void aSourceThatMovesWithThePieceIsHeardLiveAsInTheFile() throws Exception {
        float[] sine = tone(4 * RATE, 220, RATE);
        List<SpatialStemMixer.Signal> signals = List.of(new SpatialStemMixer.Signal(sine, sine, RATE),
                new SpatialStemMixer.Signal(noise(4 * RATE, 5), noise(4 * RATE, 6), RATE));
        List<SoundSourcePlacement> placements = List.of(
                new SoundSourcePlacement("violin", -0.5, 0.2, 0, 1, 0.3, false, false, null),
                new SoundSourcePlacement("pad", 0.3, 0.6, -6, 0.8, 0.5, false, false, null));
        StageAutomation movements = movements("violin", "c1");
        for (AcousticRoom room : new AcousticRoom[]{AcousticRoom.STUDIO.withAmount(0), AcousticRoom.HALL}) {
            SpatialStemMixer.Mix file = new SpatialStemMixer().withAutomation(movements)
                    .render(signals, placements, room, 2);
            float[][] live;
            try (LiveStage stage = LiveStage.of(signals, placements, room, 2, movements)) {
                live = drain(stage.graph(), SpatialStemMixer.BLOCK);
            }
            Assert.assertArrayEquals(room.kind() + " left", file.left, live[0], 2e-3f);
            Assert.assertArrayEquals(room.kind() + " right", file.right, live[1], 2e-3f);
            // It does move: at the start the violin is on the left, at the end on the right.
            Assert.assertTrue(rms(live[0], 0, RATE / 2) > 1.5 * rms(live[1], 0, RATE / 2));
        }
    }

    /** A curve drawn while the piece plays: the source follows it from the next block on, without a jump. */
    @Test
    public void movementsHandedOverWhilePlayingAreFollowedWithoutAJump() throws Exception {
        float[] sine = tone(4 * RATE, 110, RATE);
        SoundSourcePlacement left = new SoundSourcePlacement("violin", -1, 0, 0, 1, 0, false, false, null);
        try (LiveStage stage = LiveStage.of(List.of(new SpatialStemMixer.Signal(sine, sine, RATE)), List.of(left),
                AcousticRoom.STUDIO.withAmount(0), 2)) {
            LiveGraph graph = stage.graph();
            int blockFrames = SpatialStemMixer.BLOCK;
            float[][] out = new float[][]{new float[(int) graph.frames()], new float[(int) graph.frames()]};
            AudioBlock block = new AudioBlock(blockFrames);
            graph.prepare(RATE, blockFrames);
            int at = 0;
            int changedAt = -1;
            int stillAt = -1;
            int got;
            do {
                if (changedAt < 0 && at >= RATE) {
                    // From now on the violin stands far right for the whole piece, says its curve.
                    stage.moving(StageAutomation.of(mix("violin", "c1", new AutomationLane("l1", "c1",
                            AutomationLane.X, List.of(new AutomationLane.Point(0, 1)))), TimeMap.of(120)));
                    changedAt = at;
                } else if (stillAt < 0 && at >= 2 * RATE) {
                    // A drag of the disc does not move it while the curve says where it is ...
                    Assert.assertTrue(stage.move(left.at(-1, 0).withLevel(0)));
                    // ... and without the curve it stands where its placement says again.
                    stage.moving(StageAutomation.NONE);
                    stillAt = at;
                }
                block.setFrames(blockFrames);
                got = graph.read(block);
                System.arraycopy(block.left, 0, out[0], at, got);
                System.arraycopy(block.right, 0, out[1], at, got);
                at += got;
            } while (got == blockFrames);
            Assert.assertTrue(rms(out[0], changedAt - 4096, changedAt) > 3 * rms(out[1], changedAt - 4096, changedAt));
            int after = changedAt + 2 * blockFrames;
            Assert.assertTrue(rms(out[1], after, after + 4096) > 3 * rms(out[0], after, after + 4096));
            int back = stillAt + graph.glideFrames() + 2 * blockFrames;
            Assert.assertTrue(rms(out[0], back, back + 4096) > 3 * rms(out[1], back, back + 4096));
            float most = 0;
            for (float[] side : out) {
                for (int i = changedAt - blockFrames; i < back; i++) {
                    most = Math.max(most, Math.abs(side[i] - side[i - 1]));
                }
            }
            Assert.assertTrue("largest step " + most, most < 0.02f);
        }
    }

    /** The violin wanders from the left to the right and into the depth, and its room share rises. */
    private static StageAutomation movements(String track, String channel) {
        long bar = 1920;
        return StageAutomation.of(mix(track, channel,
                new AutomationLane("l1", channel, AutomationLane.X,
                        List.of(new AutomationLane.Point(0, -0.9), new AutomationLane.Point(2 * bar, 0.9))),
                new AutomationLane("l2", channel, AutomationLane.DEPTH,
                        List.of(new AutomationLane.Point(bar / 2, 0.1), new AutomationLane.Point(2 * bar, 0.8))),
                new AutomationLane("l3", channel, AutomationLane.REVERB,
                        List.of(new AutomationLane.Point(0, 0.1), new AutomationLane.Point(bar, 0.7)))),
                TimeMap.of(120));
    }

    private static MixScene mix(String track, String channel, AutomationLane... lanes) {
        return new MixScene(new Room("hall", 2, 0.4), 0, com.aresstack.audio.composition.mix.LevelMode.LEGACY_PEAK,
                List.of(new MixChannel(channel, MixChannel.Owner.part(track), 0, 0.2, 0, 1, 0.3, false, false)),
                List.of(lanes));
    }

    @Test
    public void liveMasterIsTheFileLevel() {
        MixLevel fixed = MixLevel.fixed(-2);
        MasterBus.Settings live = LiveStage.master(fixed, Float.NaN);
        Assert.assertEquals(LevelMode.FIXED, live.mode());
        Assert.assertEquals(fixed.master().gain(), live.gain(), 0f);
        MasterBus.Settings legacy = LiveStage.master(MixLevel.legacy(-3), 2.5f);
        Assert.assertEquals(LevelMode.LEGACY_PEAK, legacy.mode());
        Assert.assertEquals(2.5f, legacy.gain(), 0f);
        // never mixed: the master level alone
        Assert.assertEquals((float) Math.pow(10, -3 / 20.0), LiveStage.master(MixLevel.legacy(-3), Float.NaN).gain(), 0f);
    }

    private static float[][] drain(LiveGraph graph, int blockFrames) {
        float[] left = new float[(int) graph.frames()];
        float[] right = new float[(int) graph.frames()];
        AudioBlock block = new AudioBlock(blockFrames);
        graph.prepare(RATE, blockFrames);
        int at = 0;
        int got;
        do {
            block.setFrames(blockFrames);
            got = graph.read(block);
            System.arraycopy(block.left, 0, left, at, got);
            System.arraycopy(block.right, 0, right, at, got);
            at += got;
        } while (got == blockFrames);
        return new float[][]{left, right};
    }

    private static float[] noise(int frames, long seed) {
        Random random = new Random(seed);
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (float) (random.nextGaussian() * 0.2);
        }
        return samples;
    }

    private static float[] tone(int frames, double frequency, int rate) {
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * i / rate));
        }
        return samples;
    }

    private static double rms(float[] samples, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += samples[i] * (double) samples[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }
}
