package com.aresstack.audio.studio.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Which titles have been heard: a piece whose mix nobody has listened to long enough is new. The
 * mark lies in the folder of the piece ({@value #FILE_NAME}) with the time of the mix that was
 * heard, so a piece that is produced anew (a new mix) is new again, while a piece that was only
 * mixed anew on the stage is not ({@link #producedAgain}).
 */
public final class HeardTitles {
    /** The file in the folder of a piece that says it was heard. */
    public static final String FILE_NAME = "heard.properties";
    /** The mix of a piece: what is heard of it. */
    public static final String MIX = "arrangement-instruments.wav";
    /** How much of a title must have been heard, in percent, until it counts as heard (setting). */
    public static final int DEFAULT_PERCENT = 80;
    /** The least and the most the setting takes, in percent. */
    public static final int LEAST_PERCENT = 1;
    public static final int MOST_PERCENT = 100;

    private static final String HEARD = "heard";
    private static final String MIX_TIME = "mixModified";

    private HeardTitles() {
    }

    /** Whether the piece has a mix to hear that nobody has heard yet. */
    public static boolean isNew(Path piece) {
        return piece != null && Files.isRegularFile(piece.resolve(MIX)) && !heard(piece);
    }

    /** Whether the piece was heard. */
    public static boolean heard(Path piece) {
        return "true".equals(read(piece).getProperty(HEARD));
    }

    /** Marks the piece as heard. */
    public static void markHeard(Path piece) throws IOException {
        Properties mark = new Properties();
        mark.setProperty(HEARD, "true");
        mark.setProperty(MIX_TIME, Long.toString(mixTime(piece)));
        try (OutputStream out = Files.newOutputStream(piece.resolve(FILE_NAME))) {
            mark.store(out, "Audio Studio: this title was heard");
        }
    }

    /**
     * The piece was produced (again): if its mix is another one than the one heard, it is new
     * again. A production that failed and left the old mix leaves the mark.
     */
    public static void producedAgain(Path piece) throws IOException {
        Properties mark = read(piece);
        if (mark.isEmpty()) {
            return;
        }
        if (!Long.toString(mixTime(piece)).equals(mark.getProperty(MIX_TIME))) {
            Files.deleteIfExists(piece.resolve(FILE_NAME));
        }
    }

    private static long mixTime(Path piece) {
        try {
            return Files.getLastModifiedTime(piece.resolve(MIX)).toMillis();
        } catch (IOException | RuntimeException none) {
            return 0L;
        }
    }

    private static Properties read(Path piece) {
        Properties mark = new Properties();
        if (piece == null) {
            return mark;
        }
        Path file = piece.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return mark;
        }
        try (InputStream in = Files.newInputStream(file)) {
            mark.load(in);
        } catch (IOException | RuntimeException unreadable) {
            mark.clear();
        }
        return mark;
    }

    /**
     * Counts how long the piece that plays has been heard. Only time that really played counts, a
     * jump does not; another piece starts the count anew.
     */
    public static final class Counter {
        /** What the count may miss at the very end of a piece, in seconds. */
        static final double END_SLACK_SECONDS = 1.0;
        private Path piece;
        private double seconds;
        private boolean told;

        /**
         * Some more seconds of a piece were heard.
         *
         * @param length  how long the piece is
         * @param percent how much of it must be heard
         * @return {@code true} once, when the piece has just been heard long enough
         */
        public boolean heard(Path playing, double more, double length, int percent) {
            if (playing == null) {
                return false;
            }
            if (!playing.equals(piece)) {
                piece = playing;
                seconds = 0;
                told = false;
            }
            if (told || !(more > 0) || !(length > 0)) {
                return false;
            }
            seconds += more;
            int share = Math.max(LEAST_PERCENT, Math.min(MOST_PERCENT, percent));
            // The count follows the clock of the window: at 100 % the last moments must not be missed.
            double needed = Math.min(length * share / 100.0, Math.max(0, length - END_SLACK_SECONDS));
            if (seconds >= needed) {
                told = true;
                return true;
            }
            return false;
        }

        /** Starts anew: the piece counted now may be heard again (it was produced anew). */
        public void reset() {
            piece = null;
            seconds = 0;
            told = false;
        }

        /** How long the piece counted now has been heard. */
        public double seconds() {
            return seconds;
        }
    }
}
