package com.aresstack.audio.studio.album;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.album.AlbumProductionState.Status;
import com.aresstack.audio.studio.application.SongLibrary;
import com.aresstack.audio.studio.progress.Progress;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Albums, production: every title gets its piece through one interface, a few at a time; a piece
 * that fails does not stop the others; and after an interruption the next start goes on where
 * the album stood.
 */
public final class AlbumProducerTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Path library;
    private SongLibrary songs;
    private AlbumFiles files;

    /** Makes pieces as a test says: counts what runs at once, fails or waits on demand. */
    private static final class Pieces implements PieceProduction {
        final List<PieceOrder> orders = Collections.synchronizedList(new ArrayList<PieceOrder>());
        final AtomicInteger atOnce = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        volatile String failing = "";
        volatile CountDownLatch gate;
        volatile boolean cancelled;

        @Override
        public ProducedPiece produce(PieceOrder order, Progress progress) throws Exception {
            orders.add(order);
            int now = atOnce.incrementAndGet();
            most.accumulateAndGet(now, Math::max);
            try {
                progress.update("composing " + order.name());
                Thread.sleep(30);
                if (gate != null && !gate.await(20, TimeUnit.SECONDS)) {
                    throw new IOException("the test never opened the gate");
                }
                if (cancelled) {
                    throw new IOException("cancelled");
                }
                if (order.name().equals(failing)) {
                    throw new IOException("Die KI hat keine Noten geliefert.");
                }
                Path mix = order.folder().resolve("arrangement-instruments.wav");
                Files.write(mix, order.name().getBytes(StandardCharsets.UTF_8));
                return new ProducedPiece(order.folder(), mix, order.spec().seconds());
            } finally {
                atOnce.decrementAndGet();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            CountDownLatch open = gate;
            if (open != null) {
                open.countDown();
            }
        }
    }

    @Before
    public void aLibrary() throws Exception {
        library = temporary.newFolder("compositions").toPath();
        songs = new SongLibrary(library);
        files = new AlbumFiles(library);
    }

    private Album album(int tracks) throws IOException {
        Album album = files.create("Christmas Memories", new AlbumConcept("A Christmas album", "orchestral", tracks, 60));
        List<AlbumTrack> list = new ArrayList<AlbumTrack>();
        for (int i = 1; i <= tracks; i++) {
            list.add(new AlbumTrack("t" + i, "Track " + i, "mood " + i, 120, "C major", "4/4", "strings", "", 60, ""));
        }
        album = album.withTracks(list);
        files.save(album);
        return album;
    }

    private static final AlbumProductionCoordinator.Listener QUIET = (album, state) -> { };

    @Test
    public void everyTitleGetsItsPieceAFewAtATimeAndOneFailureDoesNotStopTheOthers() throws Exception {
        Album album = album(6);
        Pieces pieces = new Pieces();
        pieces.failing = "Track 3";
        AlbumProducer producer = new AlbumProducer(files, songs, pieces, 2);
        List<String> told = Collections.synchronizedList(new ArrayList<String>());

        AlbumProductionState state = producer.start(album.id(), new AlbumProductionCoordinator.Listener() {
            @Override
            public void changed(Album now, AlbumProductionState at) {
            }

            @Override
            public void progress(AlbumTrack track, String message) {
                told.add(track.id() + " " + message);
            }
        }).await();

        Album produced = files.album(album.id()).orElseThrow();
        assertEquals(5, state.count(produced, Status.DONE));
        assertEquals(Status.FAILED, state.status("t3"));
        assertEquals("Die KI hat keine Noten geliefert.", state.of("t3").reason());
        assertEquals("30 bars of 4/4 at 120 BPM", 60, state.of("t1").seconds(), 1e-9);
        assertEquals("never more pieces at once than allowed", 2, pieces.most.get());
        assertTrue(told.contains("t5 composing Track 5"));
        assertEquals("what was written is what is kept", state.status("t3"), producer.state(album.id()).status("t3"));

        // Every title has an ordinary piece of the library with its name, and the piece was told about its album.
        assertEquals(6, songs.songs().size());
        for (AlbumTrack track : produced.tracks()) {
            assertTrue(track.hasPiece());
            assertEquals(track.title(), songs.name(library.resolve(track.piece())));
        }
        PieceProduction.PieceOrder second = pieces.orders.stream().filter(o -> o.name().equals("Track 2"))
                .findFirst().orElseThrow();
        assertEquals(30, second.spec().bars());
        assertTrue(second.description(), second.description().startsWith("Track 2: mood 2")
                && second.description().contains("track 2 of 6") && second.description().contains("\"Track 5\""));
        assertEquals(second.description(), songs.description(second.folder()));

        // Started again: only the failed title is made, in the folder it has; the finished ones are not touched.
        pieces.failing = "";
        pieces.orders.clear();
        Path changedByUser = library.resolve(produced.track("t1").piece()).resolve("arrangement-instruments.wav");
        Files.write(changedByUser, "edited in the editor".getBytes(StandardCharsets.UTF_8));
        AlbumProductionState again = producer.start(album.id(), QUIET).await();
        assertEquals(6, again.count(produced, Status.DONE));
        assertEquals(List.of("Track 3"), pieces.orders.stream().map(PieceProduction.PieceOrder::name).toList());
        assertEquals(library.resolve(produced.track("t3").piece()), pieces.orders.get(0).folder());
        assertEquals("edited in the editor", new String(Files.readAllBytes(changedByUser), StandardCharsets.UTF_8));
        assertEquals(6, songs.songs().size());

        // Only if the user asks for it is a finished piece made again.
        pieces.orders.clear();
        producer.again(album.id(), "t1");
        producer.start(album.id(), QUIET).await();
        assertEquals(List.of("Track 1"), pieces.orders.stream().map(PieceProduction.PieceOrder::name).toList());
    }

    /**
     * PO finding on PR #41: after "Abbrechen" the same producer goes on in the same studio. The
     * production of pieces of a run is stopped for good by its cancel (as a model lineup is), so
     * every start gets one of its own; the limit of pieces at once holds in every run.
     */
    @Test
    public void afterCancelTheSameProducerGoesOnWithAFreshProductionOfPieces() throws Exception {
        Album album = album(4);
        List<Pieces> made = Collections.synchronizedList(new ArrayList<Pieces>());
        AlbumProducer producer = new AlbumProducer(files, songs, () -> {
            Pieces pieces = new Pieces();
            pieces.gate = made.isEmpty() ? new CountDownLatch(1) : null;
            made.add(pieces);
            return pieces;
        }, 2);
        AlbumProductionCoordinator.Production production = producer.start(album.id(), QUIET);
        Pieces first = made.get(0);
        for (long until = System.nanoTime() + 10_000_000_000L; first.orders.size() < 2; ) {
            assertTrue(System.nanoTime() < until);
            Thread.sleep(5);
        }
        production.cancel();
        AlbumProductionState stopped = production.await();
        Album begun = files.album(album.id()).orElseThrow();
        assertEquals(4, stopped.count(begun, Status.QUEUED));
        assertTrue("the production of the first run is stopped for good", first.cancelled);
        List<Path> begunFolders = first.orders.stream().map(PieceProduction.PieceOrder::folder).toList();

        // start -> cancel -> await -> start, same producer, no new studio.
        AlbumProductionState done = producer.start(album.id(), QUIET).await();
        assertEquals(2, made.size());
        Pieces second = made.get(1);
        assertFalse(second.cancelled);
        assertEquals("nothing failed: " + done.of("t1").reason(), 4, done.count(begun, Status.DONE));
        assertEquals(4, second.orders.size());
        assertTrue("the pieces that were begun are finished in their own folders",
                second.orders.stream().map(PieceProduction.PieceOrder::folder).toList().containsAll(begunFolders));
        assertEquals(4, songs.songs().size());
        assertTrue(first.most.get() <= 2 && second.most.get() <= 2);

        // And once more: cancelled and started a third time, nothing is left to do.
        producer.start(album.id(), QUIET).await();
        assertEquals(3, made.size());
        assertEquals(0, made.get(2).orders.size());
    }

    /** PO finding on PR #39: a piece folder that leads out of the library is never opened or written. */
    @Test
    public void aTitleNeverWritesOutsideTheLibrary() throws Exception {
        Album album = album(1);
        Path outside = temporary.newFolder("outside").toPath();
        Path file = files.folder(album.id()).resolve(AlbumFiles.FILE_NAME);
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
        json.getAsJsonArray("tracks").get(0).getAsJsonObject().addProperty("piece", "../outside");
        Files.write(file, json.toString().getBytes(StandardCharsets.UTF_8));
        Pieces pieces = new Pieces();
        AlbumProductionState done = new AlbumProducer(files, songs, pieces, 1).start(album.id(), QUIET).await();
        assertEquals(Status.DONE, done.status("t1"));
        Path used = pieces.orders.get(0).folder();
        assertEquals("the piece got a folder of its own in the library", library.toAbsolutePath().normalize(),
                used.toAbsolutePath().normalize().getParent());
        try (Stream<Path> left = Files.list(outside)) {
            assertEquals("nothing was written outside", 0, left.count());
        }
    }

    @Test
    public void anInterruptedProductionGoesOnWhereItStood() throws Exception {
        Album album = album(4);
        Pieces pieces = new Pieces();
        pieces.gate = new CountDownLatch(1);
        AlbumProducer producer = new AlbumProducer(files, songs, pieces, 1);
        AlbumProductionCoordinator.Production production = producer.start(album.id(), QUIET);
        for (long until = System.nanoTime() + 10_000_000_000L; pieces.orders.isEmpty(); ) {
            assertTrue(System.nanoTime() < until);
            Thread.sleep(5);
        }
        assertTrue(production.running());
        assertEquals("the title in work is written as such", Status.RUNNING, producer.state(album.id()).status("t1"));
        try {
            producer.start(album.id(), QUIET);
            org.junit.Assert.fail("an album is produced once at a time");
        } catch (IllegalStateException expected) {
            // as it should be
        }

        production.cancel();
        AlbumProductionState stopped = production.await();
        assertFalse(production.running());
        Album begun = files.album(album.id()).orElseThrow();
        assertEquals("nothing failed: what was cancelled waits", 0, stopped.count(begun, Status.FAILED));
        assertEquals(4, stopped.count(begun, Status.QUEUED));
        assertEquals(1, pieces.orders.size());
        Path first = pieces.orders.get(0).folder();

        // As after a crash: the state on disk says a title was running. A new producer takes it up in the same folder.
        stopped.with("t1", Status.RUNNING).save(files.folder(album.id()));
        Pieces fresh = new Pieces();
        AlbumProductionState done = new AlbumProducer(new AlbumFiles(library), new SongLibrary(library), fresh, 2)
                .start(album.id(), QUIET).await();
        assertEquals(4, done.count(begun, Status.DONE));
        assertEquals(4, fresh.orders.size());
        assertTrue("the piece that was begun is finished in its own folder",
                fresh.orders.stream().anyMatch(order -> order.folder().equals(first)));
        assertEquals(4, songs.songs().size());

        // A finished piece whose folder is gone is made again; the others stay.
        Album finished = files.album(album.id()).orElseThrow();
        try (Stream<Path> gone = Files.walk(library.resolve(finished.track("t2").piece()))) {
            for (Path file : gone.sorted(Collections.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
        Pieces last = new Pieces();
        new AlbumProducer(files, songs, last, 2).start(album.id(), QUIET).await();
        assertEquals(List.of("Track 2"), last.orders.stream().map(PieceProduction.PieceOrder::name).toList());
    }

    @Test
    public void allPiecesTogetherAskTheModelOnlySoOftenAtOnce() throws Exception {
        AtomicInteger atOnce = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        ChatModel slow = new ChatModel() {
            @Override
            public JsonObject generate(String instruction, String prompt, int maxTokens, Path cache) {
                most.accumulateAndGet(atOnce.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                atOnce.decrementAndGet();
                return new JsonObject();
            }
        };
        ChatModel shared = new LimitedChatModel(slow, 3);
        List<Thread> askers = new ArrayList<Thread>();
        for (int piece = 0; piece < 4; piece++) {
            for (int request = 0; request < 5; request++) {
                Thread asker = new Thread(() -> {
                    try {
                        shared.generate("i", "p", 16, null);
                    } catch (IOException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                });
                askers.add(asker);
                asker.start();
            }
        }
        for (Thread asker : askers) {
            asker.join();
        }
        assertEquals(3, most.get());
    }

    @Test
    public void theAlbumKnowsNeitherARendererNorAComposer() throws Exception {
        // The production reaches a piece through PieceProduction only (design note, section 3).
        List<String> forbidden = List.of("GemmaMusicComposer", "StudioMusicRenderer", "RenderService", "StageMixService",
                "InstrumentProposalService", "StemRenderer", "SpatialStemMixer", "javax.swing");
        try (Stream<Path> sources = Files.list(Paths.get("src", "main", "java", "com", "aresstack", "audio", "studio",
                "album"))) {
            for (Path source : sources.toList()) {
                String name = source.getFileName().toString();
                if (name.startsWith("Autopilot")) {
                    continue; // the one adapter that calls the pipeline of a single piece
                }
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                for (String word : forbidden) {
                    assertFalse(name + " names " + word, text.contains(word));
                }
            }
        }
    }
}
