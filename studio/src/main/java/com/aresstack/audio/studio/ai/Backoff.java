package com.aresstack.audio.studio.ai;

import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;

/**
 * How long to wait before an AI request is tried again: growing exponentially from a first pause
 * up to a longest one, and then every so often for as long as it takes. A request is never given
 * up because of a broken connection or an answer that cannot be used; only cancelling stops it
 * (studio.properties: {@code retryFirstSeconds}, {@code retryMostSeconds}).
 */
public final class Backoff {
    /** The first pause by default, in seconds. */
    public static final int FIRST_SECONDS = 2;
    /** The longest pause by default, in seconds. */
    public static final int MOST_SECONDS = 300;

    private static volatile long firstMillis = FIRST_SECONDS * 1000L;
    private static volatile long mostMillis = MOST_SECONDS * 1000L;

    private Backoff() {
    }

    /** After how many tries a request gives up; 0, the default, never gives up. */
    private static volatile int giveUpAfter;

    /** A listener for the lines that tell about a request tried again (the protocol of the window). */
    private static volatile java.util.function.Consumer<String> told;

    /** Who hears about requests tried again; {@code null} for nobody. */
    public static void tellTo(java.util.function.Consumer<String> listener) {
        told = listener;
    }

    /** Tells a line about a request tried again. */
    public static void tell(String line) {
        java.util.function.Consumer<String> listener = told;
        if (listener != null) {
            try {
                listener.accept(line);
            } catch (RuntimeException unheard) {
                // telling must never break a request
            }
        }
    }

    /** For tests: give up after so many tries (0 never) and wait so many milliseconds at most. */
    public static void forTests(int tries, long pauseMillis) {
        giveUpAfter = Math.max(0, tries);
        if (giveUpAfter == 0) {
            configure(FIRST_SECONDS, MOST_SECONDS); // back to the standard
            return;
        }
        firstMillis = Math.max(1, pauseMillis);
        mostMillis = firstMillis;
    }

    /** Whether a request that failed {@code tries} times gives up (only in tests). */
    public static boolean givesUp(int tries) {
        int limit = giveUpAfter;
        return limit > 0 && tries >= limit;
    }

    /** Sets the first and the longest pause, in seconds. */
    public static void configure(int firstSeconds, int mostSeconds) {
        long first = Math.max(1, firstSeconds) * 1000L;
        firstMillis = first;
        mostMillis = Math.max(first, Math.max(1, mostSeconds) * 1000L);
    }

    /** The pause before try {@code attempt + 1}, after {@code attempt} tries (from 1), in milliseconds. */
    public static long delay(int attempt) {
        long delay = firstMillis;
        for (int i = 1; i < attempt && delay < mostMillis; i++) {
            delay *= 2;
        }
        return Math.min(delay, mostMillis);
    }

    /** The pause in words, e.g. "8 s" or "5 min". */
    public static String words(long millis) {
        long seconds = Math.max(1, (millis + 500) / 1000);
        return seconds < 120 ? seconds + " s" : (seconds + 30) / 60 + " min";
    }

    /**
     * Waits the pause after {@code attempt} tries; ends early, with an error, when the thread is
     * interrupted or {@code cancelled} says so.
     */
    public static void pause(int attempt, BooleanSupplier cancelled) throws InterruptedIOException {
        long delay = delay(attempt);
        // Up to a quarter more, by chance: requests that broke off together do not all come back
        // at the same moment and break off together again.
        long jitter = (long) (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * delay / 4);
        long until = System.currentTimeMillis() + delay + jitter;
        while (true) {
            if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
                throw new InterruptedIOException("KI-Anfrage abgebrochen.");
            }
            long left = until - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(left, 250));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("KI-Anfrage abgebrochen.");
            }
        }
    }
}
