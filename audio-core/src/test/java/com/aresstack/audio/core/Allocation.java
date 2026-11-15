package com.aresstack.audio.core;

import java.lang.management.ManagementFactory;

/** Measures what the current thread allocates (rule 1 of the package documentation). */
public final class Allocation {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private Allocation() {
    }

    /** The bytes the current thread has allocated so far. */
    public static long sofar() {
        return THREADS.getCurrentThreadAllocatedBytes();
    }

    /** Runs the work once to warm up, then counts the bytes a second run allocates. */
    public static long of(ThrowingRunnable work) throws Exception {
        for (int i = 0; i < 3; i++) {
            work.run();
        }
        long before = sofar();
        work.run();
        return sofar() - before;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
