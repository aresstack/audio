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
}
