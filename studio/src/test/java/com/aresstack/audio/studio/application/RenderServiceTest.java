package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Part;
import com.aresstack.audio.composition.mix.MixScene;
import com.aresstack.audio.studio.project.MixJson;
import com.aresstack.audio.studio.project.StemManifest;
import com.aresstack.audio.studio.render.BuiltinStemRenderer;
import com.aresstack.audio.studio.render.PartMidi;
import com.aresstack.audio.studio.render.RenderFixtures;
import com.aresstack.audio.studio.render.RenderLimits;
import com.aresstack.audio.studio.render.SamplerUnavailableException;
import com.aresstack.audio.studio.render.StemJob;
import com.aresstack.audio.studio.render.StemRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Acceptance of work package R1 (architecture review 7.2): only what changed is rendered again. */
public final class RenderServiceTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** The built-in sound, counting the parts it renders. */
    private static final class Counting implements StemRenderer {
        private final StemRenderer inner;
        private final List<String> rendered = Collections.synchronizedList(new ArrayList<>());

        Counting(StemRenderer inner) {
            this.inner = inner;
        }

        @Override
        public String engine() {
            return inner.engine();
        }

        @Override
        public String identity(StemJob job) throws Exception {
            return inner.identity(job);
        }

        @Override
        public void render(StemJob job, Path output) throws Exception {
            rendered.add(job.part().id());
            inner.render(job, output);
        }
    }

    /** A sampler whose sound does not depend on the role: it only copies a WAV. */
    private static final class FakeSampler implements StemRenderer {
        private final List<String> rendered = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String engine() {
            return "fake";
        }

        @Override
        public String identity(StemJob job) {
            return "fake sampler";
        }

        @Override
        public void render(StemJob job, Path output) throws Exception {
            rendered.add(job.part().id());
            new BuiltinStemRenderer().render(job, output);
        }
    }

    private static RenderService service(StemRenderer renderer) {
        return new RenderService(part -> new RenderService.Voicing(renderer, null, PartMidi.OWN_PROGRAM),
                RenderLimits.STANDARD);
    }

    @Test
    public void rendersEveryPartOnceAndThenNothing() throws Exception {
        ProjectSession session = RenderFixtures.open("district-dawn", folder.getRoot().toPath().resolve("piece"));
        Counting builtin = new Counting(new BuiltinStemRenderer());
        RenderService.Result first = service(builtin).render(session, message -> { });
        int parts = session.composition().parts().size();
        assertEquals(parts, first.rendered().size());
        assertEquals(parts, builtin.rendered.size());

        RenderService.Result second = service(builtin).render(session, message -> { });
        assertEquals(List.of(), second.rendered());
        assertEquals(parts, second.reused().size());
        assertEquals(parts, builtin.rendered.size());
        assertEquals(first.stems(), second.stems());
    }

    @Test
    public void aChangeToOnePartRendersExactlyOneStem() throws Exception {
        ProjectSession session = RenderFixtures.open("edge-cases", folder.getRoot().toPath().resolve("piece"));
        Counting builtin = new Counting(new BuiltinStemRenderer());
        RenderService.Result before = service(builtin).render(session, message -> { });
        builtin.rendered.clear();

        String changed = session.composition().parts().get(1).id();
        Composition louder = RenderFixtures.louder(session.composition(), changed);
        RenderService.Result after = service(builtin).render(session.folder(), louder, message -> { });
        assertEquals(List.of(changed), builtin.rendered);
        assertEquals(List.of(changed), after.rendered());
        for (Part part : louder.parts()) {
            boolean same = before.stems().get(part.id()).equals(after.stems().get(part.id()));
            assertEquals(part.id(), !part.id().equals(changed), same);
        }
    }

    @Test
    public void aChangeToTheMixRendersNothing() throws Exception {
        Path piece = folder.getRoot().toPath().resolve("piece");
        ProjectSession session = RenderFixtures.open("district-dawn", piece);
        Counting builtin = new Counting(new BuiltinStemRenderer());
        service(builtin).render(session, message -> { });
        builtin.rendered.clear();

        MixScene mix = session.mix();
        MixJson.save(piece, new MixScene(mix.room(), mix.masterDb() - 6, mix.levelMode(), mix.channels(),
                mix.automation()));
        ProjectSession reopened = ProjectSession.open(piece).orElseThrow();
        assertEquals(mix.masterDb() - 6, reopened.mix().masterDb(), 1e-9);
        RenderService.Result result = service(builtin).render(reopened, message -> { });
        assertEquals(List.of(), builtin.rendered);
        assertEquals(List.of(), result.rendered());
    }

    @Test
    public void aChangeOfRoleOnlyRendersTheBuiltinStemAgain() throws Exception {
        ProjectSession session = RenderFixtures.open("district-dawn", folder.getRoot().toPath().resolve("piece"));
        Composition composition = session.composition();
        Part part = RenderFixtures.melodic(composition);
        String role = "bass".equals(part.role()) ? "melody" : "bass";
        Composition recast = RenderFixtures.withRole(composition, part.id(), role);

        Counting builtin = new Counting(new BuiltinStemRenderer());
        service(builtin).render(session.folder(), composition, message -> { });
        builtin.rendered.clear();
        service(builtin).render(session.folder(), recast, message -> { });
        assertEquals(List.of(part.id()), builtin.rendered);

        Path other = folder.getRoot().toPath().resolve("sampled");
        Files.createDirectories(other);
        FakeSampler sampler = new FakeSampler();
        service(sampler).render(other, composition, message -> { });
        sampler.rendered.clear();
        service(sampler).render(other, recast, message -> { });
        assertEquals("a sampler plays by program and bank, not by role", List.of(), sampler.rendered);
    }

    @Test
    public void theManifestAndTheStageKnowTheStems() throws Exception {
        Path piece = folder.getRoot().toPath().resolve("piece");
        ProjectSession session = RenderFixtures.open("district-dawn", piece);
        RenderService.Result result = service(new BuiltinStemRenderer()).render(session, message -> { });

        List<StemManifest.Entry> entries = StemManifest.load(piece);
        assertEquals(session.composition().parts().size(), entries.size());
        for (StemManifest.Entry entry : entries) {
            assertEquals(StemManifest.RENDERED, entry.state());
            assertEquals("builtin", entry.origin());
            assertTrue(entry.file(), entry.file().startsWith("render/stems/" + entry.partId() + "-"));
            assertEquals(64, entry.key().length());
            assertEquals(piece.resolve(entry.file()), StageMixService.stem(piece, entry.partId()));
            assertEquals(result.stems().get(entry.partId()).toAbsolutePath().normalize(),
                    piece.resolve(entry.file()).toAbsolutePath().normalize());
        }
        Path mix = new StageMixService().mix(piece.resolve("arrangement.tsv"), piece, message -> { });
        assertTrue(Files.size(mix) > 1000);
    }

    @Test
    public void aSamplerThatCannotStartLeavesTheJavaSound() throws Exception {
        ProjectSession session = RenderFixtures.open("district-dawn", folder.getRoot().toPath().resolve("piece"));
        List<String> attempts = Collections.synchronizedList(new ArrayList<>());
        StemRenderer missing = new StemRenderer() {
            @Override
            public String engine() {
                return "fluidsynth";
            }

            @Override
            public String identity(StemJob job) {
                return "missing";
            }

            @Override
            public void render(StemJob job, Path output) throws Exception {
                attempts.add(job.part().id());
                throw new SamplerUnavailableException("no-such-sampler", new IOException("not found"));
            }
        };
        int parts = session.composition().parts().size();
        RenderService.Result first = service(missing).render(session, message -> { });
        assertEquals(parts, first.warnings().size());
        assertEquals(parts, first.rendered().size());
        for (StemManifest.Entry entry : StemManifest.load(session.folder())) {
            assertEquals("builtin", entry.origin());
        }
        // Next time the sampler is tried again, but the Java sound is not rendered again.
        RenderService.Result second = service(missing).render(session, message -> { });
        assertEquals(2 * parts, attempts.size());
        assertEquals(List.of(), second.rendered());
        assertEquals(first.stems(), second.stems());
    }
}
