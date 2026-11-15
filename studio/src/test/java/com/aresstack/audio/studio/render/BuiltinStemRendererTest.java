package com.aresstack.audio.studio.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Clip;
import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Meta;
import com.aresstack.audio.composition.MidiClip;
import com.aresstack.audio.composition.Note;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.TimeMap;
import com.aresstack.audio.composition.Track;
import com.aresstack.audio.composition.Voice;
import com.aresstack.audio.music.synth.BuiltinSynthesis;
import com.aresstack.audio.studio.application.ProjectSession;
import com.aresstack.audio.studio.golden.Golden;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class BuiltinStemRendererTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** The stem from the model is the stem of the reference renderer, byte for byte. */
    @Test
    public void soundsLikeTheReferenceRendererToTheSample() throws Exception {
        for (String fixture : Golden.FIXTURES) {
            Path reference = folder.newFolder(fixture + "-reference").toPath();
            Golden.renderReference(Golden.fixture(fixture), reference);
            ProjectSession session = RenderFixtures.open(fixture, folder.getRoot().toPath().resolve(fixture));
            Composition composition = session.composition();
            for (Part part : composition.parts()) {
                Path stem = folder.getRoot().toPath().resolve(fixture + "-" + part.id() + ".wav");
                new BuiltinStemRenderer().render(new StemJob(composition, part, null, null, RenderLimits.STANDARD),
                        stem);
                assertArrayEquals(fixture + "/" + part.id(), Files.readAllBytes(reference.resolve(part.id() + ".wav")),
                        Files.readAllBytes(stem));
            }
        }
    }

    /** A note held across a change of tempo lasts from its start to its end in real time. */
    @Test
    public void aNoteHeldAcrossATempoChangeLastsItsRealTime() {
        // 120 BPM for the first bar (2 s), then 60 BPM: a note from beat 3 to the end of bar 2
        // lasts one second at 120 and four seconds at 60.
        TimeMap time = new TimeMap(List.of(new TimeMap.Tempo(0, 120), new TimeMap.Tempo(1920, 60)),
                List.of(new TimeMap.Meter(0, 4, 4)), List.of(new TimeMap.KeyChange(0,
                com.aresstack.audio.composition.Key.C_MAJOR)));
        assertEquals(5.0, BuiltinStemRenderer.heldSeconds(time, 960, 3840), 1e-12);
        assertEquals(time.seconds(3840) - time.seconds(960), BuiltinStemRenderer.heldSeconds(time, 960, 3840), 0);
        // Within one tempo nothing changes: half a bar at 60 BPM is two seconds.
        assertEquals(2.0, BuiltinStemRenderer.heldSeconds(time, 1920, 2880), 1e-12);
        // A note ending exactly where the tempo changes is held at its own tempo.
        assertEquals(1.0, BuiltinStemRenderer.heldSeconds(time, 960, 1920), 1e-12);

        // The sound follows: the note rings on until 1 s + 5 s, not until 1 s + 1.5 s.
        Part part = new Part("pad", "Pad", "harmony", null, 0, "builtin", Part.StaffLayout.SINGLE,
                List.of(new Voice("v1", null, null, null)), null);
        Track track = new Track("t1", "Pad", Track.Kind.MIDI, "pad",
                List.<Clip>of(new MidiClip("c1", 0, 3840, null, List.of(new Note("n1", 960, 2880, 57, 100, "v1")))));
        Composition piece = new Composition(Meta.EMPTY, time, List.of(), List.of(part), List.of(track), List.of());
        double[] samples = BuiltinStemRenderer.samples(piece, part, BuiltinSynthesis.frames(StemJob.pieceSeconds(piece)));
        double late = 0;
        for (int i = (int) (5.0 * BuiltinSynthesis.SAMPLE_RATE); i < (int) (5.5 * BuiltinSynthesis.SAMPLE_RATE); i++) {
            late = Math.max(late, Math.abs(samples[i]));
        }
        assertTrue("still held four seconds after the tempo change", late > 0.01);
    }

    /** A14: twelve minutes of music give a stem of twelve minutes, sounding in its last bar. */
    @Test
    public void rendersTwelveMinutesToTheLastBar() throws Exception {
        TimeMap time = TimeMap.of(120); // a bar of 4/4 lasts two seconds
        int bars = 360;
        long lastBar = time.tickOfBar(bars - 1);
        List<Note> notes = List.of(new Note("n1", 0, 480, 60, 100, "v1"),
                new Note("n2", lastBar, 960, 64, 100, "v1"));
        Part part = new Part("lead", "Lead", "melody", null, 0, "builtin", Part.StaffLayout.SINGLE,
                List.of(new Voice("v1", null, null, null)), null);
        Track track = new Track("t1", "Lead", Track.Kind.MIDI, "lead",
                List.<Clip>of(new MidiClip("c1", 0, time.tickOfBar(bars), null, notes)));
        Composition piece = new Composition(Meta.EMPTY, time, List.of(), List.of(part), List.of(track), List.of());
        assertEquals(720.0, StemJob.pieceSeconds(piece), 1e-9);

        Path stem = folder.getRoot().toPath().resolve("long.wav");
        new BuiltinStemRenderer().render(new StemJob(piece, part, null, null, RenderLimits.STANDARD), stem);
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(stem.toFile())) {
            AudioFormat format = audio.getFormat();
            assertEquals(BuiltinSynthesis.frames(720.0), audio.getFrameLength());
            long skip = (long) (718.0 * format.getSampleRate()) * format.getFrameSize();
            assertEquals(skip, audio.skip(skip));
            byte[] last = audio.readAllBytes();
            int loudest = 0;
            for (int i = 0; i + 1 < last.length; i += 2) {
                loudest = Math.max(loudest, Math.abs((short) ((last[i + 1] << 8) | (last[i] & 255))));
            }
            assertTrue("the last bar sounds", loudest > 1000);
        }
    }
}
