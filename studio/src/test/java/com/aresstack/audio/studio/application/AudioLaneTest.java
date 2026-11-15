package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.AudioClip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.CompositionValidator;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.mix.MixChannel;
import com.aresstack.audio.sounds.SoundDesign;
import com.aresstack.audio.studio.render.AudioTrackStem;
import com.aresstack.audio.studio.stage.SoundSourcePlacement;
import com.aresstack.audio.studio.stage.SpatialScene;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Work package B3: audio lanes with a channel strip of their own, audio clips with gain and fades,
 * files and sound effects as assets of the piece. An audio clip at bar 20 begins in the mix at the
 * sample its tick falls on, within one block (A8).
 */
public final class AudioLaneTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path folder;
    private ProjectSession session;
    private PieceEditing editing;

    @Before
    public void anOldPieceOfTheStudio() throws Exception {
        folder = temporary.newFolder("piece").toPath();
        try (Stream<Path> files = Files.list(Paths.get("src", "test", "resources", "legacy", "studio-piece"))) {
            for (Path file : files.toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        session = ProjectSession.open(folder).orElseThrow();
        editing = new PieceEditing(() -> Optional.of(session));
    }

    /** A WAV of constant level, at a rate of its own. */
    private Path tone(String name, float rate, double seconds, double level) throws Exception {
        int frames = (int) (rate * seconds);
        ByteBuffer bytes = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            bytes.putShort((short) Math.round(level * 32767));
        }
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        Path file = temporary.getRoot().toPath().resolve(name);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(bytes.array()), format, frames)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }

    private String audioLane() {
        PieceEditing.Outcome added = editing.addAudioTrack("Effekte");
        assertTrue(added.said(), added.changed());
        return added.selectedClip();
    }

    private AudioClip onlyClip(String trackId) {
        return (AudioClip) session.composition().track(trackId).clips().get(0);
    }

    @Test
    public void anAudioLaneHasAChannelStripButNoPart() throws Exception { // O1
        int parts = session.composition().parts().size();
        String lane = audioLane();
        Track track = session.composition().track(lane);
        assertEquals(Track.Kind.AUDIO, track.kind());
        assertNull(track.defaultPartId());
        assertEquals(parts, session.composition().parts().size());
        assertNotNull(session.mix().channelOf(new MixChannel.Owner(MixChannel.Owner.Kind.AUDIO_TRACK, lane)));
        editing.undo();
        assertNull(session.composition().track(lane));
        assertNull(session.mix().channelOf(new MixChannel.Owner(MixChannel.Owner.Kind.AUDIO_TRACK, lane)));
    }

    @Test
    public void aFileBecomesAnAssetOfThePieceAndAClipOnTheLane() throws Exception {
        String lane = audioLane();
        Path wav = tone("whoosh.wav", 22050, 0.5, 0.5);
        PieceEditing.Outcome placed = editing.placeFile(lane, 1920, wav);
        assertTrue(placed.said(), placed.changed());
        AudioClip clip = onlyClip(lane);
        assertEquals(1920, clip.startTick());
        assertEquals("half a second at 44.1 kHz", 22050, clip.lengthSamples());
        var asset = session.composition().asset(clip.assetId());
        assertNotNull(asset);
        assertTrue(asset.path().startsWith("assets/"));
        assertTrue(Files.isRegularFile(folder.resolve(asset.path())));
        assertEquals(List.of(), CompositionValidator.validate(session.composition()));
        assertEquals(session.composition(), ProjectSession.open(folder).orElseThrow().composition());

        // A second sound where the first still sounds is refused; after it, it is placed.
        long end = clip.endTick(session.composition().timeMap());
        assertFalse(editing.placeFile(lane, end - 10, wav).changed());
        assertTrue(editing.placeFile(lane, end, wav).changed());
        // Moving onto the first is refused as well.
        String second = session.composition().track(lane).clips().get(1).id();
        PieceEditing.Outcome onto = editing.moveClip(second, 1920, null);
        assertFalse(onto.changed());
        assertTrue(editing.moveClip(second, end + 960, null).changed());
    }

    @Test
    public void gainAndFadesShapeTheStem() throws Exception {
        String lane = audioLane();
        editing.placeFile(lane, 0, tone("flat.wav", 44100, 1.0, 0.5));
        String clip = onlyClip(lane).id();
        assertTrue(editing.fades(clip, 4410, 22050).changed());
        assertTrue(editing.gain(clip, -6).changed());
        AudioClip shaped = onlyClip(lane);
        assertEquals(4410, shaped.fadeInSamples());
        assertEquals(22050, shaped.fadeOutSamples());
        assertEquals(-6, shaped.gainDb(), 0);

        Path stem = temporary.getRoot().toPath().resolve("stem.wav");
        assertEquals(List.of(), AudioTrackStem.write(session.composition(), folder, session.composition().track(lane), stem));
        float[] left = samples(stem);
        double half = 0.5 * Math.pow(10, -6 / 20.0);
        assertEquals("silent at the start of the fade-in", 0, Math.abs(left[0]), 0.01);
        assertEquals("half way into the fade-in", half / 2, Math.abs(left[2205]), 0.02);
        assertEquals("full level in the middle", half, Math.abs(left[15000]), 0.02);
        assertEquals("half way through the fade-out", half / 2, Math.abs(left[44100 - 11025]), 0.02);
        assertTrue("quiet at the very end", Math.abs(left[44099]) < 0.01);
    }

    @Test
    public void anAudioClipAtBarTwentyBeginsInTheMixAtItsSample() throws Exception { // A8
        String lane = audioLane();
        Composition piece = session.composition();
        long bar20 = piece.timeMap().tickOfBar(19);
        Path click = tone("click.wav", 48000, 0.3, 0.9);
        assertTrue(editing.placeFile(lane, bar20, click).changed());
        long expected = Math.round(session.composition().timeMap().seconds(bar20) * 44100);
        assertEquals(expected, AudioTrackStem.firstSample(session.composition(), onlyClip(lane)));

        // Only the audio lane is heard: every part is muted, so their stems need not be rendered.
        SpatialScene scene = new SpatialSceneStore().load(folder)
                .fitting(StageMixService.tracks(folder.resolve("arrangement.tsv")));
        for (SoundSourcePlacement placement : scene.placements()) {
            scene = scene.with(placement.withMuted(true));
        }
        Path mix = new StageMixService().mix(folder.resolve("arrangement.tsv"), folder, scene, message -> { });
        float[] left = samples(mix);
        int first = -1;
        for (int i = 0; i < left.length; i++) {
            if (Math.abs(left[i]) > 0.01) {
                first = i;
                break;
            }
        }
        assertTrue("the click is in the mix", first >= 0);
        assertTrue("begins at sample " + expected + " within one block, was " + first,
                first >= expected - 512 && first <= expected + 512);
    }

    @Test
    public void renderingFromTheModelTakesTheAudioLaneIntoTheMix() throws Exception { // A8 over R1
        String lane = audioLane();
        long bar3 = session.composition().timeMap().tickOfBar(2);
        assertTrue(editing.placeFile(lane, bar3, tone("click.wav", 44100, 0.2, 0.9)).changed());
        long expected = Math.round(session.composition().timeMap().seconds(bar3) * 44100);

        // The parts are rendered as ever, but silent on the stage: what is heard is the audio lane.
        SpatialSceneStore store = new SpatialSceneStore();
        SpatialScene scene = store.load(folder).fitting(StageMixService.Piece.of(session.composition()).tracks());
        for (SoundSourcePlacement placement : scene.placements()) {
            scene = scene.with(placement.withMuted(true));
        }
        store.save(folder, scene);
        java.util.Map<String, String> builtin = new java.util.HashMap<String, String>();
        for (var part : session.composition().parts()) {
            builtin.put(part.id(), "builtin");
        }
        PieceRendering.Outcome rendered = new PieceRendering(
                new com.aresstack.audio.instruments.InstrumentRegistry(folder.resolve("none.properties")))
                .render(session, builtin, com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.GERVILL,
                        "fluidsynth", "sfizz_render", new java.util.HashMap<String, Integer>(), message -> { });
        float[] left = samples(rendered.mix());
        int first = -1;
        for (int i = 0; i < left.length; i++) {
            if (Math.abs(left[i]) > 0.01) {
                first = i;
                break;
            }
        }
        assertTrue("the click is in the mix", first >= 0);
        assertTrue("begins at sample " + expected + " within one block, was " + first,
                first >= expected - 512 && first <= expected + 512);
    }

    @Test
    public void soundEffectsOfTheModuleSoundsArePlacedAsAssets() throws Exception {
        List<SoundDesign> effects = AudioAssets.effects();
        assertFalse(effects.isEmpty());
        String lane = audioLane();
        PieceEditing.Outcome placed = editing.placeEffect(lane, 3840, effects.get(0));
        assertTrue(placed.said(), placed.changed());
        AudioClip clip = onlyClip(lane);
        assertEquals(Math.round(effects.get(0).durationMs() * 44.1), clip.lengthSamples(), 2);
        assertTrue(Files.isRegularFile(folder.resolve(session.composition().asset(clip.assetId()).path())));
    }

    @Test
    public void aMidiClipDoesNotGoOnAnAudioLane() {
        String lane = audioLane();
        String melody = session.composition().track("melody").clips().get(0).id();
        PieceEditing.Outcome moved = editing.moveClip(melody, 0, lane);
        assertFalse(moved.changed());
        assertTrue(moved.said(), moved.said().contains("MIDI-Spur"));
    }

    /** The left side of a WAV, between -1 and 1. */
    private static float[] samples(Path wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(wav.toString()))) {
            AudioFormat format = in.getFormat();
            int bytes = format.getSampleSizeInBits() / 8;
            int frame = format.getFrameSize();
            byte[] all = in.readAllBytes();
            float[] left = new float[all.length / frame];
            for (int i = 0; i < left.length; i++) {
                int at = i * frame;
                int value;
                if (bytes == 3) {
                    value = (all[at] & 0xFF) | ((all[at + 1] & 0xFF) << 8) | (all[at + 2] << 16);
                    left[i] = value / 8388608f;
                } else {
                    value = (all[at] & 0xFF) | (all[at + 1] << 8);
                    left[i] = value / 32768f;
                }
            }
            return left;
        }
    }
}
