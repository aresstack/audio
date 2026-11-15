package com.aresstack.audio.studio.ui;

import com.aresstack.audio.studio.application.LiveDeck;
import com.aresstack.audio.studio.stage.SpatialScene;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * What the desk plays: the stage live ({@link LiveDeck}, work package D2), where a moved
 * instrument is heard while the piece plays, or one WAV file ({@link DeckPlayer}) as before. The
 * file stays for what has no stage (the Java reference mix), as the picture of the waveform, and
 * as the fallback when the stage cannot be played live.
 *
 * <p>Used from the event thread like the file player; only reading the tracks of a live stage
 * happens on a thread of its own.
 */
final class StageDeck {
    /**
     * A way out for a machine on which live playback crackles: {@code -Daudio.studio.live=false}
     * plays the mixed file as before, exchanged after every change.
     */
    static final boolean LIVE_WANTED = !"false".equalsIgnoreCase(System.getProperty("audio.studio.live"));

    private final DeckPlayer file = new DeckPlayer();
    private final LiveDeck.Sinks sinks;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "live-stage-loader");
        thread.setDaemon(true);
        return thread;
    });
    private LiveDeck live;
    /** Counts what was asked for: a live stage that is ready after something else was asked is dropped. */
    private int asked;
    private boolean loading;
    /** Where the live stage that is being read will start: shown as the place until it is ready. */
    private double pending = Double.NaN;
    /** Whether the live stage that is being read plays as soon as it is ready; Stop and Play change it meanwhile. */
    private boolean autoplay;

    StageDeck() {
        this(LiveDeck.SOUND_CARD);
    }

    StageDeck(LiveDeck.Sinks sinks) {
        this.sinks = sinks;
    }

    // ---- the file: picture of the waveform, and what is played without a live stage ----

    /** Shows a file as a waveform. The file is not played; a live stage that plays goes on. */
    void show(Path chosen) {
        file.show(chosen);
    }

    /** Gets a file ready to play instead of a live stage. */
    void open(Path chosen) throws Exception {
        closeLive();
        file.open(chosen);
    }

    /**
     * The file was mixed anew. Played from the file, it is exchanged at the same place without a
     * gap; with a live stage only its picture is new, the change itself was heard long ago.
     */
    void swap(Path chosen) throws Exception {
        if (live != null) {
            file.show(chosen);
        } else {
            file.swap(chosen);
        }
    }

    Path file() {
        return file.file();
    }

    DeckPlayer.Envelope envelope() {
        return file.envelope();
    }

    // ---- the live stage ----

    /**
     * Reads the tracks of a piece in the background and plays its stage live from then on. Until
     * it is ready, whatever plays goes on.
     *
     * @param at    the second to go on at; not a number means: where playback stands when the
     *              stage is ready, playing or not as it was then (a stage made anew for another room)
     * @param play  whether to play at once (ignored if {@code at} is not a number)
     * @param ready told on the event thread when the stage plays ({@code null}) or why it cannot
     */
    void openLive(Path arrangement, Path directory, SpatialScene scene, String only, float legacyFactor,
                  double at, boolean play, Consumer<Exception> ready) {
        final int ticket = ++asked;
        loading = true;
        pending = live == null ? at : Double.NaN;
        autoplay = play;
        loader.submit(() -> {
            LiveDeck made = null;
            Exception problem = null;
            try {
                made = LiveDeck.open(arrangement, directory, scene, only, legacyFactor, sinks);
            } catch (Exception failed) {
                problem = failed;
            } catch (Error failed) {
                problem = new IllegalStateException(String.valueOf(failed), failed); // e.g. out of memory
            }
            final LiveDeck deck = made;
            final Exception said = problem;
            SwingUtilities.invokeLater(() -> {
                if (ticket != asked) {
                    if (deck != null) {
                        deck.close(); // something else was asked for in the meantime
                    }
                    return;
                }
                loading = false;
                if (deck == null) {
                    pending = Double.NaN;
                }
                if (deck != null) {
                    // What the transport was told while the tracks were read counts, not what was asked first.
                    boolean first = live == null && !file.ready();
                    boolean goOn = first ? autoplay : playing();
                    double from = first && !Double.isNaN(pending) ? pending : seconds();
                    pending = Double.NaN;
                    Path shown = file.file();
                    closeLive();
                    if (shown != null) {
                        file.show(shown); // the sound card is free for the stage, the picture stays
                    }
                    live = deck;
                    if (from > 0) {
                        deck.seek(from);
                    }
                    if (goOn) {
                        deck.play();
                    }
                }
                ready.accept(said);
            });
        });
    }

    /** Whether the stage is played live (and not a file). */
    boolean live() {
        return live != null;
    }

    /** Whether a live stage is being read right now. */
    boolean loading() {
        return loading;
    }

    /**
     * The stage as it stands now, also in the middle of a drag: heard at once if it is played live.
     *
     * @return {@code false} if the live stage cannot play it (another room, another way of setting
     *         the level) and has to be made anew
     */
    boolean stage(SpatialScene scene, String only) {
        return live == null || live.stage(scene, only);
    }

    /**
     * A curve of the place or of the room share of a track was saved with the piece: the live
     * stage reads the movements again, in the background, and follows them while it plays.
     */
    void movementsChanged() {
        final LiveDeck deck = live;
        if (deck != null) {
            loader.submit(deck::reloadMovements);
        }
    }

    /** The file mix found its factor (compatibility mode): live playback stays as loud as the file. */
    void legacyFactor(float factor, double masterDb) {
        if (live != null) {
            live.legacyFactor(factor, masterDb);
        }
    }

    /** The gain of the limiter of live playback now; 1 while it rests or while a file plays. */
    float limiterGain() {
        LiveDeck deck = live;
        return deck != null && deck.playing() ? deck.limiterGain() : 1f;
    }

    // ---- transport, whatever plays ----

    boolean playing() {
        return live != null ? live.playing() : file.playing();
    }

    /** Whether there is something to play at a key press; a live stage whose audio thread failed is not. */
    boolean ready() {
        return live != null ? !live.failed() : file.ready();
    }

    void play() {
        if (live != null) {
            live.play();
        } else if (awaited()) {
            autoplay = true;
        } else {
            file.play();
        }
    }

    void pause() {
        if (live != null) {
            live.pause();
        } else if (awaited()) {
            autoplay = false; // Stop while the tracks are read: the stage is ready, but silent
        } else {
            file.pause();
        }
    }

    /** Whether nothing plays yet because the first live stage is still being read. */
    private boolean awaited() {
        return loading && live == null && !file.ready();
    }

    double seconds() {
        return live != null ? live.seconds() : loading && !Double.isNaN(pending) ? pending : file.seconds();
    }

    double length() {
        return live != null ? live.length() : file.length();
    }

    /** Jumps to a share of the length between 0 and 1. */
    void seek(double share) {
        if (live != null) {
            live.seek(Math.max(0, Math.min(1, share)) * live.length());
        } else if (awaited()) {
            DeckPlayer.Envelope shown = file.envelope();
            pending = shown == null ? 0 : Math.max(0, Math.min(1, share)) * shown.seconds;
        } else {
            file.seek(share);
        }
    }

    /**
     * How loud one side of the master is right now, between 0 and 1: live what the master bus just
     * gave to the sound card (after the limiter; one meter for both sides), else from the file.
     */
    float level(boolean rightSide) {
        if (live != null) {
            return live.playing() ? Math.min(1f, live.peak()) : 0f;
        }
        DeckPlayer.Envelope envelope = file.envelope();
        return file.playing() && envelope != null ? envelope.at(file.seconds(), rightSide) : 0f;
    }

    private void closeLive() {
        if (live != null) {
            live.close();
            live = null;
        }
    }

    /** Stops everything and forgets the file; a live stage that is still being read is dropped. */
    void close() {
        asked++;
        loading = false;
        pending = Double.NaN;
        closeLive();
        file.close();
    }
}
