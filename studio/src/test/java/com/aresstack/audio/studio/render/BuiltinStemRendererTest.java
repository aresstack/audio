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
