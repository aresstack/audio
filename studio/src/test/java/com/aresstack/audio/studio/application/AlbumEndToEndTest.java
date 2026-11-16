package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.List;
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
        final FakeChatModel piece;

        Model() {
            this("C", "G", "Am", "F");
        }

        Model(String... chords) {
            piece = new FakeChatModel(chords);
        }

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
     * The user pressed "Im Album produzieren" on a favourite piece of the unnamed album: the
     * production chose new instruments, wrote them, the stage and other words into the piece, and
     * only then the composer refused it (the piece was planned with the old defaults). The score
     * was empty afterwards. Now a piece planned otherwise is left exactly as it is, the AI is not
     * even asked, and it stays the user's own piece. Making it anew sets all of it aside as a
     * version first, from where it comes back byte for byte.
     */
    @Test
    public void aPieceOfTheUsersIsNeverWrittenOverByTheAlbum() throws Exception {
        Path library = temporary.newFolder("own").toPath();
        SongLibrary songs = new SongLibrary(library);
        AlbumFiles files = new AlbumFiles(library);
        Model model = new Model(CompositionSpec.LEGACY_CHORDS.toArray(new String[0]));
        ModelLineup lineup = ModelLineup.of(new LimitedChatModel(model, new Semaphore(2, true)));
        PieceAutopilot autopilot = new PieceAutopilot(new InstrumentRegistry(library.resolve("none.properties")),
                lineup, 4096, 8, PieceAutopilot.Rendering.BUILT_IN);

        // The user's own piece, composed alone with the old defaults.
        Path own = songs.create("Pastel Bloom City");
        songs.save(own, null, "a cozy city tune");
        PieceModel window = new PieceModel(line -> { });
        window.open(own);
        autopilot.run("a cozy city tune", own, null, null, songs, window, line -> { }, null);
        assertTrue(GemmaMusicComposer.specOf(own).isLegacy());
        List<String> kept = List.of("arrangement.tsv", "tracks.json", "composition.json", "composition-plan.json",
                "instrument-proposal.json", SongLibrary.FILE_NAME, SpatialSceneStore.FILE_NAME, StageMixService.MIX_FILE);
        java.util.Map<String, byte[]> before = new java.util.LinkedHashMap<String, byte[]>();
        for (String name : kept) {
            before.put(name, Files.readAllBytes(own.resolve(name)));
        }

        // What the fault left behind before this was mended: other instruments, their stage and
        // other words beside the notes, which were never touched. The tracks and the stage of the
        // notes come back from the model ("Besetzung aus den Noten zurückholen").
        TrackArrangement lineUp = new TrackArrangementStore().load(own);
        SpatialSceneStore stages = new SpatialSceneStore();
        com.aresstack.audio.studio.stage.SpatialScene stage = stages.load(own);
        List<MusicTrack> others = List.of(new MusicTrack("pad", "harmony", "Warm Pad", 89, "builtin"),
                new MusicTrack("kit", "drums", "Drums", 0, "builtin"));
        new TrackArrangementStore().save(own, new TrackArrangement(others));
        stages.save(own, stage.fitting(others));
        PieceModel damaged = new PieceModel(line -> { });
        damaged.open(own);
        LineUpOfNotes.LineUp back = LineUpOfNotes.of(damaged.session().orElseThrow(), stages.load(own));
        assertEquals(lineUp.tracks().stream().map(MusicTrack::id).toList(),
                back.tracks().stream().map(MusicTrack::id).toList());
        for (int i = 0; i < lineUp.tracks().size(); i++) {
            MusicTrack was = lineUp.tracks().get(i);
            MusicTrack now = back.tracks().get(i);
            assertEquals(was.role(), now.role());
            assertEquals(was.instrument(), now.instrument());
            assertEquals(was.program(), now.program());
            assertEquals(was.libraryId(), now.libraryId());
            assertEquals("where " + was.id() + " stands", stage.placement(was.id()).x(),
                    back.stage().placement(was.id()).x(), 1e-9);
            assertEquals(stage.placement(was.id()).depth(), back.stage().placement(was.id()).depth(), 1e-9);
            assertEquals(stage.placement(was.id()).levelDb(), back.stage().placement(was.id()).levelDb(), 1e-9);
        }
        assertEquals(lineUp.tracks().size(), back.stage().placements().size());
        new TrackArrangementStore().save(own, new TrackArrangement(back.tracks()));
        stages.save(own, back.stage());
        for (String name : kept) {
            before.put(name, Files.readAllBytes(own.resolve(name)));
        }

        // It is a title of an album, with what its plan says about it (as in the unnamed album),
        // and the fault left it as failed: "Fehlende Titel produzieren" takes it up.
        Album album = files.create("Unbenanntes Album", new AlbumConcept("", "", 1, 18));
        AlbumTrack title = new AlbumTrack("t1", "Pastel Bloom City", "playful", 108, "C major", "4/4", "", "", 18,
                own.getFileName().toString());
        files.save(album.withTracks(List.of(title)));
        assertFalse("the same bars, tempo and key, but not bound to the old harmonies",
                title.spec().equals(GemmaMusicComposer.specOf(own)));
        AlbumProducer producer = new AlbumProducer(files, songs, new AutopilotPieceProduction(autopilot, songs), 1);
        producer.state(album.id()).with("t1", Status.FAILED, "Dieses Stück ist als ... geplant", 0)
                .save(files.folder(album.id()));
        int proposals = model.proposals.get();
        int asked = model.piece.prompts.size();
        List<String> told = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

        AlbumProductionState state = producer.start(album.id(), new com.aresstack.audio.studio.album
                .AlbumProductionCoordinator.Listener() {
            @Override
            public void changed(Album now, AlbumProductionState at) {
            }

            @Override
            public void progress(AlbumTrack track, String message) {
                told.add(message);
            }
        }).await();

        assertEquals("it stays the user's own piece, not a failed title", Status.PLANNED, state.status("t1"));
        for (String name : kept) {
            assertTrue(name + " is as it was", java.util.Arrays.equals(before.get(name),
                    Files.readAllBytes(own.resolve(name))));
        }
        assertEquals("the AI is not asked for new instruments", proposals, model.proposals.get());
        assertEquals(asked, model.piece.prompts.size());
        assertTrue(told.toString(), told.stream().anyMatch(line -> line.contains("bleibt, wie es ist")
                && line.contains("Neue Fassung")));
        // A whole album production leaves it alone as well.
        producer.start(album.id(), (now, at) -> { }).await();
        assertTrue(java.util.Arrays.equals(before.get("tracks.json"), Files.readAllBytes(own.resolve("tracks.json"))));

        // "Neue Fassung": all the piece is goes aside, then it is made as the album asks.
        PieceVersions.Version first = producer.again(album.id(), "t1");
        assertNotNull(first);
        for (String name : kept) {
            assertTrue(name + " lies in the version", java.util.Arrays.equals(before.get(name),
                    Files.readAllBytes(first.folder().resolve(name))));
        }
        state = producer.start(album.id(), (now, at) -> { }).await();
        assertEquals(state.of("t1").reason(), Status.DONE, state.status("t1"));
        assertEquals(title.spec(), GemmaMusicComposer.specOf(own));
        assertEquals("the version is untouched by the new production", List.of(first), PieceVersions.list(own));

        // The favourite comes back, byte for byte, and the new one is a version now.
        PieceVersions.Version made = PieceVersions.restore(own, first.id());
        assertNotNull(made);
        for (String name : kept) {
            assertTrue(name + " is back", java.util.Arrays.equals(before.get(name), Files.readAllBytes(own.resolve(name))));
        }
        assertEquals(title.spec(), GemmaMusicComposer.specOf(made.folder()));
        PieceModel again = new PieceModel(line -> { });
        again.open(own);
        assertTrue("and it opens as the piece it was", again.session().isPresent());
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
