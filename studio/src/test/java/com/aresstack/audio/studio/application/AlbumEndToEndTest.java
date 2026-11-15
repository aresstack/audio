package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.instruments.InstrumentRegistry;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelLineup;
import com.aresstack.audio.studio.album.Album;
import com.aresstack.audio.studio.album.AlbumConcept;
import com.aresstack.audio.studio.album.AlbumFiles;
import com.aresstack.audio.studio.album.AlbumProducer;
import com.aresstack.audio.studio.album.AlbumProductionState;
import com.aresstack.audio.studio.album.AlbumProductionState.Status;
import com.aresstack.audio.studio.album.AlbumRoles;
import com.aresstack.audio.studio.album.AlbumTrack;
import com.aresstack.audio.studio.album.AutopilotPieceProduction;
import com.aresstack.audio.studio.album.ChatAlbumPlanner;
import com.aresstack.audio.studio.album.LimitedChatModel;
import com.aresstack.audio.studio.stage.SpatialSceneStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * An album from its concept to its mixed pieces, with a model of a test: the AI plans the list of
 * titles, and every title becomes an ordinary piece by the very pipeline of a single piece (the
 * autopilot of the window), with the length and the meter the list asks for.
 */
public final class AlbumEndToEndTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    /** Answers the album plan and the proposal of the players itself, plan and notes of a piece as the fake model does. */
    private static final class Model implements ChatModel {
        final FakeChatModel piece = new FakeChatModel("C", "G", "Am", "F");
        final AtomicInteger atOnce = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final AtomicInteger proposals = new AtomicInteger();

        @Override
        public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) throws IOException {
            most.accumulateAndGet(atOnce.incrementAndGet(), Math::max);
            try {
                String answer;
                if (ChatAlbumPlanner.INSTRUCTION.equals(instruction)) {
                    answer = "{\"tracks\":[{\"title\":\"Christmas Overture\",\"mood\":\"festive\",\"tempoBpm\":120,"
                            + "\"key\":\"D major\",\"meter\":\"4/4\",\"instrumentation\":\"orchestra\",\"targetSeconds\":16},"
                            + "{\"title\":\"Winter Waltz\",\"mood\":\"lively\",\"tempoBpm\":90,\"key\":\"G major\","
                            + "\"meter\":\"3/4\",\"instrumentation\":\"strings\",\"targetSeconds\":16}]}";
                } else if (prompt.startsWith("Suggest an ORIGINAL instrumental arrangement")) {
                    proposals.incrementAndGet();
                    answer = "{\"tracks\":[{\"id\":\"lead\",\"role\":\"melody\",\"instrument\":\"Flute\",\"program\":73},"
                            + "{\"id\":\"low\",\"role\":\"bass\",\"instrument\":\"Bass\",\"program\":32}]}";
                } else {
                    return piece.generate(instruction, prompt, maxTokens, cache);
                }
                if (cache != null && Files.isRegularFile(cache)) {
                    return JsonParser.parseString(new String(Files.readAllBytes(cache), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                }
                if (cache != null) {
                    Files.createDirectories(cache.getParent());
                    Files.write(cache, answer.getBytes(StandardCharsets.UTF_8));
                }
                return JsonParser.parseString(answer).getAsJsonObject();
            } finally {
                atOnce.decrementAndGet();
            }
        }
    }

    @Test
    public void anAlbumIsPlannedAndEveryTitleBecomesAPieceOfTheLibrary() throws Exception {
        Path library = temporary.newFolder("compositions").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        Model model = new Model();
        // All models of the production share two permits: never more than two requests at once, over both pieces.
        Semaphore permits = new Semaphore(2, true);
        ModelLineup lineup = ModelLineup.of(new LimitedChatModel(model, permits));

        Album album = files.create("Christmas Memories", new AlbumConcept("A Christmas album", "orchestral", 2, 16));
        album = new ChatAlbumPlanner(lineup.model(AlbumRoles.ALBUM), 4096)
                .plan(album, files.folder(album.id()), line -> { });
        files.save(album);
        assertEquals(2, album.tracks().size());
        assertEquals("16 s × 120 BPM ÷ 240", 8, album.tracks().get(0).spec().bars());
        assertEquals("16 s × 90 BPM ÷ 180", 8, album.tracks().get(1).spec().bars());

        PieceAutopilot autopilot = new PieceAutopilot(new InstrumentRegistry(library.resolve("none.properties")),
                lineup, 4096, 8, PieceAutopilot.Rendering.BUILT_IN);
        AlbumProducer producer = new AlbumProducer(files, songs, new AutopilotPieceProduction(autopilot, songs), 2);
        AlbumProductionState state = producer.start(album.id(), (now, at) -> { }).await();

        Album produced = files.album(album.id()).orElseThrow();
        for (AlbumTrack track : produced.tracks()) {
            assertEquals(track.title() + ": " + state.of(track.id()).reason(), Status.DONE, state.status(track.id()));
            Path piece = library.resolve(track.piece());
            assertTrue(Files.isRegularFile(piece.resolve("arrangement.tsv")));
            assertTrue(Files.isRegularFile(piece.resolve(StageMixService.MIX_FILE)));
            assertTrue("the stage the AI proposed is kept with the piece",
                    Files.isRegularFile(piece.resolve(SpatialSceneStore.FILE_NAME)));
            assertEquals("composed with what the list asks for", track.spec(), GemmaMusicComposer.specOf(piece));
            assertEquals(2, new TrackArrangementStore().load(piece).tracks().size());
            assertTrue("the piece is in the model, as after composing in the window",
                    Files.isRegularFile(piece.resolve("composition.json")));
            // The mix is as long as the piece and its reverb tail: within the tolerance of the list.
            double seconds = state.of(track.id()).seconds();
            assertTrue(track.title() + " lasts " + seconds, seconds >= 16 && seconds < 16 + 8);
        }
        assertEquals("3/4", GemmaMusicComposer.specOf(library.resolve(produced.tracks().get(1).piece())).meter());
        assertEquals(2, songs.songs().size());
        assertEquals(2, model.proposals.get());
        assertTrue("requests of both pieces together stay within the shared limit: " + model.most.get(),
                model.most.get() <= 2);

        // Started again, nothing is asked and nothing is made: the album is finished.
        int asked = model.piece.prompts.size();
        producer.start(album.id(), (now, at) -> { }).await();
        assertEquals(asked, model.piece.prompts.size());
        assertEquals(2, model.proposals.get());
        // A piece made once more (as after an interruption) renders from its model and keeps the
        // stems that are still right: nothing is synthesized a second time.
        AlbumTrack first = produced.tracks().get(0);
        Path folder = library.resolve(first.piece());
        PieceModel piece = new PieceModel(line -> { });
        piece.open(folder);
        java.util.List<String> said = new java.util.ArrayList<String>();
        autopilot.run(produced.brief(first), folder, first.spec(), null, songs, piece,
                line -> {
                    synchronized (said) {
                        said.add(line);
                    }
                }, null);
        assertTrue("the stems come from the stem cache: " + said,
                said.stream().anyMatch(line -> line.contains("unverändert übernommen")));
        assertEquals("and the model is not asked again", asked, model.piece.prompts.size());
    }

    /**
     * PO findings on PR #41 and #44: a production that is cancelled while a piece is being rendered
     * (composed, one stem made, the other not) goes on with the same producer in the same studio.
     * The run after the cancel gets models of its own (the lineup of the first run is stopped for
     * good), the piece is finished in the same folder, the stem that was made is not synthesized a
     * second time, and the title is DONE only then.
     */
    @Test
    public void aProductionCancelledWhileRenderingGoesOnAndKeepsTheStemsItHas() throws Exception {
        Path library = temporary.newFolder("interrupted").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        Model model = new Model();
        Semaphore permits = new Semaphore(2, true);
        Album album = files.create("One Piece", new AlbumConcept("A Christmas album", "orchestral", 1, 16));
        album = new ChatAlbumPlanner(model, 4096).plan(album, files.folder(album.id()), line -> { });
        files.save(album);
        AlbumTrack title = album.tracks().get(0);

        java.util.List<ModelLineup> lineups = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        InstrumentRegistry registry = new InstrumentRegistry(library.resolve("none.properties"));
        // As the page does: every start makes its autopilot with a lineup of its own; the permits are shared.
        AlbumProducer producer = new AlbumProducer(files, songs, () -> {
            ModelLineup lineup = ModelLineup.of(model).through(each -> new LimitedChatModel(each, permits));
            lineups.add(lineup);
            return new AutopilotPieceProduction(
                    new PieceAutopilot(registry, lineup, 4096, 8, PieceAutopilot.Rendering.BUILT_IN), songs);
        }, 2);

        java.util.List<String> firstRun = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<Status> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        com.aresstack.audio.studio.album.AlbumProductionCoordinator.Production[] running =
                new com.aresstack.audio.studio.album.AlbumProductionCoordinator.Production[1];
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        running[0] = producer.start(album.id(), new com.aresstack.audio.studio.album.AlbumProductionCoordinator.Listener() {
            @Override
            public void changed(Album now, AlbumProductionState at) {
                seen.add(at.status(title.id()));
            }

            @Override
            public void progress(AlbumTrack track, String message) {
                firstRun.add(message);
                if (message.startsWith("Rendere low mit ")) {
                    // The user presses "Abbrechen" while the second stem is being made.
                    try {
                        started.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    running[0].cancel();
                    throw new java.util.concurrent.CancellationException("cancelled while rendering");
                }
            }
        });
        started.countDown();
        AlbumProductionState stopped = running[0].await();
        assertEquals("cancelled, not failed, and not finished: " + stopped.of(title.id()).reason(), Status.QUEUED,
                stopped.status(title.id()));
        assertTrue("never DONE before it was finished: " + seen, !seen.contains(Status.DONE));
        assertTrue(firstRun.toString(), firstRun.stream().anyMatch(line -> line.startsWith("Rendere lead mit ")));
        Path folder = library.resolve(files.album(album.id()).orElseThrow().tracks().get(0).piece());
        assertTrue("composed before the cancel", Files.isRegularFile(folder.resolve("arrangement.tsv")));
        int asked = model.piece.prompts.size();

        // start -> cancel -> await -> start with the same producer.
        java.util.List<String> secondRun = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AlbumProductionState done = producer.start(album.id(),
                new com.aresstack.audio.studio.album.AlbumProductionCoordinator.Listener() {
                    @Override
                    public void changed(Album now, AlbumProductionState at) {
                    }

                    @Override
                    public void progress(AlbumTrack track, String message) {
                        secondRun.add(message);
                    }
                }).await();
        assertEquals(done.of(title.id()).reason(), Status.DONE, done.status(title.id()));
        assertEquals("a lineup per run", 2, lineups.size());
        assertEquals("finished in the same folder", folder,
                library.resolve(files.album(album.id()).orElseThrow().tracks().get(0).piece()));
        assertEquals(1, songs.songs().size());
        assertTrue("the stem that was made is not synthesized again: " + secondRun,
                secondRun.stream().noneMatch(line -> line.startsWith("Rendere lead mit ")));
        assertTrue("only the missing stem is made: " + secondRun,
                secondRun.stream().anyMatch(line -> line.startsWith("Rendere low mit "))
                        && secondRun.stream().anyMatch(line -> line.startsWith("Rendere 1 von 2 Spuren"))
                        && secondRun.stream().anyMatch(line -> line.contains("1 Spuren neu gerendert, 1 unverändert")));
        assertEquals("the answers of the models are there from the first run", asked, model.piece.prompts.size());
        assertTrue(Files.isRegularFile(folder.resolve(StageMixService.MIX_FILE)));
        assertTrue(Files.isRegularFile(folder.resolve(PieceRendering.MIDI_FILE)));
    }
}
