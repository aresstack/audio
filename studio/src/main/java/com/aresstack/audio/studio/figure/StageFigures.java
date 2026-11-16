package com.aresstack.audio.studio.figure;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The figures on the stage of one piece: which figure stands at which track, taken from the
 * {@link FigureLibrary} first and drawn by the AI only where the library has none left. What
 * the AI draws stands on the stage at once and goes into the library; every change of the cast
 * can be taken back ({@link #undo}). The drawing runs on a thread of its own, so the studio
 * stays free for other work. No Swing here: the {@link Listener} is told on that thread.
 */
public final class StageFigures {
    /** Told what changed; called on the thread that changed it. */
    public interface Listener {
        /** The cast changed: figures to show anew. */
        void cast(FigureCast cast);

        /** What is going on, in a few words for the status line. */
        void said(String text);

        /** Whether figures are being drawn. */
        void busy(boolean busy);
    }

    private final FigureLibrary library;
    private final Listener listener;
    private final ExecutorService drawing = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "stage-figures");
        thread.setDaemon(true);
        return thread;
    });
    private final Deque<FigureCast> undo = new ArrayDeque<>();
    private Path directory;
    private List<FigureCast.Seat> seats = List.of();
    private FigureCast cast = FigureCast.EMPTY;
    private int running;

    public StageFigures(FigureLibrary library, Listener listener) {
        this.library = library;
        this.listener = listener;
    }

    public FigureLibrary library() {
        return library;
    }

    /**
     * Shows the figures of a piece: those it keeps, and for tracks without one a figure of the
     * library, picked at random but always the same for the same track of the same piece. Nothing
     * is written and nothing is drawn by this.
     */
    public synchronized void open(Path pieceDirectory, List<FigureCast.Seat> pieceSeats) {
        boolean other = !Objects.equals(pieceDirectory, directory);
        if (other) {
            undo.clear();
            cast = FigureCast.of(pieceDirectory);
        }
        directory = pieceDirectory;
        seats = List.copyOf(pieceSeats);
        FigureCast shown = cast.cast(seats, library, new Random(seed()));
        if (other || !shown.equals(cast)) {
            cast = shown;
            listener.cast(cast);
        }
    }

    /** The figure of a track as a picture, or {@code null} if it has none. */
    public BufferedImage picture(String trackId) {
        String figure;
        synchronized (this) {
            figure = cast.figure(trackId);
        }
        return library.picture(figure);
    }

    public synchronized FigureCast cast() {
        return cast;
    }

    public synchronized boolean canUndo() {
        return !undo.isEmpty();
    }

    /**
     * Gives every track a figure: from the library where it has a free one, and for the others
     * the AI draws one, track by track; each stands on the stage as soon as it is drawn.
     */
    public void drawMissing(Supplier<FigureArtist> artist) {
        List<FigureCast.Seat> wanted;
        synchronized (this) {
            FigureCast filled = cast.cast(seats, library, new Random());
            change(filled);
            keep(); // asked for: the piece keeps its figures from now on, also those the library gave unasked
            wanted = filled.missing(seats);
        }
        if (wanted.isEmpty()) {
            listener.said("Alle Spuren haben eine Figur.");
            return;
        }
        FigureArtist drawer = artist.get();
        if (drawer == null) {
            return;
        }
        final java.nio.file.Path asked = piece();
        submit(() -> {
            int drawn = 0;
            for (FigureCast.Seat seat : wanted) {
                listener.said("Die KI zeichnet " + seat.instrument() + " (" + (drawn + 1) + " von " + wanted.size() + ") …");
                if (!drawFor(drawer, seat, asked)) {
                    return;
                }
                drawn++;
            }
            listener.said(drawn == 1 ? "Eine Figur gezeichnet." : drawn + " Figuren gezeichnet.");
        });
    }

    /** The AI draws a new figure for one track; it goes into the library and stands at the track. */
    public void redraw(String trackId, Supplier<FigureArtist> artist) {
        FigureCast.Seat seat = seat(trackId);
        if (seat == null) {
            return;
        }
        FigureArtist drawer = artist.get();
        if (drawer == null) {
            return;
        }
        final java.nio.file.Path asked = piece();
        submit(() -> {
            listener.said("Die KI zeichnet " + seat.instrument() + " neu …");
            if (drawFor(drawer, seat, asked)) {
                listener.said("Neue Figur für " + trackId + ".");
            }
        });
    }

    /**
     * Another figure of the library for one track, one no other track wears.
     *
     * @return {@code false} if the library has no other one of its kind
     */
    public boolean swap(String trackId) {
        FigureCast.Seat seat = seat(trackId);
        if (seat == null) {
            return false;
        }
        synchronized (this) {
            FigureCast swapped = cast.another(seat, seats, library);
            if (swapped == null) {
                listener.said("Keine andere Figur für " + seat.instrument() + " in der Bibliothek: „Neu zeichnen“ lässt die KI eine zeichnen.");
                return false;
            }
            change(swapped);
        }
        return true;
    }

    /** Takes back the last change of the cast. */
    public synchronized boolean undo() {
        if (undo.isEmpty()) {
            return false;
        }
        cast = undo.pop();
        keep();
        listener.cast(cast);
        listener.said("Figuren: letzter Schritt zurückgenommen.");
        return true;
    }

    /** Stops drawing. */
    public void close() {
        drawing.shutdownNow();
    }

    /** The piece that is open now: what was drawn for another piece does not go to this one. */
    private synchronized java.nio.file.Path piece() {
        return directory;
    }

    private boolean drawFor(FigureArtist drawer, FigureCast.Seat seat, java.nio.file.Path asked) {
        try {
            int variation = library.figures(seat.kind()).size();
            String svg = drawer.draw(seat.instrument(), role(seat), variation, library.directory().resolve("answers"));
            String figure = library.add(seat.kind(), svg, drawer.modelName(), seat.instrument());
            synchronized (this) {
                // Another piece was opened meanwhile: the figure stays in the library, its tracks are not ours.
                if (Objects.equals(directory, asked) && seat(seat.trackId()) != null) {
                    change(cast.with(seat.trackId(), figure));
                }
            }
            return true;
        } catch (IOException failed) {
            listener.said("Figur für " + seat.instrument() + " nicht gezeichnet: " + failed.getMessage());
            return false;
        }
    }

    private final java.util.Map<String, String> roles = new java.util.HashMap<>();

    /** Tells the role of each track, which the AI hears with the instrument. */
    public synchronized void roles(java.util.Map<String, String> byTrack) {
        roles.clear();
        roles.putAll(byTrack);
    }

    private synchronized String role(FigureCast.Seat seat) {
        return roles.getOrDefault(seat.trackId(), "");
    }

    private void submit(Runnable work) {
        synchronized (this) {
            running++;
        }
        listener.busy(true);
        drawing.submit(() -> {
            try {
                work.run();
            } finally {
                boolean idle;
                synchronized (StageFigures.this) {
                    idle = --running == 0;
                }
                if (idle) {
                    listener.busy(false);
                }
            }
        });
    }

    private synchronized FigureCast.Seat seat(String trackId) {
        for (FigureCast.Seat seat : seats) {
            if (seat.trackId().equals(trackId)) {
                return seat;
            }
        }
        return null;
    }

    /** A new cast, with the old one kept to go back to, written to the piece and shown. */
    private void change(FigureCast changed) {
        if (changed.equals(cast)) {
            return;
        }
        undo.push(cast);
        cast = changed;
        keep();
        listener.cast(cast);
    }

    private void keep() {
        if (directory == null) {
            return;
        }
        try {
            cast.save(directory);
        } catch (IOException failed) {
            listener.said("Figuren nicht gespeichert: " + failed.getMessage());
        }
    }

    private long seed() {
        return directory == null ? 0 : directory.toAbsolutePath().toString().hashCode();
    }

    /** The seats as the cast saw them last, for tests. */
    synchronized List<FigureCast.Seat> seats() {
        return new ArrayList<>(seats);
    }
}
