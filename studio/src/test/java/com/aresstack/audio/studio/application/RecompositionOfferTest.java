package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.edit.ChangeSet;
import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.render.InstrumentSampleRenderer.Sf2Engine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

/** A track composed anew by the AI is a proposal: heard first, and the piece changes only when it is taken. */
public final class RecompositionOfferTest {
    private static final List<String> PIECE = Arrays.asList("tempo\t120",
            "track\tmelody\t10\t0\tmelody", "note\tmelody\t0\t480\t72\t80",
            "track\tbass\t32\t1\tbass", "note\tbass\t0\t960\t48\t70");

    private Path folder;
    private PieceModel model;
    private PieceRendering rendering;

    @Before
    public void aRenderedPieceWithKeptAnswers() throws Exception {
        folder = Files.createTempDirectory("recomposition-");
        Files.write(folder.resolve("arrangement.tsv"), PIECE, StandardCharsets.UTF_8);
        Files.write(folder.resolve("composition-plan.json"), "{\"plan\":1}".getBytes(StandardCharsets.UTF_8));
        Files.write(folder.resolve("melody-0123456789ab.json"), "{\"old\":\"melody\"}".getBytes(StandardCharsets.UTF_8));
        Files.write(folder.resolve("bass-0123456789ab.json"), "{\"old\":\"bass\"}".getBytes(StandardCharsets.UTF_8));
        model = new PieceModel(line -> { });
        model.open(folder);
        rendering = new PieceRendering(new InstrumentRegistry(folder.resolve("none.properties")));
        render(folder, model.session().get().composition());
    }

    @Test
    public void theProposalIsHeardWithoutTouchingThePieceAndTakingItChangesOnlyThatTrack() throws Exception {
        ProjectSession session = model.session().get();
        TreeMap<String, String> before = snapshot();

        RecompositionOffer offer = RecompositionOffer.stage(session, "bass", "run-1");
        assertTrue("the composer finds the plan and the answers of the other tracks",
                Files.isRegularFile(offer.staging().resolve("composition-plan.json"))
                        && Files.isRegularFile(offer.staging().resolve("melody-0123456789ab.json")));
        assertFalse("but not the answer for the track it shall write anew",
                Files.exists(offer.staging().resolve("bass-0123456789ab.json")));
        assertFalse(Files.exists(offer.staging().resolve("composition.json")));
        compose(offer.staging(), 43);

        Path preview = offer.propose("run-1");
        PieceRendering.Outcome heard = render(preview, offer.proposal().result());

        assertTrue(offer.changesSomething());
        assertEquals("only the new track is rendered for the preview", Arrays.asList("bass"), heard.stems().rendered());
        assertTrue(Files.size(heard.mix()) > 1000);
        assertEquals("nothing of the piece was written", before, snapshot());
        assertEquals(48, session.composition().notesOf("bass").get(0).note().pitch());

        ChangeSet changes = offer.accept();

        assertEquals(Arrays.asList("bass"), new ArrayList<String>(changes.partsToRender()));
        assertEquals(43, session.composition().notesOf("bass").get(0).note().pitch());
        assertEquals(72, session.composition().notesOf("melody").get(0).note().pitch());
        assertTrue(String.join("\n", Files.readAllLines(folder.resolve("arrangement.tsv"))).contains("\t43\t"));
        assertEquals("what was heard is not rendered again", Arrays.asList(),
                render(folder, session.composition()).stems().rendered());
        assertEquals("{\"new\":\"bass\"}", new String(Files.readAllBytes(folder.resolve("bass-ba9876543210.json")),
                StandardCharsets.UTF_8));
        assertFalse("the answer kept so far went aside", Files.exists(folder.resolve("bass-0123456789ab.json")));
        try (Stream<Path> aside = Files.list(folder.resolve("earlier"))) {
            assertEquals(1, aside.count());
        }
        assertFalse(Files.exists(offer.staging()));
        assertFalse(Files.exists(preview));
        assertTrue(session.canUndo());
        assertEquals("the original, and the state the AI made", 2, session.revisions().size());

        session.undo();
        assertEquals(48, session.composition().notesOf("bass").get(0).note().pitch());
        assertEquals("the old stem is still in the cache", Arrays.asList(),
                render(folder, session.composition()).stems().rendered());
    }

    @Test
    public void aDiscardedProposalLeavesNoTrace() throws Exception {
        ProjectSession session = model.session().get();
        TreeMap<String, String> before = snapshot();
        RecompositionOffer offer = RecompositionOffer.stage(session, "bass", "run-2");
        compose(offer.staging(), 43);
        render(offer.propose("run-2"), offer.proposal().result());

        offer.discard();

        assertEquals(before, snapshot());
        assertFalse(session.pending().isPresent());
        assertFalse(session.canUndo());
        assertEquals(1, session.revisions().size());
    }

    @Test
    public void aTrackThePieceDoesNotHaveIsNotStaged() throws Exception {
        try {
            RecompositionOffer.stage(model.session().get(), "choir", "run-3");
            org.junit.Assert.fail();
        } catch (java.io.IOException unknown) {
            assertTrue(unknown.getMessage().contains("choir"));
        }
    }

    /** What the composer does in its folder: the arrangement with a new bass, and its answer for the bass. */
    private static void compose(Path staging, int bassPitch) throws Exception {
        List<String> composed = new ArrayList<String>(PIECE);
        composed.set(4, "note\tbass\t0\t960\t" + bassPitch + "\t70");
        Files.write(staging.resolve("arrangement.tsv"), composed, StandardCharsets.UTF_8);
        Files.write(staging.resolve("bass-ba9876543210.json"), "{\"new\":\"bass\"}".getBytes(StandardCharsets.UTF_8));
    }

    private PieceRendering.Outcome render(Path where, com.aresstack.audio.composition.Composition what)
            throws Exception {
        return rendering.render(where, what, null, Sf2Engine.GERVILL, "fluidsynth", "sfizz_render",
                new HashMap<String, Integer>(), message -> { });
    }

    /** Every file of the piece outside the previews, with its content. */
    private TreeMap<String, String> snapshot() throws Exception {
        TreeMap<String, String> files = new TreeMap<String, String>();
        try (Stream<Path> all = Files.walk(folder)) {
            for (Path file : (Iterable<Path>) all::iterator) {
                String name = folder.relativize(file).toString().replace('\\', '/');
                if (Files.isRegularFile(file) && !name.startsWith("preview/")) {
                    files.put(name, java.util.Base64.getEncoder().encodeToString(
                            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                }
            }
        }
        return files;
    }
}
