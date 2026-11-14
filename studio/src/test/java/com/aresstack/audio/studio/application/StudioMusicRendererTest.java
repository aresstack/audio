package com.aresstack.audio.studio.application;

import com.aresstack.audio.instruments.InstrumentCatalog;
import com.aresstack.audio.instruments.InstrumentLibrary;
import com.aresstack.audio.instruments.InstrumentRegistry;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Render an original model arrangement even when FluidSynth is not installed. */
public final class StudioMusicRendererTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void fallsBackToJavaSoundWhenConfiguredSamplerCannotBeStarted() throws Exception {
        Path home = folder.getRoot().toPath();
        Path arrangement = home.resolve("arrangement.tsv");
        try (InputStream source = getClass().getResourceAsStream("/district-dawn.tsv")) {
            Assert.assertNotNull(source);
            Files.copy(source, arrangement);
        }

        InstrumentRegistry registry = new InstrumentRegistry(home.resolve("instruments.properties"));
        InstrumentLibrary generalUser = InstrumentCatalog.standard().get(0);
        Path bank = home.resolve("GeneralUser-GS.sf2");
        Files.write(bank, new byte[]{'R', 'I', 'F', 'F'});
        registry.register(generalUser, bank);

        Map<String, InstrumentLibrary> assignments = new HashMap<String, InstrumentLibrary>();
        assignments.put("melody", generalUser);
        final List<String> messages = new ArrayList<String>();
        Path audio = new StudioMusicRenderer(registry).render(
                arrangement, home.resolve("render"), assignments,
                java.util.Collections.<String, Integer>emptyMap(),
                com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine.FLUIDSYNTH,
                "intentionally-missing-fluidsynth-audio-studio-test-executable",
                "intentionally-missing-sfizz-test-executable",
                new GemmaMusicComposer.Progress() {
                    @Override
                    public void update(String message) {
                        messages.add(message);
                    }
                });

        Assert.assertTrue(Files.size(audio) > 1000);
        Assert.assertTrue(Files.size(audio.getParent().resolve("arrangement.wav")) > 1000);
        Assert.assertTrue(Files.size(audio.getParent().resolve("arrangement.mid")) > 100);
        Assert.assertTrue(messages.toString().contains("WARN: FluidSynth"));
        Assert.assertTrue(messages.toString().contains("built-in Java sound"));
    }

    @Test
    public void rendersAndMixesMoreTracksThanAMidiFileHasChannels() throws Exception {
        Path home = folder.getRoot().toPath();
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (int i = 0; i < 20; i++) {
            tracks.add(new MusicTrack("voice_" + i, i % 2 == 0 ? MusicTrack.MELODY : MusicTrack.HARMONY,
                    "Voice " + i, i, "builtin"));
        }
        tracks.add(new MusicTrack("kit", MusicTrack.DRUMS, "Kit", 0, "builtin"));
        TrackArrangement arrangement = new TrackArrangement(tracks);
        List<String> lines = new ArrayList<String>();
        lines.add("tempo\t120");
        for (MusicTrack track : tracks) {
            boolean drums = MusicTrack.DRUMS.equals(track.role());
            lines.add("track\t" + track.id() + "\t" + track.program() + "\t" + arrangement.channelFor(track.id())
                    + "\t" + track.role());
            lines.add("note\t" + track.id() + "\t0\t480\t" + (drums ? 36 : 60 + track.program()) + "\t90");
        }
        Path written = home.resolve("many.tsv");
        Files.write(written, lines, java.nio.charset.StandardCharsets.UTF_8);

        Path audio = new StudioMusicRenderer(new InstrumentRegistry(home.resolve("none.properties"))).render(
                written, home.resolve("many"), new HashMap<String, InstrumentLibrary>(), "missing-fluidsynth",
                "missing-sfizz", message -> { });

        Assert.assertTrue(Files.size(audio) > 1000);
        for (MusicTrack track : tracks) {
            Assert.assertTrue(track.id(), Files.size(audio.getParent().resolve(track.id() + ".wav")) > 1000);
        }
        // In the MIDI file the tracks that share a channel are told apart by their port.
        javax.sound.midi.Sequence midi = javax.sound.midi.MidiSystem.getSequence(
                audio.getParent().resolve("arrangement.mid").toFile());
        Assert.assertEquals(22, midi.getTracks().length);
        int onSecondPort = 0;
        for (javax.sound.midi.Track track : midi.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                javax.sound.midi.MidiMessage message = track.get(i).getMessage();
                if (message instanceof javax.sound.midi.MetaMessage
                        && ((javax.sound.midi.MetaMessage) message).getType() == 0x21) {
                    onSecondPort++;
                }
            }
        }
        Assert.assertEquals("voices 16 to 20 share channels with voices 1 to 5", 5, onSecondPort);
    }
}
