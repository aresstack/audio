package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.album.AlbumProductionState.Status;
import com.aresstack.audio.studio.application.SongLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The production of albums: every title that has no finished piece gets one, a few at a time.
 *
 * <p>A piece that fails is noted and the others go on. Every change of a title is written at
 * once, so that after an interruption (a cancel, a crash, the end of the program) the next start
 * takes up what is left: a finished piece is not made again, and a piece that was in the middle
 * is asked for again in its own folder, where {@link PieceProduction} finds what it had made.
 *
 * <p>The production writes only into pieces it made itself and that are not finished. A finished
 * piece, which the user may have changed in the editor since, is touched again only if the user
 * asks for exactly that ({@link #again}).
 */
public final class AlbumProducer implements AlbumProductionCoordinator {
    private final AlbumFiles albums;
    private final SongLibrary songs;
    private final java.util.function.Supplier<PieceProduction> perRun;
    private final int atOnce;
    private final Set<String> running = new HashSet<String>();

    /**
     * A producer whose every start gets a production of pieces of its own. Cancelling a run stops
     * the models of that run for good ({@code ModelLineup.cancel()}), so a later start in the same
     * studio must not get them again: it asks {@code perRun} for a fresh production, with fresh
     * models, and goes on where the queue stood.
     *
     * @param perRun makes the production of pieces of one run; called once per {@link #start}, on
     *               the thread that starts
     * @param atOnce how many pieces are made at the same time
     */
    public AlbumProducer(AlbumFiles albums, SongLibrary songs, java.util.function.Supplier<PieceProduction> perRun,
                         int atOnce) {
        if (atOnce < 1) {
            throw new IllegalArgumentException("Mindestens ein Stück zugleich.");
        }
        this.albums = albums;
        this.songs = songs;
        this.perRun = java.util.Objects.requireNonNull(perRun, "perRun");
        this.atOnce = atOnce;
    }

    /**
     * A producer that uses one production of pieces for every start: only for a production that
     * can be used again after {@link PieceProduction#cancel()}.
     */
    public AlbumProducer(AlbumFiles albums, SongLibrary songs, PieceProduction pieces, int atOnce) {
        this(albums, songs, () -> pieces, atOnce);
    }

    /**
     * The folder of the piece of a title, directly in the library; never anything else, whatever
     * the album file says.
     */
    private Path pieceFolder(AlbumTrack track) throws IOException {
        Path root = songs.root().toAbsolutePath().normalize();
        Path folder = root.resolve(track.piece()).normalize();
        if (!AlbumTrack.isFolderName(track.piece()) || !root.equals(folder.getParent())) {
            throw new IOException("Kein Ordner eines Stücks in der Bibliothek: " + track.piece());
        }
        return folder;
    }

    private boolean pieceThere(AlbumTrack track) {
        try {
            return track.hasPiece() && Files.isDirectory(pieceFolder(track));
        } catch (IOException outside) {
            return false;
        }
    }

    /** How the production of an album stands, as it is kept. */
    public AlbumProductionState state(String albumId) {
        return AlbumProductionState.load(albums.folder(albumId));
    }

    /**
     * The user wants the piece of a title made again, also if it is finished: it waits for the
     * next start. Its folder stays; what is in it is used as far as it still fits.
     */
    public void again(String albumId, String trackId) throws IOException {
        synchronized (running) {
            if (running.contains(albumId)) {
                throw new IllegalStateException("Dieses Album wird gerade produziert.");
            }
            state(albumId).with(trackId, Status.QUEUED).save(albums.folder(albumId));
        }
    }

    @Override
    public Production start(String albumId, Listener listener) throws IOException {
        return start(albumId, listener, null);
    }

    /**
     * Starts the production of one title alone ({@code onlyTrack}), as "Titel neu produzieren"
     * asks; the other titles keep their state. {@code null}: every title that is not finished.
     */
    public Production start(String albumId, Listener listener, String onlyTrack) throws IOException {
        Album album = albums.album(albumId)
                .orElseThrow(() -> new IOException("Das Album \"" + albumId + "\" lässt sich nicht lesen."));
        synchronized (running) {
            if (!running.add(albumId)) {
                throw new IllegalStateException("Dieses Album wird gerade produziert.");
            }
        }
        try {
            Run run = new Run(album, listener, onlyTrack);
            run.begin();
            return run;
        } catch (IOException | RuntimeException failed) {
            synchronized (running) {
                running.remove(albumId);
            }
            throw failed;
        }
    }

    private final class Run implements Production {
        private final Listener listener;
        private final Path folder;
        private final Object lock = new Object();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicInteger open = new AtomicInteger();
        private Album album;
        private AlbumProductionState state;
        private volatile boolean cancelled;
        private ExecutorService workers;
        /** The production of pieces of this run alone; cancelling it does not reach the next run. */
        private final PieceProduction pieces = java.util.Objects.requireNonNull(perRun.get(),
                "the production of pieces of a run");

        /** The one title this run makes, or {@code null} for all that are not finished. */
        private final String only;

        Run(Album album, Listener listener, String only) {
            this.only = only;
            this.album = album;
            this.listener = listener;
            this.folder = albums.folder(album.id());
        }

        void begin() throws IOException {
            List<AlbumTrack> waiting = new ArrayList<AlbumTrack>();
            synchronized (lock) {
                state = AlbumProductionState.load(folder);
                for (AlbumTrack track : album.tracks()) {
                    if (only != null && !only.equals(track.id())) {
                        // Another title of the album: left as it is, but a "running" left from a run
                        // that ended without it is not true now (no run of this album goes on): it waits.
                        if (state.of(track.id()).status() == Status.RUNNING) {
                            state = state.with(track.id(), Status.QUEUED);
                        }
                        continue;
                    }
                    AlbumProductionState.Entry entry = state.of(track.id());
                    boolean there = pieceThere(track);
                    if (entry.status() == Status.DONE && there) {
                        continue; // finished, and maybe changed by the user since: not touched
                    }
                    if (entry.status() == Status.PLANNED && there) {
                        // A piece the user put into the album by hand: it is theirs, not made here.
                        continue;
                    }
                    // Planned, waiting, failed, interrupted while running, or finished but its piece is gone.
                    state = state.with(track.id(), Status.QUEUED);
                    waiting.add(track);
                }
                state.save(folder);
            }
            listener.changed(album, state);
            if (waiting.isEmpty()) {
                end();
                return;
            }
            open.set(waiting.size());
            workers = Executors.newFixedThreadPool(Math.min(atOnce, waiting.size()), task -> {
                Thread thread = new Thread(task, "album-" + album.id());
                thread.setDaemon(true);
                return thread;
            });
            for (AlbumTrack track : waiting) {
                workers.submit(() -> {
                    try {
                        produce(track.id());
                    } finally {
                        if (open.decrementAndGet() == 0) {
                            end();
                        }
                    }
                });
            }
            workers.shutdown();
        }

        private void produce(String trackId) {
            if (cancelled) {
                return; // stays queued for the next start
            }
            AlbumTrack track;
            Path piece;
            try {
                synchronized (lock) {
                    track = album.track(trackId);
                    if (!pieceThere(track)) {
                        Path made = songs.create(track.title());
                        track = track.withPiece(made.getFileName().toString());
                        // The album as the file has it now: the window may have put a piece into it meanwhile.
                        Album now = albums.album(album.id()).orElse(album);
                        album = (now.track(trackId) != null ? now : album).with(track);
                        albums.save(album); // first the album knows its piece, then the piece is filled
                    }
                    piece = pieceFolder(track);
                    // Notes given for the title bind the piece: it is made from them, not composed freely.
                    new AlbumTemplates(albums).intoPiece(album.id(), trackId, piece);
                    // The piece keeps its own words; what it is told about its album is added for the AI only.
                    songs.save(piece, track.title(), own(track));
                    set(trackId, Status.RUNNING, "", 0);
                }
            } catch (IOException | RuntimeException failed) {
                fail(trackId, failed);
                return;
            }
            AlbumTrack told = track;
            try {
                // Everything the AI streams for the piece can be shown where the piece is open.
                com.aresstack.audio.studio.progress.Progress progress = listener.watching(told, piece,
                        message -> listener.progress(told, message));
                PieceProduction.ProducedPiece produced = pieces.produce(new PieceProduction.PieceOrder(piece,
                        track.title(), description(album, track), track.spec()), progress);
                if (produced == null || produced.mix() == null || !Files.isRegularFile(produced.mix())) {
                    throw new IOException("Das Stück hat keine gemischte Datei.");
                }
                synchronized (lock) {
                    set(trackId, Status.DONE, "", produced.seconds());
                }
            } catch (Exception failed) {
                if (cancelled) {
                    synchronized (lock) {
                        try {
                            set(trackId, Status.QUEUED, "", 0); // not its fault: it goes on at the next start
                        } catch (IOException unwritten) {
                            // The state on disk says RUNNING, which the next start takes as waiting as well.
                        }
                    }
                    if (failed instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    fail(trackId, failed);
                }
            }
        }

        private void fail(String trackId, Exception failed) {
            String reason = failed.getMessage() == null || failed.getMessage().isBlank()
                    ? failed.getClass().getSimpleName() : failed.getMessage();
            // The protocol says every failure with its whole cause, not only the table.
            for (AlbumTrack each : album.tracks()) {
                if (each.id().equals(trackId)) {
                    try {
                        listener.progress(each, "Fehler: " + causes(failed));
                    } catch (RuntimeException unheard) {
                        // telling it must not keep the title from being marked as failed
                    }
                }
            }
            synchronized (lock) {
                try {
                    set(trackId, Status.FAILED, reason, 0);
                } catch (IOException unwritten) {
                    state = state.with(trackId, Status.FAILED, reason, 0);
                    listener.changed(album, state);
                }
            }
        }

        /** A failure and every cause behind it, with where the first one happened in the code. */
        private String causes(Throwable failed) {
            StringBuilder said = new StringBuilder();
            Throwable deepest = failed;
            for (Throwable at = failed; at != null && said.length() < 4000; at = at.getCause()) {
                said.append(said.length() == 0 ? "" : " · Ursache: ").append(at);
                deepest = at;
                if (at.getCause() == at) {
                    break;
                }
            }
            StackTraceElement[] where = deepest.getStackTrace();
            if (where.length > 0) {
                said.append(" (bei ").append(where[0]).append(")");
            }
            return said.toString();
        }

        /** Under the lock: notes where a title stands, writes it, and tells the listener. */
        private void set(String trackId, Status status, String reason, double seconds) throws IOException {
            state = state.with(trackId, status, reason, seconds);
            state.save(folder);
            listener.changed(album, state);
        }

        private void end() {
            synchronized (running) {
                running.remove(album.id());
            }
            finished.countDown();
        }

        @Override
        public void cancel() {
            cancelled = true;
            // The titles that still wait take their turn and leave at once (they stay queued);
            // what is running is stopped by the production of pieces itself.
            pieces.cancel();
        }

        @Override
        public boolean running() {
            return finished.getCount() > 0;
        }

        @Override
        public AlbumProductionState await() throws InterruptedException {
            finished.await();
            synchronized (lock) {
                return state;
            }
        }
    }

    /** The music wish of a piece: its own title and mood first, then what it is told about its album. */
    static String description(Album album, AlbumTrack track) {
        return own(track) + "\n" + album.brief(track);
    }

    /** The piece's own words: its title, mood and players, as its description shows them. */
    static String own(AlbumTrack track) {
        StringBuilder text = new StringBuilder(track.title());
        if (!track.mood().isEmpty()) {
            text.append(": ").append(track.mood());
        }
        if (!track.instrumentation().isEmpty()) {
            text.append(". Players: ").append(track.instrumentation());
        }
        return text.append('.').toString();
    }
}
